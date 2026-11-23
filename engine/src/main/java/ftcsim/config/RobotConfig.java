package ftcsim.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * A simulated robot, loaded from a JSON file in robots/. Device names must match the names in
 * your real robot configuration on the Driver Station, so hardwareMap lookups work unchanged.
 * Distances are meters, angles degrees; robot frame is +x forward, +y left, +z up.
 */
public final class RobotConfig {

    public String name = "Robot";
    public String description = "";
    public Chassis chassis = new Chassis();
    public Drive drive = new Drive();
    public Imu imu = new Imu();
    public List<Device> devices = new ArrayList<>();
    public Io io = new Io();
    /**
     * Where the robot starts, instead of the season's spot: x, y in meters and heading in degrees
     * in the season's field coordinates, per season: {@code {"biobuzz": [x, y, h], "decode": [...]}}.
     * A plain {@code [x, y, h]} is a BIOBUZZ spot: robot files from before there were seasons
     * (and the CAD robots made from them) have one. Absent, the season decides.
     */
    public com.google.gson.JsonElement start;
    /**
     * The robot's CAD model (a STEP or URDF file). When present, the robot looks like its CAD, its
     * mass properties and collision shape come from it, and its moving parts are rigid bodies on
     * joints. Without it the robot is built from the numbers above. {@code "cad": "cad/x.step"} is
     * short for {@code "cad": {"file": "cad/x.step"}}.
     */
    public Cad cad;

    /** How to read the CAD model. Lengths in the CAD's own coordinates are millimeters. */
    public static final class Cad {
        /** A .step/.stp or .urdf file, relative to the robot file. */
        public String file;
        /**
         * The CAD axes that point to the robot's front and up: "+x", "-y", "+z"... Left out (or
         * "auto"): a STEP file's are worked out from its wheels and part names, a URDF's are +x, +z.
         */
        public String forward;
        public String up;
        /** The point on the floor under the robot's center, in CAD coordinates (mm); default: automatic. */
        public double[] originMm;
        /** Parts to leave out entirely (name patterns). */
        public List<String> ignore = new ArrayList<>();
        /**
         * The drivetrain wheels: wheel (front_left, front_right, back_left, back_right; for "tank"
         * left_front, left_middle, ...) to the CAD parts (STEP name patterns) or links (URDF) that
         * make it up. Default: parts named like *wheel*, *mecanum* or *omni*, sorted by position.
         */
        public JsonObject wheels;
        /** STEP: the parts that move, each on a joint. URDF: settings for joints, by joint name. */
        public List<CadLink> links = new ArrayList<>();
        /** URDF: total mass to scale the CAD masses to (kg); default: the URDF's own masses. */
        public Double massKg;
    }

    /** One moving part of the CAD model and what drives it. Angles in degrees, lengths in mm. */
    public static final class CadLink {
        /** STEP: a name for the link. URDF: the joint's name. */
        public String name;
        /**
         * STEP: the parts that move together (name patterns; a pattern matches any assembly level).
         * Default: the parts or sub-assembly called {@link #name}.
         */
        public List<String> parts = new ArrayList<>();
        /** STEP: the link this one is mounted on ("base" = the chassis). */
        public String parent = "base";
        /**
         * STEP: revolute (an arm, a hood, a claw), prismatic (a slide), continuous (a roller).
         * Default: continuous for a roller or flywheel motor and a CR servo, otherwise revolute.
         */
        public String joint;
        /**
         * STEP: the shaft, rail or servo spline the link turns or slides along (name pattern).
         * Default: a round part named like a shaft, axle or pivot in or next to the link; a slide
         * moves along its own length.
         */
        public String axisPart;
        /** STEP: or the axis given directly, in CAD coordinates: a point (mm) and a direction. */
        public double[] axisPointMm;
        public double[] axisDir;
        /** Turns the positive direction around. */
        public boolean reversed;
        /** The motor, servo or CR servo (by name) that drives the joint. */
        public String device;
        /** Joint travel per servo travel (degrees per degree), for servo-driven joints. */
        public double ratio = 1;
        /** Joint position (from the CAD pose) when a servo is at 0.5. */
        public double servoCenter = 0;
        /** Hard stops [min, max] from the CAD pose; default: the URDF's limits, or none. */
        public double[] limits;
        /** Position from the CAD pose when the robot is placed or reset. */
        public double start = 0;
        /** Mass of the link's parts (kg); default: its share of the robot's mass by volume. */
        public Double massKg;
        /** Prismatic joints driven by a motor: diameter of the spool or pulley (mm). */
        public double spoolDiameterMm = 38;
        /** Servo-driven joints: the servo's stall torque (N m). */
        public double torqueNm = 2.5;
        /** STEP: follow another link (a cascade slide stage): position = ratio x that link's. */
        public String mimic;
        public double mimicRatio = 1;
    }

