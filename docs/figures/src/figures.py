"""The technical report's figures, drawn from the simulator's own data.

Writes <slug>.svg (served by the website); with --jsx also <slug>.jsx, the same drawing as report-doc widget code.
Figure 5 in the doc is a data-driven widget written by hand from LOOP_ROWS below.
Text ids match the doc's widgets: keep them when a label changes, since comments hang on them.

usage (from the repository root):
    python -B docs/figures/src/figures.py seasons/biobuzz/season.json docs/figures
"""
import json
import math
import os
import sys

from figlib import Fig, fmt

FIELD_JSON, OUT = sys.argv[1], sys.argv[2]


# ---------------------------------------------------------------------------- Figure 1: architecture
def architecture():
    f = Fig("architecture", 720, "Every robot, sensor and camera reads from one 1 kHz physics world")
    q = dict(anchor="middle", size=11.5, fill="quiet")
    Y = 104   # everything below the top row (editing and deploying) moves down by one row
    with f.group("sdk-layer"):
        f.rect(24, Y + 160, 688, 120, fill="tint", stroke="edge")
        f.text("b-title", 40, Y + 182, "FTC SDK 12.0 API, checked against FIRST’s jars (111 classes, 1,130 members)", weight=600)
    with f.group("connectors"):
        f.line(124, 128, 124, Y + 56, arrow="end")
        f.path(f"M224 {Y + 76}H246V92H268", arrow="end")
        f.line(468, 92, 512, 92, arrow="end")
        f.line(224, Y + 92, 268, Y + 92, arrow="end")
        f.line(468, Y + 92, 512, Y + 92, arrow="end")
        f.line(612, Y + 128, 612, Y + 160, arrow="end")
        f.line(612, Y + 264, 612, Y + 312, arrow="end")
        f.line(224, Y + 340, 268, Y + 340, arrow="end")
        f.path(f"M184 {Y + 384}V{Y + 400}H300V{Y + 416}", arrow="end")
        f.line(368, Y + 384, 368, Y + 416, arrow="both")
        f.line(612, Y + 416, 612, Y + 384, arrow="end")
        f.line(224, Y + 452, 268, Y + 452, arrow="end")
        f.line(468, Y + 452, 512, Y + 452, arrow="end")
        f.line(300, Y + 488, 300, Y + 520, arrow="end")
        f.line(344, Y + 556, 392, Y + 556, arrow="both")
    with f.group("code-editor"):
        f.box(24, 56, 200, 72)
        f.text("h1-name", 124, 80, "Code editor, in the page", anchor="middle", weight=600)
        f.text("h1-l1", 124, 96, "saves the team’s files,", maxw=176, **q)
        f.text("h1-l2", 124, 112, "underlines javac’s errors", maxw=176, **q)
    with f.group("deploy"):
        f.box(268, 56, 200, 72)
        f.text("h2-name", 368, 80, "Deploy", anchor="middle", weight=600)
        f.text("h2-l1", 368, 96, "the project’s own Gradle", maxw=176, **q)
        f.text("h2-l2", 368, 112, "build, installed by ADB", maxw=176, **q)
    with f.group("real-robot"):
        f.box(512, 56, 200, 72)
        f.text("h3-name", 612, 80, "The real robot", anchor="middle", weight=600)
        f.text("h3-l1", 612, 96, "Control Hub or phone,", maxw=176, **q)
        f.text("h3-l2", 612, 112, "over USB or Wi-Fi", maxw=176, **q)
    with f.group("team-project"):
        f.box(24, Y + 56, 200, 72)
        f.text("a1-name", 124, Y + 80, "Team’s project", anchor="middle", weight=600)
        f.text("a1-l1", 124, Y + 96, "TeamCode, unchanged", maxw=176, **q)
        f.text("a1-l2", 124, Y + 112, "your FtcRobotController", maxw=176, **q)
    with f.group("compiler"):
        f.box(268, Y + 56, 200, 72)
        f.text("a2-name", 368, Y + 80, "TeamCode compiler", anchor="middle", weight=600)
        f.text("a2-l1", 368, Y + 96, "javac --release 8, on save", maxw=176, **q)
        f.text("a2-l2", 368, Y + 112, "skips unsupported files", maxw=176, **q)
    with f.group("class-loader"):
        f.box(512, Y + 56, 200, 72)
        f.text("a3-name", 612, Y + 80, "Class loader per robot", anchor="middle", weight=600)
        f.text("a3-l1", 612, Y + 96, "statics and blackboard", maxw=176, **q)
        f.text("a3-l2", 612, Y + 112, "kept apart per robot", maxw=176, **q)
    with f.group("first-classes"):
        f.box(40, Y + 192, 208, 72)
        f.text("b1-name", 144, Y + 216, "FIRST’s own classes", anchor="middle", weight=600)
        f.text("b1-l1", 144, Y + 232, "Vision, AprilTagProcessor,", maxw=184, **q)
        f.text("b1-l2", 144, Y + 248, "RobotCore math, run as-is", maxw=184, **q)
    with f.group("apriltag-native"):
        f.box(264, Y + 192, 208, 72)
        f.text("b2-name", 368, Y + 216, "AprilTag C detector", anchor="middle", weight=600)
        f.text("b2-l1", 368, Y + 232, "OpenFTC plugin 2.1.0,", maxw=184, **q)
        f.text("b2-l2", 368, Y + 248, "compiled for PCs", maxw=184, **q)
    with f.group("stand-ins"):
        f.box(488, Y + 192, 208, 72)
        f.text("b3-name", 592, Y + 216, "Stand-ins", anchor="middle", weight=600)
        f.text("b3-l1", 592, Y + 232, "HardwareMap, motors,", maxw=184, **q)
        f.text("b3-l2", 592, Y + 248, "sensors, VisionPortalImpl", maxw=184, **q)
    with f.group("robot-file"):
        f.box(24, Y + 312, 200, 72)
        f.text("f1-name", 124, Y + 336, "Robot file per robot", anchor="middle", weight=600)
        f.text("f1-l1", 124, Y + 352, "devices, drive, chassis;", maxw=176, **q)
        f.text("f1-l2", 124, Y + 368, "JSON, or CAD (STEP, URDF)", maxw=176, **q)
    with f.group("sim-hardware"):
        f.box(268, Y + 312, 444, 72)
        f.text("c-name", 490, Y + 336, "Simulated hardware (ftcsim.hal)", anchor="middle", weight=600)
        f.text("c-l1", 490, Y + 352, "hub firmware and PIDF, bus latency, bulk caching,", maxw=420, **q)
        f.text("c-l2", 490, Y + 368, "battery, sensor mounts from the robot file", maxw=420, **q)
    with f.group("season-pack"):
        f.box(24, Y + 416, 200, 72)
        f.text("g1-name", 124, Y + 440, "Season pack", anchor="middle", weight=600)
        f.text("g1-l1", 124, Y + 456, "season.json and Java", maxw=176, **q)
        f.text("g1-l2", 124, Y + 472, "BIOBUZZ (default), DECODE", maxw=176, **q)
    with f.group("physics"):
        f.box(268, Y + 416, 200, 72, main=True)
        f.text("d1-name", 368, Y + 440, "Physics at 1 kHz", anchor="middle", weight=600)
        f.text("d1-l1", 368, Y + 456, "ODE4j: robots, game pieces,", anchor="middle", size=11.5, maxw=176)
        f.text("d1-l2", 368, Y + 472, "field, motors, battery sag", anchor="middle", size=11.5, maxw=176)
    with f.group("renderer"):
        f.box(512, Y + 416, 200, 72)
        f.text("d2-name", 612, Y + 440, "Webcam renderer", anchor="middle", weight=600)
        f.text("d2-l1", 612, Y + 456, "software, SDK lens table", maxw=176, **q)
        f.text("d2-l2", 612, Y + 472, "30 fps, 50 ms latency", maxw=176, **q)
    with f.group("web-server"):
        f.box(24, Y + 520, 320, 72)
        f.text("e1-name", 184, Y + 544, "Web server", anchor="middle", weight=600)
        f.text("e1-l1", 184, Y + 560, "Javalin over WebSocket", maxw=296, **q)
        f.text("e1-l2", 184, Y + 576, "state out, gamepads in", maxw=296, **q)
    with f.group("browser"):
        f.box(392, Y + 520, 320, 72)
        f.text("e2-name", 552, Y + 544, "Browser", anchor="middle", weight=600)
        f.text("e2-l1", 552, Y + 560, "Three.js 3D view, Driver Station", maxw=296, **q)
        f.text("e2-l2", 552, Y + 576, "Xbox, PlayStation, keyboard", maxw=296, **q)
    f.text("lbl-saves", 132, 148, "saves", size=11.5, fill="quiet")
    f.text("lbl-builds", 254, 148, "builds", size=11.5, fill="quiet")
    f.text("lbl-apk", 490, 84, "app", anchor="middle", size=11.5, fill="quiet")
    f.text("lbl-torques", 378, Y + 404, "torques, readings", size=11.5, fill="quiet")
    f.text("lbl-frames", 622, Y + 404, "frames", size=11.5, fill="quiet")
    f.text("lbl-shapes", 246, Y + 444, "shapes", anchor="middle", size=11.5, fill="quiet")
    f.text("lbl-poses", 490, Y + 444, "poses", anchor="middle", size=11.5, fill="quiet")
    f.text("lbl-state", 310, Y + 508, "world state", size=11.5, fill="quiet")
    return f


