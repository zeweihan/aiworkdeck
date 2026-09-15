# Extraction guide — reading, analyzing & decomposing the source

This skill's whole promise is **ugly source in → high-grade legal diagram out**.
The rendering pipeline guarantees the *out* (deterministic layout + frozen visual
standards). This guide governs the *in*: turning a messy, hand-drawn, screenshotted
or over-decorated source into a **correct** `semantic-map.json`. Extraction is where
the skill lives or dies — a beautiful diagram of the wrong structure is a failure.

Read this before your first extraction in a session. It never changes any visual
standard; it only governs how faithfully you read.

---

## The one rule above the six steps

**Fidelity outranks tidiness, always.** Never improve, normalize, complete, or
"fix" the source. If the source says `2023年5月左右`, that is the text. If two boxes
have the same label, keep both. If you cannot read a character, it goes in
`provenance.uncertainties` — never a confident guess. When in doubt, extract *less*
and raise it at the checkpoint.

---

## Step 1 — Classify the source → pick the layout

Read the whole image once before touching anything. Decide what KIND of diagram it
is; that fixes the layout. Signals → layout:

| What you see in the source | Layout |
|---|---|
| A horizontal/vertical line with dated events hung off it; a fact chronology; events **close in time or undated** | `numbered_point_timeline` |
| Dated events where the **time gaps matter and are large** (limitation periods, long performance) | `dated_point_timeline` |
| **Periods** that run, overlap, or leave gaps (诉讼时效, 保证期间, 主债权, 履行期间) — bars, not points | `proportional_gantt` |
| Steps, decisions (drawn as diamonds in the source), branches, merges; a procedure/runbook (合同审查, 诉讼程序) | `graphviz_flow` |
| Parties/entities joined by **labeled relationships** in a free network (债权人↔债务人↔保证人, 资金/担保关系) | `graphviz_relation` |
| A **top-down hierarchy** (实际控制人→控股→子公司, 股权/控制层级, org-chart shape) | `relation_tree` |

Tie-breakers: "the spacing between things is time" → a proportional layout (dated /
gantt). "Only the order matters" → numbered. A Mermaid/text paste is read for
**structure only** — lay it out fresh in the matching layout; never transcribe its
styling.

### Tree vs. network (a common, dangerous mis-pick)

Use **`relation_tree`** ONLY for a **strict hierarchy**: one root, **every other node
has exactly one parent**, no cross-links, no cycles (org chart / 股权穿透 / 控制层级).
The moment **any** node needs a second parent, or there is a cross-link, a cycle, or
it's a hierarchy-plus-side-chain **mixed** graph → use **`graphviz_relation`** (the
general network layout; a tree is just a special case it can also draw). When unsure,
choose `graphviz_relation` — it can never mis-represent structure the way a forced
tree does. `render_tree` **enforces** this: a non-tree input is refused with a message
pointing to `graphviz_relation`, so a wrong pick fails loudly instead of drawing a
clean-looking diagram of the wrong structure.

*Bad (real case):* a developer-dispute graph that is mostly a hierarchy but has a
government-approval side-chain and cryptic placeholder nodes — forcing it into a tree
either drops the cross-link or invents a parent. Use `graphviz_relation`, and confirm
every edge at the checkpoint.

### Which timeline? (decide in order — first match wins)

Don't pick the timeline form by feel; run this ladder:

1. **Any event lacks a real, parseable date** (only a year, "约"/"上旬", or no date) →
   **`numbered_point_timeline`**. The dated form requires a real `date` per event and
   would otherwise force an estimated axis position and a logged uncertainty. The
   equidistant form sidesteps this — order without spurious precision. (Dates still
   show verbatim in the cards.)
2. **Every event has a precise date AND the time GAPS carry legal meaning** (whether
   本诉 falls outside 诉讼时效, length of delay, interval between two acts) →
   **`dated_point_timeline`** — let distance = time do the arguing.
3. **Events are tightly clustered** (several within days / the same month) →
   **`numbered_point_timeline`** — proportional would jam them together; equidistant
   reads cleanly.
4. **They are periods, not instants** (时效 / 保证期间 / 履行期间 — start+end, may
   overlap) → **`proportional_gantt`**.
