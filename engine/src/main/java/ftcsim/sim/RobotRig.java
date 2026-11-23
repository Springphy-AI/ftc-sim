package ftcsim.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.ode4j.math.DMatrix3C;
import org.ode4j.math.DVector3;

import com.qualcomm.hardware.bosch.BHI260IMU;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.lynx.LynxVoltageSensor;
import com.qualcomm.hardware.rev.Rev2mDistanceSensor;
import com.qualcomm.hardware.rev.RevColorSensorV3;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.hardware.rev.RevTouchSensor;
import com.qualcomm.hardware.sparkfun.SparkFunOTOS;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.CRServoImplEx;
import com.qualcomm.robotcore.hardware.ColorSensor;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorImplEx;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.ServoImplEx;
import com.qualcomm.robotcore.hardware.TouchSensor;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.ServoConfigurationType;

import ftcsim.config.MotorModels;
import ftcsim.config.RobotConfig;
import ftcsim.hal.CameraBackend;
import ftcsim.hal.DigitalPort;
import ftcsim.hal.HalSession;
import ftcsim.hal.ImuBackend;
import ftcsim.hal.MotorPort;
import ftcsim.hal.OtosBackend;
import ftcsim.hal.PinpointBackend;
import ftcsim.hal.Rotations;
import ftcsim.hal.SensorBackends;
import ftcsim.hal.ServoPort;
import ftcsim.hal.SimDcMotorController;
import ftcsim.hal.SimDigitalChannel;
import ftcsim.hal.SimHub;
import ftcsim.hal.SimServoController;
import ftcsim.hal.SimWebcamName;
import ftcsim.physics.Mechanism;
import ftcsim.physics.MotorUnit;
import ftcsim.physics.PhysicsWorld;
import ftcsim.physics.RobotBody;
import ftcsim.physics.ServoJoint;
import ftcsim.physics.ServoUnit;
import ftcsim.cad.RobotModel;
import ftcsim.vision.CameraWorld;

/**
 * The simulated robot's electronics and mechanisms, built from a robot JSON file: hubs and
 * ports, motors, servos and sensors, all stepped by the physics thread.
 */
public final class RobotRig {
    private static final double BATTERY_OPEN_CIRCUIT_V = 13.2;
    private static final double BATTERY_RESISTANCE_OHM = 0.06;
    /** FTC robot frame (+X right, +Y forward) expressed in the physics frame (+x forward, +y left). */
    private static final double[][] PHYS_FROM_FTC = {{0, 1, 0}, {-1, 0, 0}, {0, 0, 1}};

    public final RobotConfig config;
    public final PhysicsWorld world;
    public final RobotBody body;
    /** Where Reset puts the robot: x, y (m) and heading (degrees). */
    public final double[] start;
    public final List<SimHub> hubs = new ArrayList<>();
    public final Map<String, MotorUnit> motors = new LinkedHashMap<>();
    public final List<Mechanism> mechanisms = new ArrayList<>();
    public final List<ServoUnit> servos = new ArrayList<>();
    /** Servos that drive CAD joints. */
    public final List<ServoJoint> servoJoints = new ArrayList<>();

    private final ImuBackend imu;
    private final List<PinpointMount> pinpoints = new ArrayList<>();
    private final List<OtosMount> otoses = new ArrayList<>();
    private final List<RangeMount> ranges = new ArrayList<>();
    private final List<ColorMount> colors = new ArrayList<>();
    private final List<TouchMount> touches = new ArrayList<>();
    private final List<RobotConfig.Device> webcams = new ArrayList<>();
    private final Map<RobotConfig.Device, CameraBackend> cameras = new LinkedHashMap<>();
    private final Map<RobotConfig.Device, ServoPort> servoPorts = new LinkedHashMap<>();
    private final Map<RobotConfig.Device, DigitalPort> digitalPorts = new LinkedHashMap<>();

    private double[][] imuReference = Rotations.identity();
    private double batteryVolts = BATTERY_OPEN_CIRCUIT_V;
    private long stepCount = 0;
    private double lastHeading = 0;

    record PinpointMount(RobotConfig.Device device, PinpointBackend backend) {
    }

    record OtosMount(RobotConfig.Device device, OtosBackend backend) {
    }

    record RangeMount(RobotConfig.Device device, SensorBackends.Range backend) {
    }

    record ColorMount(RobotConfig.Device device, SensorBackends.Color backend) {
    }

    record TouchMount(RobotConfig.Device device, DigitalPort port) {
    }