# ---------------------------------------------------------------------------- Figure 6: robots from CAD
def cad_import():
    f = Fig("cad-import", 640, "The CAD gives the shape and mass, the robot file the devices and limits",
            "A STEP file is converted once in the browser; a URDF already has its joints and masses")
    q = dict(anchor="middle", size=11.5, fill="quiet")
    with f.group("connectors"):
        for y1, y2 in ((128, 160), (216, 248), (304, 336), (408, 440)):
            f.line(132, y1, 132, y2, arrow="end")
        f.line(380, 128, 380, 440, arrow="end")
        f.line(628, 128, 628, 440, arrow="end")
        f.line(194, 496, 194, 528, arrow="end")
        f.line(566, 496, 566, 528, arrow="end")
    with f.group("step-file"):
        f.box(24, 72, 216, 56)
        f.text("step-name", 132, 96, "STEP file", anchor="middle", weight=600)
        f.text("step-1", 132, 112, "the assembly, as exported", maxw=192, **q)
    with f.group("urdf"):
        f.box(272, 72, 216, 56)
        f.text("urdf-name", 380, 96, "URDF and meshes", anchor="middle", weight=600)
        f.text("urdf-1", 380, 112, "links, joints, STL or OBJ", maxw=192, **q)
    with f.group("robot-file"):
        f.box(520, 72, 216, 56)
        f.text("robot-name", 628, 96, "Robot file (JSON)", anchor="middle", weight=600)
        f.text("robot-1", 628, 112, "devices, drive, cad section", maxw=192, **q)
    with f.group("convert"):
        f.box(24, 160, 216, 56)
        f.text("convert-name", 132, 184, "Converted in the browser", anchor="middle", weight=600)
        f.text("convert-1", 132, 200, "OpenCascade in WebAssembly", maxw=192, **q)
    with f.group("cadmesh"):
        f.box(24, 248, 216, 56)
        f.text("cadmesh-name", 132, 272, "Saved triangles", anchor="middle", weight=600)
        f.text("cadmesh-1", 132, 288, "name.step.cadmesh + SHA-256", maxw=192, **q)
    with f.group("detect"):
        f.box(24, 336, 216, 72)
        f.text("detect-name", 132, 360, "Worked out from names", anchor="middle", weight=600)
        f.text("detect-1", 132, 376, "facing, drive wheels,", maxw=192, **q)
        f.text("detect-2", 132, 392, "rollers, flywheels, link axes", maxw=192, **q)
    with f.group("builder"):
        f.box(24, 440, 712, 56)
        f.text("builder-name", 380, 464, "Model builder", anchor="middle", weight=600)
        f.text("builder-1", 380, 480,
               "mass properties from the meshes, oriented collision boxes per part, a joint for each moving link",
               maxw=688, **q)
    with f.group("physics"):
        f.box(24, 528, 340, 88, main=True)
        f.text("physics-name", 194, 552, "Physics world (ODE4j, 1 kHz)", anchor="middle", weight=600)
        f.text("physics-1", 194, 568, "chassis and wheels as bodies,", anchor="middle", size=11.5, maxw=316)
        f.text("physics-2", 194, 584, "links on hinge and slider joints,", anchor="middle", size=11.5, maxw=316)
        f.text("physics-3", 194, 600, "driven by the robot’s motors and servos", anchor="middle", size=11.5, maxw=316)
    with f.group("views"):
        f.box(396, 528, 340, 88)
        f.text("views-name", 566, 552, "3D view and webcams", anchor="middle", weight=600)
        f.text("views-1", 566, 568, "drawn from the CAD meshes,", maxw=316, **q)
        f.text("views-2", 566, 584, "simplified for the webcam renderer", maxw=316, **q)
    f.text("lbl-urdf", 390, 272, "its own joints and masses", size=11.5, fill="quiet")
    f.text("lbl-robot-1", 618, 320, "devices and drive;", anchor="end", size=11.5, fill="quiet")
    f.text("lbl-robot-2", 618, 336, "for each moving link:", anchor="end", size=11.5, fill="quiet")
    f.text("lbl-robot-3", 618, 352, "part, device, limits", anchor="end", size=11.5, fill="quiet")
    return f


