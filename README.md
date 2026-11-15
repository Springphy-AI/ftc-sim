# FTC Simulator

[![Windows](https://github.com/leelening/ftc-sim/actions/workflows/windows.yml/badge.svg?branch=main)](https://github.com/leelening/ftc-sim/actions/workflows/windows.yml)
[![Ubuntu](https://github.com/leelening/ftc-sim/actions/workflows/ubuntu.yml/badge.svg?branch=main)](https://github.com/leelening/ftc-sim/actions/workflows/ubuntu.yml)
[![macOS](https://github.com/leelening/ftc-sim/actions/workflows/macos.yml/badge.svg?branch=main)](https://github.com/leelening/ftc-sim/actions/workflows/macos.yml)

Run your real FTC robot code (the `TeamCode` in your FtcRobotController project) against a
simulated robot on the **BIOBUZZ** (2026–27) field. Drive it from a browser Driver Station with
an Xbox/PlayStation controller or the keyboard. When it works in the simulator, deploy the
same code to the robot as usual. Nothing in TeamCode changes.

![Driver Station and 3D field](docs/screenshot.png)

## Quick start

1. Install a JDK 17 or newer (Android Studio's bundled JDK works, or [Temurin](https://adoptium.net)).
   `run-sim.bat` also finds a JDK unpacked in `%USERPROFILE%\.jdks`.
2. Start the simulator with your robot project:

   ```bat
   .\run-sim.bat --project "C:\path\to\FtcRobotController"
   ```

   On macOS and Linux: `sh run-sim.sh --project ~/FtcRobotController`. Without `--project` it
   runs the bundled examples and the official FTC samples.
3. Your browser opens at <http://localhost:8080>. Pick an OpMode, press **INIT**, then **▶ START**.

To avoid typing the path every time, copy `sim.properties.example` to `sim.properties` and set
`project=`.

## Several robots on one field

Up to four robots can share the field, two per alliance, as in a match. Each one is a separate
Robot Controller with its own TeamCode, OpMode, gamepads, telemetry and robot file. The robots
collide with each other, push each other and see each other with their distance sensors.

```bat
rem two robots running the bundled examples, to try it out
.\run-sim.bat --robots 2

rem your own code on two robots, as opponents (robot 1 red, robot 2 blue)
.\run-sim.bat --project "C:\path\to\FtcRobotController" --robots 2

rem your own code on two robots on the same alliance
.\run-sim.bat --project "C:\path\to\FtcRobotController" --robots 2 --alliance2 red

rem two projects: robot 1 runs one, robot 2 the other
.\run-sim.bat --project "C:\path\to\FtcRobotController" --project2 "C:\path\to\OtherFtcRobotController"

rem a full match: two red and two blue robots
.\run-sim.bat --project "C:\path\to\FtcRobotController" --robots 4
```

| Option | Sets |
|---|---|
| `--robots N` | number of robots (1–4). Robots without options of their own run robot 1's project and robot file |
| `--project2 DIR` … `--project4 DIR` | that robot's FtcRobotController project |
| `--robot2 FILE` … `--robot4 FILE` | that robot's description |
| `--alliance red\|blue`, `--alliance2` … `--alliance4` | that robot's alliance. Default: robot 1 red, robot 2 blue, robot 3 red, robot 4 blue |

The same keys work in `sim.properties`:

```properties
robots=2
project2=C:/path/to/OtherFtcRobotController
robot2=robots/tank.json
alliance2=red
```

### Two projects

Give each robot its own FtcRobotController folder with `--project2` (and `--project3`,
`--project4`). Any FtcRobotController folder works: a teammate's repository, another team's, or a
second clone of the official one.

- **Separate builds.** The simulator compiles each folder's `TeamCode/src/main/java` (and its
  FTC samples) on its own. Both projects can have classes with the same name, and static fields
  in one robot's code never affect the other robot.
- **Separate OpMode lists.** Each robot's Driver Station lists only its own project's OpModes. Its
  *Build* tab shows which folder it compiles and which files were skipped.
- **Separate rebuilds.** Saving a file in one project rebuilds only the robots that run it. If such
  a robot's OpMode is running, its rebuild waits until it stops; the other robots keep going.
- **Separate hardware.** `--robot2` gives robot 2 its own robot file. The device names in each
  project must match its robot's file.
- **Deploying.** The simulator never writes to either folder, so each project deploys to its own
  real robot as usual.

### Alliance partners and opponents

Robots alternate between the alliances by default (robot 1 red, robot 2 blue, and so on). Put two
robots on the same alliance with `--allianceN`, for example `--alliance2 red` to practice with an
alliance partner. An alliance has at most two robots.

- **Starting spots.** The robot file's `start` is the first spot of the red alliance. The red
  alliance's second robot starts beside it, mirrored across the field's center line. The blue
  spots are the red ones turned half a turn about the field center, as the field itself is. With
  `robots/mecanum.json` the red robots start 61 in from the center by the red wall, at y = −11.8 in
  and +11.8 in, facing the blue wall; the blue robots start at the matching spots by the blue wall.
- **Pre-loads.** Every simulated robot starts with its 4 POLLEN. A LOADING ZONE holds the pre-loads
  of its alliance's robots that aren't on the field: none when both of its robots are simulated.
- **Telling them apart.** With more than one robot, each robot's side plates take its alliance
  color and a numbered marker floats above it.

### Driving several robots

- **A card per robot.** The side panel starts with a card for every robot: its name and alliance,
  its state and time (an Autonomous with the 30 s timer counts down, e.g. *Running · 0:28 left*),
  its OpMode list, its INIT / START / STOP button and a stop button. If that robot's code throws an
  error, the error appears in its card, with *Dismiss* (and *Use tank.json* when another robot
  file has the missing device).
- **Starting a match.** Below the cards, *INIT all* initializes every stopped robot's selected
  OpMode, *START all* starts every robot in INIT at the same moment (so their 30 s timers line up),
  and *STOP all* stops them all. Each card's own buttons still start and stop that robot alone.
  *30 s auto timer* and *FTC samples* (also list the SDK's sample OpModes) apply to every robot.
- **The selected robot.** Click a robot's card to show its telemetry, gamepads, devices, build and
  log below; that card is outlined, and the section titles name the robot
  (*Telemetry · Robot 2*). The top bar shows that robot's TeamCode folder and robot file, and
  *Place robot* and the chase camera act on it.
- **Reset** starts a new match: every robot back at its start and the game pieces restaged, once
  every OpMode has stopped.
- **Controllers.** With *Auto*, the first controller drives robot 1's gamepad 1, the second
  controller robot 2's gamepad 1, and so on. The keyboard drives the robot on screen if it has no
  controller. Each robot's gamepad pickers can override this. Two drivers at one computer should
  share one browser window: browsers may send controller input only to the window in focus.
- **One window per driver.** `http://localhost:8080/?robot=2` opens a page that shows and drives
  only robot 2, for example on a second screen. Give each robot its own `?robot=N` page; a page
  without `?robot` sends input for every robot.
- **Kept apart.** Robots running the same project still get separate copies of TeamCode, so static
  fields aren't shared. `OpMode.blackboard`, `RobotLog`'s global error, and the bus latency from
  the robot file are also per robot.

## The edit → simulate → robot loop

- Edit TeamCode in Android Studio or VS Code as you normally do. When you save, the simulator
  recompiles within a second or two and the OpMode list refreshes. If an OpMode is running, the
  rebuild waits until you stop it.
- TeamCode is compiled the way Android Studio compiles it: **Java 8**, against the **FTC SDK 12.0**
  API. An automated test checks the simulator's SDK against the official SDK 12.0 jars, class by
  class and method by method, so code that compiles here also compiles for the robot.
- Files that use something the simulator does not model (for example an EasyOpenCV camera, or
  Android UI classes) are skipped and listed under **Build** with the reason. Everything else still
  runs.
- Never import `ftcsim.*` in TeamCode. Those are simulator internals that don't exist on the robot,
  and the simulator refuses to build a file that uses them.
- Deploy to the robot with your usual tools. The simulator never writes to your project.

## Driving

**Controller.** Plug in or pair an Xbox or PlayStation controller, click the simulator page, and
**press any button**: browsers only expose a controller after a button press. *Gamepad 1 = Auto*
uses the first controller. Rumble (`gamepad1.rumble(...)`, `rumbleBlips(...)`) plays on the
controller in Chrome and Edge (Firefox has no controller rumble), so use Chrome or Edge on any
operating system. The Xbox Guide button may be taken by the Windows Game Bar.

**Keyboard** (used for gamepad 1 when no controller is present, or pick *Keyboard* for either slot):

| Keys | Gamepad |
|---|---|
| W A S D | left stick (W = forward) |
| ← ↑ ↓ → | right stick |
| K · L · J · I | A · B · X · Y |
| Q · E | left / right bumper |
| Z · C | left / right trigger |
| T F G H | d-pad up / left / down / right |
| [ · ] | back · start |
| hold Shift | sticks at 40 % |

With one controller, set **Gamepad 2 → Keyboard** to try two-driver code.

**The example BIOBUZZ TeleOp** (`examples/teamcode`, default robot):

| Control | Does |
|---|---|
| left stick | drive. Robot-centric: forward = toward the intake, whichever way the robot faces |
| right stick X | turn |
| BACK / View (`[`) | switch to field-centric and back (forward = away from the driver, whichever way the robot faces) |
| A (`K`) | field-centric: make "away from the driver" the way the robot faces now |
| RT / LT (`C` / `Z`) | intake in / out |
| Y (`I`) | launcher on/off; the controller rumbles when it is up to speed |
| RB (`E`) | shoot (only once the launcher is up to speed) |
| d-pad up/down (`T` / `G`) | raise / lower the hood |
| d-pad left/right (`F` / `H`) | launcher speed down / up, 100 ticks/s per press (starts at 970) |

To score, shoot into the open end of your HIVE's raised CELL, not at the side of the HIVE. Each
CELL is a tube that is closed at the pivot end and open at the outer end. For red, line the robot
up with the red HIVE (x = −0.32 m) on the audience side, facing the rear wall, with its center
anywhere from y = −1.0 m (1.0 m from the field's center line) back to y = −1.6 m, where the
robot's back touches the audience wall. Blue is the mirror image from the rear side. From
anywhere in that range, the default hood and launcher speed drop the ball into the CELL. With
the 3 starting NECTAR in it, the 3rd POLLEN that lands tips the HIVE, and then its other CELL is
the raised one, open toward the rear wall.

## The Driver Station panel

- **OpMode list** with Autonomous and TeleOp groups. Tick *FTC samples* to show the FTC SDK samples,
  which are `@Disabled` on a real robot.
- **INIT / START / STOP** with the same rules as the real app. The optional **30 s auto timer** stops
  Autonomous OpModes and then preselects the TeleOp named in `preselectTeleOp`.
- **Errors** appear the way the Driver Station shows them, e.g.
  `User code threw an uncaught exception: IllegalArgumentException - Unable to find a hardware device with name "left_drive" and type DcMotor (at MyOpMode.init:42)`.
- **Telemetry**, including `telemetry.log()`, `Func` items, retained items, the 250 ms transmission
  interval and the HTML display format.
- **Devices**: every motor's mode, power, encoder, velocity and current; servo positions; sensor
  readings; hub LED and bulk-caching mode. **Robot log** shows `RobotLog` and `android.util.Log`.
- **3D view**: audience, overhead, red-driver and chase cameras. *Place robot* puts the robot
  anywhere (drag to aim it). *Reset* power-cycles it, which resets encoders and the IMU heading,
  puts it back at its start and restages the game pieces.
- **Camera**: while a `VisionPortal` streams, its live view, with the processors' overlays (tag
  outlines, axes, ids), like the Robot Controller's camera preview.

## Vision: VisionPortal and AprilTags

`VisionPortal`, `AprilTagProcessor`, `ColorBlobLocatorProcessor` and `PredominantColorProcessor`
work as on the robot, because they **are** the robot's code. The simulator runs FIRST's own
compiled classes from the FTC SDK 12.0 Vision package, unmodified, along with the pure-Java parts
of RobotCore they use (units, poses, matrices, camera calibration). The AprilTag detector is the
C library FIRST ships (OpenFTC's EOCV-AprilTag-Plugin 2.1.0), built from the same source for PCs
(see [`natives/apriltag/`](natives/apriltag/README.md)). OpenCV is 4.10.0, the robot's version.

Only what touches hardware is the simulator's:

- **The webcam**. It renders what it would see from where the robot file mounts it: the field, the
  HIVES with their real tag36h11 AprilTags (IDs 30–45), robots and balls. Its lens is the camera
  model's entry in the SDK's own calibration table. FIRST's AprilTag processor looks up the same
  entry by the webcam's USB ids, so poses come out right, just as with a calibrated webcam on the
  robot. Frames arrive at the camera's frame rate (default 30 fps), each showing the world as it was
  `latency_ms` earlier (default 50 ms), with that capture time.
- **The portal's plumbing**: opening the camera, camera states, `getFps()`, `stopStreaming()`,
  `saveNextFrameRaw()` (saved to `captures/`), the camera controls (exposure, gain, focus, white
  balance and PTZ settings are kept but don't change the image), and closing the portal when the
  OpMode stops.

With the season's tag library, the BIOBUZZ tags come back as FIRST's clusters (`RED SCORING`,
`BLUE AUDIENCE`, …), whose origin is the center of the CELL opening. As the SDK notes, the tags
move with the HIVE, so use them for aiming, not for locating the robot on the field.

The official vision samples are in `examples/samples` (`ConceptAprilTag`, `ConceptAprilTagEasy`,
`ConceptAprilTagLocalization`, `ConceptAprilTagOptimizeExposure`, `ConceptAprilTagMultiPortal`,
`ConceptVisionColorLocator_*`, `ConceptVisionColorSensor`, `RobotAutoDriveToAprilTag*`). To see
one work, place the default robot at x = −0.25 m, y = −1.15 m, heading 90° (facing the red HIVE),
INIT *Concept: AprilTag* and press START.

## Describing your robot

Robots are JSON files in `robots/`. Choose one with `--robot` or from the top bar.
**Device names must match your robot configuration on the Driver Station**, because your code
looks devices up by those names.

- `robots/mecanum.json`: 18 in mecanum robot (goBILDA 312 RPM, 104 mm wheels) using the SDK 12
  sample names `front_left_drive` …, plus `imu`, `pinpoint`, `sensor_otos`, `sensor_distance`,
  `sensor_color`, `sensor_touch`, `digitalTouch`, and a BIOBUZZ-style `intake`, `launcher`
  (flywheel), `hood` servo and `feeder` CR servo.
- `robots/tank.json`: six-wheel drop-center tank with `left_drive` / `right_drive`, an arm
  (`left_arm`, also `arm`) and claw servos (`left_hand`, `right_hand`), for the classic samples.

Main fields (meters and degrees; robot frame +x forward, +y left, +z up):

| Section | What it sets |
|---|---|
| `chassis` | size, mass, color |
| `drive` | `type` (`mecanum`, `tank`, `tank4`), wheel diameter, wheelbase, track width, `motor` model, external gear ratio, motor names, grip |
| `imu` | name and how the Control Hub is mounted (`logo`, `usb`, same words as `RevHubOrientationOnRobot`) |
| `devices` | `motor` (with a `load`: `roller`, `flywheel`, `arm` with gravity and hard stops, `slide`), `servo`, `crservo`, `pinpoint` (pod offsets, pod type), `otos`, `distance`, `color`, `touch`, `digital_touch`, `webcam` (`model`: `Logitech C920` (default), `C270`, `C310`, `Microsoft LifeCam HD-3000`, `goBILDA USB Camera`, `Arducam`; `fps`; `latency_ms`; plus `roll_deg` for a camera mounted turned); each with a mounting position (`x_m`, `y_m`, `z_m` from the floor under the robot's center, `yaw_deg`, `pitch_deg`) and an optional `role` for the 3D model |
| `io` | simulated bus latency (default 1 ms per hub command, 2.5 ms per I2C read) |
| `start` | starting pose `[x, y, heading]` on the field |

Motor models come from vendor datasheets: goBILDA 5203 (all ratios), REV HD Hex (spur and
UltraPlanetary), REV Core Hex, NeveRest, TETRIX. If you mount a sensor differently from what
your code assumes (for example the IMU orientation or the Pinpoint pod offsets), the readings
are wrong in the same way they would be on the robot.

## What is simulated

- **Physics** uses [ODE4j](https://github.com/tzaeschke/ode4j) at 1 kHz in real time. The robot
  is a chassis plus one body per wheel. Each mecanum wheel grips only along its roller axis, which
  is the standard way Gazebo models mecanum wheels, so strafing, wheel slip and pushing against
  walls behave physically. The robot collides with the walls, the HIVE structure's A-frames and
  the FLOWERS. Balls also hit the A-frame legs, the axle, the sign board and both HIVES' CELLS.
- **Motors** follow their torque-speed curve, sag the battery under load, and BRAKE/FLOAT
  correctly. The **hub firmware** implements `RUN_WITHOUT_ENCODER`, `RUN_USING_ENCODER`
  (velocity PIDF), `RUN_TO_POSITION`, `STOP_AND_RESET_ENCODER`, `setVelocity`, PIDF tuning,
  current and over-current, and **bulk caching** (`OFF` / `AUTO` / `MANUAL`). Encoders don't
  reset between OpModes, just as on a real robot.
- **Sensors**: the Control Hub IMU (`IMU`, yaw/pitch/roll, angular velocity, mounting
  orientation), goBILDA Pinpoint (its own odometry from your offsets, resolution and directions),
  SparkFun OTOS, REV 2m distance (raycast), REV Color Sensor V3 (tape and tiles under it), REV
  touch sensor / digital channel, and battery voltage.
- **Game pieces**: the 40 yellow POLLEN (7.1 cm) and 8 red + 8 blue NECTAR (9.1 cm), staged
  as Competition Manual section 10.3.1 sets them up: 4 POLLEN in each FLOWER, 4 in each GARDEN and
  4 pre-loaded in each simulated robot; the absent robots' pre-loads sit in the middle of their
  LOADING ZONE against the wall; 3 NECTAR in each upward-facing CELL and 5 in each ALLIANCE AREA. Loose
  balls roll and bounce, slow down with air drag in flight and rolling resistance on the tiles,
  and the robot pushes them. A robot with an `intake` role picks up balls
  in front of its roller (reverse it to spit one out) and holds at most 4 (rule G407); a `feeder`
  then sends them into the `launcher`, which throws them at about half the flywheel's surface
  speed, at an angle set by the `hood` servo.
- **HIVES** are solid seesaws. Each CELL has a floor, side walls and a peaked roof, is closed at
  the end next to the pivot and open at the outer end. A ball that goes in through the open end of
  an upward-facing CELL rolls to the back and stays; one that lands on the roof bounces off; one
  in a downward-facing CELL rolls out. Each HIVE turns on its pivot between stops at +-30 degrees
  and is bi-stable like the real one: it holds its position until enough weight lands in the
  upward-facing CELL, then tips (slowed by a damper) and dumps what was in that CELL. It is
  calibrated to the Event Field Setup Guide, section 12.3: a POLLEN tossed onto 3 NECTAR + 1 POLLEN
  or onto 6 POLLEN does not tip it, one tossed onto 3 NECTAR + 2 POLLEN or onto 7 POLLEN does. With
  the 3 starting NECTAR, 2 POLLEN launched by the robot do not tip it and a 3rd does.
  Just over the tipping point it starts slowly: the HIVE can creep for a few seconds before it
  swings over. The
  top bar shows how many balls are in each HIVE's CELLS (audience / rear) and how many times it
  has tipped. That is not a MATCH score. Balls in FLOWERS and ALLIANCE AREAS stay put (those
  holders are not modeled as containers yet). *Reset* also resets the HIVES and the game pieces.
- **Human player NECTAR**: press **N** (red) or **M** (blue), or the *+ Red NECTAR* / *+ Blue
  NECTAR* buttons, to put one NECTAR from that ALLIANCE AREA into its LOADING ZONE, at rest at
  the first free spot along the alliance wall. Each button shows how many are left (5 per color
  until *Reset*). The robot can intake and launch NECTAR like POLLEN.
- **OpMode lifecycle**: iterative and linear OpModes, `waitForStart`, `opModeInInit`,
  `isStopRequested`, thread interruption on STOP, `requestOpModeStop`, `terminateOpModeNow`,
  `OpMode.blackboard`. When an OpMode ends, motors stop and servos go limp.
- **Loop timing**: hardware calls take about as long as on a real Control Hub, so control-loop
  tuning carries over.

**Not simulated yet**: scoring, taking POLLEN out of a FLOWER, LEDs and sounds, Limelight,
OctoQuad, legacy BNO055 / Modern Robotics sensors, switchable webcams, cameras driven through
EasyOpenCV directly (`OpenCvCameraFactory`), lens distortion and motion blur in the webcam image.
OpModes that use them are skipped at build time with the reason.

## Checking the simulator itself

```bat
gradlew test
```

The suite includes:

- `ApiConformanceTest`: the simulator's SDK must be a subset of the official FTC SDK 12.0. It
  downloads the official jars from Maven Central.
- `GameElementsTest`: POLLEN and NECTAR counts and staging per section 10.3.1, balls at rest,
  pushing, intake (4-ball limit), launching, and the CELLS: NECTAR stays in the upward-facing
  CELLS, a ball shot into the open end stays, a ball dropped on the roof bounces off, and a ball
  in a downward-facing CELL rolls out, and a ball thrown at a CELL's side bounces off.
- `HiveTipTest`: the HIVE calibration table from the Event Field Setup Guide (section 12.3), with
  balls gently placed or tossed in, and a tip dumping the CELL and turning the other CELL up.
- `DrivetrainPhysicsTest`, `RestDriftTest`: speed, strafing, turning, RUN_TO_POSITION, wall
  contact, no drift at rest.
- `MultiRobotTest`: two robots on one field. Each has its own TeamCode class loader, starting
  spot, pre-load, blackboard and gamepads; one robot pushes the other; swapping one robot's file
  leaves the other alone; alliance partners start side by side and an alliance takes at most two
  robots; *INIT all* / *START all* / *STOP all* run both robots' Autonomous together.
- `VisionLinkageTest`: every class, method and field that FIRST's Vision and RobotCore classes
  refer to exists in the simulator, so a missing stand-in can't surface as a `NoSuchMethodError`
  in your OpMode. `AndroidMatrixTest` checks the `android.opengl.Matrix` stand-in they compute with.
- `AprilTagVisionTest`: FIRST's `AprilTagProcessor` on the simulated webcam finds the HIVE's tags,
  the poses it solves match where the camera and tags really are to about a millimeter, and the
  `RED SCORING` cluster origin it reports sits at the CELL opening. `CameraStreamTest`: the live
  view reaches the Driver Station page.
- `SampleOpModesTest`, `ExampleOpModesTest`: the official samples and the examples run end to
  end. For example, `RobotAutoDriveByGyro_Linear` returns to within about 10 cm of its start, the
  Pinpoint auto to within 0.2 in, and from the near end, the middle and the far end of its
  documented shooting range the BIOBUZZ TeleOp's first 2 shots land in the red HIVE's CELL and
  the 3rd tips the HIVE.

On GitHub, these tests run on Windows, Ubuntu and macOS (JDK 21, plus JDK 17 on Ubuntu) for every
push to `main`/`master` and every pull request. Each OS has its own workflow (`windows.yml`,
`ubuntu.yml`, `macos.yml`) and its own badge at the top of this page. All three call the same
steps in [`.github/workflows/test.yml`](.github/workflows/test.yml). On each OS the simulator is
also started with `run-sim.bat` or `run-sim.sh` against a robot project whose path contains
spaces. The run then checks that TeamCode compiles with no files skipped and that the Driver
Station page loads.

## Project layout

| Path | Contents |
|---|---|
| `sdk/` | The FTC SDK 12.0 API on a PC: FIRST's own classes where they are plain Java (Vision, RobotCore math), stand-ins with the same packages and signatures for the rest; simulator internals are in `ftcsim.hal` |
| `natives/apriltag/` | FIRST's AprilTag detector compiled for PCs (Windows x64 and Linux x64 so far), with the script that builds it |
| `engine/` | Physics, robot model, OpMode runner, TeamCode compiler, web server, browser UI (`src/main/resources/web`) |
| `examples/teamcode/` | Example OpModes for the default robot |
| `examples/samples/` | The official FTC sample OpModes that run in the simulator (unmodified) |
| `robots/` | Robot descriptions |

## License

The simulator is released under the [MIT License](LICENSE).

Third-party code in this repository keeps its own license:

| Path | What | License |
|---|---|---|
| `examples/samples/` | FTC SDK sample OpModes, © FIRST | BSD-3-Clause-Clear ([LICENSE-FTC-SDK](examples/samples/LICENSE-FTC-SDK)) |
| `engine/src/main/resources/web/vendor/` | Three.js and OrbitControls, © three.js authors | MIT ([THREE-LICENSE](engine/src/main/resources/web/vendor/THREE-LICENSE)) |
| `natives/apriltag/` | AprilTag detector, © The Regents of The University of Michigan, and its JNI glue, © OpenFTC Team (compiled) | BSD-2-Clause ([LICENSE-apriltag](natives/apriltag/LICENSE-apriltag.md)), MIT ([LICENSE-eocv-apriltag-plugin](natives/apriltag/LICENSE-eocv-apriltag-plugin.txt)) |
| `engine/src/main/java/ftcsim/vision/Tag36h11.java` | tag36h11 codes from AprilRobotics/apriltag | BSD-2-Clause ([LICENSE-apriltag](natives/apriltag/LICENSE-apriltag.md)) |

Libraries that Gradle downloads at build time: ODE4j (LGPL-2.1-or-later or BSD-style, at your
choice), Javalin, Gson and Kotlin stdlib (Apache-2.0), Jetty (Apache-2.0 or EPL-2.0), SLF4J (MIT),
FIRST's FTC SDK packages RobotCore and Vision (BSD-3-Clause), OpenFTC's AprilTag plugin (MIT),
and OpenCV with JavaCPP and OpenBLAS from Bytedeco (Apache-2.0, BSD-3-Clause).

The field layout follows the *BIOBUZZ presented by RTX* Competition Manual (FIRST Tech Challenge
2026–27). FIRST®, FIRST® Tech Challenge and BIOBUZZ™ are trademarks of FIRST (For Inspiration and
Recognition of Science and Technology). This project is not affiliated with or endorsed by FIRST.