    /** A robot at its start spot for station 1 of the season's home alliance. */
    public RobotRig(RobotConfig config, PhysicsWorld world) {
        this(config, world, defaultStart(config, world));
    }

    private static double[] defaultStart(RobotConfig config, PhysicsWorld world) {
        double[] own = config.startFor(world.field.season.id);
        if (own != null) return own;
        double[] s = world.field.season.startSpot(world.field.season.homeAlliance, 1);
        return s != null ? s : new double[]{0, 0, 0};
    }

    /** A robot in {@code world} (which may hold other robots too), placed at {@code start} (x, y, heading°). */
    public RobotRig(RobotConfig config, PhysicsWorld world, double[] start) {
        this.config = config;
        this.world = world;
        this.start = start.clone();
        TimerResolution.hold();   // (Windows) the physics loop, bus latency and webcams pause for about 1 ms, not 15.6

        Allocator alloc = new Allocator();
        // Drivetrain motors first, so they land on the Control Hub like on most robots.
        MotorModels.Spec driveSpec = MotorModels.get(config.drive.motor);
        for (Map.Entry<String, com.google.gson.JsonElement> e : config.drive.motors.entrySet()) {
            String name = e.getValue().getAsString();
            if (motors.containsKey(name)) continue;   // one motor for several wheels: one port
            MotorPort port = alloc.motor(name, driveSpec);
            motors.put(name, new MotorUnit(port, driveSpec));
        }
        List<RobotConfig.Device> motorDevices = new ArrayList<>();
        for (RobotConfig.Device d : config.devices) {
            switch (d.type) {
                case "motor": {
                    MotorModels.Spec spec = MotorModels.get(d.motor);
                    MotorPort port = alloc.motor(d.name, spec);
                    MotorUnit unit = new MotorUnit(port, spec);
                    motors.put(d.name, unit);
                    motorDevices.add(d);
                    break;
                }
                case "servo":
                case "crservo": {
                    ServoPort port = alloc.servo(d.name, "crservo".equals(d.type));
                    servoPorts.put(d, port);
                    servos.add(new ServoUnit(d, port));
                    break;
                }
                case "pinpoint": {
                    double t = "swingarm".equalsIgnoreCase(d.pod)
                            ? PinpointBackend.SWINGARM_TICKS_PER_MM : PinpointBackend.FOUR_BAR_TICKS_PER_MM;
                    pinpoints.add(new PinpointMount(d, new PinpointBackend(t, t)));
                    break;
                }
                case "otos":
                    otoses.add(new OtosMount(d, new OtosBackend()));
                    break;
                case "distance":
                    ranges.add(new RangeMount(d, new SensorBackends.Range()));
                    break;
                case "color":
                    colors.add(new ColorMount(d, new SensorBackends.Color()));
                    break;
                case "touch":
                case "digital_touch": {
                    DigitalPort port = alloc.digital(d.name);
                    digitalPorts.put(d, port);
                    touches.add(new TouchMount(d, port));
                    break;
                }
                case "webcam":
                    webcams.add(d);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown device type \"" + d.type + "\" for " + d.name);
            }
        }