# ---------------------------------------------------------------------------- Figure 2: field map
def field_map():
    d = json.load(open(FIELD_JSON, encoding="utf-8"))
    half = d["field_m"][0] / 2
    S = 432 / d["field_m"][0]
    X0, Y0 = 56, 96
    px = lambda x: X0 + (x + half) * S
    py = lambda y: Y0 + (half - y) * S
    f = Fig("field-map", 572, "The field is the same after a half turn, with red and blue swapped",
            "Top view, to scale, drawn from the simulator’s field description file")
    tile = d["tile_m"] * S

    with f.group("tiles"):
        f.rect(X0, Y0, 432, 432, rx=0, fill="tint", stroke="edge", sw=2)
        for i in range(1, 6):
            f.line(X0 + tile * i, Y0, X0 + tile * i, Y0 + 432, stroke="grid", sw=1)
            f.line(X0, Y0 + tile * i, X0 + 432, Y0 + tile * i, stroke="grid", sw=1)
    with f.group("walls"):
        f.text("wall-rear", X0 + 216, Y0 - 10, "Rear wall", fill="quiet", size=11.5, anchor="middle")
        f.text("wall-audience", X0 + 216, Y0 + 452, "Audience wall", fill="quiet", size=11.5, anchor="middle")
        f.text("wall-red", X0 - 14, Y0 + 216, "Red alliance wall", fill="quiet", size=11.5, anchor="middle", rotate=-90)
        f.text("wall-blue", X0 + 446, Y0 + 216, "Blue alliance wall", fill="quiet", size=11.5, anchor="middle", rotate=90)

    role = {"red": "red", "blue": "accent"}
    with f.group("loading-zones"):
        for a in ("red", "blue"):
            z = d["loading_zones"][a]
            f.rect(px(z["x_m"][0]) + 1.5, py(z["y_m"][1]) + 1.5, (z["x_m"][1] - z["x_m"][0]) * S - 3,
                   (z["y_m"][1] - z["y_m"][0]) * S - 3, rx=0, stroke=role[a], sw=3)
    with f.group("gardens"):
        for a in ("red", "blue"):
            g = d["gardens"][a]
            f.rect(px(g["x_m"][0]), py(g["y_m"][1]), (g["x_m"][1] - g["x_m"][0]) * S,
                   (g["y_m"][1] - g["y_m"][0]) * S, rx=0, fill=role[a], stroke="none")
    with f.group("flowers"):
        for name in ("flower_red", "flower_rear", "flower_blue", "flower_audience"):
            fl = d["flowers"][name]
            x, y = px(fl["axis_m"][0]), py(fl["axis_m"][1])
            f.circle(x, y, d["structure"]["flower_top"]["radius_m"] * S, fill="none", stroke="ink", sw=1.5)
            f.circle(x, y, fl["opening_diameter_m"] / 2 * S, fill="ink", stroke="none", op=0.25)

    cells = d["cells"]
    hw = cells["half_width_m"] * S
    tags = {("red", "rear"): "30–33", ("red", "audience"): "34–37",
            ("blue", "audience"): "38–41", ("blue", "rear"): "42–45"}
    for a in ("red", "blue"):
        h = d["hives"][a]
        cx = px(h["pivot_m"][0])
        with f.group(f"hive-{a}"):
            for cell, sign in (("rear", 1), ("audience", -1)):
                y_in, y_out = py(sign * cells["along_arm_m"][0]), py(sign * cells["along_arm_m"][1])
                ytop = min(y_in, y_out)
                up = h["start_up"] == cell
                f.rect(cx - hw, ytop, 2 * hw, abs(y_out - y_in), rx=3, stroke=role[a], fill=role[a],
                       sw=1.5, op=0.45 if up else 0.12)
            f.line(cx, py(cells["along_arm_m"][0]), cx, py(-cells["along_arm_m"][0]), stroke=role[a], sw=3)
            f.line(cx - hw - 6, py(0), cx + hw + 6, py(0), stroke="ink", sw=1, dash="3 3")
            f.circle(cx, py(0), 3, fill="ink", stroke="none")
    for a in ("red", "blue"):
        cx = px(d["hives"][a]["pivot_m"][0])
        for cell, sign in (("rear", 1), ("audience", -1)):
            ymid = (py(sign * cells["along_arm_m"][0]) + py(sign * cells["along_arm_m"][1])) / 2
            f.text(f"tags-{a}-{cell}", cx, ymid + 4, tags[(a, cell)], size=11.5, anchor="middle")
    f.text("hive-red-name", px(d["hives"]["red"]["pivot_m"][0]), py(cells["along_arm_m"][1]) - 8, "Red HIVE",
           anchor="middle", weight=600)
    f.text("hive-blue-name", px(d["hives"]["blue"]["pivot_m"][0]), py(cells["along_arm_m"][1]) - 8, "Blue HIVE",
           anchor="middle", weight=600)
    with f.group("origin"):
        f.circle(px(0), py(0), 3.5, fill="ink", stroke="none")

    # key
    kx, tx = 540, 572
    y = 104
    with f.group("key"):
        f.rect(kx, y - 2, 22, 22, rx=0, fill="tint", stroke="grid", sw=1)
        f.text("key-tile", tx, y + 8, "Tile: 0.61 m (24 in)", size=11.5, maxw=164)
        f.text("key-field", tx, y + 24, "Field: 3.66 m square", size=11.5, fill="quiet", maxw=164)
        y += 52
        f.rect(kx + 2, y - 6, 18, 10, rx=2, stroke="edge", fill="edge", op=0.12)
        f.rect(kx + 2, y + 10, 18, 10, rx=2, stroke="edge", fill="edge", op=0.45)
        f.line(kx + 11, y + 4, kx + 11, y + 10, stroke="edge", sw=2)
        f.text("key-hive", tx, y + 4, "HIVE: two CELLS on a pivot", size=11.5, maxw=164)
        f.text("key-hive-2", tx, y + 20, "turning about the x axis", size=11.5, fill="quiet", maxw=164)
        f.text("key-up", tx, y + 36, "Darker CELL: up at the start", size=11.5, fill="quiet", maxw=170)
        y += 68
        f.rect(kx, y - 8, 22, 16, rx=2, stroke="edge", fill="none")
        f.text("key-tags-n", kx + 11, y + 4, "30", size=10.5, anchor="middle")
        f.text("key-tags", tx, y + 4, "AprilTag IDs on each CELL", size=11.5, maxw=164)
        f.text("key-tags-2", tx, y + 20, "tag36h11, 8.25 cm", size=11.5, fill="quiet", maxw=164)
        y += 52
        f.circle(kx + 11, y, 10, fill="none", stroke="ink", sw=1.5)
        f.circle(kx + 11, y, 5, fill="ink", stroke="none", op=0.25)
        f.text("key-flower", tx, y + 4, "FLOWER, against a wall", size=11.5, maxw=164)
        y += 40
        f.rect(kx + 1, y - 10, 9, 22, rx=0, stroke="red", sw=3)
        f.rect(kx + 13, y - 10, 9, 22, rx=0, stroke="accent", sw=3)
        f.text("key-loading", tx, y + 4, "LOADING ZONE: tape outline", size=11.5, maxw=164)
        y += 40
        f.rect(kx, y - 4, 10, 6, rx=0, fill="red", stroke="none")
        f.rect(kx + 12, y - 4, 10, 6, rx=0, fill="accent", stroke="none")
        f.text("key-garden", tx, y + 4, "GARDEN: solid tape strip", size=11.5, maxw=164)
        y += 44
        f.line(kx + 2, y + 22, kx + 26, y + 22, stroke="ink", sw=1.25, arrow="end")
        f.line(kx + 2, y + 22, kx + 2, y - 2, stroke="ink", sw=1.25, arrow="end")
        f.circle(kx + 2, y + 22, 3, fill="ink", stroke="none")
        f.text("key-x", tx, y + 4, "+x toward the blue wall", size=11.5, maxw=164)
        f.text("key-y", tx, y + 20, "+y away from the audience", size=11.5, maxw=164)
        f.text("key-origin", tx, y + 36, "Origin: dot at the center", size=11.5, fill="quiet", maxw=164)
    return f


