"""Tiny figure builder: one description -> (a) Claude Docs widget JSX, (b) a static SVG for the website.

Colors are roles. The widget gets the doc's theme tokens; the static SVG gets CSS variables with
light and dark values, so it follows the page's color scheme.
"""
import html
import re

TOKENS = {
    "ink": "var(--cds-text-primary)",
    "quiet": "var(--cds-text-secondary)",
    "edge": "var(--cds-chart-axis)",
    "grid": "var(--cds-chart-grid)",
    "tint": "var(--cds-chart-reference-tint)",
    "accent": "var(--cds-chart-categorical-1)",
    "red": "var(--cds-chart-status-critical)",
    "warn": "var(--cds-chart-status-warning)",
    "good": "var(--cds-chart-status-good)",
    "none": "none",
}
STATIC = {  # light, dark
    "ink": ("#1f2328", "#e6edf3"),
    "quiet": ("#59636e", "#9198a1"),
    "edge": ("#818b98", "#6e7781"),
    "grid": ("#d1d9e0", "#30363d"),
    "tint": ("#f6f8fa", "#161b22"),
    "accent": ("#0969da", "#4493f8"),
    "red": ("#cf222e", "#f85149"),
    "warn": ("#bf8700", "#d29922"),
    "good": ("#1a7f37", "#3fb950"),
}
CAMEL = {
    "stroke-width": "strokeWidth", "text-anchor": "textAnchor", "font-size": "fontSize",
    "font-weight": "fontWeight", "fill-opacity": "fillOpacity", "stroke-dasharray": "strokeDasharray",
    "marker-end": "markerEnd", "marker-start": "markerStart", "stroke-linecap": "strokeLinecap",
    "stroke-linejoin": "strokeLinejoin", "font-style": "fontStyle", "stroke-opacity": "strokeOpacity",
}
CHAR_W = {(15, 600): 8.5, (13, 600): 7.4, (13, 400): 7.0, (11.5, 400): 6.2, (11.5, 600): 6.6, (12, 400): 6.5}


def fmt(v):
    if isinstance(v, str):
        return v
    r = round(v, 2)
    if r == int(r):
        return str(int(r))
    return f"{r:.2f}".rstrip("0") if abs(r) < 10 else f"{round(v, 1):g}"


