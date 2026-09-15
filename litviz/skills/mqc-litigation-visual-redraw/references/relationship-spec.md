# Relationship-diagram spec (frozen)

For party/entity diagrams: nodes are parties (债权人/债务人/保证人, companies,
shareholders…), edges are labeled directed **relationships** (借贷, 担保, 追偿,
连带清偿, 股权, 资金流, 控制…). Rendered by `render_relation.py`. Requires `dot`
(graphviz) on PATH.

## Layout is free-form — do NOT template it

A relationship diagram has high degrees of freedom. Let the source's real
structure decide the shape: a chain/row, a central hub with spokes, or a loose
network. Pick the graphviz engine to match and set it in `engine`:

| structure | engine | note |
|---|---|---|
| chain / row / layered | `dot` | ranks left→right (LR) or top→bottom |
| central hub + spokes | `twopi` or `circo` | radial |
| loose network | `neato` or `fdp` | force-directed |

Never impose a fixed template (e.g. "three-column evidence↔view"). That column
layout is just one possible result of a `dot` LR graph, not a required form.

## Positioning: graphviz for coordinates ONLY

As with flowcharts, use graphviz for **node positions** and draw everything else
ourselves — cards, labeled relationship lines, per-node notes, and skip-routes.
Do not rely on graphviz's edge routes.

## Nodes

- Card: rounded rect (`rx=12`), light-gray fill, gray border, bold centered
  `title` (verbatim). Same restrained palette as the rest of the skill.
- `note` (optional): a short verbatim annotation rendered as small gray text
  **below** the node — no connector line. Notes describe a party (its identity /
  obligation), they are not relationships. In the source, an annotation that
  merely labels a party (e.g. an up-arrow tag "还款义务（本金+利息）") is a `note`,
  NOT an edge.

## Edges (relationships)

- Directed, drawn between node borders, arrow at the head; keep the source's
  direction.
