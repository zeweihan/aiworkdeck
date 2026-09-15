# Flowchart spec (frozen)

For process / procedure diagrams: steps, decisions, branches, merges. Rendered
by `render_flow.py`. Requires `dot` (graphviz) on PATH.

## Positioning: graphviz for coordinates ONLY

Feed graphviz sized, label-less boxes and read back **node positions** via
`dot -Tplain`. Do NOT use graphviz's edge routes — in graphviz 2.43 the ortho
splines come back truncated for non-aligned (branch/merge) edges, so the
renderer draws every connector itself from the node coordinates. This keeps the
same philosophy as the timelines: the engine handles collision-free placement,
we own all styling.

`build_dot` sets `rankdir` (TB by default) and takes `nodesep` / `ranksep` from
`style-tokens.json` → `tuning` (currently 0.85 / **1.1** — the ranksep was widened
from 0.70 so an edge label has room to sit beside its line rather than on it)
(vertical gap must stay generous — too small and the connector's turn crowds the
arrowhead), and gives each node a fixed `width`/`height` computed from its
wrapped text.

## Node shapes (function, not decoration)

| kind | shape | fill / text |
|---|---|---|
| `step` | rounded rect (`rx=12`) | solid light-gray block `#E9ECEF`, **no border**, ink text |
| `decision` | **rounded hexagon** (angled ends, corners r≈2.5) | white fill, gray border, ink text |
| `terminal` | pill (rx = h/2) | dark-gray `#374151`, white text |
| emphasis (any kind) | solid **deep-red** block, white text, no border |

The decision node is a **rounded hexagon** (a rectangle with angled left/right ends),
not a diamond. A diamond is a poor container for multi-line Chinese — it forces a
wastefully large, pointy shape with the text stranded in the middle; the hexagon's
full-height middle rectangle holds the text cleanly while its angled ends still signal
"decision". Its six vertices are shaved to the skill's r≈2.5 corner. Inset per side is
`radius.decision_hex_inset` (22). (Timeline markers remain circles only — never a
hexagon or diamond there.)

Gray/white variation across steps is aesthetic only — it does not encode party
(甲/乙/丙) or any legal category. Only deep red carries meaning.

## Connectors

- Drawn by the renderer, orthogonal, from tail-bottom-center to head-top-center.
- **Rounded corners are tiny** (`r≈2.5`) — essentially right angles with the
  sharp point shaved off. Do not use large radii.
- **Forks/merges are level**: all edges out of one parent share a single
  horizontal "bus" y just below it; all edges into one child share a bus y just
  above it. So sibling branches turn at the same height, never staggered — even
  when sibling nodes differ in height (graphviz aligns a rank by center, so tops
  can differ; the shared bus hides that).
- Arrowhead is a small filled triangle at the head; every edge points downstream
  (verify each edge's end sits at the head node).
- Edge labels (是/否/合格/不合格): plain text placed beside a vertical segment or just above a horizontal/bus segment — NO background box, so the connector is never masked. Weight 600.

## Aspect ratio

Legal processes are long, so the layout is portrait. To avoid an over-skinny
strip, the renderer targets ~A4 portrait: it spreads branches and tightens rows,
then pads the sides symmetrically (content centered) if still skinnier than
`TARGET_MIN` (0.66). Keep top-down (TB); only switch to LR if the user asks for
a landscape/wide result.

## Logic tidy-up (allowed, within fidelity limits)

A source flowchart's connectors are often incoherent. You MAY reorganize the
graph into one coherent flow **as long as the legal meaning is unchanged** and
every text label is preserved verbatim. Record what you did in
`provenance.tidy_note` and list anything removed/merged in `uncertainties`, e.g.:
dropping a contentless node (a bare "决策" hexagon with no criterion), merging a
duplicate node, or omitting an OCR-noise prefix. Surface these at the human
checkpoint. Never drop a node that carries real information.

## Emphasis

Deep red marks the pivotal node(s) — 1-2 per diagram (e.g. the key holding and
the reasoning that leads to it). AI may choose the emphasis when the source
doesn't dictate one; note it in `emphasis_note` for confirmation.

The renderer routes connectors for BOTH flow directions: **TB** (top→bottom, source
bottom → target top, horizontal buses) and **LR** (left→right, source right → target
left, vertical buses; back-edges via a bottom channel). Set `direction` to `TB`
(default) or `LR`; wide multi-input data-flows read better as `LR`.

## Frozen charting standard (layout tidiness)

These are locked by `flow-std` regression guards — do not loosen them:

1. **Uniform step width.** Every `step` box is drawn at ONE uniform width (the
   widest step's content width). Columns then line up, and the diagram stops
   mixing many box sizes. Terminals (pill) and decisions (hexagon) keep their own
   sizing — they are distinct functional shapes, few in number.
2. **Straight-first connectors — no needless bends.** A connector between two
   (near-)aligned nodes is a **straight vertical**. Bends appear ONLY where they
   carry meaning: a fan-out or fan-in shares a level "bus" (bracket), and a single
   edge into an offset column takes **one small jog near the head**, never a tall
   mid-height S. Every turn keeps the r≈2.5 rounded corner.
3. **Symmetric framing, centered title.** The canvas pads the content's real
   left/right bounds by an EQUAL margin, so the content sits in the true middle and
   the centered title sits exactly over the content center (= canvas center). Never
   pad one side only.

---

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2
