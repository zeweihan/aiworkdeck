# Litigation Visualization

Turn case materials into figures fit to go straight into court filings. The method in
one sentence: **absorb first, then redraw** - read the source faithfully, then draw it
afresh; **change not a single word and no legal meaning - change only the visual
presentation**.

## Iron rule: you extract, the script draws

**NEVER hand-write SVG coordinates, and never "eyeball" node placement.** Language
models place boxes and route lines badly (overlaps, overflow, crossings), so this
pipeline divides the work:

- **Your job**: read the source -> transcribe verbatim -> produce one
  `semantic-map.json`. The judgment calls (which single point decides the case, event
  order, card above or below the axis) live in that JSON.
- **The script's job**: ALL geometry - column widths, date scaling, text wrapping,
  collision stacking, line routing, colors.

Under this division, output quality depends on whether the JSON is right - not on how
clever you are with pixels.

## Five steps

**1. Read, decompose, understand.** This step decides success or failure. The source may
be a judgment, evidence exhibits, a photographed hand sketch, a screenshot, someone
else's overdecorated chart, or a plain narrative of the facts.

- Find the **backbone** first (the axis of a timeline, the main path of a flow, the core
  parties of a relationship map), then hang the branches on it. Do not copy
  left-to-right blindly.
- **Transcribe verbatim**: dates, names, exhibit numbers, amounts - not one character
  changed. "around May 2023" stays "around May 2023" - do not normalize it to
  "2023-05". Do not paraphrase, do not merge, do not reorder for looks, do not invent
  dates.
- **Strip the decoration**: pie charts, waveforms, icons, ornament - all discarded. You
  are redrawing the **structure**, not imitating an illustration.
- Anything you cannot read with confidence goes into `provenance.uncertainties` -
  **never guess**.
- When there is a lot of material, read the folder with `extract_file_text` /
  `search_project_files` before starting.

**2. Write the semantic map (JSON).** If the fields are unfamiliar, first call
`litigation_reference('schema')`; if unsure how to decompose the source, first call
`litigation_reference('extraction')`. These documents are long and are not preloaded -
**read them on demand**.

"`semantic-map.json`" is the name of that JSON, not a file you have to create. **It is
passed INLINE as a tool argument all the way through** - it is the first argument of both
`litigation_checkpoint` and `litigation_render`. **Do NOT save it with `write_file` and do
NOT read it back with `read_file`**: the round trip achieves nothing except losing track of
it in the next step (on a real run `read_file` came back "File does not exist"). Once the
render succeeds the engine stores the map next to the figure as `<name>.map.json` - that is
when it belongs on disk.

**3. A confirmation round is MANDATORY before rendering.** Call
`litigation_checkpoint` (same JSON as the argument), present **the question block above the
separator** to the user **verbatim** (everything below the separator is execution guidance
for you - never relay it), then stop and wait for the reply.

**Call this tool once per round.** The questions are generated deterministically; calling
it again before the user has answered returns the same text and only burns steps.

Do not compose those three questions yourself. They are generated deterministically by
the script - their consequences (no unauthorized crimson accent; an unconfirmed figure
is named `-draft`) are enforced by the script anyway, and if the asking itself depended
on your diligence, it would be the only soft link in the chain.

When the answers come back, fill them into the map:

```jsonc
"checkpoint": { "confirmed": true, "emphasis_source": "user" }
```