    public static final class Chassis {
        public double lengthM = 0.4572;
        public double widthM = 0.4572;
        public double frameHeightM = 0.09;
        public double groundClearanceM = 0.025;
        public double massKg = 13.0;
        public String color = "#b3261e";
    }

    public static final class Drive {
        /**
         * "mecanum" (4 motors), "tank" (6 wheels, drop center, one motor per side) or "tank4" (4
         * wheels, traction rear and omni front, a motor per wheel; give two wheels the same motor
         * for a 2-motor robot whose wheels are chained).
         */
        public String type = "mecanum";
        public double wheelDiameterMm = 104;
        public double wheelbaseM = 0.336;
        public double trackWidthM = 0.38;
        public String motor = "goBILDA 5203 312rpm";
        public double externalGearRatio = 1.0;
        /** Keys depend on the type: front_left/front_right/back_left/back_right or left/right. */
        public JsonObject motors = new JsonObject();
        /**
         * The side whose drive motors are mounted mirrored, so FORWARD power rolls those wheels
         * backward: "left", "right" or "none". It is the side your code reverses with
         * {@code setDirection(REVERSE)}. Left out, {@link #leftSideMirrored} decides.
         */
        public String mirroredSide;
        /** Older files: true is {@code "mirrored_side": "left"}, false is "none". */
        public boolean leftSideMirrored = true;

        /** The mirrored side, "left", "right" or "none". */
        public String mirroredSide() {
            if (mirroredSide == null) return leftSideMirrored ? "left" : "none";
            return mirroredSide.trim().toLowerCase(java.util.Locale.ROOT);
        }
        public double tractionMu = 0.9;
        public double rollerMu = 0.04;
    }

    public static final class Imu {
        public String name = "imu";
        public String logo = "UP";
        public String usb = "FORWARD";
    }

    /** Any non-drivetrain device. Only the fields relevant to its type are used. */
    public static final class Device {
        /** motor, servo, crservo, pinpoint, otos, distance, color, touch, digital_touch, webcam, analog, led, blinkin */
        public String type;
        public String name;
        public List<String> aliases = new ArrayList<>();
        // mounting (robot frame); roll only matters for webcams (turned clockwise, seen from behind)
        public double xM = 0, yM = 0, zM = 0.05, yawDeg = 0, pitchDeg = 0, rollDeg = 0;
        // motor
        public String motor;
        public double externalGearRatio = 1.0;
        public Load load = new Load();
        // servo
        public double rangeDeg = 300;
        public double speedDegPerSec = 400;
        public double rpm = 50;          // crservo
        // pinpoint
        public double xPodOffsetMm = -84;
        public double yPodOffsetMm = -168;
        public String pod = "4bar";
        // webcam: model (sets its USB ids, so the SDK finds its lens calibration), frame rate, and
        // the delay from exposure until VisionPortal gets the frame
        public String model;
        public double fps = 30;
        public double latencyMs = 50;
        // visual role in the browser view (intake, launcher, hood, feeder, arm, claw, lift...)
        public String role;
        /**
         * An intake (a motor or CR servo with role "intake"): which way its port turns to take a
         * game piece in. "forward" (the default): positive power takes pieces in, negative spits
         * them out. "reverse": the other way round, for an intake whose code sets the motor or
         * servo to REVERSE and takes pieces in with positive power.
         */
        public String intakeDirection;

        /** Whether this intake takes pieces in when its port turns backward. */
        public boolean intakeReversed() {
            return "reverse".equalsIgnoreCase(intakeDirection == null ? "" : intakeDirection.trim());
        }

        // analog: a constant voltage, or the servo or motor whose angle it reads (0-3.3 V per turn)
        public double volts = 0;
        public String encoderOf;
    }

