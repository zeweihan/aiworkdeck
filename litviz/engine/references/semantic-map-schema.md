# semantic-map.json schema

The model produces this file; the scripts consume it. Keep every string
**verbatim** from the source. All coordinates are computed by the scripts — the
JSON contains no x/y.

## Top level

```json
{
  "schema_version": 1,          // REQUIRED — pins the IR contract (const 1)
  "diagram_type": "timeline",
  "layout": "numbered_point_timeline | dated_point_timeline | proportional_gantt",
  "title_text": "…",            // neutral chart name; verbatim if source had one
  "visual_mode": "奇川风",        // OPTIONAL — 奇川风 (default) | 白描 (court/print) | 歸藏风 (online/lecture); set from the checkpoint. CLI --baimiao / --guizang override.
  "axis": { … },                // gantt only (see below)
  "axis_unit": "year | month",  // dated_point_timeline only, optional (auto by span)
  "events": [ … ],              // point layouts (numbered / dated)
  "spans":  [ … ],              // gantt layout
  "points": [ … ],              // gantt layout, optional point markers
  "provenance": { … }
}
```

Choose ONE primary array: `events` for the point layouts
(`numbered_point_timeline`, `dated_point_timeline`), `spans` for
`proportional_gantt`. A gantt may additionally carry `points`.

## `numbered_point_timeline` → `events[]`

```json
{
  "id": "1",                 // number; preserve source numbering if present
  "band": "up" | "down",     // above or below the axis (mirror the source)
  "date_text": "2025.03.10", // verbatim; "" if the source had none
  "text": "签订借款合同和保证承诺书",  // verbatim event text
  "emphasis": false          // true on AT MOST the single key event
}
```

- Axis is equidistant; events render left→right in array order.
- `band` mirrors the source's above/below arrangement; if the source is a plain
  line, alternate up/down for legibility and note it in provenance.

## `dated_point_timeline` → `events[]`

Same card design as the numbered form, but the axis is **date-proportional** (a
light-gray bar with an honest year/month ruler), markers are dots (no numbering),
and each event needs a real `date` for positioning.

```json
{
  "id": "1",
  "band": "up" | "down",
  "date": "2013/6/20",       // YYYY/M/D — REQUIRED (drives the true x position)
  "date_text": "2013.06.20", // verbatim date shown in the card ("" if undated)
  "text": "工程竣工验收合格",   // verbatim event text
  "emphasis": false
}
```

- `date` is required and parseable (`YYYY/M/D`); an undated event raises a clear
  error — use `numbered_point_timeline` for undated or tightly-clustered events.
- `axis_unit` (top level, optional): `"year"` or `"month"`; omitted → auto (a
  span ≥ ~3 years reads by year, else by month). The ruler label is drawn from
  `date_text` (verbatim), never invented for an undated event.
- Best for **long, well-separated** chronologies; events only a few days apart
  will collide — that is by design (real time distances are honest).

## `proportional_gantt` → `spans[]`

```json
{
  "id": "B7",
  "label_text": "最保守起算的诉讼时效",   // verbatim
  "from": "2020/10/30",                 // YYYY/M/D, verbatim
  "to":   "2023/10/29",
  "emphasis": true,                     // solid deep-red bar + white label
  "directional": true                   // add a sharp arrowhead at the end
}
```

- `directional: true` draws a sharp triangular arrowhead at `to` — use it for a
  period that "runs toward" a deadline (a limitation period, a running clock).
  Never for a closed, bounded period. No curved/rounded arrows, ever.
- Rows render top→bottom in array order.

### `axis` (gantt only)

```json
"axis": { "mode": "proportional_year", "start": "2015/01/01", "end": "2025/12/31" }
```

`start`/`end` bound the proportional scale; pick them to cover all dates with a
little padding. Year gridlines are drawn automatically.

### `points[]` (gantt only, optional)

Dashed vertical markers for discrete events on a gantt (转让公告, 提起本诉):

```json
{
  "id": "E3",
  "label_text": "原告提起本诉",
  "date": "2025/02/20",
  "label_level": 0,         // 0/1/2 vertical slot to avoid label collisions
  "label_side": "left",     // left | right | center — which way the label reads
  "emphasis": true          // red dashed line + red label
}
```

When two point markers are close in time, give them different `label_level`
and opposite `label_side` so labels don't overlap.

## `graphviz_flow` → `nodes[]` + `edges[]`

```json
"nodes": [
  {"id":"n1","kind":"terminal","title":"【起点】…"},
  {"id":"n3","kind":"decision","title":"【决策】甲是否催款？"},
  {"id":"n11","kind":"step","title":"【法院审理】查明事实",
   "lines":["借款合同、保证合同有效；","…","认定丙的抗辩理由不成立。"],
   "emphasis":true}
],
"edges": [
  {"from":"n3","to":"n4","label":"是"},
  {"from":"n4","to":"n5"}
]
```

- `kind`: `step` (rounded rect) | `decision` (rounded hexagon) | `terminal` (pill).
- `title`: main verbatim label (bold). `lines`: optional verbatim detail lines
  (smaller). Text wraps automatically; the node grows to fit.
- `emphasis`: solid deep-red block + white text; 1-2 per diagram. **Only renders
  when the map records that the USER chose it** — see `checkpoint` below.
- `direction`: `"TB"` (default) or `"LR"` at the map top level.
- `edges[].label`: optional branch label (是/否/合格…).
- The renderer computes positions (via graphviz) and routes connectors itself,
  so nodes/edges carry no coordinates. See `references/flowchart-spec.md`.

## `graphviz_relation` → `nodes[]` + `edges[]`