class Fig:
    def __init__(self, slug, height, title, subtitle=None, width=760, size=13):
        self.slug, self.w, self.h, self.title, self.size = slug, width, height, title, size
        self.root = []
        self.stack = [self.root]
        self.markers = []
        self.ids = set()
        self.warnings = []
        self.text("title", 24, 32, title, size=15, weight=600)
        if subtitle:
            self.text("subtitle", 24, 52, subtitle, fill="quiet", size=11.5)

    # ---- structure
    def _add(self, tag, attrs, kids=None):
        node = [tag, attrs, kids if kids is not None else []]
        self.stack[-1].append(node)
        return node

    class _Group:
        def __init__(self, fig, node):
            self.fig, self.node = fig, node

        def __enter__(self):
            self.fig.stack.append(self.node[2])

        def __exit__(self, *a):
            self.fig.stack.pop()

    def group(self, anchor, **attrs):
        a = {"data-claude-anchor": anchor}
        a.update(self._style(attrs))
        return Fig._Group(self, self._add("g", a))

    def _marker(self, role):
        mid = f"{self.slug}-arrow" + ("" if role == "edge" else f"-{role}")
        if role not in [r for r, _ in self.markers]:
            self.markers.append((role, mid))
        return f"url(#{mid})"

    def _style(self, kw):
        out = {}
        for k, v in kw.items():
            k = k.replace("_", "-")
            if k in ("fill", "stroke") and v in TOKENS and v != "none":
                v = "ROLE:" + v
            out[k] = v
        return out

    # ---- shapes
    def rect(self, x, y, w, h, stroke="edge", fill="none", sw=1.25, rx=8, op=None, dash=None, **kw):
        a = {"x": x, "y": y, "width": w, "height": h}
        if rx:
            a["rx"] = rx
        a.update(self._style({"fill": fill, "stroke": stroke}))
        if stroke != "none":
            a["stroke-width"] = sw
        if op is not None:
            a["fill-opacity"] = op
        if dash:
            a["stroke-dasharray"] = dash
        a.update(self._style(kw))
        return self._add("rect", a)

    def box(self, x, y, w, h, main=False, **kw):
        if main:
            return self.rect(x, y, w, h, stroke="accent", fill="accent", sw=2, op=0.12, **kw)
        return self.rect(x, y, w, h, **kw)

    def line(self, x1, y1, x2, y2, stroke="edge", sw=1.25, dash=None, arrow=None, **kw):
        return self.path(f"M{fmt(x1)} {fmt(y1)}L{fmt(x2)} {fmt(y2)}", stroke=stroke, sw=sw, dash=dash, arrow=arrow, **kw)

    def path(self, d, stroke="edge", sw=1.25, fill="none", dash=None, arrow=None, **kw):
        a = {"d": d}
        a.update(self._style({"fill": fill, "stroke": stroke}))
        a["stroke-width"] = sw
        if dash:
            a["stroke-dasharray"] = dash
        if arrow in ("end", "both"):
            a["marker-end"] = self._marker(stroke)
        if arrow in ("start", "both"):
            a["marker-start"] = self._marker(stroke)
        a.update(self._style(kw))
        return self._add("path", a)

    def circle(self, cx, cy, r, fill="none", stroke="edge", sw=1.25, op=None, **kw):
        a = {"cx": cx, "cy": cy, "r": r}
        a.update(self._style({"fill": fill, "stroke": stroke}))
        if stroke != "none":
            a["stroke-width"] = sw
        if op is not None:
            a["fill-opacity"] = op
        a.update(self._style(kw))
        return self._add("circle", a)

    def polygon(self, pts, fill="none", stroke="edge", sw=1.25, op=None):
        a = {"points": " ".join(f"{fmt(x)},{fmt(y)}" for x, y in pts)}
        a.update(self._style({"fill": fill, "stroke": stroke}))
        if stroke != "none":
            a["stroke-width"] = sw
        if op is not None:
            a["fill-opacity"] = op
        return self._add("polygon", a)

    def text(self, tid, x, y, words, fill="ink", size=None, weight=None, anchor=None, rotate=None,
             maxw=None, italic=False):
        if tid in self.ids:
            raise SystemExit(f"{self.slug}: duplicate text id {tid}")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", tid):
            raise SystemExit(f"{self.slug}: bad text id {tid}")
        if re.search(r"[{}<>\"]", words):
            raise SystemExit(f"{self.slug}: unsafe characters in {words!r}")
        self.ids.add(tid)
        a = {"data-claude-text-id": tid, "x": x, "y": y}
        if anchor:
            a["text-anchor"] = anchor
        if size and size != self.size:
            a["font-size"] = size
        if weight:
            a["font-weight"] = weight
        if italic:
            a["font-style"] = "italic"
        if rotate is not None:
            a["transform"] = f"rotate({fmt(rotate)} {fmt(x)} {fmt(y)})"
        a.update(self._style({"fill": fill}))
        s = size or self.size
        est = len(words) * CHAR_W.get((s, weight or 400), s * 0.55)
        if maxw and est > maxw:
            self.warnings.append(f"{tid}: ~{est:.0f}px > {maxw}px: {words}")
        if rotate is None:
            left = x - (est / 2 if anchor == "middle" else est if anchor == "end" else 0)
            right = left + est
            if left < 4 or right > self.w - 4:
                self.warnings.append(f"{tid}: spans {left:.0f}..{right:.0f}, outside frame: {words}")
        return self._add("text", a, words)

    # ---- output
    def _attrs(self, a, jsx):
        out = []
        for k, v in a.items():
            if isinstance(v, str) and v.startswith("ROLE:"):
                role = v[5:]
                v = TOKENS[role] if jsx else f"var(--f-{role})"
            if not jsx and k.startswith("data-claude"):
                continue
            name = CAMEL.get(k, k) if jsx else k
            val = fmt(v)
            if jsx:
                if "'" in val:
                    raise SystemExit(f"quote in attribute {k}={val}")
                out.append(f"{name}='{val}'")
            else:
                out.append(f'{name}="{html.escape(val, quote=True)}"')
        return " ".join(out)

    def _node(self, n, jsx):
        tag, a, kids = n
        attrs = self._attrs(a, jsx)
        if isinstance(kids, str):
            body = kids if jsx else html.escape(kids, quote=False)
            return f"<{tag} {attrs}>{body}</{tag}>"
        inner = "".join(self._node(k, jsx) for k in kids)
        if not inner:
            return f"<{tag} {attrs}/>"
        return f"<{tag} {attrs}>{inner}</{tag}>"

    def _defs(self, jsx):
        if not self.markers:
            return ""
        parts = []
        for role, mid in self.markers:
            fill = TOKENS[role] if jsx else f"var(--f-{role})"
            if jsx:
                parts.append(f"<marker id='{mid}' viewBox='0 0 10 10' refX='9' refY='5' markerWidth='6' markerHeight='6' "
                             f"orient='auto-start-reverse'><path d='M0 0L10 5L0 10z' fill='{fill}'/></marker>")
            else:
                parts.append(f'<marker id="{mid}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" '
                             f'orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="{fill}"/></marker>')
        return "<defs>" + "".join(parts) + "</defs>"

    def jsx(self):
        if "'" in self.title:
            raise SystemExit("apostrophe in title: use a typographic one")
        body = "".join(self._node(n, True) for n in self.root)
        return (f"export default () => <svg viewBox='0 0 {self.w} {self.h}' role='img' aria-label='{self.title}' "
                f"fontSize='{fmt(self.size)}'>{self._defs(True)}{body}</svg>;")

    def svg(self):
        light = ";".join(f"--f-{k}:{v[0]}" for k, v in STATIC.items())
        dark = ";".join(f"--f-{k}:{v[1]}" for k, v in STATIC.items())
        # svg:root: a figure opened on its own (a phone zooming in) gets a background of the palette's own
        # mode, else dark mode puts its light ink on the browser's white canvas. Inlined in a page, the
        # svg isn't the root, so the page's background shows through.
        style = (f".ftcfig{{{light}}}svg:root{{background:#ffffff}}"
                 f"@media (prefers-color-scheme: dark){{.ftcfig{{{dark}}}svg:root{{background:#0d1117}}}}")
        body = "".join(self._node(n, False) for n in self.root)
        return (f'<svg xmlns="http://www.w3.org/2000/svg" class="ftcfig" viewBox="0 0 {self.w} {self.h}" role="img" '
                f'aria-labelledby="{self.slug}-title" font-size="{fmt(self.size)}" '
                f'font-family="system-ui, -apple-system, \'Segoe UI\', Roboto, Helvetica, Arial, sans-serif">'
                f'<title id="{self.slug}-title">{html.escape(self.title)}</title><style>{style}</style>'
                f"{self._defs(False)}{body}</svg>\n")