        RevHubOrientationOnRobot mount = new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.valueOf(config.imu.logo.trim().toUpperCase(java.util.Locale.ROOT)),
                RevHubOrientationOnRobot.UsbFacingDirection.valueOf(config.imu.usb.trim().toUpperCase(java.util.Locale.ROOT)));
        Quaternion q = mount.imuCoordinateSystemOrientationFromPerspectiveOfRobot();
        imu = new ImuBackend(Rotations.fromQuaternion(q.w, q.x, q.y, q.z));

        body = new RobotBody(world, config, motors::get);
        try {
            // Motors and servos that drive CAD joints act on the physics bodies; the rest as before.
            RobotModel model = body.model;
            for (RobotConfig.Device d : motorDevices) {
                RobotModel.Group g = model == null ? null : model.drivenBy(d.name);
                if (g != null && g.kind == RobotModel.Kind.LINK) {
                    mechanisms.add(new Mechanism(d, motors.get(d.name), body.link(model.index(g)), Double.NaN));
                } else if (g != null && g.kind == RobotModel.Kind.SPINNER) {
                    mechanisms.add(new Mechanism(d, motors.get(d.name), null, g.axisInertia));
                } else {
                    mechanisms.add(new Mechanism(d, motors.get(d.name)));
                }
            }
            if (model != null) {
                for (ServoUnit s : servos) {
                    RobotModel.Group g = model.drivenBy(s.device.name);
                    if (g != null && g.kind == RobotModel.Kind.LINK) servoJoints.add(new ServoJoint(s, body.link(model.index(g))));
                }
            }
            place(this.start[0], this.start[1], Math.toRadians(this.start[2]));
        } catch (RuntimeException | Error e) {
            // A robot that can't be built (a bad robot file) must not leave its chassis and wheels
            // in the world, where they would still collide with the other robots and the game pieces.
            body.destroy();
            throw e;
        }
    }

    /**
     * Where each part of the CAD model is (see {@link RobotBody#groupTransforms}), with rollers,
     * flywheels and continuous servos turned by their mechanisms. Null without a CAD model.
     */
    public double[][] groupTransforms() {
        RobotModel model = body.model;
        if (model == null) return null;
        return body.groupTransforms(i -> spinnerAngle(model.groups.get(i)));
    }

    private double spinnerAngle(RobotModel.Group g) {
        if (g.device == null) return 0;
        for (Mechanism m : mechanisms) if (m.device.name.equals(g.device)) return m.position();
        for (ServoUnit s : servos) {
            if (s.device.name.equals(g.device)) return g.servoCenter + Math.toRadians(s.angleDeg()) * g.ratio;
        }
        return 0;
    }

    /** Hands out hub ports in order: Control Hub first, then Expansion Hub 2. */
    private final class Allocator {
        private int motorIndex = 0, servoIndex = 0, digitalIndex = 0;

        private SimHub hub(int index) {
            while (hubs.size() <= index) {
                boolean parent = hubs.isEmpty();
                hubs.add(new SimHub(parent ? "Control Hub" : "Expansion Hub " + (hubs.size() + 1),
                        parent ? 173 : hubs.size() + 1, parent));
            }
            return hubs.get(index);
        }

        MotorPort motor(String name, MotorModels.Spec spec) {
            int i = motorIndex++;
            SimHub h = hub(i / SimHub.MOTORS);
            MotorConfigurationType type = new MotorConfigurationType();
            type.setTicksPerRev(spec.ticksPerRev());
            type.setGearing(spec.gearing());
            type.setMaxRPM(spec.freeRpm());
            MotorPort p = new MotorPort(name, h, i % SimHub.MOTORS, type);
            h.motors[p.port] = p;
            return p;
        }

        ServoPort servo(String name, boolean continuous) {
            int i = servoIndex++;
            SimHub h = hub(i / SimHub.SERVOS);
            ServoPort p = new ServoPort(name, h, i % SimHub.SERVOS, continuous);
            h.servos[p.port] = p;
            return p;
        }

        DigitalPort digital(String name) {
            int i = digitalIndex++;
            SimHub h = hub(i / SimHub.DIGITAL);
            DigitalPort p = new DigitalPort(name, h, i % SimHub.DIGITAL);
            h.digital[p.channel] = p;
            return p;
        }
    }

    // ---- per-OpMode hardware map ---------------------------------------------------------------

    /** A fresh HardwareMap for one OpMode run, as the Robot Controller builds at INIT. */
    public HardwareMap createHardwareMap(HalSession session) {
        HardwareMap map = new HardwareMap();
        Map<SimHub, SimDcMotorController> motorControllers = new LinkedHashMap<>();
        Map<SimHub, SimServoController> servoControllers = new LinkedHashMap<>();
        for (SimHub hub : hubs) {
            LynxModule module = new LynxModule(hub, session);
            map.put(hub.name, module);
            LynxVoltageSensor voltage = new LynxVoltageSensor(hub);
            map.put(hub.name, voltage);
            map.voltageSensor.put(hub.name, voltage);
            SimDcMotorController mc = new SimDcMotorController(hub, session);
            SimServoController sc = new SimServoController(hub, session);
            motorControllers.put(hub, mc);
            servoControllers.put(hub, sc);
            map.dcMotorController.put(hub.name, mc);
            map.servoController.put(hub.name, sc);
        }
        for (MotorUnit u : motors.values()) {
            MotorPort p = u.port;
            DcMotorImplEx m = new DcMotorImplEx(motorControllers.get(p.hub), p.port);
            put(map, p.name, aliasesOf(p.name), m);
            map.dcMotor.put(p.name, m);
        }
        for (Map.Entry<RobotConfig.Device, ServoPort> e : servoPorts.entrySet()) {
            ServoPort p = e.getValue();
            SimServoController sc = servoControllers.get(p.hub);
            if (p.continuous) {
                CRServoImplEx s = new CRServoImplEx(sc, p.port, ServoConfigurationType.getStandardServoType());
                put(map, p.name, e.getKey().aliases, s);
                map.crservo.put(p.name, s);
            } else {
                ServoImplEx s = new ServoImplEx(sc, p.port, ServoConfigurationType.getStandardServoType());
                put(map, p.name, e.getKey().aliases, s);
                map.servo.put(p.name, s);
            }
        }
        put(map, config.imu.name, List.of(), new BHI260IMU(imu));
        for (PinpointMount pm : pinpoints) put(map, pm.device.name, pm.device.aliases, new GoBildaPinpointDriver(pm.backend));
        for (OtosMount om : otoses) put(map, om.device.name, om.device.aliases, new SparkFunOTOS(om.backend));
        for (RangeMount rm : ranges) put(map, rm.device.name, rm.device.aliases, new Rev2mDistanceSensor(rm.backend));
        for (ColorMount cm : colors) {
            RevColorSensorV3 sensor = new RevColorSensorV3(cm.backend);
            put(map, cm.device.name, cm.device.aliases, sensor);
            map.colorSensor.put(cm.device.name, sensor);
        }
        for (TouchMount tm : touches) {
            if ("touch".equals(tm.device.type)) {
                RevTouchSensor t = new RevTouchSensor(tm.port);
                put(map, tm.device.name, tm.device.aliases, t);
                map.touchSensor.put(tm.device.name, t);
            } else {
                SimDigitalChannel ch = new SimDigitalChannel(tm.port, session);
                put(map, tm.device.name, tm.device.aliases, ch);
                map.digitalChannel.put(tm.device.name, ch);
            }
        }
        for (RobotConfig.Device w : webcams) put(map, w.name, w.aliases, new SimWebcamName(w.name, cameras.get(w)));
        return map;
    }

    /** Gives the robot's webcams something to see: robot number {@code robot} in {@code world}. */
    public void attachCameras(CameraWorld world, int robot) {
        cameras.clear();
        for (RobotConfig.Device w : webcams) cameras.put(w, world.camera(robot, w));
    }

    private List<String> aliasesOf(String name) {
        for (RobotConfig.Device d : config.devices) if (name.equals(d.name)) return d.aliases;
        return List.of();
    }

    private static void put(HardwareMap map, String name, List<String> aliases, HardwareDevice device) {
        map.put(name, device);
        if (aliases != null) for (String a : aliases) map.put(a, device);
    }

    // ---- stepping --------------------------------------------------------------------------------

    /** One physics step of a world that holds only this robot. Called on the physics thread only. */
    public void step(double dt) {
        beforeWorldStep(dt);
        world.step(dt);
        afterWorldStep(dt);
    }

    /** Motors and servos act; call for every robot, then step the world once, then {@link #afterWorldStep}. */
    public void beforeWorldStep(double dt) {
        for (ServoUnit s : servos) s.step(dt);
        for (ServoJoint j : servoJoints) j.step();
        for (Mechanism m : mechanisms) m.step(batteryVolts, dt);
        body.applyDrive(batteryVolts, dt);
    }

    /** Encoders, battery and sensors catch up with the world step. */
    public void afterWorldStep(double dt) {
        body.afterStep(dt);
        for (Mechanism m : mechanisms) m.afterWorldStep(dt);
        updateBattery(dt);
        updateMotionSensors(dt);
        if (stepCount++ % 5 == 0) updateSlowSensors();
    }

    /** Removes the robot from the world. */
    public void destroy() {
        body.destroy();
    }

    private void updateBattery(double dt) {
        double current = 0;
        for (MotorUnit u : motors.values()) current += u.batteryAmps();
        current = Math.max(0, current);   // (the battery isn't charged by motors pushing current back)
        double target = BATTERY_OPEN_CIRCUIT_V - BATTERY_RESISTANCE_OHM * current;
        batteryVolts += (target - batteryVolts) * Math.min(1, dt / 0.03);
        batteryVolts = Math.max(7.0, batteryVolts);
        for (SimHub h : hubs) {
            // What each hub's current sensor reads: its own motors (servos and sensors draw little).
            double hubAmps = 0;
            for (MotorUnit u : motors.values()) if (u.port.hub == h) hubAmps += u.batteryAmps();
            h.inputVoltage = batteryVolts;
            h.totalCurrentAmps = Math.max(0, hubAmps);
        }
    }

    private void updateMotionSensors(double dt) {
        DMatrix3C r = body.rotation();
        double[][] rPhys = {{r.get00(), r.get01(), r.get02()}, {r.get10(), r.get11(), r.get12()}, {r.get20(), r.get21(), r.get22()}};
        double[][] robotToWorldFtc = Rotations.multiply(rPhys, PHYS_FROM_FTC);
        double[][] relative = Rotations.multiply(Rotations.transpose(imuReference), robotToWorldFtc);
        double[] wPhys = body.bodyAngularVelocity();
        double[] wFtc = Rotations.apply(Rotations.transpose(PHYS_FROM_FTC), wPhys);
        imu.set(new ImuBackend.State(relative, wFtc, System.nanoTime()));

        double[] v = body.bodyVelocity();
        double heading = body.heading();
        double dHeading = Math.atan2(Math.sin(heading - lastHeading), Math.cos(heading - lastHeading));
        lastHeading = heading;
        double omega = wPhys[2];
        for (PinpointMount pm : pinpoints) {
            double xPodLeft = pm.device.xPodOffsetMm / 1000.0;     // X pod's sideways offset (left +)
            double yPodForward = pm.device.yPodOffsetMm / 1000.0;  // Y pod's forward offset
            double xPod = (v[0] - omega * xPodLeft) * dt * 1000;
            double yPod = (v[1] + omega * yPodForward) * dt * 1000;
            pm.backend.step(xPod, yPod, dHeading, dt);
        }
        for (OtosMount om : otoses) {
            RobotConfig.Device d = om.device;
            double vx = v[0] - omega * d.yM, vy = v[1] + omega * d.xM;     // sensor point, physics frame
            double rightX = -vy, fwdY = vx;                                  // FTC frame (X right, Y forward)
            double yaw = Math.toRadians(d.yawDeg);
            double c = Math.cos(-yaw), s = Math.sin(-yaw);
            om.backend.step((rightX * c - fwdY * s) * dt, (rightX * s + fwdY * c) * dt, dHeading, dt);
        }
    }

    private void updateSlowSensors() {
        for (RangeMount rm : ranges) {
            RobotConfig.Device d = rm.device;
            DVector3 origin = body.pointToWorld(d.xM, d.yM, d.zM);
            double yaw = Math.toRadians(d.yawDeg), pitch = Math.toRadians(d.pitchDeg);
            DVector3 dir = body.vectorToWorld(Math.cos(yaw) * Math.cos(pitch), Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch));
            double dist = world.raycast(origin.get0(), origin.get1(), origin.get2(), dir.get0(), dir.get1(), dir.get2(), 2.0, body);
            rm.backend.meters = dist;
        }
        for (ColorMount cm : colors) {
            RobotConfig.Device d = cm.device;
            DVector3 p = body.pointToWorld(d.xM, d.yM, d.zM);
            float[] c = world.field.floorColor(p.get0(), p.get1());
            // Light falls off with the square of the height above the floor (reference: 1.5 cm).
            double h = Math.max(0.005, p.get2());
            float k = (float) Math.min(1.5, Math.pow(0.015 / h, 2));
            cm.backend.red = c[0] * k;
            cm.backend.green = c[1] * k;
            cm.backend.blue = c[2] * k;
            cm.backend.alpha = c[3] * k;
            cm.backend.distanceMeters = h;
        }
        for (TouchMount tm : touches) {
            RobotConfig.Device d = tm.device;
            DVector3 sp = body.pointToWorld(d.xM, d.yM, d.zM);
            boolean pressed = false;
            for (PhysicsWorld.ChassisContact c : world.chassisContacts(body.chassis)) {
                double dx = c.x() - sp.get0(), dy = c.y() - sp.get1();
                if (dx * dx + dy * dy < 0.05 * 0.05) {
                    pressed = true;
                    break;
                }
            }
            tm.port.inputLevel = !pressed;   // REV touch sensors pull the line low when pressed
        }
    }

    // ---- control -------------------------------------------------------------------------------

    /** What the Robot Controller does when an OpMode ends: motors off, servos go limp. */
    public void failsafe() {
        for (SimHub h : hubs) {
            for (MotorPort p : h.motors) {
                if (p == null) continue;
                p.power = 0;
                p.velocityCommand = false;
            }
            for (ServoPort p : h.servos) if (p != null) p.pwmEnabled = false;
        }
    }

    /** Called before INIT of a new OpMode, like the SDK resetting devices for an OpMode. */
    public void resetForOpMode() {
        for (SimHub h : hubs) {
            h.setBulkCachingMode(LynxModule.BulkCachingMode.OFF);
            for (MotorPort p : h.motors) {
                if (p == null) continue;
                p.mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER;
                p.zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE;
                p.targetPositionSet = false;
                p.velocityCommand = false;
                p.power = 0;
                p.enabled = true;
                // As the SDK's resetDeviceConfigurationForOpMode: the configured motor type and the
                // original PIDF, whatever the last OpMode set (setMotorType, setPositionPIDFCoefficients...).
                p.velocityPidf = MotorPort.defaultVelocityPidf(p.physicalType);
                p.positionPidf = MotorPort.defaultPositionPidf();
                p.configuredType = p.physicalType.clone();
                p.modeGeneration++;
            }
        }
    }

    /** Turning the robot off and on: encoders, sensor settings and the IMU heading all reset. */
    public void powerCycle() {
        for (SimHub h : hubs) h.powerCycle();
        for (PinpointMount pm : pinpoints) pm.backend.powerCycle();
        for (OtosMount om : otoses) om.backend.powerCycle();
        for (MotorUnit u : motors.values()) u.resetState();
        for (Mechanism m : mechanisms) m.reset();   // with its encoder back at 0, the arm must be back at its start too
        for (ServoJoint j : servoJoints) j.hornAtLinkStart();   // (Reset put the CAD links back at their start)
        imu.yawOffsetRad = 0;
        captureImuReference();
    }

    public void place(double x, double y, double headingRad) {
        body.place(x, y, headingRad);
        lastHeading = headingRad;
        captureImuReference();
    }

    private void captureImuReference() {
        DMatrix3C r = body.rotation();
        double[][] rPhys = {{r.get00(), r.get01(), r.get02()}, {r.get10(), r.get11(), r.get12()}, {r.get20(), r.get21(), r.get22()}};
        imuReference = Rotations.multiply(rPhys, PHYS_FROM_FTC);
    }

    public double batteryVolts() {
        return batteryVolts;
    }

    // Typed views used by the UI
    public List<ServoPort> servoPorts() {
        return new ArrayList<>(servoPorts.values());
    }

    public boolean touchPressed(String name) {
        for (TouchMount tm : touches) if (tm.device.name.equals(name)) return !tm.port.inputLevel;
        return false;
    }

    public List<RobotConfig.Device> sensorDevices() {
        List<RobotConfig.Device> out = new ArrayList<>();
        for (PinpointMount pm : pinpoints) out.add(pm.device);
        for (OtosMount om : otoses) out.add(om.device);
        for (RangeMount rm : ranges) out.add(rm.device);
        for (ColorMount cm : colors) out.add(cm.device);
        for (TouchMount tm : touches) out.add(tm.device);
        return out;
    }

    /** Latest readings of the slow sensors for the device panel. */
    public Map<String, Object> sensorReadings() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RangeMount rm : ranges) out.put(rm.device.name, rm.backend.meters == Double.MAX_VALUE ? null : rm.backend.meters);
        for (ColorMount cm : colors) out.put(cm.device.name, new float[]{cm.backend.red, cm.backend.green, cm.backend.blue, cm.backend.alpha});
        for (TouchMount tm : touches) out.put(tm.device.name, !tm.port.inputLevel);
        for (PinpointMount pm : pinpoints) {
            PinpointBackend.Snapshot s = pm.backend.read();
            out.put(pm.device.name, pose(s.xMm, s.yMm, "mm", Math.toDegrees(s.headingRad)));
        }
        for (OtosMount om : otoses) {
            double[] p = om.backend.robotPose();   // meters and radians
            out.put(om.device.name, pose(p[0] / 0.0254, p[1] / 0.0254, "in", Math.toDegrees(p[2])));
        }
        return out;
    }

    /** An odometry sensor's pose for the device panel, in the units its driver reports by default (Pinpoint mm, OTOS inches). */
    private static Map<String, Object> pose(double x, double y, String unit, double headingDeg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("unit", unit);
        m.put("headingDeg", headingDeg);
        return m;
    }

    @SuppressWarnings("unused")
    private static Class<?>[] typesUsed() {
        // Keeps the FTC interfaces visible in this file for readers looking for what each device is.
        return new Class<?>[]{CRServo.class, ColorSensor.class, DigitalChannel.class, Servo.class, TouchSensor.class};
    }
}