- `label`: the relationship, verbatim, as plain text beside/above the line (NO masking box). Omit if the
  source edge had no label (don't invent one).
- `route`: `straight` (default, adjacent nodes) | `top` | `bottom`. Use `top`/
  `bottom` when a straight line would cross an intervening node (e.g. a
  first-party-to-guarantor 连带清偿责任 arc over the debtor). Skip-routes are
  orthogonal with **tiny rounded corners** (`r≈2.5`), same as flowcharts.
- `emphasis`: the pivotal relationship — solid **deep-red** thick line + red
  label. 1-2 per diagram; AI may choose it if the source doesn't dictate one.

## Spacing / occlusion

Keep node separation generous enough that a straight edge's label fits in the gap
between the two nodes without overlapping either card. If a label is wider than
the gap, widen `ranksep` (the renderer already uses a comfortable value) rather
than letting text collide with a node.

## Gray/white and red

Gray/white variation is aesthetic only — it does not encode party or category.
Only deep red carries meaning (the key relationship). No blue.

## Hierarchical tree (`relation_tree`) — frozen charting standard

For a top-down hierarchy (主体关系图 / 股权·控制层级, org-chart-shaped) use the
`relation_tree` layout (`render_tree.py`). Its geometry is a **fixed standard**,
locked by `tree-std` regression guards — do not loosen it:

1. **Tidy-tree layout, self-computed (no graphviz).** Leaves occupy **equal
   horizontal slots**; every parent is placed at the **exact midpoint of its
   children**. Consequence: **every fork is symmetric — the distance from a parent
   to each of its children is basically equal — and sibling gaps are uniform.**
2. **Uniform box height** across all levels (no level looks thicker than another),
   and **one uniform box width per level** so the levels read as tidy columns.
3. **Bracket connectors** (parent → shared bus → each child) with the skill's tiny
   **r≈2.5 rounded corners** (near right-angle). Structural edges carry **no
   arrowheads** by default — a hierarchy line is not a directed relationship; set
   `"arrows": true` only when the source's arrows are meaningful.
4. **Depth shading**: root dark (`#374151`, white text) → middle mid-gray → leaves
   light card (`#EDEFF2`, hairline border). This encodes DEPTH for legibility only;
   it is not legal semantics. Deep red remains the single carrier of meaning
   (a pivotal entity = solid red block + white text; 1-2 per diagram).
5. Optional per-branch `label` (持股比例 …, verbatim, no box) sits beside the child
   drop; optional per-node `note` sits below the node.

Use `relation_tree` when the source is a hierarchy; use `graphviz_relation` for a
free-form network of labeled relationships.

## Node layout — frozen standard (`graphviz_relation`)

For a relationship network, the renderer **places the nodes itself**
(`_layout_nodes`, deterministic, **no graphviz needed**) before routing. Fixing
the layout first is what keeps the connectors orderly — you cannot route cleanly
on top of a scattered layout. The method (locked by the `relation · layout` guard):

1. **Find the hub.** The node with the **most relationships** (highest degree) is
   the party everything connects to; in a dense graph its four sides all become
   line endpoints, so it belongs in the **centre**.
2. **Place on an aligned, near-rectangular grid.** Nodes sit on a grid of ≈3 per
   row (3, 3+1, 3+2, 2+2 …). **Every row shares one vertical position** and one
   column pitch, so rows read as tidy bands and the whole reads as a horizontal
   rectangle. A **dominant** hub (degree clearly above the rest, in a graph of ≥5)
   is dropped into the central slot; otherwise nodes keep their **source order**
   row-major, so a simple/linear diagram (甲→乙→丙) is never scrambled by degree.
3. **Moderate, controlled spacing.** Column and (especially) **row** gaps are kept
   controlled and uniform — enough that notes under a node and labels beside a
   vertical line have room, not so much that the diagram sprawls.
4. Notes under a node MAY cross a vertical connector (unavoidable, acceptable);
   an edge's explanation LABEL never does (see the label standard below).
5. Arrowheads point from `from` to `to`, landing exactly on the target's border.

## Connector routing — frozen standard (`graphviz_relation`)

graphviz places the NODES; the renderer routes every connector itself
(`_route_edges` in `render_relation.py`). Routing is a **fixed standard**, locked
by the `relation · routes avoid nodes…` guard against a high-density stress
fixture (`edge_relation_dense.json`). The rules — general, not fitted to one
diagram — are:

1. **Orthogonal only.** Every segment is horizontal or vertical; corners carry the
   skill's tiny r≈2.5 rounding. No diagonal, no curve, no spline.
2. **Clean route first; land on the border.** An edge leaves a node's border and
   enters the target from the side **facing the source**, ending **exactly on that
   border line** (the arrowhead sits on the edge, never inside/occluded). It tries,
   in order, a straight run → a direct L/Z toward the near side → (only if that
   would cross a node) a **tight detour around the nearer side** → a bottom trunk.
   A connector never passes over a box that is not its own endpoint, and it never
   takes a sweeping detour when a direct route is clear.
3. **Border-centre by default; minimal on-border offset only for parallels.** A
   lone edge attaches at the **dead centre** of its border side. When several edges
   share one side, extras take a **small offset that stays strictly on the border
   line** (clamped inside the corners) — never pushed off the module edge.
4. **Lanes are separated.** Parallel mid-trunks that would share a line are offset
   into distinct lanes (pitch ≈16px), so **no two edges ever run on top of each
   other**. Verified by geometry: parallel-overlap = 0.
5. **Straight stub into every arrowhead.** The last run into a node (and the first
   run out of it) is a straight segment — no corner sits next to the arrowhead, so
   heads never look bent or distorted.

## Edge-label placement — frozen standard

Labels are wrapped and placed AFTER routing (`place_edge_labels`), and the rules
are locked by the same guard (label-vs-node, label-vs-label, and
label-crossed-by-a-line all = 0). The label of an edge:

1. **Wraps; the line is never lengthened to fit text.** Above-labels wrap to
   ~176px, side-labels wrap NARROWER (~150px) so they sit compactly on one side.
2. **Sits wholly on ONE side of its line and never crosses it.** A label on a
   horizontal run goes ENTIRELY above that run; a label on a vertical run goes
   ENTIRELY to one side — the side **away from the node cluster** — and is
   left-aligned so it reads outward from the line.
3. **Keeps a real gap from the line**, and **red / emphasis labels sit further
   out** (more vertical breathing room) than plain gray ones.
4. **Nudges only AWAY from its line**, by a bounded amount, to dodge nodes, other
   labels and every connector segment — it never flies far and never moves onto
   the line. In the rare fully-crowded case it takes the least-conflicting spot.
5. The canvas reserves **horizontal room on both sides** for outward side-labels,
   so a label beside a detour line never runs off-canvas.

These routing and label rules are **skill-wide standards**, not per-diagram
tweaks: they are enforced on every `graphviz_relation` render by the regression
guard, using a deliberately dense, fully-anonymized stress case.

---

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2