# ---------------------------------------------------------------------------- Figure 3: HIVE seesaw
def hive_seesaw():
    f = Fig("hive-seesaw", 492, "A HIVE tips when the balls’ moment beats its own holding moment",
            "Side view along the pivot axis, rear CELL up against its 30° stop; center-of-mass offset drawn larger than scale")
    S, P = 430, (372, 256)
    phi = math.radians(30)
    cells = {"in": 0.2393, "out": 0.5451, "floor": -0.035, "wall": 0.1935}

    def pt(yh, zh):
        Y = yh * math.cos(phi) - zh * math.sin(phi)
        Z = yh * math.sin(phi) + zh * math.cos(phi)
        return P[0] + Y * S, P[1] - Z * S

    def seg(a, b):
        (x1, y1), (x2, y2) = pt(*a), pt(*b)
        return f"M{fmt(x1)} {fmt(y1)}L{fmt(x2)} {fmt(y2)}"

    with f.group("level"):
        f.line(P[0] - 230, P[1], P[0] + 250, P[1], stroke="grid", sw=1, dash="4 4")
    with f.group("structure"):
        f.path(f"M{P[0]} {P[1]}L{P[0] - 46} {P[1] + 150}M{P[0]} {P[1]}L{P[0] + 46} {P[1] + 150}", stroke="edge", sw=2)
        f.line(P[0] - 70, P[1] + 150, P[0] + 70, P[1] + 150, stroke="edge", sw=2)
        f.text("structure", P[0] + 56, P[1] + 140, "HIVE structure", fill="quiet", size=11.5)
    fl, wl = cells["floor"], cells["floor"] + cells["wall"]
    for side, sgn in (("rear", 1), ("audience", -1)):
        i, o = sgn * cells["in"], sgn * cells["out"]
        with f.group(f"cell-{side}"):
            f.path(seg((i, wl), (i, fl)), stroke="ink", sw=2.5)
            f.path(seg((i, fl), (o, fl)), stroke="ink", sw=2.5)
            f.path(seg((i, wl), (o, wl)), stroke="edge", sw=1.25, dash="4 3")
            f.path(seg((o, fl), (o, wl)), stroke="edge", sw=1, dash="2 4")
        with f.group(f"arm-{side}"):
            f.path(seg((0, 0), (i, 0)), stroke="ink", sw=4)
    with f.group("balls"):
        r_n, r_p = 0.0455, 0.0355
        c1 = cells["in"] + r_n
        c2 = c1 + 2 * r_n
        c3 = c2 + r_n + r_p
        for c, r in ((c1, r_n), (c2, r_n), (c3, r_p)):
            x, y = pt(c, fl + r)
            f.circle(x, y, r * S, fill="warn", stroke="ink", sw=1, op=0.35)
    with f.group("pivot"):
        f.circle(P[0], P[1], 6.5, fill="tint", stroke="ink", sw=1.5)

    # center of mass, drawn 2.5x farther from the pivot than scale so it is visible
    hc = 0.0345 * 2.5
    cmx, cmy = pt(0, hc)
    with f.group("center-of-mass"):
        f.line(P[0], P[1], cmx, cmy, stroke="ink", sw=1, dash="2 2")
        f.circle(cmx, cmy, 4.5, fill="ink", stroke="none")
        f.line(cmx, cmy + 6, cmx, cmy + 58, stroke="ink", sw=1.5, arrow="end")
    with f.group("com-leader"):
        f.line(cmx - 6, cmy - 6, cmx - 26, cmy - 40, stroke="quiet", sw=1)
    f.text("com", cmx - 30, cmy - 60, "center of mass, 34.5 mm", anchor="end", size=11.5, weight=600)
    f.text("com-2", cmx - 30, cmy - 44, "above the pivot; its weight acts here", anchor="end", size=11.5, fill="quiet")

    b2x, b2y = pt(c2, fl + r_n)
    with f.group("ball-weight"):
        f.line(b2x, b2y + r_n * S + 4, b2x, b2y + r_n * S + 52, stroke="ink", sw=1.5, arrow="end")
    f.text("balls-weight", b2x + 8, b2y + r_n * S + 46, "balls’ weight", size=11.5)

    # moments
    def arc(r, a0, a1):
        x0, y0 = P[0] + r * math.cos(math.radians(a0)), P[1] + r * math.sin(math.radians(a0))
        x1, y1 = P[0] + r * math.cos(math.radians(a1)), P[1] + r * math.sin(math.radians(a1))
        sweep = 1 if a1 > a0 else 0
        return f"M{fmt(x0)} {fmt(y0)}A{r} {r} 0 0 {sweep} {fmt(x1)} {fmt(y1)}"

    with f.group("holding-moment"):
        f.path(arc(96, 200, 166), stroke="accent", sw=2, arrow="end")
    f.text("holding", P[0] - 106, P[1] - 42, "holding moment", anchor="end", size=11.5, weight=600)
    f.text("holding-2", P[0] - 106, P[1] - 26, "pulls this side down", anchor="end", size=11.5, fill="quiet")
    with f.group("tipping-moment"):
        f.path(arc(96, -12, 22), stroke="red", sw=2, arrow="end")
    f.text("tipping", P[0] + 108, P[1] + 20, "tipping moment", size=11.5, weight=600)
    f.text("tipping-2", P[0] + 108, P[1] + 36, "pulls this side down", size=11.5, fill="quiet")

    # angle and labels on the cells
    with f.group("stop-angle"):
        f.path(arc(52, 150, 180), stroke="quiet", sw=1)
    f.text("angle", P[0] - 70, P[1] + 18, "30°", size=11.5, fill="quiet")
    ox, oy = pt(cells["out"], fl)
    f.text("open-end", ox + 10, oy - 4, "open end", size=11.5, fill="quiet")
    ix, iy = pt(cells["in"], wl)
    f.text("closed-end", ix - 6, iy - 8, "closed end", anchor="end", size=11.5, fill="quiet")
    f.text("cell-up", ox - 22, oy - 82, "CELL facing up", size=11.5, weight=600)
    lx, ly = pt(-cells["out"], fl)
    f.text("cell-down", lx + 6, ly + 26, "CELL facing down", size=11.5, weight=600)

    f.text("rule", 24, 452, "Each side’s moment = weight × horizontal distance from the pivot; a damper at the pivot slows the swing.",
           size=11.5, fill="quiet")
    f.text("rule-2", 24, 470, "When the balls win, the HIVE swings to its other stop and these balls roll out of the lowered open end.",
           size=11.5, fill="quiet")
    return f


