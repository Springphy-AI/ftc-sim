r"""
Converts a robot's STEP file into the simulator's mesh file (<name>.step.cadmesh) on this
computer, with the full (native) OpenCascade library instead of the Driver Station page's.

The page's converter (OpenCascade compiled to WebAssembly) can use at most 2 GB of memory. A big
assembly, such as a whole competition robot with every screw and mecanum roller, fills that and
comes out with no triangles at all. This converter uses as much memory as the computer has.

The output is what the page would have made: the same file format, part names, colors, units
(meters) and level of detail. The simulator checks that it belongs to the STEP file as it is now
(its fingerprint) and that its triangles are valid, then builds the robot from it.

Set up once (Python 3; about 1 GB to download), from the simulator's folder:

    python3 -m venv tools/.venv
    tools/.venv/bin/pip install cadquery-ocp          (Windows: tools\.venv\Scripts\pip)

Then, after each new STEP export:

    tools/.venv/bin/python tools/step2cadmesh.py ../cad_robots/OurRobot.step

It writes ../cad_robots/OurRobot.step.cadmesh. Restart the simulator, or start it afterwards, so it
reads the new file.
"""
import hashlib
import json
import struct
import sys
import time
from array import array
from pathlib import Path

from OCP.Bnd import Bnd_Box
from OCP.BRep import BRep_Tool
from OCP.BRepBndLib import BRepBndLib
from OCP.BRepMesh import BRepMesh_IncrementalMesh
from OCP.IFSelect import IFSelect_RetDone
from OCP.Quantity import Quantity_Color
from OCP.STEPCAFControl import STEPCAFControl_Reader
from OCP.TCollection import TCollection_ExtendedString
from OCP.TDataStd import TDataStd_Name
from OCP.TDF import TDF_Label
try:
    from OCP.TDF import TDF_LabelSequence
except ImportError:   # OCP 8 names it after NCollection_Sequence
    from OCP.OCP.collections import Sequence_TDF_Label as TDF_LabelSequence
from OCP.TDocStd import TDocStd_Document
from OCP.TopAbs import TopAbs_FACE, TopAbs_REVERSED, TopAbs_SOLID
from OCP.TopExp import TopExp_Explorer
from OCP.TopLoc import TopLoc_Location
from OCP.TopoDS import TopoDS  # module of casts in OCP 8 (TopoDS.Face)
from OCP.XCAFApp import XCAFApp_Application
from OCP.XCAFDoc import XCAFDoc_ColorCurv, XCAFDoc_ColorGen, XCAFDoc_ColorSurf, XCAFDoc_ColorTool, XCAFDoc_DocumentTool

CONVERTER = "step2cadmesh (native OpenCascade)"
# The same detail as the simulator's browser converter (cadconvert.js TESSELLATION).
LINEAR_RATIO = 0.0015      # of the model's size
ANGULAR_RAD = 0.35
DEFAULT_COLOR = "#9aa0a6"
MM = 0.001                 # OpenCascade reads STEP files in millimeters; the mesh file is in meters


