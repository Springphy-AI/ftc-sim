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
    /** Starting pose on the field: x, y in meters, heading in degrees (0 faces +x, the blue wall). */
    public double[] start = {-1.55, -0.30, 0};

    public static final class Chassis {
        public double lengthM = 0.4572;
        public double widthM = 0.4572;
        public double frameHeightM = 0.09;
        public double groundClearanceM = 0.025;
        public double massKg = 13.0;
        public String color = "#b3261e";
    }

    public static final class Drive {
        /** "mecanum", "tank" (2 motors, traction rear + omni front) or "tank4" (4 motors). */
        public String type = "mecanum";
        public double wheelDiameterMm = 104;
        public double wheelbaseM = 0.336;
        public double trackWidthM = 0.38;
        public String motor = "goBILDA 5203 312rpm";
        public double externalGearRatio = 1.0;
        /** Keys depend on the type: front_left/front_right/back_left/back_right or left/right. */
        public JsonObject motors = new JsonObject();
        /** Motors on the left side are mounted mirrored, so positive power drives those wheels backward. */
        public boolean leftSideMirrored = true;
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
        /** motor, servo, crservo, pinpoint, otos, distance, color, touch, digital_touch, webcam */
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

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    public static RobotConfig load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            RobotConfig c = GSON.fromJson(r, RobotConfig.class);
            if (c == null) throw new IOException("empty robot file " + file);
            c.source = file;
            c.validate();
            return c;
        }
    }

    public static Gson gson() {
        return GSON;
    }

    private void validate() {
        if (chassis.widthM <= 0 || chassis.lengthM <= 0) throw new IllegalArgumentException("chassis size must be positive");
        if (drive.trackWidthM / 2 > chassis.widthM / 2) throw new IllegalArgumentException("wheels must fit inside the chassis width");
        for (Device d : devices) {
            if (d.type == null || d.name == null) throw new IllegalArgumentException("every device needs a type and a name");
        }
        MotorModels.get(drive.motor);
        for (Device d : devices) {
            if ("motor".equals(d.type)) MotorModels.get(d.motor);
            if ("webcam".equals(d.type)) ftcsim.vision.WebcamModels.get(d.model);
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
