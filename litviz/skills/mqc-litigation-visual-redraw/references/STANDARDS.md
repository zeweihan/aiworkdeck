# mqc-litigation-visual-redraw — consolidated standards (authoritative)

**This file is the single source of truth for the RULES and cross-cutting
decisions.** Where it conflicts with the handoff document or any earlier
note/comment, **this file wins** (later decisions override earlier ones).

**Number ownership (anti-drift):** the *exact pixel/color/font values* are owned by
the specific spec that governs them — `visual-style.md` (palette, type, spacing,
title), `flowchart-spec.md` (flow shapes/connectors), `relationship-spec.md` (tree),
`semantic-map-schema.md` (fields), `assets/style-tokens.json` (the machine values).
Where this file restates a number it is a convenience echo; **on any numeric
conflict, the owning spec / the token file is authoritative.** Change a value there
first, not here.

---

## 0. Core principle (never change)

The **model only emits `semantic-map.json`; the scripts do ALL geometry** (layout,
date scaling, wrapping, collision-free placement, styling, rasterization). Never
hand-write coordinates. Output quality comes from a correct JSON, so the skill
works even on weaker models.

Fidelity outranks beauty: text is **verbatim** (only line-breaks + visual hierarchy
allowed), never reorder events for looks, never invent emphasis silently. Anything
unreadable/uncertain goes in `provenance.uncertainties` and is raised at the
**human checkpoint** before rendering.

### The three pillars

1. **Read → analyze → decompose the source** (the input; make-or-break): governed by
   `references/extraction-guide.md` — classify → **spine-first** → verbatim → strip
   decoration → one emphasis → density triage. A beautiful diagram of the *wrong*
   structure is a failure, so this pillar is enforced by the checkpoint gate (below)
   and the `extraction` regression guards, not left to chance.
2. **Learn other skills' engineering, never their aesthetics** — see the borrowed/
   declined ledger in §9. Adopt schema/lint/CLI/rasterizer discipline; refuse
   model-places-coordinates, multi-colour, legends, and infographic shells.
3. **Deterministic quality floor** — the pipeline (frozen tokens + scripts-do-geometry
   + validate + audit + lint + 136 regression guards, 149 assertions) means a correct JSON always
   renders to standard, regardless of model strength.

---

## 1. Layouts — seven, in three families

| family | layout | when | renderer | graphviz? |
|---|---|---|---|---|
| timeline · numbered | `numbered_point_timeline` | order matters, spacing does NOT (dense / undated events) | render_points | no |
| timeline · dated | `dated_point_timeline` | real time distances matter; long, well-separated points | render_dated | no |
| timeline · gantt | `proportional_gantt` | periods that run/overlap/gap (时效, 保证期间) | render_spans | no |
| flowchart | `graphviz_flow` | steps / decisions / branches / merges | render_flow | **yes** |
| relationship · network | `graphviz_relation` | free-form labeled relationships | render_relation | **yes** |
| relationship · tree | `relation_tree` | top-down hierarchy (主体关系图, 股权/控制) | render_tree | no |
| relationship · table | `comparison_table` | A vs B read across, dimension by dimension (两裁判要旨 / 两方案) | render_compare | no |

---

## 2. Palette (unchanged from the frozen baseline)

Neutral gray ramp + **one** deep red `#991B1B`. **No blue** (slate was rejected —
never reintroduce it). Flat only: no gradients/shadows/3D/texture. Gray/white
variation is aesthetic, not semantic; **only deep red carries meaning** and is used
**1–2 times per diagram** (solid red block + white text; emphasized edge = red line
+ red label).

Key tokens live in `assets/style-tokens.json`.

## 3. Title font — **Song (宋体)** (SUPERSEDES the old "YaHei title" rule)

Chart **titles** use a display Song face; **body/card text stays sans (黑体/雅黑)**.
Title stack (degrades only through well-known Song faces, **never 仿宋/FangSong**):

```
'方正小标宋简体','思源宋体','Source Han Serif SC','Noto Serif CJK SC','华文中宋',serif
```

- 优先 方正小标宋简体 (commercial — **referenced by name only, never bundled**;
  install it locally for the true face) → 思源宋体 (OFL, listed under its 3 OS
  names so it resolves everywhere, and what the sandbox PNG uses) → 华文中宋 (兜底).
