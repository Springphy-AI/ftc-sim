# FTC Simulator

[![Windows](https://github.com/Springphy-AI/ftc-sim/actions/workflows/windows.yml/badge.svg?branch=main)](https://github.com/Springphy-AI/ftc-sim/actions/workflows/windows.yml)
[![Ubuntu](https://github.com/Springphy-AI/ftc-sim/actions/workflows/ubuntu.yml/badge.svg?branch=main)](https://github.com/Springphy-AI/ftc-sim/actions/workflows/ubuntu.yml)
[![macOS](https://github.com/Springphy-AI/ftc-sim/actions/workflows/macos.yml/badge.svg?branch=main)](https://github.com/Springphy-AI/ftc-sim/actions/workflows/macos.yml)

Run your real FTC robot code (the `TeamCode` in your FtcRobotController project) against a
simulated robot on the **BIOBUZZ** (2026–27) field, or on another season's field: **DECODE**
(2025–26) is included, and each new game is added as a [season pack](#seasons). Drive it from a
browser Driver Station with an Xbox/PlayStation controller or the keyboard. When it works in the
simulator, deploy the same code to the robot as usual. Nothing in TeamCode changes.

![Driver Station and 3D field](docs/screenshot.png)

## Quick start

1. Have a JDK 17 or newer. Android Studio's own JDK is enough: the launchers find it where
   Android Studio installs it. Otherwise install one, such as [Temurin](https://adoptium.net).
   `run-sim.bat` and `run-sim.sh` use `JAVA_HOME` if it is a JDK 17 or newer, else the JDK of the
   `javac` on `PATH` if it is, else the newest JDK 17 or newer, by version, among those unpacked
   in `%USERPROFILE%\.jdks` or `~/.jdks` (on macOS, the `Contents/Home` layout too) and Android
   Studio's. They pass over an older `JAVA_HOME` or `javac`, and macOS's placeholder `javac` that
   says "Unable to locate a Java Runtime".
2. Start the simulator with your robot project:

   ```bat
   .\run-sim.bat --project "C:\path\to\FtcRobotController"
   ```

   On macOS and Linux: `sh run-sim.sh --project ~/FtcRobotController`. Without `--project` it
   runs the bundled examples and the official FTC samples. The first start downloads Gradle and
   the libraries the simulator uses (a few hundred MB) and builds it, quietly, which takes a few
   minutes; later starts take seconds.
3. Your browser opens at <http://localhost:8080>. Pick an OpMode, press **INIT**, then **▶ START**.
   If another program has port 8080 (at `127.0.0.1` or at `[::1]`), the simulator takes the next
   free one up to 8089, and says which.

To avoid typing the path every time, copy `sim.properties.example` to `sim.properties` and set
`project=`. A Windows path can be written as it is (`C:\Users\you\...`).

`run-sim` builds the simulator when its code has changed (after a `git pull`, say) and starts it
from a copy of the build in `build/run/`, so a second simulator started later never rewrites the
files a running one is using; copies no simulator uses any more are deleted by themselves.

### All options

`--help` lists them too. Options on the command line win over `sim.properties`.

| Option | `sim.properties` key | Sets |
|---|---|---|
| `-p`, `--project DIR` | `project` | your FtcRobotController project (the folder with `TeamCode`); without it, the bundled examples |
| `-r`, `--robot FILE` | `robot` | the robot file; default `robots/mecanum.json` |
| `-s`, `--season NAME` | `season` | the game: a season in `seasons/` (`biobuzz`, `decode`) or a season folder; default `biobuzz` |
| `--alliance A` | `alliance` | robot 1's alliance, `red` (default) or `blue` |
| `--port N` | `port` | the Driver Station page's port; default 8080, or the next free one up to N+9 |
| `--no-browser` | `open_browser=false` | don't open the page in the browser |
| `--robots N` | `robots` | how many robots (1–4); on the command line, robots after N that `sim.properties` sets up are left out |
| `--project2 DIR` … `--project4 DIR`, `--robot2 FILE` …, `--alliance2 A` … | `project2`, `robot2`, `alliance2` … | robot 2–4's own project, robot file and alliance ([Several robots on one field](#several-robots-on-one-field)) |
| `-h`, `--help` | | print the options and stop |

A relative `--project` is where you typed the command. A relative `--robot` or `--season` is
looked for there first, then in the simulator's folder (so `--robot robots/tank.json` works from
anywhere). Paths in `sim.properties` are relative to the simulator's folder. A wrong option or
value is explained in a sentence.

## Run it in GitHub Codespaces

The simulator also runs in a [GitHub codespace](https://docs.github.com/en/codespaces): a
computer in the cloud that you use from the browser, with nothing to install. A personal GitHub
account may use 120 core hours a month for free, which is 60 hours on the standard 2-core machine.

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/Springphy-AI/ftc-sim)

- **To try it**, open this repository in a codespace (the button above). The first start builds
  the simulator, which takes a few minutes. Then it runs the bundled examples, and the Driver
  Station page opens in a new browser tab. Later starts take seconds.
- **With your team's code**, copy [`tools/codespaces/devcontainer.json`](tools/codespaces/devcontainer.json)
  to `.devcontainer/devcontainer.json` in your FtcRobotController repository, and open that
  repository in a codespace. The codespace downloads the simulator next to your project and runs
  your TeamCode on it. You edit, commit and push as in any codespace, whether the repository is
  private or not. The file changes nothing in the Android Studio build.
- The page is at `https://<codespace name>-8080.app.github.dev`, and in VS Code's **Ports** tab.
  Keep the port private, as it starts: whoever can open the page can change your TeamCode and run
  it.
- **Deploy** can't reach a robot from the cloud: deploy from Android Studio, or from a simulator
  on the computer next to the robot.
- A codespace stops after 30 minutes without use, and its core hours stop counting then. Your
  files stay.

## Folder layout

Without options, the simulator runs its own example robots (`robots/`) and example TeamCode
(`examples/teamcode`). To run your team's code on your own robot, keep three folders side by side:

```
FTC/
  FtcRobotController/      your team's robot code
  ftc-sim/                 this simulator
  cad_robots/              your robots' CAD and robot profiles
    OurRobot.step
    sim-profiles/
      our-robot.json
```

Then start the simulator from `ftc-sim/` (the folder `git clone` makes) with your project and
your robot's profile:

```sh
sh run-sim.sh --project ../FtcRobotController --robot ../cad_robots/sim-profiles/our-robot.json
```

```bat
.\run-sim.bat --project ..\FtcRobotController --robot ..\cad_robots\sim-profiles\our-robot.json
```

| Folder | What it is for | What it needs |
|---|---|---|
| `FtcRobotController/` | The code your team writes and deploys to the real robot. The simulator compiles its `TeamCode` as it is and never adds files to it. Push it to your team's repository as usual. | Your team's FtcRobotController project, with OpModes in `TeamCode/src/main/java`. |
| `ftc-sim/` | The simulator: the engine, the seasons, the Driver Station page and the example robots. Your team's files don't go here, so you can update it with `git pull` at any time. | Nothing of yours. |
| `cad_robots/` | Your robots: their CAD and the robot profiles the simulator runs. It is outside both repositories, so your CAD and profiles stay private unless you choose to share them. | One STEP export per robot (see [Robots from CAD](#robots-from-cad)), and in `sim-profiles/` one robot profile per robot ([Describing your robot](#describing-your-robot)). |

Each robot profile in `cad_robots/sim-profiles/` should have:

- **The devices**, named exactly as in your Driver Station configuration, because your TeamCode looks
  them up by those names. Start from `robots/mecanum.json` (or `tank.json`) and rename them.
- **The CAD**: `"cad": {"file": "../OurRobot.step"}`, a path relative to the profile. The first run
  converts it in the browser and saves `OurRobot.step.cadmesh` next to the STEP file. A whole
  competition robot can be too big for the browser: convert it with `tools/step2cadmesh.py`
  instead ([Big assemblies](#big-assemblies)).
- **Where it starts autonomous**: `"start": {"biobuzz": [x, y, heading]}`, in meters and degrees in
  field coordinates (the field's center is 0, 0). The robot is placed there at launch and on every
  Reset. Give your autonomous the same pose at START, for example
  `pinpoint.setPosition(new Pose2D(DistanceUnit.INCH, x / 0.0254, y / 0.0254, AngleUnit.DEGREES, heading))`,
  so the code knows where the robot is. For several starting spots, make one profile per spot.
- **The robot's real numbers**: `chassis.mass_kg`, the drive motor and gear ratio,
  `mirrored_side`, the odometry pod offsets and the camera's position.

The simulator lists the profiles in the robot file's folder in the Driver Station's robot menu,
and *+ From CAD* saves new robots in that folder too, so with this layout they stay in `cad_robots/`.

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
- **Deploying.** Each project deploys to its own real robot: with your usual tools, or from the
  simulator's code editor, which builds the project of the robot shown and installs it on the
  robot you pick ([Editing and deploying in the simulator](#editing-and-deploying-in-the-simulator)).
  The simulator writes to a project only when you save a file in its code editor.
- **Editing.** The code editor shows the TeamCode of the robot you clicked. Two robots that run
  the same folder share its files, so saving there rebuilds both; give a robot its own folder with
  `--project2` to edit its code apart.

### Alliance partners and opponents

Robots alternate between the alliances by default (robot 1 red, robot 2 blue, and so on). Put two
robots on the same alliance with `--allianceN`, for example `--alliance2 red` to practice with an
alliance partner. An alliance has at most two robots.

- **Starting spots.** Each alliance's two spots come from the season ([Seasons](#seasons)). On
  BIOBUZZ the red robots start 61 in from the center by the red wall, at y = −11.8 in and
  +11.8 in, facing the blue wall, and the blue robots at the matching spots by the blue wall (the
  red ones turned half a turn about the field center, as the field itself is). A robot file with
  its own `start` for the season starts there as the first red robot; its partner starts
  mirrored across the field's center line, facing the mirrored way (or beside it, facing the same
  way, when the spot is on that line).
- **Pre-loads.** Every simulated robot starts with its 4 POLLEN. A LOADING ZONE holds the pre-loads
  of its alliance's robots that aren't on the field: none when both of its robots are simulated.
- **Telling them apart.** With more than one robot, each robot's side plates take its alliance
  color and a numbered marker floats above it.

### Driving several robots

- **A card per robot.** The side panel starts with a card for every robot: its name and alliance,
  its TeamCode folder and battery voltage, its state and time (an Autonomous with the 30 s timer
  counts down, e.g. *Running · 0:28 left*), its OpMode list, its INIT / START / STOP button and a
  stop button, and below them its robot file and *+ From CAD*
  (see [Robots from CAD](#robots-from-cad)); both work while that robot is stopped. If that
  robot's code throws an error, the error appears in its card, with *Dismiss* (and
  *Use tank.json* when another robot file has the missing device).
- **Starting a match.** Below the cards, *INIT all* initializes every stopped robot's selected
  OpMode, *START all* starts every robot in INIT at the same moment (so their 30 s timers line up),
  and *STOP all* stops them all. Each card's own buttons still start and stop that robot alone.
  *30 s auto timer* and *FTC samples* (also list the SDK's sample OpModes) apply to every robot.
- **The selected robot.** Click a robot's card to show its telemetry, gamepads, devices, build and
  log below; that card is outlined, and the section titles name the robot
  (*Telemetry · Robot 2*), and *Place robot* and the chase camera act on it. The top bar keeps
  what the whole field shares: the connection, the HIVEs and the real-time rate.
- **Reset** starts a new match: every robot back at its start and the game pieces restaged, once
  every OpMode has stopped.
- **Controllers.** With *Auto*, the first controller drives robot 1's gamepad 1, the second
  controller robot 2's gamepad 1, and so on. The keyboard drives the robot on screen if it has no
  controller. Each robot's gamepad pickers can override this. *Auto* takes only controllers the
  browser maps in the standard layout (the Xbox, PlayStation and Logitech pads FTC allows); others
  are marked in the pickers and drive only when picked there. Two drivers at one computer should
  share one browser window: browsers may send controller input only to the window in focus.
- **One window per driver.** `http://localhost:8080/?robot=2` opens a page that shows and drives
  only robot 2, for example on a second screen. Pages don't get in each other's way:
  - Controllers drive from pages in sight, not from a background tab or a minimized window.
  - The keyboard drives from the page in focus once you press a driving key there, until that
    page loses the focus. A tab just opened, or left open from an earlier run, stays quiet.
  - A `?robot=N` page takes its controllers first: a page showing every robot leaves those
    gamepads to it (its picker says *its own page (?robot=N)*), and `?robot=N` pages share the
    controllers in robot order.
  - The simulator also ignores one page's "nothing pressed" while another page holds a button or
    stick on that gamepad, so a page still running older page code can't let go of the driver's.
  - When a page closes or crashes, the gamepads it drove are let go.
  - A `?robot=N` page whose robot isn't on the field (the simulator restarted with fewer) shows
    the last robot with its controls off, and robot N again once it is back.
- **Kept apart.** Robots running the same project still get separate copies of TeamCode, so static
  fields aren't shared. `OpMode.blackboard`, `RobotLog`'s global error, and the bus latency from
  the robot file are also per robot.

## Seasons

Every FTC season is a different game. The simulator keeps what stays the same (the 12 ft field,
tiles and perimeter, the physics, your robot, the SDK, the Driver Station) apart from what changes
each year (the field's structures and tape, the game pieces and how they start a match, the
AprilTags, the human player). Each game is a **season pack** in `seasons/`:

| Season | Pack | |
|---|---|---|
| BIOBUZZ presented by RTX (2026–27) | `seasons/biobuzz/` | the default |
| DECODE presented by RTX (2025–26) | `seasons/decode/` | |

Pick one with `--season`, or `season=` in `sim.properties`:

```bat
.\run-sim.bat --season decode --project "C:\path\to\FtcRobotController"
```

The top bar shows the season, and the 3D view's camera presets, the human player buttons and the
numbers in the top bar follow it. Field coordinates are that season's official FTC field
coordinates, the same ones as the SDK's tag library for the season, so AprilTag localization and
odometry in your TeamCode give the field positions you'd get on the real field. The SDK 12.0 that
TeamCode builds against has the tag libraries of CENTERSTAGE, INTO THE DEEP, DECODE and BIOBUZZ
(`AprilTagGameDatabase.getDecodeTagLibrary()`...); `getCurrentGameTagLibrary()` is BIOBUZZ's.

**DECODE** in the simulator: the field from FIRST's DECODE manual and official field CAD; 36
ARTIFACTS (24 purple, 12 green, 5 in) staged on the SPIKE MARKS, in the LOADING ZONES and in the
ALLIANCE AREAS, 3 pre-loaded in each robot (and at most 3 held, rule G408); both GOALS with their
RAMPS, SQUARES and GATES; the OBELISK, turned to a random MOTIF at every *Reset*; the GOAL
AprilTags 20 and 24 and the OBELISK's 21–23. An ARTIFACT that drops into a GOAL rolls down its RAMP
and waits at the GATE; push the GATE's paddle toward the wall with the robot and the RAMP empties
toward the audience, and gravity closes the GATE again. The human player puts a purple or green
ARTIFACT into the alliance's LOADING ZONE with **N** / **M** (red) and **V** / **B** (blue). The
top bar shows the MOTIF and, for each GOAL, the ARTIFACTS that went through its SQUARE, the ones on
its RAMP and how many RAMP places match the MOTIF (not a MATCH score). Simplified: the inside of the
GOALS, the RAMP rails (a flat plate) and the GATE lever are simplified from the CAD, and an
ARTIFACT's mass (75 g) and bounce are estimates.

**Where robots start** comes from the season (`"start"` in its `season.json`, two spots per
alliance). A robot file can give its own spot per season, `"start": {"biobuzz": [-1.2, -1.2, 0]}`
(x, y in meters, heading in degrees). A plain `"start": [x, y, h]`, as robot files made before
there were seasons have, is a BIOBUZZ spot; other seasons ignore it.

### Adding a season

When FIRST reveals a new game, copy a pack folder, say `seasons/decode/` to `seasons/<game>/`,
and make the copy its own: every pack is compiled together with the simulator, so two packs can't
both have the classes `decode.Decode`. In the copy, change the Java files' `package decode;` to
`package <game>;` and rename the classes (`Decode` → `<Game>`, `DecodeGame` → `<Game>Game`), then
set `"id"` to `<game>` and `"code"` to `<game>.<Game>` in its `season.json`. (Or delete the copy's
`.java` files and its `"code"` line, and start from data alone.) Then describe the new field in
`season.json` (meters, in the season's field coordinates):

| Key | What |
|---|---|
| `id`, `title`, `years`, `frame` | the game's name and how its field coordinates are set |
| `field_m`, `tile_m`, `wall_height_m` | the perimeter's inside size, tile pitch, wall height |
| `view` | where the audience and each alliance's drivers are (`"+x"`, `"-y"`...), for the cameras |
| `alliance` | the alliance a robot on its own plays on (default `"red"`), and the one a robot file's own `start` spot is for |
| `start`, `symmetry`, `partner` | each alliance's start spots; how one alliance's side maps onto the other's; how a robot file's own start spot gives its alliance partner's (`"mirror_y"` or `"mirror_x"`: across that center line; none: the season's second spot) |
| `structures` | boxes, bars and cylinders: `box_m` + `at_m` (+ `yaw_deg`, `pitch_deg`, `roll_deg`), `beam_m`, `cylinder_m`, each with `color`, `collide`, `visible`, `friction`; `prism_m` (a convex `outline` made `length` thick) is only drawn, for a shape a box can't show. `"mirror": "y"` (or `"x"`) adds the other alliance's copy, reflected across that line, red and blue swapped |
| `tape` | `rect_m` (outline or `"fill": true`), `line_m` polylines, `polygon_m` |
| `field_tags` | AprilTags: `size_m` (the black square's side) and `tags`, an object keyed by tag id, each with `center_m`, `normal` (out of the face), `up` and, for a tag of another size, its own `size_m` |
| `movers` | parts that turn on a hinge: `name`, `pivot_m`, `axis`, `limits_rad`, `parts`, `tags`; with `mass_kg`, `com_m`, `inertia_kgm2` and `damping_nms` gravity, robots and game pieces move it (a GATE); without, only code does (the OBELISK). A mirrored mover has red or blue in its name, so its copy gets its own |
| `game_pieces` | balls: `diameter_m`, `mass_kg`, `colors` with their counts, `bounce`, `friction` |
| `robot`, `preload`, `staging` | how many pieces a robot may hold; its pre-load; where every piece starts (one at `at_m`, several evenly along `line_m`, or waiting in an `area`) |
| `alliance_areas`, `human_player` | the areas outside the field; the human player's buttons (`zone_m`, `wall`, `key`) |

What data can't say goes in Java in the same folder, package `<game>`, named by `"code"`: a
`SeasonCode` that can add shapes worked out from the data (`describe`) and a `SeasonGame` for the
match (`restage`, `beforeStep`, `act` for the human player, `stats` for the top bar). BIOBUZZ's
HIVES, their tipping and its staging rules are in `seasons/biobuzz/`; DECODE needs code only for
its MOTIF and what it counts. The simulator compiles the packs with itself (`gradlew build`), and
`SeasonsTest` loads every pack in `seasons/` and plays a little of each. FIRST publishes the
Competition Manual, the field drawings and a STEP model of the whole field at kickoff: the numbers
for `season.json` come from there.

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
- Deploy to the robot with your usual tools, or from the simulator (below).

### Editing and deploying in the simulator

Click **</> Code** in the top bar to edit TeamCode beside the field, without another editor open.

- **The robot's own files.** The editor opens the TeamCode of the robot whose details are shown
  (with several robots, click its card), starting with the file of the OpMode in its Driver
  Station menu; choosing another OpMode there opens that one's file. Saving (**Ctrl+S**) writes the
  file in that robot's project folder, the same file Android Studio builds and deploys, keeping its
  line endings, and the simulator rebuilds that robot at once. While *FTC samples* is ticked in the
  Driver Station, the samples' files are listed too, read-only as on the robot.
- **Compile errors where they are.** After a save, errors are underlined in the file and marked
  in the margin; point at one to read javac's message. A file with many errors (one that uses a
  library the simulator doesn't have) shows its first 20 and how many more there are. Every other
  file that compiles is still built, however many fail.
- **▶ Save & run** (**Ctrl+Enter**, **Cmd+Enter** on a Mac) stops that robot's OpMode, saves every
  changed file of the project (as Android Studio's Run does), waits for the build, then INITs
  and STARTs the OpMode in the file (or, for a file without one, the OpMode in the Driver Station's
  menu). It runs nothing while the file has compile errors. Once the OpMode starts, the keys drive
  the robot instead of typing into the file; click the file to type again, and **Esc** leaves it.
  The status line says which.
- **+ New** adds a LinearOpMode (TeleOp or Autonomous) next to the open file.
- **Other editors.** If the file was saved elsewhere (Android Studio, VS Code) since the editor
  opened it, an unchanged copy reloads by itself; with changes of your own, saving asks whether to
  keep yours or load the file from disk. A file deleted on disk while it has unsaved changes stays
  in the list: saving offers to make it again with your text, or to discard your changes.

**⇪ Deploy** puts the code on the real robot, as Android Studio's Run button does:

1. Connect the computer to the robot: a Control Hub by USB-C, or join the Control Hub's Wi-Fi and
   click *Control Hub Wi-Fi* (ADB at 192.168.43.1:5555); a Robot Controller phone by USB with USB
   debugging on.
2. Pick the robot in the list (*↻* looks again) and click **⇪ Deploy**. The simulator saves every
   changed file, runs your project's own Gradle wrapper (`gradlew :TeamCode:installDebug`) to build
   the Robot Controller app with your TeamCode and install it on that robot, and starts the app
   again. Gradle's output appears below; *Cancel* stops the build.
3. Without a robot, *No robot: only check that it builds* runs the real Android build
   (`assembleDebug`) to check that the code builds for the robot.

Deploying needs what Android Studio needs: the Android SDK (Android Studio installs it; the
simulator finds it from the project's `local.properties`, `ANDROID_HOME`, or its usual folder) and
its `adb`. Gradle runs on the JDK Android Studio builds with: the project's own Gradle JDK
setting (`.gradle/config.properties`), else Android Studio's bundled JDK (JetBrains Toolbox
installs too), else `JAVA_HOME`, else the simulator's; it takes the first that is 17 or newer and
that the project's Gradle version can run on, and the log's first line names it and why. The first build of a project downloads
Gradle, the Android build tools and the SDK platform it needs, as in Android Studio. With several
robots, each one deploys its own project to the robot picked for it, one at a time. The bundled
examples aren't a robot project and can't be deployed.

The simulator listens only on this computer (`127.0.0.1` and `[::1]`) and only answers its own page
(`localhost`): another web site open in your browser can't read or change your code, start a
build, or watch and drive the robots.

## Driving

**Controller.** Plug in or pair an Xbox or PlayStation controller, click the simulator page, and
**press any button**: browsers only expose a controller after a button press. *Gamepad 1 = Auto*
uses the first controller. Rumble (`gamepad1.rumble(...)`, `rumbleBlips(...)`) plays on the
controller in Chrome and Edge (Firefox has no controller rumble), so use Chrome or Edge on any
operating system. A new rumble, or `stopRumble()`, stops the one playing, and
`RUMBLE_DURATION_CONTINUOUS` plays until `stopRumble()`. The Xbox Guide button may be taken by the Windows Game Bar.

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

With one controller, set **Gamepad 2 → Keyboard** to try two-driver code. The keys drive even
right after you pick from a menu or tick a checkbox: the menu lets go of them, so an arrow key
doesn't change the OpMode or the robot file.

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
up with the red HIVE (x = −0.32 m, −12.6 in in the Driver Station's readout) on the audience
side, facing the rear wall, with its center anywhere from y = −1.0 m (−39 in, 1.0 m from the
field's center line) back to y = −1.6 m (−63 in), where the robot's back touches the audience
wall. Blue is the mirror image from the rear side. From anywhere in that range, the default
hood and launcher speed drop the ball into the CELL. With
the 3 starting NECTAR in it, the 3rd POLLEN that lands tips the HIVE, and then its other CELL is
the raised one, open toward the rear wall.

**The example BIOBUZZ Parking** autonomous (`examples/teamcode`, with `--robot robots/my-robot.json`):
starts with its back against the audience wall, facing into the open end of the red HIVE's
audience CELL, then drives around the A-frame and the red FLOWER and backs into the red LOADING
ZONE, using Pinpoint field coordinates (`pinpoint.setPosition` at START). It takes about 7
seconds and then preselects BIOBUZZ TeleOp. It assumes that start: from another robot file's
start spot it drives somewhere else.

## The Driver Station panel

- **The robot**: its TeamCode folder and battery above the OpMode list, its robot file menu and
  *+ From CAD* below INIT (with several robots, each robot's card has its own). The top bar keeps
  what the whole field shares.
- **OpMode list** with Autonomous and TeleOp groups. Tick *FTC samples* to show the FTC SDK samples,
  which are `@Disabled` on a real robot.
- **INIT / START / STOP** with the same rules as the real app. The optional **30 s auto timer** stops
  Autonomous OpModes and then preselects the TeleOp named in `preselectTeleOp`.
- **Errors** appear the way the Driver Station shows them, e.g.
  `User code threw an uncaught exception: IllegalArgumentException - Unable to find a hardware device with name "left_drive" and type DcMotor (at MyOpMode.init:42)`.
- **Telemetry**, including `telemetry.log()` (oldest first), `Func` items, retained items, the
  250 ms transmission interval and the HTML display format, as FIRST's `TelemetryImpl` does it:
  numbers formatted as the Driver Station shows them (`0.3`, not `0.30000000000000004`), `Func`s
  and actions run once per transmission (and what they throw stops the OpMode, as on the robot),
  and an `update()` with nothing in it leaves the screen as it was.
- **Devices**: every motor's mode, power, encoder, velocity and current; servo positions; sensor
  readings; hub LED and bulk-caching mode. **Robot log** shows `RobotLog` and `android.util.Log`.
- **3D view**: audience, overhead, red-driver and chase cameras. *Place robot* puts the robot
  anywhere (drag to aim it). *Reset* power-cycles it, which resets encoders and the IMU heading,
  puts it back at its start and restages the game pieces.
- **Camera**: while a `VisionPortal` streams, its live view, with the processors' overlays (tag
  outlines, axes, ids), like the Robot Controller's camera preview.
- **</> Code**: the robot's TeamCode in an editor beside the field, with *Save & run* and *Deploy*
  ([Editing and deploying in the simulator](#editing-and-deploying-in-the-simulator)).

## Vision: VisionPortal and AprilTags

`VisionPortal`, `AprilTagProcessor`, `ColorBlobLocatorProcessor` and `PredominantColorProcessor`
work as on the robot, because they **are** the robot's code. The simulator runs FIRST's own
compiled classes from the FTC SDK 12.0 Vision package, unmodified, along with the pure-Java parts
of RobotCore they use (units, poses, matrices, camera calibration). The AprilTag detector is the
C library FIRST ships (OpenFTC's EOCV-AprilTag-Plugin 2.1.0), built from the same source for PCs
(see [`natives/apriltag/`](natives/apriltag/README.md)). OpenCV is 4.10.0, the robot's version
(4.11.0 on macOS, whose 4.10.0 build needs a camera library it doesn't ship).

Only what touches hardware is the simulator's:

- **The webcam**. It renders what it would see from where the robot file mounts it: the field, the
  HIVES with their real tag36h11 AprilTags (IDs 30–45), robots and balls. Its lens is the camera
  model's entry in the SDK's own calibration table. FIRST's AprilTag processor looks up the same
  entry by the webcam's USB ids, so poses come out right, just as with a calibrated webcam on the
  robot. A resolution the table has no entry for (the C270's and the LifeCam HD-3000's 16:9 ones,
  and every resolution of the Arducam, which isn't in it) behaves as on the robot too: the image
  keeps the camera's real field of view, FIRST's processor warns that pose estimates will likely be
  inaccurate and assumes its default lens, and the ranges it solves are off by the difference
  (about 0.7 times the true range for a C270 at 640×360, 1.3 times for an Arducam at 640×480).
  Frames arrive at the camera's frame rate (default 30 fps), each showing the world as it was
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
one work, place the default robot at x = −0.25 m, y = −1.15 m (−9.8 in, −45.3 in in the Driver
Station's readout), heading 90° (facing the red HIVE), INIT *Concept: AprilTag* and press START.

## Describing your robot

Robots are JSON files in `robots/`. Choose one with `--robot`, or from the robot file menu under
INIT in the Driver Station panel (with several robots, on each robot's card).
**Device names must match your robot configuration on the Driver Station**, because your code
looks devices up by those names.

- `robots/mecanum.json`: 18 in mecanum robot (goBILDA 312 RPM, 104 mm wheels) using the SDK 12
  sample names `front_left_drive` …, plus `imu`, `pinpoint`, `sensor_otos`, `sensor_distance`,
  `sensor_color`, `sensor_touch`, `digitalTouch`, and a BIOBUZZ-style `intake`, `launcher`
  (flywheel), `hood` servo and `feeder` CR servo.
- `robots/my-robot.json`: the same mecanum robot, starting on BIOBUZZ with its back against the
  audience wall and facing into the red HIVE's audience CELL
  (`"start": {"biobuzz": [-0.3239, -1.5964, 90]}`), where the BIOBUZZ Parking example starts.
  The tests keep using `robots/mecanum.json`.
- `robots/tank.json`: six-wheel drop-center tank with `left_drive` / `right_drive`, an arm
  (`left_arm`, also `arm`) and claw servos (`left_hand`, `right_hand`), for the classic samples.
- `robots/mecanum-cad.json` and `robots/tank-urdf.json`: the same two robots built from CAD (a
  STEP file and a URDF). See [Robots from CAD](#robots-from-cad).

Main fields (meters and degrees; robot frame +x forward, +y left, +z up):

| Section | What it sets |
|---|---|
| `chassis` | size, mass, color |
| `drive` | `type` (`mecanum`, `tank`, `tank4`), wheel diameter, wheelbase, track width, `motor` model, external gear ratio, motor names, `mirrored_side`, grip |
| `imu` | name and how the Control Hub is mounted (`logo`, `usb`, same words as `RevHubOrientationOnRobot`) |
| `devices` | `motor` (with a `load`: `roller`, `flywheel`, `arm` with gravity and hard stops, `slide`), `servo`, `crservo`, `pinpoint` (pod offsets, pod type), `otos`, `distance`, `color`, `touch`, `digital_touch`, `analog` (`volts`, or `encoder_of` a servo or motor, read as 0–3.3 V per turn), `led`, `blinkin`, `webcam` (`model`: `Logitech C920` (default), `C270`, `C310`, `Microsoft LifeCam HD-3000`, `goBILDA USB Camera`, `Arducam`; `fps`; `latency_ms`; plus `roll_deg` for a camera mounted turned); each with a mounting position (`x_m`, `y_m`, `z_m` from the floor under the robot's center, `yaw_deg`, `pitch_deg`) and an optional `role` (`intake`, `feeder`, `launcher`, `hood`) for the game pieces and the 3D model; an intake can set `intake_direction` (`"forward"`, the default, or `"reverse"`) |
| `io` | simulated bus latency (default 1 ms per hub command, 2.5 ms per I2C read) |
| `start` | optional starting pose instead of the season's spot, per season: `{"biobuzz": [x, y, heading]}`; a plain `[x, y, heading]` is a BIOBUZZ spot |
| `cad` | optional: the robot's CAD model, see below |

Every name your code can look up is one device's: two devices, a device and a drive motor, or an
alias and a device can't share a name (the wheels of one side may share their drive motor), as a
Driver Station configuration doesn't allow it either. A mistake in a robot file is reported when
the file is read, naming the field and the file.

`mirrored_side` is the side of the drivetrain whose motors are mounted facing the other way, so
positive power rolls those wheels backward: `"left"` (the default, as in the FTC samples),
`"right"` or `"none"`. It is the side your code calls `setDirection(DcMotor.Direction.REVERSE)`
on. If your code reverses the right motors, set `"mirrored_side": "right"`; then your TeamCode
drives the simulated robot the same way it drives the real one, with no changes. Older robot
files say `"left_side_mirrored": true` (left) or `false` (none), which still works.

Motor models come from vendor datasheets: goBILDA 5203 (all ratios), REV HD Hex (spur and
UltraPlanetary), REV Core Hex, NeveRest, TETRIX. If you mount a sensor differently from what
your code assumes (for example the IMU orientation or the Pinpoint pod offsets), the readings
are wrong in the same way they would be on the robot.

## Robots from CAD

A robot file can point at the robot's CAD model. The robot then looks like its CAD, in the 3D view
and in other robots' webcams. Its mass, center of mass, inertia and collision shape come from the
CAD. Its arms, slides, hoods and claws become rigid bodies on joints, driven by your motors and
servos. Robots without `cad` work as before.

### Add your robot from its CAD

1. Export the robot's assembly as a STEP file. In Onshape, right-click the assembly's tab and
   choose *Export*, then STEP. In Fusion 360, use *File → Export*; in SolidWorks and Inventor,
   *Save As*.
2. In the simulator, pick the robot file whose devices match your Driver Station configuration
   from the **Robot** menu under INIT. Then click **+ From CAD** next to it and choose the STEP
   file, or drop the file anywhere on the page. With several robots, the robot file menu and
   **+ From CAD** are on each robot's card in the Robots panel, and dropping the file on a card
   makes that robot.

That's all. The simulator saves the file as `robots/cad/<name>.step` and writes
`robots/<name>.json` with the devices and drive of the robot file you had picked, so your TeamCode
finds the same hardware. The page turns the STEP file into triangles with OpenCascade, which runs
in the browser, and the simulator saves them next to it as `<name>.step.cadmesh`. A big assembly
takes up to a minute. A note at the bottom of the 3D view then says what the simulator worked out
by itself:

- **Which way the robot faces.** Up comes from the drive wheels: their centers are level and the
  robot is above them. The front is the end with the intake, or the end with parts named *front*
  rather than *back*. If it guessed wrong, set `forward` and `up` (below).
- **The drive wheels**: parts and sub-assemblies named like *wheel*, *mecanum* or *omni* that touch
  the floor (not a flywheel, an intake wheel, or an odometry wheel: names with *odo*, *dead*,
  *tracking* or *encoder*, or a wheel under 0.6 of the others' size), each taken for the corner it
  is nearest. Extra wheels are left out, with a note.
- **Rollers and flywheels**: round parts named like the motor or CR servo that turns them, with
  the parts on the same axis. An `intake` motor turns *Intake Roller* and its shaft, a `launcher`
  or a `flywheel` load turns the *Flywheel* and its hub. They keep the robot file's `load.inertia`.
- **Mass**: `chassis.mass_kg` spread over the parts by volume. Set it to what your robot weighs.

To update the robot, export the STEP file again and drop it with the same name: the STEP file is
replaced and the robot file keeps what you added to it. Commit the STEP, `.cadmesh` and robot
files so your teammates and CI don't convert the STEP file again.

### Big assemblies

The Driver Station page converts the STEP file with OpenCascade compiled to WebAssembly, which can
use at most 2 GB of memory. A whole competition robot with every screw and mecanum roller can need
more (a 150 MB STEP export does). Then no triangles come out, and the 3D view says so and names
`tools/step2cadmesh.py`. That script converts the STEP file on your computer with the full
OpenCascade library, using as much memory as the computer has, and writes the same
`<name>.step.cadmesh` the page would have, its parts colored as the page colors them. Set it up
once, from this folder (about 1 GB):

```sh
python3 -m venv tools/.venv
tools/.venv/bin/pip install cadquery-ocp
```

```bat
python -m venv tools\.venv
tools\.venv\Scripts\pip install cadquery-ocp
```

Then convert each new export before starting the simulator:

```sh
tools/.venv/bin/python tools/step2cadmesh.py ../cad_robots/OurRobot.step
```

```bat
tools\.venv\Scripts\python tools\step2cadmesh.py ..\cad_robots\OurRobot.step
```

A whole robot takes about a minute. A lighter export converts faster and draws faster: hide or
suppress the screws, nuts and other small hardware before exporting, but keep the drive wheels.

Arms, slides, hoods and claws need one line each in the robot file's `cad.links`, because how far
they may move isn't in a STEP file:

```json
"cad": {
  "file": "cad/our-robot.step",
  "links": [
    { "name": "Arm Assembly", "device": "arm", "limits": [-35, 120] },
    { "name": "Hood", "device": "hood", "ratio": 0.2, "limits": [-35, 35] },
    { "name": "Slide Stage", "device": "lift", "joint": "prismatic", "limits": [0, 600] }
  ]
}
```

`name` is the part or sub-assembly in the CAD that moves. It turns on the round part named like a
shaft, axle, pivot or hinge that is in it or touches it, and a slide moves along its longest side.
The robot log says which part each link turns on; `axis_part` names a different one.

`robots/mecanum-cad.json` is a STEP example with everything spelled out. It runs the same OpModes
as `mecanum.json`.

### The cad section of a STEP robot

`"cad": "cad/our-robot.step"` is short for `"cad": {"file": "cad/our-robot.step"}`. Everything
else is optional.

| Field | What it sets |
|---|---|
| `file` | the STEP file, relative to the robot file |
| `forward`, `up` | the CAD axes that point to the robot's front and up: `+x`, `-y`, `+z`... Default: worked out from the wheels and part names, as above |
| `origin_mm` | the point on the floor under the robot's center, in CAD coordinates (mm). Default: the middle of the drive wheels, at the bottom of the wheels |
| `wheels` | the parts of each drive wheel, such as `{"front_left": ["FL Wheel*"], ...}`. Default: parts or assemblies named like *wheel*, *mecanum* or *omni* that touch the floor, sorted by where they are |
| `ignore` | parts to leave out, such as field elements or reference geometry |
| `links` | the parts that move, one entry each, as below. Rollers and flywheels that aren't listed are found by their motor's name |

| Link field | What it sets |
|---|---|
| `name` | the link's name, which other links use for `parent` and `mimic` |
| `parts` | name patterns of its parts. A pattern matches a part's name or the name of any assembly it is in, so `"Arm Assembly"` takes a whole sub-assembly. `*` and `?` are wildcards; case doesn't matter. Default: the parts and sub-assemblies called `name` |
| `joint` | `revolute` (an arm, a hood, a wrist, a claw finger), `prismatic` (a slide stage) or `continuous` (a roller, a flywheel, a feeder wheel). Default: `continuous` for a `roller` or `flywheel` motor and a CR servo, otherwise `revolute` |
| `axis_part` | the shaft, rail or servo spline the link moves on. The axis is that part's long axis (a shaft, a rail) or its short axis (a disk, a servo horn). Or give the axis as `axis_point_mm` and `axis_dir`, in CAD coordinates. Default: a round part named like a shaft, axle, pivot or hinge in the link or touching it; a roller's own round part; a slide's longest side |
| `device` | the motor, servo or CR servo that drives it |
| `reversed` | flips which way is positive. The axis points along whichever of the robot's +x, +y, +z it is closest to, and positive turns right-handed about it |
| `limits` | hard stops `[min, max]`, in degrees or mm from where the CAD has it |
| `start` | where it is when the robot is placed or reset |
| `parent` | the link it is mounted on. Default: the chassis |
| `mass_kg` | its mass. Without it, the link gets a share of `chassis.mass_kg` by volume, which makes solid parts such as flywheels too heavy |
| `ratio`, `servo_center` | servo-driven links: joint degrees per servo degree, and the joint's position when the servo is at 0.5 |
| `torque_nm` | servo-driven links: the servo's stall torque. Default 2.5 N m |
| `spool_diameter_mm` | motor-driven slides: the spool or pulley diameter. Default 38 mm |
| `mimic`, `mimic_ratio` | a cascade stage that follows another link |

### From a URDF

A URDF (`.urdf` with STL or OBJ meshes) has the joints and the CAD's mass properties. CAD exporters
write it: [onshape-to-robot](https://onshape-to-robot.readthedocs.io) for Onshape,
[fusion2urdf](https://github.com/syuntoku14/fusion2urdf) for Fusion 360, and the SolidWorks URDF
exporter. Put its folder in `robots/cad/` and point a robot file at it; `robots/tank-urdf.json` is
an example that runs the same OpModes as `tank.json`.

```json
"cad": {
  "file": "cad/my-robot/robot.urdf",
  "links": [ { "name": "arm_joint", "device": "arm", "start": -35 } ]
}
```

A joint is driven by the device with the same name as the joint, or by the `device` a `links`
entry gives for it (the entry's `name` is the joint's name). Such an entry can also set `reversed`,
`limits` (which override the URDF's), `start`, `ratio`, `servo_center`, `torque_nm` and
`spool_diameter_mm`. A joint whose URDF limit gives only `lower` or only `upper` has 0 for the
other, as the URDF format says. Joints that no device drives stay where the CAD has them. The drive wheels
are the links named like wheels, or on continuous joints that no device drives. Masses and
inertias come from the URDF; `mass_kg` scales them to what your robot weighs on a scale. Mesh paths
such as `package://robot/meshes/arm.stl` are looked up next to the URDF and in its parent folder.

### What the CAD changes

- **The chassis** is everything that doesn't move. Its mass, center of mass and inertia come from
  the CAD. It collides through boxes fitted to each part: a bent or curved part gets several, and
  screws and nuts get none.
- **Links on revolute and prismatic joints** are rigid bodies. Gravity pulls on them, they stop at
  their limits, and they hit balls, other robots and the field. A motor drives its joint through
  its torque curve and gearbox, and its encoder counts what the joint does. A servo pulls toward
  where its horn is, with at most its stall torque, and an unpowered servo holds only a little.
- **Rollers, flywheels and feeder wheels** turn with their motor or CR servo: with their CAD
  inertia when `links` lists them, with the robot file's `load.inertia` when they were found by
  name. The intake takes balls at the CAD roller, and the launcher throws from the CAD flywheel's
  rim.
- **The robot file's numbers** follow the CAD: the robot's size, wheel positions, wheel diameter and
  mass. The robot log lists what the simulator assumed while reading the CAD.
- **Not taken from the CAD**: how the drive wheels grip (mecanum rollers grip along their axis, as
  before), where the sensors are (still set in `devices`), mates, and materials.

If the CAD can't be used, for example because a pattern matches no part, the robot is built from
the robot file's numbers and the 3D view says why.

## What is simulated

- **Physics** uses [ODE4j](https://github.com/tzaeschke/ode4j) at 1 kHz in real time. The robot
  is a chassis plus one body per wheel. Each mecanum wheel grips only along its roller axis, which
  is the standard way Gazebo models mecanum wheels, so strafing, wheel slip and pushing against
  walls behave physically. The robot and the game pieces collide with the walls and the season's
  structures: on BIOBUZZ the HIVE structure's A-frames and the FLOWERS' frames, and for balls
  also the A-frame legs, the axle, the sign board, both HIVES' CELLS and the FLOWERS' rings.
- **Motors** follow their torque-speed curve, sag the battery under load, and BRAKE/FLOAT
  correctly. The **hub firmware** implements `RUN_WITHOUT_ENCODER`, `RUN_USING_ENCODER`
  (velocity PIDF), `RUN_TO_POSITION`, `STOP_AND_RESET_ENCODER`, `setVelocity`, PIDF tuning,
  current and over-current, and **bulk caching** (`OFF` / `AUTO` / `MANUAL`). Encoders don't
  reset between OpModes, just as on a real robot.
- **Sensors**: the Control Hub IMU (`IMU`, yaw/pitch/roll, angular velocity, mounting
  orientation), goBILDA Pinpoint (its own odometry from your offsets, resolution and directions),
  SparkFun OTOS, REV 2m distance (raycast), REV Color Sensor V3 (tape and tiles under it), REV
  touch sensor / digital channel, analog inputs (a set voltage, or an absolute encoder on a
  servo or motor), and battery voltage. **Lights**: an LED on a digital channel and a REV Blinkin
  LED driver (its pattern decoded from the pulse FIRST's driver sends) show in the device panel.
- **Files**: `ReadWriteFile` and `AppUtil`'s folders work; the Control Hub's storage (`/sdcard`,
  where `AppUtil.ROOT_FOLDER` is) is `build/sdcard` in the simulator's folder, shared by its
  robots.
- **Game pieces** (BIOBUZZ; for DECODE see [Seasons](#seasons)): the 40 yellow POLLEN (7.1 cm) and 8 red + 8 blue NECTAR (9.1 cm), staged
  as Competition Manual section 10.3.1 sets them up: 4 POLLEN in each FLOWER, 4 in each GARDEN and
  4 pre-loaded in each simulated robot; the absent robots' pre-loads sit in the middle of their
  LOADING ZONE against the wall; 3 NECTAR in each upward-facing CELL and 5 in each ALLIANCE AREA. Loose
  balls roll and bounce, slow down with air drag in flight and rolling resistance on the tiles,
  and the robot pushes them. A robot with an `intake` role (a motor or a CR servo) has a solid
  roller across its front that pushes and bumps balls; turning at 15% of its own free speed or
  more, whatever drives it, it picks up a ball that touches it (turning the other way, it spits
  one out; set the device's `"intake_direction": "reverse"` when your code sets the intake to
  `REVERSE` and takes balls in with positive power), and the robot holds at most 4 (rule G407;
  the top bar shows *In robot*); a `feeder` then sends them into the `launcher`, which throws them at about half the flywheel's surface
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
  has tipped. That is not a MATCH score. *Reset* also resets the HIVES and the game pieces.
- **FLOWERS** (Competition Manual 9.7) are open tubes: a lower ring with a 7.1 cm hole the bottom
  POLLEN sits in, the Retrieval Opening, a middle ring that lets POLLEN through but stops NECTAR,
  and a tube up to the 10.15 cm top opening. The 4 starting POLLEN are stacked inside. The intake
  takes them out of the bottom one by one, and once the bottom one is gone the others drop and
  roll out of the opening; a full robot's roller can knock the bottom one out. A NECTAR dropped in
  the top rests on the middle ring and locks POLLEN put in above it; a POLLEN put into an empty
  FLOWER falls through. The FLOWER's frame stops the robot's body, so only the low roller reaches
  in. POLLEN and NECTAR are drawn as the perforated balls they are. Balls waiting in the ALLIANCE
  AREAS stay put until the human player brings them in.
- **Human player NECTAR**: press **N** (red) or **M** (blue), or the *+ Red NECTAR* / *+ Blue
  NECTAR* buttons, to put one NECTAR from that ALLIANCE AREA into its LOADING ZONE, at rest at
  the first free spot along the alliance wall. Each button shows how many are left (5 per color
  until *Reset*). The robot can intake and launch NECTAR like POLLEN.
- **OpMode lifecycle**: iterative and linear OpModes, `waitForStart`, `opModeInInit`,
  `isStopRequested`, thread interruption on STOP, `requestOpModeStop`, `terminateOpModeNow`,
  `OpMode.blackboard`. When an OpMode ends, motors stop and servos go limp.
- **Loop timing**: hardware calls take about as long as on a real Control Hub, so control-loop
  tuning carries over.

**Not simulated yet**: scoring, sounds, Limelight,
OctoQuad, legacy BNO055 / Modern Robotics sensors, switchable webcams, cameras driven through
EasyOpenCV directly (`OpenCvCameraFactory`), lens distortion and motion blur in the webcam image.
OpModes that use them are skipped at build time with the reason.

## Checking the simulator itself

```bat
gradlew test
```

On macOS and Linux: `sh gradlew test`.

The suite includes:

- `ApiConformanceTest`: the simulator's SDK must be a subset of the official FTC SDK 12.0. It
  downloads the official jars from Maven Central and says what the members it checked are (those
  declared in FIRST's jars, `equals`/`hashCode`/`toString` overrides, constructors that take the
  simulator's internal types, and its one documented exception). `CommonSdkApiTest`: robot code
  using `AnalogInput`, `LED`, `RevBlinkinLedDriver`, `ReadWriteFile`, `AppUtil`'s folders,
  `JavaUtil`, `ThreadPool` and the other common SDK calls builds and runs, and its values flow.
- `GameElementsTest`: POLLEN and NECTAR counts and staging per section 10.3.1, balls at rest,
  pushing, intake (4-ball limit; a slow Core Hex, a CR servo or a reversed intake takes balls in
  too), launching, and the CELLS: NECTAR stays in the upward-facing
  CELLS, a ball shot into the open end stays, a ball dropped on the roof bounces off, and a ball
  in a downward-facing CELL rolls out, and a ball thrown at a CELL's side bounces off. The
  FLOWERS: the stacks stay put, the intake empties a FLOWER from the bottom, a full robot's roller
  knocks the bottom POLLEN out, the others roll out once it is gone, a NECTAR stops on the middle
  ring and locks POLLEN in, and the robot's body stops at the FLOWER's frame.
- `HiveTipTest`: the HIVE calibration table from the Event Field Setup Guide (section 12.3), with
  balls gently placed or tossed in (a row that tips must swing the HIVE all the way over, one that
  holds must leave it on its stop), and a tip dumping the CELL and turning the other CELL up.
- `DrivetrainPhysicsTest`, `RestDriftTest`: speed, strafing and turning (each between a lower
  and an upper bound), RUN_TO_POSITION, wall contact, no drift at rest, and each hub's current
  sensor reading its own motors. A robot whose right motors are mirrored drives forward under code
  that reverses its right side; `mirrored_side` takes left, right or none, and older files'
  `left_side_mirrored` still works. A 2-motor `tank4` drives each side's wheels with one motor and
  one encoder, and wheels chained to a motor share its rotor; the battery sags when motors start
  and not while they brake; Reset puts an arm back at its start; a robot file that can't be built
  leaves nothing in the world, and a drive's type and motors are checked.
- `CadImportTest`: robots from CAD. The STEP and URDF examples are read into their links, joints,
  wheels and masses. The STEP example with nothing but its file name comes out the same as with
  everything spelled out, also exported y-up or modeled facing backward; a link with only its
  name and device finds its pivot. A STEP file added from the page gets a robot file with the
  devices of the robot in use, and a newer export keeps that robot file. A STEP file is converted
  again only when it changes (line endings don't count), and when its saved conversion is damaged
  or no longer fits the robot file. A conversion without triangles (a big assembly that filled the
  page's memory) is an error that names `tools/step2cadmesh.py`, not a robot drawn silently from
  the robot file's numbers. The CAD robot drives at the plain robot's speed, and its
  odometry sees no movement while it turns in place. The URDF arm swings up on its motor with its
  encoder following, and falls back unpowered; URDF drive wheels are found without "wheel" in
  their names. The hood follows its servo, the CAD intake and flywheel take in and launch balls
  (from the front of the flywheel's rim, wherever it has turned to), and a cascade slide's second
  stage follows the first, held back by its motor's rotor. Two writes of one conversion don't
  collide. Wheels listed in the robot file orient the robot as found ones do; an open part's mass
  doesn't depend on where it is; a URDF wheel whose mesh is missing says so. Odometry pods aren't
  taken for drive wheels, and extra wheels are never merged into one; a URDF limit given on one
  side only stops at 0 on the other; after a Reset the hood moves from its start; a STEP robot
  whose wheels aren't found weighs what its robot file says. `CadMeshTest`: a webcam's own
  robot's panels near its lens are left out, and simplifying a mesh keeps every distinct triangle.
  `WebcamSceneTest`: a webcam mounted low inside the frame sees past it, and a tag is its size
  whatever length its directions are given in.
- `RobotFileTest`: a robot file's mistakes (a missing motor model, a bad IMU mounting or start,
  two devices with one name, an intake direction) are reported naming the field and the file.
- `SeasonsTest`: every season pack in `seasons/` loads and plays (its robot starts clear of the
  structures, its game pieces stay on the field). On DECODE: the ARTIFACTS are staged per section
  10.3.1, the GOAL AprilTags sit where FIRST's DECODE tag library puts them, ARTIFACTS dropped into
  a GOAL roll down the RAMP and wait at the GATE, a robot pushes the GATE open and the RAMP empties,
  the human player fills the LOADING ZONE, the OBELISK shows the MOTIF (its three tags are
  counted), an alliance partner started from a robot file's spot faces the mirrored way, and FIRST's
  `AprilTagProcessor` with the DECODE library locates the camera from tag 24 within 3 cm (it
  prints the error).
- `MultiRobotTest`: two robots on one field. Each has its own TeamCode class loader, starting
  spot, pre-load, blackboard and gamepads; one robot pushes the other; swapping one robot's file
  leaves the other alone, and never happens under a running OpMode; alliance partners start side
  by side and an alliance takes at most two robots; *INIT all* / *START all* / *STOP all* run both
  robots' Autonomous together.
- `VisionLinkageTest`: every class, method and field that FIRST's Vision, RobotCore and Hardware
  classes refer to exists in the simulator as they use it (a field of the type they read, static
  where they use it statically, not private), so a stand-in can't surface as a
  `NoSuchMethodError`, `NoSuchFieldError` or `IllegalAccessError` in your OpMode. `AndroidMatrixTest` checks the `android.opengl.Matrix` stand-in they compute with.
- `AprilTagVisionTest`: FIRST's `AprilTagProcessor` on the simulated webcam finds the HIVE's tags,
  the positions it solves match where the camera and tags really are within 2 mm (the test prints
  each error, and fails beyond 5 mm) and their orientations within 2 degrees (it fails beyond 3), and the
  `RED SCORING` cluster origin it reports sits at the CELL opening. `CameraStreamTest`: the live
  view reaches the Driver Station page.
- `HubMotorControllerTest`, `ServoTest`, `SensorTest`, `GamepadTest`, `TelemetryTest`: the SDK
  stand-ins behave as FIRST's SDK 12.0 does, checked against its code. `setVelocity` switches to
  RUN_USING_ENCODER, setting the mode a motor has does nothing, a new power enables a disabled
  motor, a NaN command is 0, and each OpMode finds the configured motor type and PIDF;
  `getBulkData()` refreshes the bulk cache. A servo reads 0 before its first position and refuses
  NaN; `setPulseWidth` sends the pulse as it is, outside the PWM range too, and a target tolerance
  the hub can't take is refused. The OTOS reports in any unit, the IMU keeps its `resetYaw()` zero
  into the next OpMode, and out of range the 2m distance sensor reads 8190 mm and the color sensor
  6 in; the device panel is told each odometry pose's units. A trigger counts as pressed from 0.5,
  INIT resets the threshold and START forgets presses made during INIT (a button still held counts
  at its next update); each PlayStation button name has its own pressed/released edge; an
  iterative OpMode's gamepads don't change while its `loop()` runs. Telemetry clears when data is
  added after `update()`, not at `update()`; it formats numbers and lays out items as FIRST's
  does, runs `Func`s and actions once per transmission, never holds up the field, and passes on
  what they throw. `ImuTest`: the IMU gives the angles FIRST's own orientation math gives, for
  every axis order and hub mounting.
- `OpModeLifecycleTest`, `PhysicsLoopTest`: a thread an earlier OpMode left can't stop the next
  one; an OpMode that ignores STOP is ended at its next hardware call and lets go of the gamepads,
  and the next OpMode gets fresh static fields, as after the robot app restarts; an iterative loop
  takes its own time plus a millisecond; a LinearOpMode's `time` follows its runtime; telemetry
  exists while the OpMode is constructed. A failure on the physics thread is logged once and the
  field carries on; a slow OpMode constructor doesn't stop the field (STOP cancels its INIT, and
  Reset is refused meanwhile); the device panel is told what has no value; the physics loop
  pauses about a millisecond between passes (on Windows too).
- `WebServerTest`, `CodeEditorTest`, `DeployTest`, `LauncherTest`, `TeamCodeCompilerTest`,
  `OptionsTest`: the page's server answers only requests addressed to this computer, and its
  WebSocket only the simulator's own page; it passes over a port another program has at
  localhost, IPv4 or IPv6; OpModes stop when the last page closes or crashes, a page that leaves
  lets go of its gamepads, and one page's "nothing pressed" doesn't undo a gamepad another page
  holds. The
  code editor saves keeping line endings, refuses stale writes, writes outside the project and
  through links to nothing, won't open a file that isn't UTF-8, and shows compile errors where
  they are; the sources are read while an editor writes temporary files; a team's copy of a
  sample is the team's OpMode; every file that compiles is built however many others fail, and
  with a file's first 20 errors shown. Deploy finds the project and the robots, builds, installs
  and restarts the Robot Controller app (with stand-ins for Gradle and adb), runs Gradle on the
  project's own Gradle JDK or one its Gradle version can run on, and says why a build failed.
  `run-sim.sh` and `run-sim.bat` pick a JDK 17 or newer by its version (Android Studio's too) and
  start the simulator from a copy no later build rewrites; a relative `--project` is where it was
  typed, and a relative `--robot` or `--season` there if it is there. `sim.properties` is read as
  written, backslashes included, and a wrong option is explained; `--robots` on the command line
  is how many robots there are; `--help` lists every option. `VisionPortalBehaviorTest`: camera states, controls and live view follow FIRST's
  portal, and the webcam sits and looks where its mount says.
- `SampleOpModesTest`, `ExampleOpModesTest`: the official samples and the examples run end to
  end (every sample file with an OpMode gives one). For example, `RobotAutoDriveByGyro_Linear`
  returns to within about 10 cm of its start, the Pinpoint auto drives its 20 in square and
  returns to within 1.5 in (about 0.5 in in practice), BIOBUZZ Parking ends in the red
  LOADING ZONE, and from the near end, the middle and the far end of its documented shooting
  range the BIOBUZZ TeleOp's first 2 shots land in the red HIVE's CELL and the 3rd tips the HIVE.

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
| `sdk/` | The FTC SDK 12.0 API on a PC: FIRST's own classes where they are plain Java (Vision; RobotCore math, `AnalogInput`, `LED`, `JavaUtil` and the IMU's orientation helper; Hardware's `RevBlinkinLedDriver` and `RevHubOrientationOnRobot`), stand-ins with the same packages and signatures for the rest; simulator internals are in `ftcsim.hal` |
| `natives/apriltag/` | FIRST's AprilTag detector compiled for PCs (Windows x64, Linux x64 with glibc 2.38 or newer such as Ubuntu 24.04, and macOS on Apple silicon and Intel), with the script that builds it. Elsewhere (Linux on ARM, older Linux) the simulator runs without AprilTag detection |
| `engine/` | Physics, robot model, OpMode runner, TeamCode compiler, code editor files and deploy (`ftcsim.deploy`), web server, browser UI (`src/main/resources/web`; the editor is CodeMirror 6, fetched from npm at build time) |
| `examples/teamcode/` | Example OpModes for the default robot |
| `examples/samples/` | The official FTC sample OpModes that run in the simulator (unmodified) |
| `robots/` | Robot descriptions; `robots/cad/` holds the CAD models of the CAD examples |
| `seasons/` | One season pack per FTC game: its `season.json` and its Java ([Seasons](#seasons)) |
| `tools/` | `step2cadmesh.py`, which converts a STEP file too big for the Driver Station page ([Big assemblies](#big-assemblies)) |
| `paper/` | The technical report (LaTeX source, figures, bibliography) |
| `docs/` | The project page, served by GitHub Pages; `docs/figures/src/` draws the report's figures |

## License

The simulator is released under the [MIT License](LICENSE).

Third-party code in this repository keeps its own license:

| Path | What | License |
|---|---|---|
| `examples/samples/` | FTC SDK sample OpModes from FtcRobotController v12.0, © FIRST, except `SensorSparkFunOTOS.java`, © SparkFun Electronics | BSD-3-Clause-Clear ([LICENSE-FTC-SDK](examples/samples/LICENSE-FTC-SDK)); `SensorSparkFunOTOS.java`: MIT (in its header) |
| `engine/src/main/resources/web/vendor/` | Three.js and OrbitControls, © three.js authors | MIT ([THREE-LICENSE](engine/src/main/resources/web/vendor/THREE-LICENSE)) |
| `natives/apriltag/` | AprilTag detector, © The Regents of The University of Michigan, and its JNI glue, © OpenFTC Team (compiled) | BSD-2-Clause ([LICENSE-apriltag](natives/apriltag/LICENSE-apriltag.md)), MIT ([LICENSE-eocv-apriltag-plugin](natives/apriltag/LICENSE-eocv-apriltag-plugin.txt)) |
| `engine/src/main/java/ftcsim/vision/Tag36h11.java` | tag36h11 codes from AprilRobotics/apriltag | BSD-2-Clause ([LICENSE-apriltag](natives/apriltag/LICENSE-apriltag.md)) |
| `docs/` | The project page: the Academic Project Page Template; Bulma 0.9.1 and bulma-carousel; five Font Awesome Free 5.15.1 icons, inlined as SVG | the page: CC BY-SA 4.0 (credited in its footer); Bulma, bulma-carousel: MIT; the icons: CC BY 4.0 |

Libraries that Gradle downloads at build time: ODE4j (LGPL-2.1-or-later or BSD-style, at your
choice), Javalin, Gson and Kotlin stdlib (Apache-2.0), Jetty (Apache-2.0 or EPL-2.0), SLF4J (MIT),
FIRST's FTC SDK packages RobotCore and Vision (BSD-3-Clause), OpenFTC's AprilTag plugin (MIT),
and OpenCV with JavaCPP and OpenBLAS from Bytedeco (Apache-2.0, BSD-3-Clause). From npm:
occt-import-js, Open CASCADE Technology compiled to WebAssembly, which the Driver Station page
uses to read STEP files (both LGPL-2.1; their license texts are served with them), and
CodeMirror 6 with the packages it uses (`@codemirror/*`, `@lezer/*`,
`@marijn/find-cluster-break`, crelt, style-mod, w3c-keyname; MIT), the code editor.

The field layouts follow the *BIOBUZZ presented by RTX* (FIRST Tech Challenge 2026–27) and
*DECODE presented by RTX* (2025–26) Competition Manuals and FIRST's field drawings and CAD.
FIRST®, FIRST® Tech Challenge, BIOBUZZ™ and DECODE™ are trademarks of FIRST (For Inspiration and
Recognition of Science and Technology). This project is not affiliated with or endorsed by FIRST.
