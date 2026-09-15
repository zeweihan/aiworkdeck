#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Shared helpers for the litigation-timeline renderers.

Design principle: the model only produces semantic-map.json; ALL spatial
work (coordinates, text wrapping, collision-free packing) happens here in
deterministic code, so output quality does not depend on model strength.
"""
import json, math, os, html
from datetime import date

_HERE = os.path.dirname(os.path.abspath(__file__))
_TOKENS_PATH = os.path.join(_HERE, "..", "assets", "style-tokens.json")

with open(_TOKENS_PATH, encoding="utf-8") as _f:
    TOKENS = json.load(_f)

C = TOKENS["colors"]
FONT = TOKENS["font_stack"]
TITLE_FONT = TOKENS.get("title_font", FONT)
FS = TOKENS["type_scale"]
ARROW = TOKENS["arrow"]
RADIUS = TOKENS["radius"]
DASH = TOKENS["dash"]
STROKE = TOKENS["stroke"]


MARKER_VB = 12.0          # the arrowhead marker's viewBox is 12x12; tip at x=12


def arrow_geom(size=None, width=None):
    """Where a connector must END so its arrowhead reads as a SHARP point.

    The head is an isosceles triangle: at viewBox-x its height is (12 - x),
    scaled by s = size/12 into user space. The marker's reference point (refX)
    is what sits on the line's last point, so the triangle's height THERE is
    what the line's square butt cap has to hide inside.

      * cap wider than the triangle  -> it pokes out both sides and the tip
        renders as a FLAT stub (the v1.0.1 defect: at refX=11 the triangle was
        only 0.4x the stroke width, so the line was 2.4x too fat for it);
      * cap far behind the triangle  -> a visible SEAM between line and head.

    So the end point is placed where the triangle is ARROW["cover"] times the
    stroke width: comfortably wider than the cap (sharp tip) while still deeply
    overlapped (no seam). This is a RATIO, not a magic number — a thicker line
    or a bigger head moves the junction automatically and the two stay in
    proportion. Head SIZE itself stays fixed per class (10px / 14px): arrowheads
    that scale continuously with stroke width are rejected (STANDARDS §10).

    Returns (refX in viewBox units, lead = px of head in FRONT of the line end).
    """
    size = float(size or ARROW["size"])
    width = float(TOKENS["stroke"]["connector"] if width is None else width)
    lead = float(ARROW["cover"]) * width
    return round(MARKER_VB - lead / (size / MARKER_VB), 3), lead


def head_trim(size=None, width=None, clear=None):
    """How far short of the head node the LINE must stop, so that the arrow TIP
    — not the line's end — keeps `tip_clear` px of breathing room from the node.
    Grows with the head, so the visible gap stays constant across line weights."""
    clear = float(ARROW["tip_clear"] if clear is None else clear)
    return round(arrow_geom(size, width)[1] + clear, 3)


def trim_end(pts, back):
    """Pull a route's LAST point back along its final segment by `back` px, so
    the arrow TIP — not the line's end — lands where the route intended. Routers
    may keep aiming straight at the node border; the head-room is applied here,
    uniformly, without touching any collision logic. Never inverts a segment:
    the pull-back is capped so at least 1px of the final segment survives."""
    if len(pts) < 2 or back <= 0:
        return pts
    (x0, y0), (x1, y1) = pts[-2], pts[-1]
    dx, dy = x1 - x0, y1 - y0
    seg = math.hypot(dx, dy)
    if seg <= 1:
        return pts
    back = min(back, seg - 1)
    k = back / seg
    return list(pts[:-1]) + [(x1 - dx * k, y1 - dy * k)]


def arrow_marker(mid, color, size=None, width=None, refX=None):
    """Clean isosceles arrowhead at a FIXED pixel size (userSpaceOnUse), so it
    does not balloon with stroke-width. Kept small so the tip never overpowers
    the connector or collides with a node. `width` is the stroke width of the
    line this head terminates — it sets the junction (see arrow_geom)."""
    size = size or ARROW["size"]
    if refX is None:
        refX = arrow_geom(size, width)[0]
    return (f'<marker id="{mid}" viewBox="0 0 12 12" refX="{refX}" refY="6" '
            f'markerWidth="{size}" markerHeight="{size}" markerUnits="userSpaceOnUse" '
            f'orient="auto"><path d="{ARROW["path"]}" fill="{color}"/></marker>')


def esc(s: str) -> str:
    """Escape text for inclusion in SVG (keeps real <text>, never paths)."""
    return html.escape(s, quote=True)


def char_w(ch: str, fs: float) -> float:
    """Approximate glyph advance. CJK ~= 1em; latin/digits ~= 0.56em.
    Good enough for wrapping and fit-tests without a font engine."""
    return fs if ord(ch) > 0x2E7F else fs * 0.56


def text_w(s: str, fs: float) -> float:
    return sum(char_w(c, fs) for c in s)


# Chinese line-breaking rules (禁则处理 / kinsoku shori):
#   NO_START — punctuation that may not BEGIN a line (closing marks). If a break
#     would push one of these to a new line, we hang it on the current line
#     instead (小幅溢出, absorbed by the box's inner padding).
#   NO_END   — punctuation that may not END a line (opening marks). If a break
#     would leave one of these at a line end, we push it down with the next char.
# Both only move break positions; characters are never added, dropped, or edited.
NO_START = set("，。、；：！？）】》」』〕｝’”》〉…—·%‰℃，。！？；：")
NO_END = set("（【《「『〔｛‘“《〈#￥")


def wrap(text: str, fs: float, max_w: float):
    """Greedy character wrap to a max pixel width, honoring CJK 禁则 (no line may
    start with closing punctuation or end with opening punctuation). Returns a
    list of lines. Verbatim: only inserts line breaks, never edits characters."""
    lines, cur, acc = [], "", 0.0
    for ch in text:
        w = char_w(ch, fs)
        if acc + w > max_w and cur:
            # A break would put `ch` at the start of the next line.
            if ch in NO_START:
                # 避头: keep the closing mark on this line (hang past max_w a touch).
                cur += ch
                acc += w
                continue
            if cur[-1] in NO_END:
                # 避尾: don't leave an opening mark stranded at the line end —
                # send it down together with `ch`.
                opener = cur[-1]
                cur = cur[:-1]
                if cur:
                    lines.append(cur)
                cur, acc = opener + ch, char_w(opener, fs) + w
                continue
            lines.append(cur)
            cur, acc = ch, w
        else:
            cur += ch
            acc += w
    if cur:
        lines.append(cur)
    return lines or [""]


def parse_date(s: str) -> date:
    """Parse 'YYYY/M/D' (single or double digit month/day)."""
    y, m, d = (int(x) for x in s.strip().split("/"))
    return date(y, m, d)


def svg_open(width, height):
    # Body font is set as a presentation attribute on the root <svg> (inherited by
    # body <text>), NOT as a <style>text{...}</style> rule. A <style> rule outranks
    # the per-title font-family presentation attribute in the CSS cascade and would
    # silently override the Song title with the body sans everywhere. Root-level
    # inheritance lets each title's own font-family attribute win.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{int(width)}" '
            f'height="{int(height)}" viewBox="0 0 {int(width)} {int(height)}" '
            f'font-family="{FONT}">'
            f'<rect width="{int(width)}" height="{int(height)}" fill="{C["bg"]}"/>')


def load_map(path):
    """Read a semantic map, failing with something a person can act on.

    The two commonest ways this goes wrong are a mistyped path and a
    hand-edited JSON with a stray comma, and both used to surface as a raw
    traceback. `validate_map` already takes the trouble to report structural
    problems clearly "so a malformed map fails clearly instead of deep in a
    renderer" — the same courtesy belongs one step earlier, at the point where
    most people actually make the mistake. A traceback also tells a weaker model
    nothing it can fix, so it retries the same call.
    """
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        raise RuntimeError(f'no semantic map at "{path}" — check the path') from None
    except IsADirectoryError:
        raise RuntimeError(f'"{path}" is a folder, not a semantic map') from None
    except UnicodeDecodeError as e:
        raise RuntimeError(f'"{path}" is not UTF-8 text ({e.reason}) — re-save it as UTF-8') from None
    except json.JSONDecodeError as e:
        line = ""
        try:
            src = open(path, encoding="utf-8", errors="replace").read().splitlines()
            if 0 < e.lineno <= len(src):
                line = f'\n    line {e.lineno}: {src[e.lineno - 1].strip()[:90]}'
        except OSError:
            pass
        raise RuntimeError(
            f'"{path}" is not valid JSON: {e.msg} at line {e.lineno}, column {e.colno}.'
            f"{line}\n    a trailing comma or a smart quote is the usual cause") from None


SCHEMA_VERSION = 1
_TOP_KEYS = {"schema_version", "diagram_type", "layout", "title_text", "visual_mode", "axis",
             "axis_unit", "events", "spans", "points", "nodes", "edges",
             "engine", "direction", "arrows", "tall_leaves", "columns", "rows",
             "provenance", "checkpoint"}


EMPHASIS_HOSTS = ("events", "spans", "points", "nodes", "edges")


def _emphasised(m):
    return [(h, it) for h in EMPHASIS_HOSTS for it in (m.get(h) or [])
            if isinstance(it, dict) and it.get("emphasis")]


def strip_unearned_emphasis(m):
    """Enforce where the deep red may come from, before anything is drawn.

    In a litigation figure the red does not merely say "look here" — it says
    *this is what the case turns on*. Choosing it is a legal judgement, so the
    map has to say WHOSE judgement it was:

      * `emphasis_source: "user"` — the user named the element. Up to two, per
        the emphasis-discipline rule.
      * `emphasis_source: "model"` — nobody was available to ask, so the model
        picked the single most decisive element rather than deliver a figure with
        no focal point at all. **Exactly one** survives; the rest are cleared.
        The caller is expected to say out loud which one it marked (see the note
        returned here), and the file is a `-draft` until the user confirms it.
      * anything else, or absent — the map cannot say where its red came from,
        so no red is drawn.

    This lives in the deterministic layer rather than in guidance to the model,
    because a rule enforced by asking the rule-breaker to confess is not a rule:
    `provenance.emphasis_note` is written by the same model whose choice it
    describes, and authorises nothing.

    Returns human-readable notes (empty when nothing was changed).
    """
    cp = m.get("checkpoint") or {}
    src = str(cp.get("emphasis_source", "")).strip().lower()
    marked = _emphasised(m)
    if not marked:
        return []

    if src == "user":
        return []

    if src == "model":
        keep = marked[0]
        for _, it in marked[1:]:
            it["emphasis"] = False
        label = (keep[1].get("title") or keep[1].get("label")
                 or keep[1].get("text") or keep[1].get("id") or "?")
        notes = [f'emphasis: AI-CHOSEN — the deep red marks "{label}". Nobody named '
                 f"it, so this is the model's reading of what the case turns on.",
                 "  say so when delivering, and let the user move or remove it."]
        if len(marked) > 1:
            notes.insert(1, f"  {len(marked) - 1} further mark(s) cleared — an "
                            f"AI-chosen emphasis is limited to ONE.")
        return notes

    for _, it in marked:
        it["emphasis"] = False
    return [f"emphasis: {len(marked)} element(s) were marked deep-red but the map does "
            f"not record WHERE that choice came from — rendered without red.",
            '  set checkpoint.emphasis_source to "user" (they named it) or "model" '
            '(you chose it, and will say so).']


def validate_map(m):
    """Structural pre-flight check with actionable, id-annotated messages. Raises
    RuntimeError listing every problem, so a malformed map fails clearly instead
    of deep in a renderer. Does not touch dates (render_spans checks those).

    If `jsonschema` is installed and schemas/semantic-map.schema.json is present,
    it is also validated against that schema (Archify-style optional dependency);
    otherwise these hand checks stand alone.
    """
    layout = m.get("layout", "")
    errs, warns = [], []

    sv = m.get("schema_version")
    if sv is None:
        warns.append('missing "schema_version" (expected 1)')
    elif sv != SCHEMA_VERSION:
        errs.append(f'unsupported schema_version {sv!r} (this build expects {SCHEMA_VERSION})')
    for k in m:
        if k not in _TOP_KEYS:
            warns.append(f'unknown top-level field "{k}" (ignored)')

    if not m.get("title_text"):
        errs.append('missing "title_text" (chart title)')
    if layout in ("graphviz_flow", "graphviz_relation", "relation_tree"):
        nodes = m.get("nodes") or []
        if not nodes:
            errs.append('"nodes" is empty')
        ids = set()
        for i, n in enumerate(nodes):
            nid = n.get("id")
            if not nid:
                errs.append(f"node #{i} has no id")
            if not n.get("title"):
                errs.append(f'node "{nid or i}" has no title')
            ids.add(nid)
        for e in m.get("edges") or []:
            if e.get("from") not in ids or e.get("to") not in ids:
                errs.append(f'edge {e.get("from")}->{e.get("to")} references a missing node id')
    elif layout in ("numbered_point_timeline", "dated_point_timeline"):
        evs = m.get("events") or []
        if not evs:
            errs.append('"events" is empty')
        for i, ev in enumerate(evs):
            if not ev.get("text"):
                errs.append(f'event "{ev.get("id", i)}" has no text')
        if layout == "dated_point_timeline":
            for i, ev in enumerate(evs):
                if not ev.get("date"):
                    errs.append(f'event "{ev.get("id", i)}" has no "date" '
                                '(dated_point_timeline needs YYYY/M/D; use numbered for undated)')
    elif layout == "proportional_gantt":
        ax = m.get("axis") or {}
        if not ax.get("start") or not ax.get("end"):
            errs.append('"axis" needs start and end')
        if not (m.get("spans") or []):
            errs.append('"spans" is empty')
        for i, s in enumerate(m.get("spans") or []):
            for k in ("from", "to", "label_text"):
                if not s.get(k):
                    errs.append(f'span "{s.get("id", i)}" missing "{k}"')
    elif layout == "comparison_table":
        cols = m.get("columns") or []
        if len(cols) != 2:
            errs.append(f'comparison_table needs exactly 2 columns (A vs B); got {len(cols)}')
        cids = set()
        for i, c in enumerate(cols):
            if not c.get("id"):
                errs.append(f"column #{i} has no id")
            if not c.get("title"):
                errs.append(f'column "{c.get("id", i)}" has no title')
            cids.add(c.get("id"))
        rows = m.get("rows") or []
        if not rows:
            errs.append('"rows" is empty')
        for i, r in enumerate(rows):
            if not r.get("dimension"):
                warns.append(f'row #{i} has no "dimension" label')
            cells = r.get("cells") or {}
            for cidk in cids:
                if cidk and not cells.get(cidk):
                    errs.append(f'row #{i} missing a cell for column "{cidk}"')
    else:
        errs.append(f'unknown layout "{layout}"')

    _schema_errors(m, errs)   # optional jsonschema pass (no-op if unavailable)

    if warns:
        print("  [validate] " + "; ".join(warns))
    if errs:
        raise RuntimeError("semantic map has problems: " + "; ".join(errs))
    return True


def _schema_errors(m, errs):
    """Best-effort JSON Schema validation; silently skipped if jsonschema or the
    schema file is absent (like Archify's optional ajv step)."""
    try:
        import jsonschema  # noqa
    except Exception:
        return
    path = os.path.join(_HERE, "..", "schemas", "semantic-map.schema.json")
    if not os.path.exists(path):
        return
    try:
        with open(path, encoding="utf-8") as f:
            schema = json.load(f)
        v = jsonschema.Draft202012Validator(schema)
        for e in sorted(v.iter_errors(m), key=lambda e: list(e.path)):
            loc = "/".join(str(p) for p in e.path) or "(root)"
            errs.append(f'schema: {loc}: {e.message}')
    except Exception as ex:
        errs.append(f'schema validator error: {ex}')