- Bold via `font-weight:700` **+ a 0.3 same-colour stroke** (so it stays bold even
  where the rasterizer's Song has no bold face).
- **PNG note**: LibreOffice does not walk the CSS font list for CJK; `render.py`'s
  `_png_safe_svg` rewrites the title to the best-installed Song for the soffice
  copy only. The master SVG keeps 方正小标宋 first.

## 4. Typography

- **CJK line-breaking (禁则)**: a line never begins with a closing mark
  (，。、；：！？）】》」』…) nor ends with an opening mark ((【《「『). Only break
  positions move — text stays verbatim. (`common.wrap`)
- **Title breathing room**: every chart leaves a generous gap (~28px added on top
  of the title band) between the title and the content — the title never sits tight
  against the diagram.
- Title is centered over the **content center** (= canvas center), not merely the
  middle of an off-center canvas. No underline, no credits/dates/marketing.

## 5. Connectors, arrows, corners, labels

- Connector: neutral gray, width 2 (emphasis red, width 3).
- Arrowhead: clean isosceles triangle `M 0 0 L 12 6 L 0 12 Z`, **fixed 10px**
  (`markerUnits=userSpaceOnUse`); the red width-3 edge uses a 14px head. Head
  SIZE stays fixed per class — it never scales continuously with stroke width.
- **The head/line JUNCTION is a ratio, not a number** (`common.arrow_geom`).
  The line ends where the triangle is `arrow.cover` (= 2.0) times the stroke
  width: wide enough to bury the square cap (so the tip stays SHARP) and deep
  enough inside the head (so there is no SEAM). `refX` is derived from that, so
  a heavier line or a bigger head moves the junction automatically. A single
  pinned `refX` is what produced the flat-head defect in v1.0.1.
- **Head-room is derived too** (`common.head_trim`): a connector stops
  `cover x width + arrow.tip_clear` short of the head node, so the arrow TIP —
  not the line's end — keeps a constant ~3.5px gap at every line weight.
- **All right-angle turns get a tiny r≈2.5 rounded corner** (near right-angle) —
  everywhere, every renderer. No large radii.
- **Edge/branch labels never get a background box** — plain text beside/above the
  line (weight 600; emphasis = red bold).
- Cards / step nodes rx=12 (**白描 squares these to r≈2.5** — see visual-style.md;
  bars at rx=0 and terminal pills are exempt); terminals = pill;
  **decision = rounded hexagon** (angled
  ends, corners r≈2.5 — never a diamond, which is a poor container for CJK text);
  **gantt period bars AND the timeline axis bar = right angle (rx=0)** — a
  running period and a time ruler are bars, not cards; the numbered axis line
  likewise takes square ends, not round caps.

## 6. Timeline standards

- **numbered** — equidistant; numbered circle markers (1-2-3); dates optional on
  the card. Spacing carries no argument.
- **dated** — **date-proportional** (honest: equal real time = equal distance).
  Axis is a slightly-thick light-gray bar `#E5E7EB` with **right-angle ends
  (`rx=0`) — a time ruler is a bar, never a pill**; thin ticks `#C6CBD2` split it;
  a small **year (or year.month for short spans, auto)** label sits per unit;
  **no dots** (connector meets the bar edge); precise date in the card. Every event
  needs a real `date` or it errors. For undated/clustered events use the numbered
  form.
- **gantt** — date-proportional period bars, right-angle, one per row; label inside
  the bar (or hugging its left edge if too long); optional `points` (dashed
  verticals). `directional:true` adds a sharp flat-based arrowhead for a period that
  "runs toward" a deadline — optional per bar, off by default.

## 7. relation_tree standard (`tree-std`, regression-guarded)

Self-computed tidy tree (no graphviz): **leaves get equal slots; every parent sits
at the exact midpoint of its children → every fork is symmetric (equal branch
distances), sibling gaps uniform.** **Uniform box height** across all levels;
**one uniform width per level** (tidy columns). Bracket connectors with r≈2.5
corners; **no arrowheads by default** (`arrows:true` to add). Depth shading: root
dark `#374151` → mid gray → leaf light `#EDEFF2`; depth-coding is aesthetic, red is
still the only meaning. Optional per-branch `label` (持股比例…, no box) and per-node
`note`.

## 8. flowchart standard (`flow-std`, regression-guarded)

- **Uniform step width** — every `step` box one width; columns align. Terminals and
  decisions keep their own shape sizing.
- **Straight-first connectors** — aligned nodes → straight vertical; bends only
  where meaningful (fan-out/fan-in share a level bus; a single offset edge takes one
  small jog near the head, never a tall mid-height S).
- **Symmetric framing** — equal left/right margins; centered title over the content
  center.

## 9. Rendering, validation & regression

SVG is the primary editable deliverable; PNG is derived (rasterizer auto-detected;
soffice→PDF→pdftoppm fallback, pinned to an EXACT integer scale — see §5). Three
further editable formats are transcribed FROM the master SVG, so each is the
delivered figure rather than a second layout: **`.drawio`** (draw.io),
**`.pptx`** (PowerPoint / WPS — lawyers who edit diagrams in slides) and
**`.vsdx`** (Visio — and the only one of the ten formats ProcessOn imports that
carries placed shapes; its other nine are outline/mind-map formats that would
flatten dates, routes and arrow direction into a tree). Pipeline gates, in order:

1. **`schema_version`** — every map declares `"schema_version": 1` (const; pins the
   IR contract so a file that validates today keeps rendering identically). An
   unsupported version is rejected.
2. **`validate_map`** — required fields per layout, dangling-edge check, dated
   events need a real `date`; **errors name the offending element id**; unknown
   top-level fields warn. If `jsonschema` is installed, `schemas/semantic-map.schema.json`
   also runs (optional, Archify-style — skipped when the dep is absent).
3. **audit** — the delivery summary AND the extraction-side self-check: elements /
   red count, **emphasis-discipline** flag (>2 reds), and a **CHECKPOINT REQUIRED**
   gate whenever uncertainties exist or the emphasis was AI-chosen. It must actually
   run (a broken audit is a delivery defect).
4. **lint** (`lint.py`) — final-SVG artifact check, **read-only, changes no visuals**:
   non-finite numbers, off-canvas boxes/anchors, arrows drawn as a single diagonal,
   **rejected blue/slate colours** — the "no blue" standard **scoped to 奇川风**, the
   colour master (歸藏风 legitimately uses Klein blue `#002FA7`; its own guard enforces
   that palette instead) — via a blacklist that does
   NOT false-flag our mildly-cool neutral grays, marker `orient` sanity, well-formed
   XML, and `url(#id)` reference integrity. Runs on every render; `--strict` makes a
   warning exit non-zero.

CLI: `python render.py <map> [base] [--strict]` · `python render.py validate <map>`
· `python render.py lint <svg>`.

`tests/run_checks.py` is the regression suite (exit 0 = all pass); `tree-std`, `flow-std`, `typography`,
`delivery`, `schema`, `lint` and the aesthetic guards lock the standards above so
they can't silently regress. Delete `__pycache__` before packaging.

### Borrowed vs. declined (cross-skill technical review)

From studying other diagram skills we **adopted**, without touching any visual
standard: from **Archify** — mandatory `schema_version` + JSON-Schema contract +
id-annotated errors, a render-time visual lint, and CLI validate/lint subcommands;
from **diagram-builder** — a "no blue/slate" palette guard and a marker-orient sanity
check; from **fireworks-tech-graph** — a **CJK-safe rasterizer order** (soffice before
cairosvg, because cairosvg tofu-boxes CJK) plus **XML-validity + `url(#id)` reference
integrity** lint checks. All folded into the read-only lint / pipeline without visual change. We **declined** anything that lets the model place
coordinates (free `pos`/`via`/`labelAt` IR, or "model hand-writes SVG"): that is the
opposite of this skill's golden rule (model = semantics only, scripts = all geometry)
and breaks weaker models. Also declined: swimlane/phase layouts, multi-colour palettes
/ per-layer colour coding, icon libraries, model-side arrow-routing hints
(ports/corridors/route_points/jump-over arcs), the summary-card / legend / theme /
animation / Mermaid "infographic shell", and scope-creep diagram types (UML class/
sequence/state/ER, network topology, mind maps) outside the legal remit. (Marker sizing: our markers already scale correctly — `viewBox`
matches the path and `markerUnits=userSpaceOnUse` scales rather than crops — so the
"markerWidth ≥ viewBox" warning from diagram-builder does not apply and was not
retro-fitted.)

## 9b. Tuning constants and the post-processing net

Numbers that were arrived at by LOOKING at output — graphviz separation, the
歸藏风 title ratio and 天头, the decision-node lift, the side-label bias — live in
`assets/style-tokens.json` under `tuning`, named and explained. Gathering them is
only worth anything if the code reads them, so a guard nudges one and asserts the
figure moves.

The two mode transforms (`to_monochrome`, `to_guizang`) still rewrite generated
SVG with regexes. Making the modes native to the renderers would be cleaner, but
only ~8 of ~64 operations are recolouring; the rest are structural (hexagon→
diamond, band, dot layer, title), so it is a cross-renderer rewrite with no
user-visible gain and real regression risk. What HAS been removed is the part
that is dangerous rather than merely ugly: **a regex that stops matching used to
fail silently**, leaving a wrong figure nobody would catch without looking at
pixels. Every step that must fire goes through `_sub()`, misses are reported by
name, and a guard asserts there are none for any layout.

Two traps that mechanism itself walked into, recorded so the next person does not
repeat them:
- a step that is layout-specific (module groups do not exist in a timeline) must
  declare that, or the report cries wolf and gets ignored;
- that declaration must NOT be derived from the pattern's own marker string. The
  first version tested `'<g data-role="node"' in svg` for both, so renaming the
  marker made the step stop being "required" exactly when it stopped working —
  a guard that goes quiet at the moment it is needed. The LAYOUT decides.

## 10. Rejected — do not revisit

slate/blue palette · notched/hollow/blocky arrows · boxed edge labels · infographic
shell (subtitles/English/legend/insight lines under the title) · arrowhead SIZE
that scales continuously with stroke width (the head/line *junction* is a ratio —
see §5 — but the head stays 10px / 14px by class) · **仿宋 (FangSong) as a title face** · equidistant spacing
with a real time ruler on the axis (a time ruler REQUIRES a proportional axis).

---

## Versioning

Now **v1.0.2**. The CHANGELOG records what each release changed.
Author/footer: 缪奇川律师 · mqc-legal-skills.

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2

## Mode & emphasis defaults (frozen)

- **Default mode is 奇川风** (colour master). 白描 and 歸藏风 engage only when the
  user picks them (checkpoint answer, `visual_mode` in the JSON, or a CLI flag).
- **Red is opt-in only.** The deep-red accent marks whatever the USER names at the
  checkpoint. **If the user skips the question, says "just render it", or does not
  reply, the figure carries NO red at all** — the model must never pick an emphasis
  on its own to fill the gap. In 歸藏风 / 白描 the same user choice is re-expressed
  (solid blue block / black), and the same opt-in rule applies.
- The delivery summary always states the mode used and either the chosen emphasis
  or "emphasis: none".

## Long text / overflow handling (frozen)

Legal titles and party names are long, so overflow is handled by the pipeline, not
by asking the model to shorten anything (that would break the verbatim rule):

- **Doc titles wrap automatically.** `fit_title` in `render.py` measures the title
  against the canvas; if it is wider, it splits into balanced lines, pushes the
  drawing down and grows the canvas. Only line breaks are inserted — never an edited
  or dropped character. A title that already fits is left byte-identical.
- **Node notes reserve their REAL height.** The relation renderer measures the
  deepest wrapped note instead of assuming two lines, so a long note can no longer
  run past the bottom of the canvas.
- **`lint.py` checks rendered text EXTENT, not just the anchor.** A centred title
  can have its anchor comfortably inside the canvas while its glyphs spill off both
  edges; the linter now measures each string (CJK ≈ 1 em, Latin ≈ 0.55 em) against
  the canvas and warns on real overflow.
- Everything that genuinely cannot fit belongs in `provenance`, and the extraction
  guide's rule still stands: **split a dense source into several diagrams** rather
  than cramming one.