`emphasis_source` takes three values: `"user"` (the user named the spot - at most two),
`"model"` (the user said "you decide" or did not reply: you mark the one spot you judge
decisive, **and you MUST state in the delivery note which spot you marked and why** -
that is your legal judgment, not the user's, and it must not slip through unremarked),
`"none"` (the user asked for no accent). If the field is missing or holds anything else,
the engine paints no red at all.

**4. Render.** Once the answers are in and the `checkpoint` fields are filled, **you MUST
call `litigation_render` once**, passing the same map JSON, the figure name, and the
projectId. This is the step that actually puts the figure in the project - updating the map
without calling it leaves the user with nothing (this happened on a real run; the user had
to ask "where is the figure?"). Once it succeeds, do not call it a second time.
By default one run delivers all output formats. Visual modes: `奇川风`
("Qichuan", the default - serif titles, grayscale, a single crimson accent; suitable for
court submissions, client delivery, and internal case work), `歸藏风` ("Guicang" -
Klein blue, sans-serif; for outreach and teaching materials), `白描` ("Baimiao" - pure
black and white; for printing, photocopying, case-file exhibits). Pass the mode values
verbatim - they are engine identifiers.

**5. Deliver.** State clearly: which layout and mode were used, where the crimson accent
sits (or "none"), and what remains uncertain. **These remarks go in the reply only -
NEVER drawn onto the figure** - any explanatory text on the figure will travel with it
into the court file.

When the user wants the figure inside the document being drafted, insert the **`.png`**
with `doc_insert_image`. It accepts bitmaps only and **rejects `.svg`** - passing the
svg will fail. The `.drawio` is the source file for further editing and is what the app
opens automatically after a render (draw.io is embedded); the `.svg` is the master for
print and for insertion.

## Choosing a layout

**Three timeline types - decide by what the spacing should express:**

- `numbered_point_timeline` - only the order matters; spacing carries no argument (a
  dense factual chronology, or events without usable dates). Evenly spaced axis,
  numbered dot markers, cards alternating above and below. **The safe default.**
- `dated_point_timeline` - date-proportional axis: the distance between two events
  faithfully reflects the time span. For long, well-separated timelines (limitation
  periods, long-running performance). **Every event MUST have a real, parseable date or
  the render errors out** - one missing date means fall back to the numbered type.
- `proportional_gantt` - periods that run, overlap, and leave gaps (limitation periods /
  guarantee periods / the principal claim / performance periods). Bar length and overlap
  **are themselves the legal argument** (e.g. whether the claim falls outside the
  limitation period).

Decision order: any event lacking a precise date, or events bunched together -> numbered
type. Real time-distance carries legal meaning -> dated type for points, Gantt for
periods.

**The other four:**

- `graphviz_flow` - processes / procedure / decision branches (procedural posture, claim
  elements, attack-and-defense paths). See `litigation_reference('flowchart')`.
- `graphviz_relation` - free-form party networks (creditor/debtor/guarantor, money
  flows, control relationships). **Do not force a stock template** (no three-column, no
  radial) - let the source's real structure decide the layout.
- `relation_tree` - top-down hierarchy (ultimate controller -> holding company ->
  subsidiaries; ownership tiers). Symmetric forks, parent centered over children.
  Hierarchies use this; free networks use the one above.
- `comparison_table` - two-column A/B comparison.

For choosing between the two relation layouts, see
`litigation_reference('relationship')`.

## Red lines

| Never | Instead |
|---|---|
| Hand-write SVG coordinates or eyeball node placement | Produce the JSON; leave geometry to the script |
| Use blue, gray-blue, or any second accent color | Grayscale base + the one crimson `#991B1B` (at most two spots) |
| Use diamonds for decision nodes | Use rounded hexagons (long labels do not fit in diamonds) |
| Stuff argument, judicial reasoning, or "the court held" passages into nodes | Nodes carry facts and conclusions only; reasoning is not a node |
| Tweak frozen values (colors, corner radii, font sizes, spacing) for looks | Do not. If they truly must change, change the spec files in `litviz/mqc-litigation-visual-redraw/` first |
| Reorder events, merge or drop entries, or invent dates for looks | Verbatim, in time order; anything uncertain goes into `provenance.uncertainties` |
| Invent new figure types, add legends, icons, or themes | These seven layouts and nothing else |
| Draw the audit summary, counsel's name, dates, or slogans onto the figure | The title carries a neutral figure name only; everything else goes in the reply |

## Dependency degradation

Only `graphviz_flow` (flowcharts) requires graphviz to be installed on the machine. The
other six layouts have zero native dependencies. If a flowchart render reports graphviz
missing, tell the user honestly that this machine lacks the component and offer an
alternative (e.g. express the same content as a relation map or a timeline) - **never
silently substitute a different layout**.

---

**One last time, because these are the rules most often ignored**: do not hand-write SVG,
do not alter one word of the source, do not skip `litigation_checkpoint` before rendering,
do not shuttle the semantic map through `write_file`/`read_file`, and after the user
confirms **always call `litigation_render`** - without that step there is no figure.