# ---------------------------------------------------------------------------- Figure 4: mecanum grip
def mecanum():
    f = Fig("mecanum-grip", 452, "Each mecanum wheel grips only along its roller axis",
            "Top view of the simulated chassis, front up; directions from the robot model’s wheel setup")
    cx, cy = 236, 262
    W, H = 200, 236
    ww, wh = 34, 70
    with f.group("chassis"):
        f.rect(cx - W / 2, cy - H / 2, W, H, rx=10, stroke="ink", sw=1.5, fill="tint")
        f.line(cx, cy - H / 2 - 44, cx, cy - H / 2 - 6, stroke="ink", sw=1.5, arrow="end")
    f.text("front", cx + 8, cy - H / 2 - 30, "front (+x)", size=11.5, fill="quiet")
    s = math.sqrt(0.5)
    wheels = [("front-left", -1, -1, (s, -s)), ("front-right", 1, -1, (s, s)),
              ("back-left", -1, 1, (s, s)), ("back-right", 1, 1, (s, -s))]
    L, Lc = 44, 28
    for name, sx, sy, (gx, gy) in wheels:
        wx = cx + sx * (W / 2 + ww / 2 + 4)
        wy = cy + sy * (H / 2 - wh / 2 - 10)
        # robot frame: x forward (screen up), y left (screen left)
        dx, dy = -gy, -gx
        with f.group(f"wheel-{name}"):
            f.rect(wx - ww / 2, wy - wh / 2, ww, wh, rx=6, stroke="ink", sw=1.25, fill="none")
            f.line(wx - dy * Lc, wy + dx * Lc, wx + dy * Lc, wy - dx * Lc, stroke="quiet", sw=1.25, dash="3 3")
            f.line(wx - dx * L, wy - dy * L, wx + dx * L, wy + dy * L, stroke="accent", sw=2.5, arrow="both")
    f.text("fl", cx - W / 2 - 24, cy - H / 2 - 14, "front_left", size=11.5, anchor="middle", fill="quiet")
    f.text("fr", cx + W / 2 + 24, cy - H / 2 - 14, "front_right", size=11.5, anchor="middle", fill="quiet")
    f.text("bl", cx - W / 2 - 24, cy + H / 2 + 26, "back_left", size=11.5, anchor="middle", fill="quiet")
    f.text("br", cx + W / 2 + 24, cy + H / 2 + 26, "back_right", size=11.5, anchor="middle", fill="quiet")

    kx, tx = 452, 492
    with f.group("key"):
        f.line(kx, 132, kx + 30, 132, stroke="accent", sw=2.5, arrow="both")
        f.text("grip", tx, 128, "Roller axis: friction direction 1", size=11.5, weight=600, maxw=244)
        f.text("grip-2", tx, 144, "full traction, μ = 0.9 by default", size=11.5, fill="quiet", maxw=244)
        f.line(kx, 188, kx + 30, 188, stroke="quiet", sw=1.25, dash="3 3")
        f.text("free", tx, 184, "Across it: friction direction 2", size=11.5, weight=600, maxw=244)
        f.text("free-2", tx, 200, "rollers spin, μ₂ = 0.04 by default", size=11.5, fill="quiet", maxw=244)
    f.text("note-1", kx, 252, "Spinning front_left forward pushes the", size=11.5, maxw=284)
    f.text("note-2", kx, 268, "robot forward and to the right. Wheels", size=11.5, maxw=284)
    f.text("note-3", kx, 284, "on a diagonal share a roller direction,", size=11.5, maxw=284)
    f.text("note-4", kx, 300, "so the four wheel speeds set forward,", size=11.5, maxw=284)
    f.text("note-5", kx, 316, "sideways and turning motion.", size=11.5, maxw=284)
    f.text("note-6", kx, 348, "Individual rollers are not modeled; a", size=11.5, fill="quiet", maxw=284)
    f.text("note-7", kx, 364, "small force-dependent slip lets wheels", size=11.5, fill="quiet", maxw=284)
    f.text("note-8", kx, 380, "creep under load instead of locking.", size=11.5, fill="quiet", maxw=284)
    return f