5. **Otherwise / undecided** → **`numbered_point_timeline`** (the safe default —
   hardest to get wrong).

In one line: **`dated_point_timeline` is the conditional优选 (precise dates + gaps
that matter + not clustered); everything else falls back to the equidistant numbered
form.** The renderer enforces rule 1 — a `dated_point_timeline` with an unparseable
event date errors out, pointing you to the numbered form.

*Good:* three nodes give only a year (2005-2006年 / 2013年 / 2015年) → numbered form
(rule 1), dates kept verbatim in the cards.
*Good:* six precise dates spanning 11 uneven years, the多年 gap is the point → dated.
*Bad:* forcing a year-only chronology into the dated form and then "estimating"
month/day to place the dots.

*Good:* a photo shows a left-to-right line with 7 dated notes above/below → timeline;
dates span 11 years unevenly → `dated_point_timeline`.
*Bad:* forcing a shareholding hierarchy into `graphviz_relation` because it also has
"relationships" — a clean parent→child tree is `relation_tree`.

---

## Step 2 — Find the SPINE first, then hang the branches (anti-collapse)

This is the single most important habit. Do **not** read the source
corner-to-corner and dump everything into a flat list — that is how extraction
collapses (missed nodes, wrong level, decoration treated as content). Instead, find
the backbone, then attach detail to it.

The spine per layout:
- **timeline** → the axis and its ordered events (the dots on the line). Everything
  else is a note attached to one event.
- **flow** → the *happy path* from start terminal to end terminal. Branches,
  exceptions and side-steps attach to a main-path node.
- **relation network** → the core parties; relationships are edges between them.
- **tree** → the root, then each level; every node has exactly one parent.

Method: (1) mark the spine elements first; (2) for each spine element, collect the
notes/branches physically nearest to it; (3) only then decide what is a real
node/event vs. an annotation.

*Good (a dense sketchnote):* identify the 6 topics sitting ON the timeline axis as
the events; treat the clouds of sub-notes around each as candidate notes, not new
events.
*Bad:* turning every scribble and arrow into its own node, producing a 40-node
tangle that mirrors the mess instead of clarifying it.

---

### Arrow direction (flow / relation) — get from/to right

Edges are `{from, to}`; a reversed pair renders a **silently wrong** diagram (the
pipeline draws whatever direction you wrote and cannot know the intended one). So:

- Read each arrow **in the source** and write `from`=tail, `to`=head. Don't infer
  direction from reading order.
- After writing, use the audit's **arrow-direction review**: it lists the **entry
  points** (no incoming) and **end points** (no outgoing) and every `A→B` edge. Check
  them against reality — the real inputs must appear as entry points and the real
  outputs as end points. A reversed arrow flips a node out of that list; `no entry
  point` / `isolated` warnings mean a reversal or a cycle. Resolve at the checkpoint.

---

## Step 3 — Transcribe verbatim; quarantine the unreadable

Copy text **character for character** into `text` / `label` / `date_text`: dates,
party letters (甲/乙/丙), evidence numbers, amounts, punctuation. Then:

- Anything you cannot read with confidence → `provenance.uncertainties` with a short
  note ("『祐子』 handwritten, name uncertain"), and leave the field as your best
  legible fragment marked with `（?）` — never a fabricated value.
- Do not silently drop text you *can* read but find inconvenient. If it won't fit,
  that is a Step-6 density decision, recorded — not a silent deletion.
- Undated events on a dated layout: do not invent a date. `dated_point_timeline`
  errors on a missing `date`; either supply the real date or use the numbered form.

*Good:* `"text": "甲邮寄催款函，乙签收，丙拒收"`, uncertainty logged for a smudged char.
*Bad:* normalizing `2025.3.11` to `2025-03-11`, or "merging" two near-identical
events into one because it reads cleaner.

---

## Step 4 — Strip decoration; keep meaning

The source may carry drawings that are **illustration, not structure**: pie charts,
sine/《长鞭效应》waveforms, clip-art icons, hand-drawn flourishes, brand logos,
grid backgrounds, legends, decorative underlines. This skill **re-draws the
structure and does not reproduce illustrations**. Extract the *fact* the drawing
conveys as text if it matters (e.g. a pie labelled "89亿CO₂ 15%" → an event/note
"排放占比15%"), and drop the drawing itself. Record in `provenance` that decorative
elements were not reproduced.

