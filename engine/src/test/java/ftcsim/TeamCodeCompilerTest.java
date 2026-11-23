package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.runner.OpModeInfo;
import ftcsim.runner.TeamCodeCompiler;

/**
 * The tolerant build: a file that doesn't compile here is set aside with the reason, and every
 * other file still builds, whatever the number of errors and however the files depend on each other.
 */
class TeamCodeCompilerTest {

    @TempDir
    Path tmp;

    private Path teamCode;

    private void source(String path, String text) throws Exception {
        Path f = teamCode.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private TeamCodeCompiler.Result build() throws Exception {
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(teamCode, false)),
                Files.createDirectories(tmp.resolve("out")));
        return compiler.compile();
    }

    private static List<String> opModes(TeamCodeCompiler.Result r) {
        return r.opModes().stream().map(OpModeInfo::name).sorted().collect(Collectors.toList());
    }

    private static String opMode(String pkg, String name) {
        return "package " + pkg + ";\nimport com.qualcomm.robotcore.eventloop.opmode.*;\n"
                + "@TeleOp(name = \"" + name + "\") public class " + name + " extends LinearOpMode { public void runOpMode() { } }\n";
    }

    @Test
    void aLongChainOfFilesThatNeedAMissingLibraryLeavesTheOthersBuilt() throws Exception {
        teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java"));
        // C00 uses a library the simulator doesn't have. C01 compiles against C00 while C00 is there
        // (with its error) and fails only once C00 is set aside, C02 once C01 is, and so on: a
        // round of javac per link, more links than rounds the build used to allow.
        source("t/C00.java", "package t; import com.acmerobotics.roadrunner.Pose2d; public class C00 { Pose2d p; }\n");
        for (int i = 1; i <= 13; i++) {
            source(String.format("t/C%02d.java", i), String.format("package t; public class C%02d extends C%02d { }\n", i, i - 1));
        }
        source("t/AGood.java", opMode("t", "AGood"));
        source("t/ZGood.java", opMode("t", "ZGood"));

        TeamCodeCompiler.Result r = build();
        System.out.println(r.message());
        assertTrue(r.ok(), r.message());
        assertEquals(List.of("AGood", "ZGood"), opModes(r), "both good OpModes, whatever their names sort to");
        assertEquals(14, r.skipped().size(), "C00 to C13, each with its reason: " + r.skipped());
        assertEquals(2, r.compiledFiles());
        assertTrue(r.message().startsWith("Built 2 files"), r.message());
    }

    @Test
    void aRoadRunnerProjectBuildsItsSelfContainedOpModes() throws Exception {
        teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java"));
        // The shape of a Road Runner quickstart with a few seasons' copies of its drive class kept in
        // TeamCode: every drive class has well over javac's default 100 errors (each Road Runner type
        // it uses, several times), and the team's classes use each other on top of them.
        String[] rr = ("Pose2d Vector2d Rotation2d PoseVelocity2d Twist2dDual Pose2dDual DualNum Time Action Actions "
                + "TrajectoryActionBuilder TrajectoryBuilderParams TurnConstraints VelConstraint AccelConstraint "
                + "AngularVelConstraint MinVelConstraint ProfileAccelConstraint ProfileParams MecanumKinematics "
                + "MotorFeedforward HolonomicController TimeTrajectory TimeTurn Vector2dDual").split(" ");
        for (String pkg : List.of("", ".old2022", ".old2023", ".old2024", ".old2025")) {
            for (String name : List.of("MecanumDrive", "TankDrive", "ThreeDeadWheelLocalizer")) {
                StringBuilder s = new StringBuilder("package org.firstinspires.ftc.teamcode" + pkg + ";\n");
                for (String t : rr) s.append("import com.acmerobotics.roadrunner.").append(t).append(";\n");
                s.append("public class ").append(name).append(" {\n");
                for (int k = 0; k < 4; k++) for (String t : rr) s.append("    ").append(t).append(" f").append(t).append(k).append(";\n");
                s.append("    public ").append(name).append("(com.qualcomm.robotcore.hardware.HardwareMap h) { }\n}\n");
                source("org/firstinspires/ftc/teamcode" + pkg.replace('.', '/') + "/" + name + ".java", s.toString());
            }
        }
        String team = "package org.firstinspires.ftc.teamcode;\nimport com.qualcomm.robotcore.hardware.HardwareMap;\n";
        source("org/firstinspires/ftc/teamcode/Robot.java", team
                + "public class Robot { public final MecanumDrive drive; public Robot(HardwareMap h) { drive = new MecanumDrive(h); } }\n");
        source("org/firstinspires/ftc/teamcode/RobotHardware.java", team
                + "public class RobotHardware { public final Robot robot; public RobotHardware(HardwareMap h) { robot = new Robot(h); } }\n");
        source("org/firstinspires/ftc/teamcode/teleop/MainTeleOp.java", "package org.firstinspires.ftc.teamcode.teleop;\n"
                + "import com.qualcomm.robotcore.eventloop.opmode.*;\nimport org.firstinspires.ftc.teamcode.RobotHardware;\n"
                + "@TeleOp public class MainTeleOp extends LinearOpMode { public void runOpMode() { new RobotHardware(hardwareMap); } }\n");
        source("org/firstinspires/ftc/teamcode/teleop/BasicMecanum.java", opMode("org.firstinspires.ftc.teamcode.teleop", "BasicMecanum"));

        TeamCodeCompiler.Result r = build();
        System.out.println(r.message());
        r.skipped().forEach(s -> System.out.println("  skipped " + s.file() + ": " + s.reason()));
        assertTrue(r.ok(), r.message());
        assertEquals(List.of("BasicMecanum"), opModes(r));
        assertEquals(18, r.skipped().size(), "the 15 Road Runner classes, Robot, RobotHardware and MainTeleOp: " + r.skipped());
        // The page underlines a file's first errors and says how many more there are, not all of them.
        String drive = "org/firstinspires/ftc/teamcode/MecanumDrive.java";
        List<TeamCodeCompiler.Problem> driveProblems = r.problems().stream().filter(p -> p.file().equals(drive)).toList();
        assertEquals(TeamCodeCompiler.PROBLEMS_PER_FILE + 1, driveProblems.size());
        String last = driveProblems.get(driveProblems.size() - 1).message();
        assertTrue(last.matches("and \\d+ more errors in this file"), last);
        assertTrue(r.problems().size() <= 18 * (TeamCodeCompiler.PROBLEMS_PER_FILE + 1), "problems: " + r.problems().size());
    }

    @Test
    void aBuildWhoseFilesAllFailLeavesNoClassesBehind() throws Exception {
        teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java"));
        source("t/A.java", "package t; import com.acmerobotics.roadrunner.Pose2d; public class A { Pose2d p; }\n");
        source("t/B.java", "package t; public class B extends A { }\n");
        TeamCodeCompiler.Result r = build();
        assertTrue(r.ok(), r.message());
        assertEquals(0, r.compiledFiles());
        assertEquals(List.of(), opModes(r));
        assertEquals(2, r.skipped().size(), r.skipped().toString());
    }
}