# ---------------------------------------------------------------------------- Figure 5: loop timing
LOOP_ROWS = [
    # mode, kind, label, start_ms, end_ms
    ("off", "read", "read", 0, 1), ("off", "read", "read", 1, 2), ("off", "read", "read", 2, 3), ("off", "read", "read", 3, 4),
    ("off", "i2c", "IMU", 4, 6.5),
    ("off", "write", "power", 6.5, 7.5), ("off", "write", "power", 7.5, 8.5), ("off", "write", "power", 8.5, 9.5),
    ("off", "write", "power", 9.5, 10.5),
    ("bulk", "bulk", "bulk", 0, 1),
    ("bulk", "i2c", "IMU", 1, 3.5),
    ("bulk", "write", "power", 3.5, 4.5), ("bulk", "write", "power", 4.5, 5.5), ("bulk", "write", "power", 5.5, 6.5),
    ("bulk", "write", "power", 6.5, 7.5),
]


def loop_timing_static():
    f = Fig("loop-timing", 312, "Bulk reads cut this control loop from 10.5 ms to 7.5 ms",
            "One loop: read 4 drive encoders, read the IMU heading, set 4 changed motor powers")
    X0, X1, T = 184, 664, 11
    X = lambda t: X0 + t / T * (X1 - X0)
    rowy = {"off": 112, "bulk": 176}
    fill = {"read": "edge", "write": "edge", "i2c": "warn", "bulk": "accent"}
    with f.group("time-axis"):
        for t in range(0, T + 1):
            f.line(X(t), 84, X(t), 212, stroke="grid", sw=1)
            f.text(f"tick-{t}", X(t), 76, f"{t}", size=11.5, anchor="middle", fill="quiet")
        f.text("ms", X(T) + 12, 76, "ms", size=11.5, fill="quiet")
    for i, (mode, kind, label, a, b) in enumerate(LOOP_ROWS):
        y = rowy[mode]
        f.rect(X(a) + 1, y - 16, X(b) - X(a) - 2, 32, rx=4, stroke=fill[kind], fill=fill[kind],
               op=0.35 if kind in ("i2c", "bulk") else 0.15, sw=1.25)
        f.text(f"seg-{i}", (X(a) + X(b)) / 2, y + 4, label, size=11.5, anchor="middle")
    f.text("row-off", 24, rowy["off"] - 2, "Bulk caching OFF", weight=600)
    f.text("row-off-2", 24, rowy["off"] + 14, "each read is a command", size=11.5, fill="quiet")
    f.text("row-bulk", 24, rowy["bulk"] - 2, "AUTO or MANUAL", weight=600)
    f.text("row-bulk-2", 24, rowy["bulk"] + 14, "one bulk read for all four", size=11.5, fill="quiet")
    f.text("total-off", X(10.5) + 8, rowy["off"] + 4, "10.5 ms", size=11.5, weight=600)
    f.text("total-bulk", X(7.5) + 8, rowy["bulk"] + 4, "7.5 ms", size=11.5, weight=600)
    with f.group("key"):
        f.rect(184, 240, 22, 14, rx=3, stroke="edge", fill="edge", op=0.15)
        f.text("key-cmd", 214, 251, "hub command, 1 ms", size=11.5)
        f.rect(364, 240, 22, 14, rx=3, stroke="warn", fill="warn", op=0.35)
        f.text("key-i2c", 394, 251, "I2C read, 2.5 ms", size=11.5)
        f.rect(524, 240, 22, 14, rx=3, stroke="accent", fill="accent", op=0.35)
        f.text("key-bulk", 554, 251, "bulk read, 1 ms", size=11.5)
    f.text("note", 24, 290, "A power write costs a command only when the power changes. Default costs; both are set per robot.",
           size=11.5, fill="quiet")
    return f