*Good:* a flowchart photo with a company logo in the corner → ignore the logo,
extract the boxes.
*Bad:* trying to recreate a pie chart or a waveform inside the legal diagram — that
is out of scope and off-standard.

---

## Step 5 — Choose the emphasis (the one deep red)

Deep red is the only colour that carries meaning, used **1–2 times per diagram**.
Pick the pivotal element: the holding/判决, the limitation bar, the被执行主体, the
key relationship. If the source already marks something (a tick, a highlight, a star)
honour it. If nothing is marked, you may choose — and you MUST record the choice in
`provenance.emphasis_note` for the user to confirm at the checkpoint. Never scatter
red; never emphasise more than two.

*Good:* `emphasis_note: "AI建议：以『判决：丙承担连带责任』为深红重点，待确认"`.
*Bad:* colouring every "important" node red, so nothing stands out.

---

## Step 6 — Triage density; never cram

Legal sources are often over-full. The point layouts carry short cards; a node
crammed with six sub-bullets renders ugly and unreadable. When the source is dense:
- Put the **spine + each element's essential line** in the diagram.
- Push secondary sub-notes to `provenance` (and offer, at the checkpoint, to add
  them as a companion note or a second diagram) — do not stuff them into one card.
- If the density is really *periods* or *hierarchy* hiding inside a "timeline",
  switch layout (gantt / tree) rather than forcing it.

Record every omission-for-space in `provenance` so nothing is *silently* dropped.

*Good:* a 6-topic forum sketchnote → a clean 6-point spine, sub-notes listed in
provenance with an offer to expand.
*Bad:* one timeline card containing 8 wrapped lines that overflow and collide.

---

## Special source: hand-drawn / photographed / screenshotted

The riskiest input. Extra discipline:
- Read slowly; handwriting and 繁体 are easy to misread. Prefer "unsure → uncertainty"
  over a confident wrong read.
- Reconstruct the spine from the physical layout (which dots sit on the line, which
  boxes connect), not from reading order.
- The **checkpoint is mandatory and hard** here: return your read of the structure +
  the uncertainty list and get confirmation BEFORE rendering a "final". Deliver the
  first pass explicitly as a draft-pending-confirmation.

## Special source: text-only (judgment, narrative, statement of facts)

The **highest-frequency legal input** and a different discipline from an image:
there is no visual spine to trace and no pre-made labels to copy — you build the
structure out of prose. Extra rules:

**1. Separate FACT from ARGUMENT.** Legal prose mixes three registers; only the
first becomes diagram content:
- **Facts** — "法院查明…", "…于2010年7月3日签订《7.3协议书》", dated acts, amounts,
  who-did-what. → these are events / nodes / edges.
- **Claims / positions** — "原告主张…", "被告辩称…". → generally NOT nodes; if a
  claim must appear, mark it as a claim, don't state it as fact.
- **Reasoning / evaluation / statute** — "本院认为…", "依照《公司法》第X条…". → NOT
  nodes. The **holding/裁判结果** may become the single emphasis; the reasoning that
  leads to it is argument, not structure. Never turn every "本院认为" clause into a box.

**2. Build the spine from the narrative, not reading order.**
- Chronology hides in the prose — scan for every date and the act attached to it,
  in time order, even if the paragraphs jump around. → timeline.
- Party structure hides in the verbs — "A与B签订", "C为D担保", "E持股F" → the party
  network / hierarchy. → relation / tree.
- Procedure hides in sequence words — "首先/经…后/若…则/最终" → steps & decisions.
  → flow.

**3. Fidelity shifts: preserve the operative tokens, compose the phrasing.** Unlike
an image (where you copy the existing label verbatim), prose has no label — you must
**condense a sentence into a short event/node text**. Therefore:
- **Verbatim, always**: dates, amounts, 书名号-titled documents (《7.3协议书》), party
  names (incl. 脱敏 傅**), evidence numbers, land-parcel ids, operative legal terms
  (连带清偿责任, 挂靠, 撤销). Copy these exactly.
