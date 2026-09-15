# Rendering, environment & workflow

## The pipeline

```
semantic-map.json ──▶ render.py ──▶ final.svg (primary, editable)
                                └──▶ final.png (derived preview/filing)
                                └──▶ audit summary (printed)
```

Run from `scripts/`:

```bash
python render.py <semantic-map.json> final
```

`render.py` selects the layout from the map's `layout` field
(`numbered_point_timeline` → `render_points.py`, `dated_point_timeline` →
`render_dated.py`, `proportional_gantt` → `render_spans.py`, `graphviz_flow` →
`render_flow.py`, `graphviz_relation` → `render_relation.py`, `relation_tree` →
`render_tree.py`), writes the SVG, rasterizes the PNG, and prints the audit.

## SVG → PNG: rasterizer detection & fallback

There is no single guaranteed rasterizer across environments, so `render.py`
tries them in order and uses the first available:

1. `rsvg-convert`  2. `resvg`  3. `inkscape`
4. `soffice` (LibreOffice) → PDF → `pdftoppm` → PNG  5. `cairosvg` (python, last resort)

**CJK caveat (why the order):** this skill's text is all Chinese. `soffice`+Noto
renders CJK reliably, so it is preferred **before** `cairosvg` — `cairosvg`'s Cairo
font API does not do fontconfig fallback and can emit □ tofu boxes for CJK, so it
sits last and is used only when nothing else exists. The soffice path is also the
common minimal setup (LibreOffice present, no dedicated SVG rasterizer) and is what
the sandbox uses. Control resolution with the `dpi` argument (default 150).

## Fonts (do this check)

CJK glyphs need a CJK font installed, or the PNG shows blank boxes (tofu):

```bash
fc-list | grep -i "CJK SC"      # expect Noto Sans CJK SC or similar
```

The SVG uses a font stack (`Noto Sans CJK SC`, `Noto Sans SC`, `Microsoft
YaHei`, `PingFang SC`, sans-serif) so it also renders on macOS/Windows viewers.
If no CJK font is present and can't be installed, warn the user that the PNG
will be blank-boxed even though the SVG is correct.

## Verify the output

If you can view images, open `final.png` and check text isn't clipped, the red
element reads as the single focus, and nothing overlaps. If you can't view
images in-session, rely on the audit + a geometry check, and tell the user to
eyeball the PNG. Useful spot-check for a gantt:

```bash
python audit.py <semantic-map.json>
```

## The human checkpoint (why it exists)

Between extraction and rendering, confirm the transcription and
`uncertainties` with the user. This is the main defense against a
wrong-but-pretty diagram, and it matters most on **weaker models**, whose
transcription of a dense legal image is less reliable. The scripts guarantee the
picture is clean; only the human can confirm it's *correct*. Skip only if the
user explicitly says "just render it".

## Output conventions

- File names are English: `semantic-map.json`, `final.svg`, `final.png`.
- Deliver SVG (editable primary) + PNG (preview/filing) + a one-line audit
  summary in the reply (elements preserved, emphasis used, uncertainties).
  Never render the audit summary onto the image.
- Default to a neutral title; never add credits/dates/marketing.

## Pre-flight validation, lint & CLI

`render.py` runs `common.validate_map(m)` before rendering: it checks
`schema_version` (const 1), the required fields per layout, that every edge
references an existing node id, and that a `dated_point_timeline` event carries a
real `date` — raising a clear `Error: semantic map has problems: ...` (non-zero
exit) with the **offending element's id** in the message, instead of failing deep
inside a renderer. Unknown top-level fields warn. If `jsonschema` is installed,
`schemas/semantic-map.schema.json` is validated too (optional; skipped otherwise).

After the SVG is written, a read-only **lint** (`lint.py`) inspects the final
artifact for non-finite numbers, off-canvas boxes, and single-diagonal arrows, and
prints warnings; it never changes the drawing. `--strict` turns a lint warning into
a non-zero exit.

CLI: `python render.py <map> [base] [--strict]` renders; `python render.py validate
<map>` validates only; `python render.py lint <svg>` lints an existing SVG.

## All three diagram types are frozen

Timelines (point + gantt), flowcharts, and relationship diagrams all share one
spine: the model emits a semantic map, a layout engine computes geometry, and the
same style tokens + PNG fallback + human checkpoint apply. Adding a future
variant means a sibling `render_*.py` module plus a `layout` value, without
touching the shared layers.

Note: `graphviz_flow` and `graphviz_relation` require `dot` (graphviz) on PATH for
positioning. The point timelines (`numbered_point_timeline`, `dated_point_timeline`),
the gantt (`proportional_gantt`) and the hierarchical `relation_tree` need **no**
graphviz — the tree computes its own tidy layout. `render.py` only invokes graphviz
for `graphviz_flow` and `graphviz_relation` maps.

---

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2


## Editable hand-offs (v1.0.2)

A figure is delivered with the master `.svg`, a `.png`, and three editable
formats, all transcribed from the master so none of them can drift from it:

| file | opens in | note |
|---|---|---|
| `.drawio` / `.drawio.svg` | draw.io / diagrams.net | themed to match the mode |
| `.pptx` | PowerPoint, WPS, Keynote | native shapes; a module is ONE object, its caption inside it; straight runs are native connectors, bent routes are traced point-for-point |
| `.vsdx` | **ProcessOn**, Visio, WPS, Edraw | inches, origin bottom-left; a caption that mixes font sizes is split per size so no importer can flatten it |

**Why `.vsdx` for ProcessOn.** ProcessOn imports xmind · mmap · txt · km · mm ·
md · opml · pos · vsdx · csv. Eight of those carry an outline, not a drawing —
a litigation figure pushed through one loses its dates, its routing and its arrow
direction, which is worse than exporting nothing. `pos` is ProcessOn's own format
and would round-trip best, but it is undocumented and private: it cannot be
verified from here, and it would break silently when they change it. `vsdx` is a
published standard, opens in four tools rather than one, and LibreOffice can read
it back — so the output is rendered and measured, not hoped over.

Self-checks that look at RENDERED output rather than at the files we wrote:
`scripts/verify_pptx.py fig.svg fig.pptx|fig.vsdx` (text position and size) and
`scripts/audit_edges.py fig.svg fig.png` (edge weight symmetry).


## The checkpoint is generated, not composed

`scripts/checkpoint.py map.json [--suggest=n]` prints the three questions. They
are generated for the same reason the geometry is computed: their consequences
are already enforced deterministically (`strip_unearned_emphasis`, the `-draft`
naming), so a question a hurried model might drop, shorten or garble would be the
one soft link in an otherwise hard chain.

Three wording rules the guards hold:

1. **A mode is described by its look and its use — never by whose style it is.**
   The mode names are the author's; the reader is another lawyer choosing a look
   for their own case file.
2. **The layout is shown with its reason and its real siblings**, as a reading to
   correct rather than a free menu. The data decides the form.
3. **Every question states what happens if it is not answered**, because the
   common case is that it will not be.