# ---------------------------------------------------------------------------- Figure 7: vision pipeline
def vision():
    f = Fig("vision-pipeline", 508, "Only the image is simulated: FIRST’s code finds the tags and solves their pose",
            "One webcam frame, from the physics state to the detections a team’s OpMode reads")
    lanes = [("lane-sim", 68, 128, "Simulator", "tint"), ("lane-first", 212, 164, "FIRST’s code, unmodified", "accent"),
             ("lane-team", 392, 92, "Team code", "tint")]
    for anchor, y, h, name, role in lanes:
        with f.group(anchor):
            if role == "accent":
                f.rect(24, y, 712, h, rx=10, stroke="accent", fill="accent", op=0.06, sw=1.25)
            else:
                f.rect(24, y, 712, h, rx=10, stroke="grid", fill="tint", sw=1)
    f.text("lane-sim-name", 40, 90, "Simulator", size=11.5, weight=600, fill="quiet")
    f.text("lane-first-name", 40, 234, "FIRST’s code, unmodified", size=11.5, weight=600, fill="quiet")
    f.text("lane-team-name", 40, 414, "Team code", size=11.5, weight=600, fill="quiet")
    bw, bh = 196, 72
    xs = [40, 268, 524]
    top, mid, low = 104, 248, 420

    def b(anchor, x, y, h=bh):
        with f.group(anchor):
            f.box(x, y, bw, h, fill="none")

    b("physics", xs[0], top)
    f.text("physics-name", xs[0] + bw / 2, top + 24, "Physics state", anchor="middle", weight=600)
    f.text("physics-1", xs[0] + bw / 2, top + 40, "world as it was 50 ms earlier", anchor="middle", size=11.5, fill="quiet", maxw=184)
    f.text("physics-2", xs[0] + bw / 2, top + 56, "(the camera’s latency)", anchor="middle", size=11.5, fill="quiet", maxw=184)
    b("renderer", xs[1], top)
    f.text("renderer-name", xs[1] + bw / 2, top + 24, "Webcam renderer", anchor="middle", weight=600)
    f.text("renderer-1", xs[1] + bw / 2, top + 40, "software rasterizer, pinhole", anchor="middle", size=11.5, fill="quiet", maxw=184)
    f.text("renderer-2", xs[1] + bw / 2, top + 56, "30 frames/s by default", anchor="middle", size=11.5, fill="quiet", maxw=184)
    b("portal", xs[2], top)
    f.text("portal-name", xs[2] + bw / 2, top + 24, "VisionPortal stand-in", anchor="middle", weight=600)
    f.text("portal-1", xs[2] + bw / 2, top + 40, "RGBA frame + capture time", anchor="middle", size=11.5, fill="quiet", maxw=184)
    f.text("portal-2", xs[2] + bw / 2, top + 56, "camera states, FPS, streaming", anchor="middle", size=11.5, fill="quiet", maxw=184)
    b("calibration", xs[0], mid)
    f.text("calibration-name", xs[0] + bw / 2, mid + 24, "SDK calibration table", anchor="middle", weight=600)
    f.text("calibration-1", xs[0] + bw / 2, mid + 40, "lens intrinsics per webcam,", anchor="middle", size=11.5, fill="quiet", maxw=184)
    f.text("calibration-2", xs[0] + bw / 2, mid + 56, "by USB vendor and product ID", anchor="middle", size=11.5, fill="quiet", maxw=184)
    b("detector", xs[1], mid)
    f.text("detector-name", xs[1] + bw / 2, mid + 24, "AprilTag C detector", anchor="middle", weight=600)
    f.text("detector-1", xs[1] + bw / 2, mid + 40, "OpenFTC plugin 2.1.0, built", anchor="middle", size=11.5, fill="quiet", maxw=184)
    f.text("detector-2", xs[1] + bw / 2, mid + 56, "for Windows, Linux and macOS", anchor="middle", size=11.5, fill="quiet", maxw=184)
    with f.group("processor"):
        f.box(xs[2], mid, bw, bh, main=True)
    f.text("processor-name", xs[2] + bw / 2, mid + 24, "AprilTagProcessor", anchor="middle", weight=600)
    f.text("processor-1", xs[2] + bw / 2, mid + 40, "solves each tag’s pose with", anchor="middle", size=11.5, maxw=184)
    f.text("processor-2", xs[2] + bw / 2, mid + 56, "the same lens intrinsics", anchor="middle", size=11.5, maxw=184)
    b("opmode", xs[2], low, 56)
    f.text("opmode-name", xs[2] + bw / 2, low + 24, "Team’s OpMode", anchor="middle", weight=600)
    f.text("opmode-1", xs[2] + bw / 2, low + 40, "reads IDs and poses", anchor="middle", size=11.5, fill="quiet", maxw=184)

    with f.group("connectors"):
        f.line(xs[0] + bw, top + 36, xs[1], top + 36, arrow="end")
        f.line(xs[1] + bw, top + 36, xs[2], top + 36, arrow="end")
        f.line(xs[2] + bw / 2, top + bh, xs[2] + bw / 2, mid, arrow="end")
        f.path(f"M{xs[0] + bw - 16} {mid}V{top + bh + 20}H{xs[1] + 56}V{top + bh}", arrow="end")
        f.line(xs[2], mid + 26, xs[1] + bw, mid + 26, arrow="end")
        f.line(xs[1] + bw, mid + 50, xs[2], mid + 50, arrow="end")
        f.path(f"M{xs[0] + bw / 2} {mid + bh}V{mid + bh + 24}H{xs[2] + 40}V{mid + bh}", arrow="end")
        f.line(xs[2] + 150, mid + bh, xs[2] + 150, low, arrow="end")
    f.text("lbl-intrinsics", xs[0] + bw - 8, top + bh + 14, "lens intrinsics", size=11.5, fill="quiet")
    f.text("lbl-frame", xs[2] + bw / 2 + 8, top + bh + 24, "frame", size=11.5, fill="quiet")
    f.text("lbl-frame-detector", xs[1] + bw + 32, mid + 20, "frame", anchor="middle", size=11.5, fill="quiet")
    f.text("lbl-corners", xs[1] + bw + 32, mid + 66, "corners", anchor="middle", size=11.5, fill="quiet")
    f.text("lbl-same", xs[0] + bw / 2 + 8, mid + bh + 18, "same entry, looked up by the webcam’s USB IDs", size=11.5, fill="quiet")
    f.text("lbl-detections", xs[2] + 142, mid + bh + 46, "detections", anchor="end", size=11.5, fill="quiet")
    return f