- **Composed, allowed**: the surrounding event wording, condensed to a clean line.
  This is summarizing, not the verbatim-label rule — so **record it** in
  `provenance.text_policy: "condensed_from_prose (operative terms verbatim)"`.
- Still never invent a date/amount/party the text doesn't state; unknowns →
  `uncertainties`.

**4. Layout via the same ladder.** Dates precise & gaps meaningful → dated timeline;
year-only or clustered → numbered; parties+relationships → relation; hierarchy →
tree; procedure → flow. A single judgment often yields **more than one** diagram
(see "Multi-diagram" below) — a fact timeline *and* a party-relationship diagram.

**5. Checkpoint is mandatory.** Composing from prose is higher-risk than copying a
label. Return your event/party list + what you treated as fact vs. argument, and
confirm before a final.

*Good:* from "2010年7月3日，甲公司与傅**等四人签订《7.3协议书》，转让项目开发权及
股权" → event `date_text:"2010年7月3日"`, `text:"甲公司与傅**等签订《7.3协议书》，
转让项目开发权及股权"` (title & names verbatim, sentence condensed).
*Bad:* turning "本院认为四被告未尽勤勉义务，应承担赔偿责任" into a node — that is the
court's reasoning; only the holding (as emphasis) and the underlying dated facts
belong on the diagram.



## Multi-diagram: when one source needs several diagrams

One rich source (a judgment, a big table) often does not fit one diagram. Split when
the material mixes **different structural kinds**: a **fact chronology** (→ timeline),
**periods** that run/overlap (→ gantt), and a **party/shareholding structure**
(→ relation / tree) are three different stories — forcing them into one diagram
muddies all three. Prefer a **small set of companion diagrams**, each clean and
single-purpose, over one crowded catch-all. Decide the set at Step 1, and offer it at
the checkpoint ("this reads as a fact timeline + a shareholding tree — shall I produce
both?"). Never invent structure to fill a second diagram; only split what is really
there.

## Special source: someone else's clean digital diagram (Mermaid / SVG / a tool export)

Read it for topology and meaning only. Re-classify into our layout, re-lay-out from
scratch, drop their colours/legend/theme, and apply our standards. Do not preserve
their visual choices — that would import a foreign aesthetic.

---

## Anti-patterns (how extraction collapses — avoid all)

1. **Flat dump**: reading corner-to-corner, every mark becomes a node → tangle.
   Fix: spine-first (Step 2).
2. **Silent normalization**: "cleaning up" dates/labels → altered legal meaning.
   Fix: verbatim (Step 3).
3. **Decoration as data**: recreating pies/waveforms/icons. Fix: strip (Step 4).
4. **Emphasis inflation**: many reds. Fix: ≤2, recorded (Step 5).
5. **Cramming**: stuffing every sub-note into cards. Fix: triage (Step 6).
6. **Confident guessing** on unreadable text. Fix: uncertainties + checkpoint.
7. **Layout forcing**: bending a hierarchy/periods into the wrong layout. Fix:
   re-classify (Step 1).

---

## Count check — record how many items the source has

Before writing the JSON, **count the source**: how many events / nodes / edges are
really there. Record it in `provenance.source_count` — an int (primary items) or a
dict like `{"nodes": 8, "edges": 10}` / `{"events": 6}`. The audit compares your
extracted counts to this number and **flags a mismatch** (a dropped or invented
item) — the one machine check that can catch "I missed a node". If they differ,
recount against the source before delivering.

## Self-check before writing / rendering the JSON

Ask, and fix any "no":
- Did I pick the layout from the source's real structure, not habit?
- Did I build from the spine, and is every element attached to it?
- Is every field verbatim, with everything unreadable in `uncertainties`?
- Did I strip decoration and record that I did?
- Did I record `provenance.source_count` and does the audit's count check match?
- Is red used on ≤2 elements, with `emphasis_note` recorded when I chose it?
- For a dense source, are omissions-for-space in `provenance` (not silent)?
- For a hand-drawn/messy source, am I going to the checkpoint before "final"?

The renderer's `audit` re-checks the mechanical parts of this (element count, red
count, whether uncertainties exist) and prints them — but the audit cannot know what
you failed to read. This discipline is your responsibility, not the script's.

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2
