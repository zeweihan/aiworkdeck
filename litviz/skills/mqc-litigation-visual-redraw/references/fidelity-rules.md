# Legal fidelity rules

The output is destined for a complaint, hearing, or arbitration. Faithfulness
outranks beauty. When the two conflict, preserve meaning and flag the trade-off.

## Text is verbatim

- Transcribe every character exactly: dates, event text, evidence numbers,
  party names. The only permitted transformations are **line breaks** and
  **visual hierarchy** (size/weight/position).
- Do **not** normalize dates ("2023年5月左右" stays; don't turn it into
  "2023.05"). Do not paraphrase ("合同签订" must not become "双方签署合同").
  Do not merge or split events.
- Never convert text to vector paths — keep it editable and selectable.

## Ordering

- Render in the source's semantic order. Priority when inferring order:
  (1) explicit dates, (2) arrows/connectors in the source, (3) left→right /
  top→bottom reading order, (4) phase grouping.
- **Never reorder events to make the layout prettier.**
- If the source's visual order conflicts with date order, do NOT silently pick
  one — record it in `uncertainties` and resolve it at the checkpoint. This can
  change the legal meaning.
- Do not infer a precise order among vague dates ("约2024年5月"). Keep them as
  given; if position is genuinely unknown, mark it.

## Numbering

- If the source numbers its events, **keep that numbering exactly**; do not
  re-number.
- If it doesn't, you MAY add numbering for readability — but record
  `numbering: added_for_readability` in provenance, and never let numbers change
  meaning or dominate the text.

## Emphasis

- Deep red marks the element that carries the argument. If the source already
  emphasizes something (red/bold/prominent position), preserve that intent.
- If the source has **no** emphasis, you may propose one (e.g. the ruling, or
  the crux limitation period) — but state in `emphasis_note` that it is a
  suggestion, and confirm it at the checkpoint. Do not invent emphasis silently.
- Keep emphasis singular where possible. Multiple reds are acceptable only when
  they tell one story (e.g. a limitation-period bar plus the filing-date line
  that together show "本诉 filed after 诉讼时效 expired").

## Ask-first vs. draw-and-note

**Pause and ask** (could change legal meaning):
- a label you can't read that affects meaning,
- date order vs. source order conflict,
- whether something even is an event,
- what the key emphasis should be.

**Draw, but note the choice** (visual only):
- horizontal vs. vertical, equidistant vs. proportional (per layout rules),
- card vs. bar styling details,
- auto-numbering style,
- gray/white fill selection.

## Scope of generation (the 10% path)

The primary job is redrawing an existing diagram. If instead the user supplies
raw facts (dates + events) with no diagram, you may generate the timeline from
them — same schema, same rules — but you are now authoring, so be extra careful
to use only the user's stated facts and not to imply order/emphasis they didn't
give.

---

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2