    public static final class Load {
        /** free, roller, flywheel, arm, slide */
        public String kind = "free";
        public double inertia = 0.0005;
        public double massKg = 0.5;
        public double lengthM = 0.3;
        public double spoolDiameterM = 0.038;
        public double minDeg = -1e9;
        public double maxDeg = 1e9;
        public double startDeg = 0;
        public double maxExtensionM = 0.7;
        /** Flywheel: wheel diameter, which sets how fast the launcher throws a ball. */
        public double wheelDiameterM = 0.096;
    }

    public static final class Io {
        public double commandMs = 1.0;
        public double i2cMs = 2.5;
    }

    public transient Path source;
    /** The CAD model, its status, and what was assumed reading it; null without a {@code cad} section. */
    public transient ftcsim.cad.CadState cadState;

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeAdapterFactory(new CadShorthand())
            .create();

    /** Reads {@code "cad": "file"} as {@code "cad": {"file": "file"}}. */
    private static final class CadShorthand implements com.google.gson.TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> com.google.gson.TypeAdapter<T> create(Gson gson, com.google.gson.reflect.TypeToken<T> type) {
            if (type.getRawType() != Cad.class) return null;
            com.google.gson.TypeAdapter<Cad> object = (com.google.gson.TypeAdapter<Cad>) gson.getDelegateAdapter(this, type);
            return (com.google.gson.TypeAdapter<T>) new com.google.gson.TypeAdapter<Cad>() {
                @Override
                public void write(com.google.gson.stream.JsonWriter out, Cad value) throws IOException {
                    object.write(out, value);
                }

                @Override
                public Cad read(com.google.gson.stream.JsonReader in) throws IOException {
                    if (in.peek() != com.google.gson.stream.JsonToken.STRING) return object.read(in);
                    Cad c = new Cad();
                    c.file = in.nextString();
                    return c;
                }
            };
        }
    }

    public static RobotConfig load(Path file) throws IOException {
        return load(file, true);
    }

    /**
     * Reads a robot file. Without {@code withCad} the CAD model isn't read: enough to know the
     * robot's devices, and quick even for a big CAD assembly.
     */
    public static RobotConfig load(Path file, boolean withCad) throws IOException {
        RobotConfig c;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            c = GSON.fromJson(r, RobotConfig.class);
            if (c == null) throw new IOException("empty robot file " + file);
        } catch (com.google.gson.JsonParseException e) {
            // Not JSON, or a field of the wrong kind ("chassis": 5): Gson says where.
            String m = String.valueOf(e.getMessage()).replaceFirst("^[\\w.$]+(Exception|Error): ", "");
            int link = m.indexOf("\nSee ");
            throw new IllegalArgumentException("not a robot file: " + (link >= 0 ? m.substring(0, link) : m), e);
        }
        c.source = file;
        c.validate();
        if (c.cad != null && withCad) c.cadState = ftcsim.cad.CadState.load(c);
        return c;
    }

    /** This robot file's own start spot (x, y, heading in degrees) for a season, or null to use the season's. */
    public double[] startFor(String season) {
        com.google.gson.JsonElement e = start;
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonArray() && !"biobuzz".equals(season)) return null;   // a spot on the BIOBUZZ field
        if (e.isJsonObject()) {
            e = e.getAsJsonObject().get(season);
            if (e == null || e.isJsonNull()) return null;
        }
        com.google.gson.JsonArray a = e.getAsJsonArray();
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.size() > 2 ? a.get(2).getAsDouble() : 0};
    }

    /** The CAD model, if this robot has one and it is ready. */
    public ftcsim.cad.RobotModel model() {
        return cadState == null ? null : cadState.model;
    }

    public static Gson gson() {
        return GSON;
    }

    /**
     * Everything the simulator later reads from the file, checked now: a mistake is reported when
     * the file is read, naming the field, not as a NullPointerException where it is used.
     */
    private void validate() {
        if (chassis == null) throw new IllegalArgumentException("chassis must be an object, not null");
        if (drive == null) throw new IllegalArgumentException("drive must be an object, not null");
        if (drive.motors == null) throw new IllegalArgumentException("drive.motors must be an object, not null");
        if (imu == null) throw new IllegalArgumentException("imu must be an object such as {\"name\": \"imu\", \"logo\": \"UP\", \"usb\": \"FORWARD\"}, not null");
        if (devices == null) throw new IllegalArgumentException("devices must be a list, not null");
        if (io == null) throw new IllegalArgumentException("io must be an object, not null");
        if (chassis.widthM <= 0 || chassis.lengthM <= 0) throw new IllegalArgumentException("chassis size must be positive");
        if (!(drive.wheelDiameterMm > 0)) throw new IllegalArgumentException("drive.wheel_diameter_mm must be positive");
        if (cad == null && drive.trackWidthM / 2 > chassis.widthM / 2) throw new IllegalArgumentException("wheels must fit inside the chassis width");
        if (cad != null && (cad.file == null || cad.file.isBlank())) throw new IllegalArgumentException("cad needs a file (.step, .stp or .urdf)");
        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            if (d == null || d.type == null || d.name == null) {
                throw new IllegalArgumentException("devices[" + i + "]: every device needs a type and a name");
            }
            if (d.aliases == null) throw new IllegalArgumentException("device " + d.name + ": aliases must be a list of names, not null");
            if ("motor".equals(d.type) && d.load == null) throw new IllegalArgumentException("device " + d.name + ": load must be an object");
            if (d.intakeDirection != null) {
                if (!"forward".equalsIgnoreCase(d.intakeDirection.trim()) && !d.intakeReversed()) {
                    throw new IllegalArgumentException("device " + d.name + ": intake_direction must be \"forward\" or \"reverse\", not \""
                            + d.intakeDirection + "\"");
                }
                if (!"intake".equals(d.role) || !("motor".equals(d.type) || "crservo".equals(d.type))) {
                    throw new IllegalArgumentException("device " + d.name + ": intake_direction is for an intake, a motor or crservo with \"role\": \"intake\"");
                }
            }
        }
        if (!java.util.Set.of("left", "right", "none").contains(drive.mirroredSide())) {
            throw new IllegalArgumentException("drive.mirrored_side must be \"left\", \"right\" or \"none\", not \""
                    + drive.mirroredSide + "\"");
        }
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : drive.motors.entrySet()) {
            com.google.gson.JsonElement v = e.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("drive.motors." + e.getKey() + " must be the motor's name, not " + v);
            }
        }
        validateDriveMotors();
        validateUniqueNames();
        validateImu();
        validateStart();
        motorModel("drive.motor", drive.motor);
        for (Device d : devices) {
            if ("motor".equals(d.type)) motorModel("device " + d.name + ": motor", d.motor);
            if ("webcam".equals(d.type)) {
                try {
                    ftcsim.vision.WebcamModels.get(d.model);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("device " + d.name + ": model: " + e.getMessage(), e);
                }
            }
        }
    }

    /** A motor model from {@link MotorModels}; {@code field} names where it is given. */
    private static void motorModel(String field, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(field + " is missing: name the motor model, one of " + MotorModels.names());
        }
        try {
            MotorModels.get(model);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": " + e.getMessage(), e);
        }
    }

    /** The IMU's name, and a way to mount a hub: as RevHubOrientationOnRobot, which RobotRig builds from it. */
    private void validateImu() {
        if (imu.name == null || imu.name.isBlank()) throw new IllegalArgumentException("imu.name is missing");
        com.qualcomm.hardware.rev.RevHubOrientationOnRobot.LogoFacingDirection logo =
                facing("imu.logo", imu.logo, com.qualcomm.hardware.rev.RevHubOrientationOnRobot.LogoFacingDirection.class);
        com.qualcomm.hardware.rev.RevHubOrientationOnRobot.UsbFacingDirection usb =
                facing("imu.usb", imu.usb, com.qualcomm.hardware.rev.RevHubOrientationOnRobot.UsbFacingDirection.class);
        try {
            new com.qualcomm.hardware.rev.RevHubOrientationOnRobot(logo, usb);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("imu: logo " + imu.logo + " and usb " + imu.usb
                    + ": the logo and the USB ports can't face the same or opposite directions", e);
        }
    }

    private static <E extends Enum<E>> E facing(String field, String value, Class<E> type) {
        java.util.List<String> names = java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        String v = value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (v == null || !names.contains(v)) {
            throw new IllegalArgumentException(field + " must be one of " + String.join(", ", names) + ", not "
                    + (value == null ? "null" : "\"" + value + "\""));
        }
        return Enum.valueOf(type, v);
    }

    /** {@link #start}: {@code [x, y, heading]}, or season ids to those. */
    private void validateStart() {
        if (start == null || start.isJsonNull()) return;
        if (start.isJsonArray()) {
            spot("start", start);
        } else if (start.isJsonObject()) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : start.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonNull()) spot("start." + e.getKey(), e.getValue());
            }
        } else {
            throw new IllegalArgumentException("start must be {\"<season>\": [x, y, heading], ...} or a BIOBUZZ spot [x, y, heading], not " + start);
        }
    }

    private static void spot(String field, com.google.gson.JsonElement e) {
        boolean ok = e.isJsonArray() && e.getAsJsonArray().size() >= 2 && e.getAsJsonArray().size() <= 3;
        if (ok) {
            for (com.google.gson.JsonElement n : e.getAsJsonArray()) {
                ok &= n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber() && Double.isFinite(n.getAsDouble());
            }
        }
        if (!ok) throw new IllegalArgumentException(field + " must be [x, y] or [x, y, heading] (meters, degrees), not " + e);
    }

    /** Which motor key drives which side, for each drive type. */
    private static final java.util.Map<String, java.util.Map<String, String>> DRIVE_SIDES = java.util.Map.of(
            "mecanum", java.util.Map.of("front_left", "left", "back_left", "left", "front_right", "right", "back_right", "right"),
            "tank4", java.util.Map.of("front_left", "left", "back_left", "left", "front_right", "right", "back_right", "right"),
            "tank", java.util.Map.of("left", "left", "right", "right"));

    /**
     * A known drive type, a motor for each of its keys, and no motor on both sides (one motor may
     * drive several wheels of one side: a 2-motor tank4).
     */
    private void validateDriveMotors() {
        java.util.Map<String, String> sides = DRIVE_SIDES.get(drive.type);
        if (sides == null) {
            throw new IllegalArgumentException("drive.type must be \"mecanum\", \"tank\" or \"tank4\", not \"" + drive.type + "\"");
        }
        java.util.Map<String, String> sideOfMotor = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : new java.util.TreeMap<>(sides).entrySet()) {
            String motor = driveMotorName(e.getKey());
            if (motor == null || motor.isBlank()) {
                throw new IllegalArgumentException("drive.motors needs " + String.join(", ", new java.util.TreeSet<>(sides.keySet()))
                        + " for a " + drive.type + " drive (" + e.getKey() + " is missing)");
            }
            String other = sideOfMotor.put(motor, e.getValue());
            if (other != null && !other.equals(e.getValue())) {
                throw new IllegalArgumentException("drive.motors: \"" + motor + "\" drives wheels on both sides");
            }
        }
    }

    /**
     * Every name robot code can look up is one device's: a drive motor (which several wheels of
     * one side may share), the IMU, a device or an alias. Two devices with one name would be one
     * port, or one hardware map entry hiding the other, as a real robot's configuration rejects.
     */
    private void validateUniqueNames() {
        java.util.Map<String, String> owner = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : drive.motors.entrySet()) {
            String name = e.getValue().getAsString();
            owner.putIfAbsent(name, "drive.motors." + e.getKey());
        }
        claimName(owner, imu.name, "imu.name");
        for (Device d : devices) {
            claimName(owner, d.name, "a " + d.type + " in devices");
            if (d.aliases != null) for (String a : d.aliases) claimName(owner, a, "the aliases of " + d.name);
        }
    }

    private static void claimName(java.util.Map<String, String> owner, String name, String what) {
        String first = owner.putIfAbsent(name, what);
        if (first != null) {
            throw new IllegalArgumentException("the name \"" + name + "\" is used twice: by " + first + " and by " + what
                    + ". Every device needs its own name");
        }
    }

    public String driveMotorName(String key) {
        return drive.motors.has(key) ? drive.motors.get(key).getAsString() : null;
    }

    /** Every name robot code can look up in the hardware map (drive motors, IMU, devices and their aliases). */
    public java.util.Set<String> deviceNames() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : drive.motors.entrySet()) names.add(e.getValue().getAsString());
        names.add(imu.name);
        for (Device d : devices) {
            names.add(d.name);
            names.addAll(d.aliases);
        }
        return names;
    }
}