```json
"engine": "dot",              // dot | neato | fdp | twopi | circo (per topology)
"direction": "LR",            // for dot: LR or TB
"nodes": [
  {"id":"jia","title":"债权人（甲）","note":"民间借贷关系（本金20万）"},
  {"id":"bing","title":"保证人（丙）","note":"保证合同关系（连带责任）"}
],
"edges": [
  {"from":"jia","to":"yi","label":""},                       // unlabeled if source was
  {"from":"yi","to":"bing","label":"追偿权（代偿后）"},
  {"from":"jia","to":"bing","label":"连带清偿责任","route":"top","emphasis":true}
]
```

- `nodes[].title`: verbatim party name. `nodes[].note`: optional verbatim
  annotation shown below the node (an identity/obligation label, NOT a relation).
- `edges[].label`: the relationship verbatim (omit / empty if the source edge had
  none — don't invent). `route`: `straight` | `top` | `bottom` (skip over an
  intervening node). `emphasis`: deep-red key relationship (1-2 per diagram).
- `engine`/`direction`: pick to match the source's structure; layout is
  free-form. See `references/relationship-spec.md`.

## `relation_tree` → `nodes[]` + `edges[]`

A top-down hierarchy (主体关系图 / 股权·控制层级). No graphviz — the renderer lays
it out itself (tidy tree). No x/y in the JSON.

```json
"arrows": false,              // optional; true adds arrowheads to structural edges
"nodes": [
  {"id":"kong","title":"实际控制人 王某"},
  {"id":"jia","title":"甲控股集团有限公司"},
  {"id":"ding","title":"丁建设有限公司","note":"本案被告 / 被执行主体","emphasis":true}
],
"edges": [
  {"from":"kong","to":"jia","label":"持股80%"},
  {"from":"jia","to":"ding","label":"持股70%","emphasis":true}
]
```

- `nodes[].title`: verbatim entity name. `nodes[].note`: optional verbatim label
  shown below the node. `nodes[].emphasis`: the pivotal entity → solid deep-red
  block, white text (1-2 per diagram). `nodes[].level`: optional explicit depth
  (normally computed from the edges).
- `edges[].from`/`to`: parent → child (direction = hierarchy). `edges[].label`:
  optional short relation on the branch (持股比例 …), verbatim, no box.
  `edges[].emphasis`: deep-red branch (1-2 per diagram).
- `arrows` (top level, default `false`): structural hierarchy lines carry NO
  arrowheads; set `true` only if the source's arrows are meaningful.
- Layout is fixed by the standard (see `references/relationship-spec.md`): equal
  leaf slots, every parent centered on its children (symmetric forks), uniform box
  height, uniform width per level, bracket connectors with r≈2.5 corners, depth
  shading. The model supplies only nodes/edges; all geometry is deterministic.

## `comparison_table` → `columns[]` + `rows[]`

The A-vs-B variant of the relationship family: two positions/options compared row
by row across shared dimensions (两裁判要旨 / 两诉讼方案 / 两罪名). Use it instead of a
wide `relation_tree` when the comparison has exactly two sides and several dimensions.

- `columns` (**exactly 2**): `{ "id", "title", "emphasis"? }`. An `emphasis` column
  gets the deep-red header + a light-red (`#FBEBEB`) cell tint (≤1 column).
- `rows`: `{ "dimension": "焦点二·责任性质", "cells": { "<col id>": "…", "<col id>": "…" } }`.
  Every row must have a cell for **both** column ids. The left gutter shows the
  dimension label; the two cells sit side by side for horizontal reading.
- Deterministic layout: the model supplies only headers + cells; the script computes
  column x-positions, per-row height (tallest cell), wrapping, and colours. Keep each
  cell to a conclusion + operative terms — never a paragraph of reasoning.

## `provenance{}`

`source_count` (optional): how many items you counted in the source — int or
`{"nodes":N,"edges":M}`. The audit cross-checks it against the extracted counts and
flags a mismatch (dropped/invented item), gating the checkpoint.


Records what you did, for the audit summary and the human checkpoint:

```json
{
  "text_policy": "verbatim",
  "numbering": "preserved_from_source(1-7) | added_for_readability | none",
  "emphasis_note": "why this element is the red one; note if it's a suggestion",
  "color_note": "e.g. source blue converted to gray per no-blue rule",
  "uncertainties": [
    "any label you couldn't read confidently",
    "any date-order vs source-order conflict",
    "anything that could change the legal meaning"
  ]
}
```

`uncertainties` is what you surface at the CHECKPOINT before rendering.

---

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2


---

## `checkpoint` — what the user actually decided

```jsonc
"checkpoint": {
  "emphasis_source": "user",   // "user" = the user named what the red marks
                               // "none" (or the block absent) = they did not
  "confirmed": true            // the structure was confirmed at the checkpoint
}
```

**Why this is a field and not a note.** The rule "red is opt-in; if the user
skips, use no red" used to live only in prose, which meant the model writing the
map was also the only thing enforcing it — and the one signal the audit looked at
(`provenance.emphasis_note`) was written by that same model. A rule policed by
asking the rule-breaker to confess is not a rule.

So it moved into the deterministic layer, where the rest of this skill's
guarantees live. `common.strip_unearned_emphasis()` runs before ANY rendering: if
`emphasis_source` is not `"user"`, every `emphasis: true` is cleared and a note is
printed saying so. The figure still renders — nobody is blocked — it simply
renders without a mark nobody authorised, in every output format at once.

Set `emphasis_source: "user"` **only** after the user has named the element the
red marks. Never set it to pre-empt the question, and note that
`provenance.emphasis_note` does NOT authorise anything: it is a record that the
model made a guess, which is the situation this rule exists to catch.