# ---------------------------------------------------------------------------- Figure 8: match
def match():
    f = Fig("match-robots", 420, "Four robots share one physics world, but each runs its own code in isolation",
            "Each robot is a separate Robot Controller; they share only the physics world and the match start")
    with f.group("match-control"):
        f.rect(24, 72, 712, 44, rx=8, fill="tint", stroke="edge")
    f.text("match-control-name", 380, 92, "Match control: INIT all, START all", anchor="middle", weight=600)
    f.text("match-control-1", 380, 108, "every robot’s OpMode starts at the same moment, so the 30 s autonomous timers line up",
           anchor="middle", size=11.5, fill="quiet")
    bw, gap, y0 = 164, 18, 148
    xs = [24 + i * (bw + gap) for i in range(4)]
    for i, x in enumerate(xs):
        with f.group(f"robot-{i + 1}"):
            f.box(x, y0, bw, 120)
    for i, x in enumerate(xs):
        n = i + 1
        cxm = x + bw / 2
        f.text(f"robot-{n}-name", cxm, y0 + 24, f"Robot {n}", anchor="middle", weight=600)
        f.text(f"robot-{n}-1", cxm, y0 + 44, "own class loader", anchor="middle", size=11.5, fill="quiet", maxw=140)
        f.text(f"robot-{n}-2", cxm, y0 + 60, "TeamCode, OpMode", anchor="middle", size=11.5, fill="quiet", maxw=140)
        f.text(f"robot-{n}-3", cxm, y0 + 76, "robot file, gamepads", anchor="middle", size=11.5, fill="quiet", maxw=140)
        f.text(f"robot-{n}-4", cxm, y0 + 92, "telemetry, log", anchor="middle", size=11.5, fill="quiet", maxw=140)
        f.text(f"robot-{n}-5", cxm, y0 + 108, f"page ?robot={n}", anchor="middle", size=11.5, maxw=140)
    with f.group("physics"):
        f.box(24, 312, 712, 72, main=True)
    f.text("physics-name", 380, 336, "One physics world (ODE4j, 1 kHz)", anchor="middle", weight=600)
    f.text("physics-1", 380, 352, "BIOBUZZ field, 56 balls, two HIVES", anchor="middle", size=11.5)
    f.text("physics-2", 380, 368, "robots collide, push each other, and appear in each other’s distance sensors and webcams",
           anchor="middle", size=11.5)
    with f.group("connectors"):
        for x in xs:
            f.line(x + bw / 2, 116, x + bw / 2, y0, arrow="end")
            f.line(x + bw / 2, y0 + 120, x + bw / 2, 312, arrow="both")
    f.text("lbl-motors", xs[0] + bw / 2 + 8, 294, "torques, readings", size=11.5, fill="quiet")
    f.text("note", 24, 406, "Kept per robot: static fields, OpMode.blackboard, RobotLog’s global error, bus latency settings.",
           size=11.5, fill="quiet")
    return f


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for build in (architecture, field_map, hive_seesaw, mecanum, loop_timing_static, cad_import, vision, match):
        fig = build()
        with open(os.path.join(OUT, fig.slug + ".svg"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(fig.svg())
        if "--jsx" in sys.argv:
            with open(os.path.join(OUT, fig.slug + ".jsx"), "w", encoding="utf-8", newline="\n") as fh:
                fh.write(fig.jsx())
        print(f"{fig.slug}.svg written")
        for w in fig.warnings:
            print("   WARN", w)