def sha256_without_cr(path):
    """The simulator's fingerprint of a STEP file: SHA-256 of its bytes with carriage returns left out."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk.replace(b"\r", b""))
    return h.hexdigest()


def srgb_hex(c):
    """OpenCascade colors are linear RGB; the simulator stores sRGB, as the browser converter does."""
    def to(v):
        v = max(0.0, min(1.0, v))
        s = 12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055
        return f"{round(s * 255):02x}"
    return "#" + to(c.Red()) + to(c.Green()) + to(c.Blue())


def label_name(label):
    attr = TDataStd_Name()
    if label.FindAttribute(TDataStd_Name.GetID_s(), attr):
        return attr.Get().ToExtString()
    return ""


# The kinds of color a STEP label or shape can have, in the order the page's converter
# (occt-import-js) looks for them.
COLOR_KINDS = (XCAFDoc_ColorSurf, XCAFDoc_ColorCurv, XCAFDoc_ColorGen)


def label_color(label):
    """The color set on a label (a part, an assembly, or one placement of either), or None."""
    c = Quantity_Color()
    for kind in COLOR_KINDS:
        if XCAFDoc_ColorTool.GetColor_s(label, kind, c):
            return srgb_hex(c)
    return None


def shape_color(colors, shape):
    """The color set on a face (or other sub-shape) of a part, or None."""
    c = Quantity_Color()
    for kind in COLOR_KINDS:
        if colors.GetColor(shape, kind, c):
            return srgb_hex(c)
    return None


def part_color(own, face_triangles):
    """
    A part's color as the page's converter picks it (cadconvert.js meshColor): its own color (the
    placement's, else the part's), else the color most of its triangles' faces have, else gray.
    face_triangles: face color -> how many triangles have it.
    """
    if own:
        return own
    best = None
    for color, n in face_triangles.items():
        if best is None or n > face_triangles[best]:
            best = color
    return best or DEFAULT_COLOR


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: step2cadmesh.py path/to/robot.step")
    step = Path(sys.argv[1]).resolve()
    if not step.is_file():
        sys.exit(f"not found: {step}")
    out = step.with_name(step.name + ".cadmesh")
    t0 = time.time()

    print(f"Reading {step.name} ({step.stat().st_size / 1e6:.0f} MB)...", flush=True)
    doc = TDocStd_Document(TCollection_ExtendedString("XmlOcaf"))
    XCAFApp_Application.GetApplication_s().InitDocument(doc)
    reader = STEPCAFControl_Reader()
    reader.SetNameMode(True)
    reader.SetColorMode(True)
    if reader.ReadFile(str(step)) != IFSelect_RetDone:
        sys.exit("OpenCascade could not read it as a STEP file")
    if not reader.Transfer(doc):
        sys.exit("OpenCascade could not build the shapes in the STEP file")
    shapes = XCAFDoc_DocumentTool.ShapeTool_s(doc.Main())
    colors = XCAFDoc_DocumentTool.ColorTool_s(doc.Main())
    print(f"  read in {time.time() - t0:.0f} s", flush=True)

    roots = TDF_LabelSequence()
    shapes.GetFreeShapes(roots)
    box = Bnd_Box()
    for i in range(1, roots.Length() + 1):
        BRepBndLib.Add_s(shapes.GetShape_s(roots.Value(i)), box)
    lo, hi = box.CornerMin(), box.CornerMax()
    x0, y0, z0, x1, y1, z1 = lo.X(), lo.Y(), lo.Z(), hi.X(), hi.Y(), hi.Z()
    size = max(x1 - x0, y1 - y0, z1 - z0)
    print(f"  model is {(x1 - x0) * MM:.3f} x {(y1 - y0) * MM:.3f} x {(z1 - z0) * MM:.3f} m", flush=True)
    deflection = size * LINEAR_RATIO

    parts = []   # (path, color, positions array('f'), indices array('I'))

    def add_part(path, color, shape, loc):
        pos, idx = array("f"), array("I")
        face_triangles = {}   # face color -> triangles, for a part without a color of its own
        exp = TopExp_Explorer(shape, TopAbs_FACE)
        while exp.More():
            face = TopoDS.Face(exp.Current())
            floc = TopLoc_Location()
            tri = BRep_Tool.Triangulation_s(face, floc)
            if tri is not None and tri.NbTriangles() > 0:
                trsf = loc.Multiplied(floc).Transformation()
                base = len(pos) // 3
                for n in range(1, tri.NbNodes() + 1):
                    p = tri.Node(n).Transformed(trsf)
                    pos.extend((p.X() * MM, p.Y() * MM, p.Z() * MM))
                fc = shape_color(colors, face)
                if fc:
                    face_triangles[fc] = face_triangles.get(fc, 0) + tri.NbTriangles()
                flip = face.Orientation() == TopAbs_REVERSED
                for t in range(1, tri.NbTriangles() + 1):
                    a, b, c = tri.Triangle(t).Get()
                    if flip:
                        b, c = c, b
                    idx.extend((base + a - 1, base + b - 1, base + c - 1))
            exp.Next()
        parts.append(("/".join(path) or "part", part_color(color, face_triangles), pos, idx))

    def walk(label, loc, path, placed):
        """
        label: a shape (assembly or part) placed at loc; path: the names down to it; placed: the
        color of this placement (its component label's), which wins over the shape's own, as in
        the page's converter. An assembly's color isn't handed down to its parts: the page's isn't.
        """
        name = label_name(label)
        here = path + [name] if name and (not path or path[-1] != name) else path
        color = placed or label_color(label)
        if shapes.IsAssembly_s(label):
            comps = TDF_LabelSequence()
            shapes.GetComponents_s(label, comps, False)
            for i in range(1, comps.Length() + 1):
                comp = comps.Value(i)
                ref = TDF_Label()
                if not shapes.GetReferredShape_s(comp, ref):
                    continue
                cname = label_name(comp)
                cpath = here + [cname] if cname else here
                walk(ref, loc.Multiplied(shapes.GetLocation_s(comp)), cpath, label_color(comp))
            return
        shape = shapes.GetShape_s(label)
        # Instances of one part share its faces; OpenCascade meshes them once and skips them after.
        BRepMesh_IncrementalMesh(shape, deflection, False, ANGULAR_RAD, True)
        # A part made of several solid bodies becomes one part per body, as in the browser converter.
        solids = []
        exp = TopExp_Explorer(shape, TopAbs_SOLID)
        while exp.More():
            solids.append(exp.Current())
            exp.Next()
        for piece in (solids if len(solids) > 1 else [shape]):
            add_part(here, color, piece, loc)

    print("Making triangles...", flush=True)
    for i in range(1, roots.Length() + 1):
        walk(roots.Value(i), TopLoc_Location(), [], None)

    triangles = sum(len(p[3]) // 3 for p in parts)
    print(f"  {len(parts)} parts, {triangles:,} triangles", flush=True)
    if triangles == 0:
        sys.exit("No triangles came out, so no mesh file was written.")

    header = {
        "source": step.name,
        "sha256": sha256_without_cr(step),
        "converter": CONVERTER,
        "parts": [{"path": p, "color": c, "vertices": len(v) // 3, "triangles": len(t) // 3}
                  for p, c, v, t in parts],
    }
    js = json.dumps(header, separators=(",", ":")).encode("utf-8")
    pad = (4 - (8 + 4 + len(js)) % 4) % 4
    tmp = out.with_name(out.name + ".tmp")
    with open(tmp, "wb") as f:
        f.write(b"FTCMESH1")
        f.write(struct.pack("<I", len(js)))
        f.write(js)
        f.write(b"\0" * pad)
        little = sys.byteorder == "little"
        for _, _, v, t in parts:
            if not little:
                v.byteswap(); t.byteswap()
            v.tofile(f)
            t.tofile(f)
    tmp.replace(out)
    print(f"Wrote {out} ({out.stat().st_size / 1e6:.1f} MB) in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
