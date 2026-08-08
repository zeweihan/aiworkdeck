#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Deterministic self-check + audit summary. Catches the failure modes that
matter legally (missing/edited text) and visually (overflow, red overuse)
BEFORE the image is delivered. Import and call report(map_dict), or run:

    python audit.py <semantic-map.json>
"""
import sys
from common import FS, text_w, parse_date, load_map

FS_LABEL = FS["edge_label"]   # gantt period-bar label size (matches render_spans)


def _count_detail(m):
    return {"events": len(m.get("events", [])), "spans": len(m.get("spans", [])),
            "points": len(m.get("points", [])), "nodes": len(m.get("nodes", [])),
            "edges": len(m.get("edges", [])),
            "columns": len(m.get("columns", [])), "rows": len(m.get("rows", []))}


def _count_check(m):
    """Order-of-magnitude sanity: show the extracted counts so the human can
    compare them against the source, and — if the model recorded how many items
    it counted in the source (provenance.source_count) — actively flag a mismatch
    (a dropped or invented node/edge). source_count may be an int (primary items)
    or a dict like {"nodes": 8, "edges": 10} / {"events": 6}."""
    cd = _count_detail(m)
    elements = cd["events"] + cd["spans"] + cd["points"] + cd["nodes"]
    parts = [f"{k}:{v}" for k, v in cd.items() if v]
    lines = [f"-- count check (compare with the source) --",
             f"  extracted: {', '.join(parts) or '0'}  (elements={elements}, edges={cd['edges']})"]
    mismatch = False
    sc = (m.get("provenance", {}) or {}).get("source_count")
    if isinstance(sc, dict):
        for k, want in sc.items():
            got = cd.get(k, cd.get("events", 0) if k in ("elements", "items") else 0)
            if k in ("elements", "items"):
                got = elements
            if int(want) != got:
                mismatch = True
                lines.append(f"  ! MISMATCH {k}: source_count={want} but extracted {got} — a node/edge was dropped or invented; recount against the source")
    elif isinstance(sc, int):
        if sc != elements:
            mismatch = True
            lines.append(f"  ! MISMATCH: source has ~{sc} items but extracted {elements} — recount against the source")
    else:
        lines.append("  (tip: record provenance.source_count — how many items you counted in the source — to auto-catch drops)")
    return lines, mismatch


def _count_nodes(m):
    return len(m.get("events", [])) + len(m.get("spans", [])) + len(m.get("points", [])) + len(m.get("nodes", []))


def _red_ratio(m):
    total = _count_nodes(m)
    red = sum(1 for e in m.get("events", []) if e.get("emphasis")) \
        + sum(1 for s in m.get("spans", []) if s.get("emphasis")) \
        + sum(1 for p in m.get("points", []) if p.get("emphasis")) \
        + sum(1 for n in m.get("nodes", []) if n.get("emphasis")) \
        + sum(1 for e in m.get("edges", []) if e.get("emphasis"))
    return red, total


def _extraction_notes(m):
    """Advisory discipline notes for the extraction stage (see
    references/extraction-guide.md). These do not block rendering; they tell the
    model/user what MUST be confirmed at the human checkpoint before a 'final'."""
    prov = m.get("provenance", {})
    red, total = _red_ratio(m)
    notes = []
    if total and red > 2:
        notes.append(f"emphasis discipline: {red} deep-red elements — the rule is ≤2; demote some to gray")
    # The map's own checkpoint record is authoritative over provenance guesswork:
    # once it says the USER named the emphasis, repeating "AI-chosen" is simply
    # wrong, and two mechanisms contradicting each other teaches the reader to
    # trust neither.
    src = str((m.get("checkpoint") or {}).get("emphasis_source", "")).strip().lower()
    if src == "model":
        notes.append("emphasis is AI-CHOSEN — say so on delivery and let the user move it")
    elif src != "user" and prov.get("emphasis_note"):
        notes.append("emphasis was AI-chosen — confirm it at the checkpoint (emphasis_note recorded)")
    if not prov.get("text_policy"):
        notes.append("provenance.text_policy not recorded — state 'verbatim' (or how text was handled)")
    if prov.get("uncertainties"):
        if (m.get("checkpoint") or {}).get("confirmed") is not True:
            notes.append(f"{len(prov['uncertainties'])} uncertainty(ies) logged — "
                         f"resolve at the checkpoint, do not guess")
    return notes


def report(m):
    lines = ["--- audit summary ---"]
    red, total = _red_ratio(m)
    lines.append(f"elements: {total} | emphasized(red): {red}"
                 + ("  <<red overused (1-2 per diagram): consider demoting some to gray"
                    if total and red > 2 else ""))

    # verbatim / numbering provenance echo
    prov = m.get("provenance", {})
    if prov.get("text_policy"):
        lines.append(f"text_policy: {prov['text_policy']}")
    if "numbering" in prov:
        lines.append(f"numbering: {prov['numbering']}")

    # geometry sanity for gantt: which labels won't fit inside their bar
    if m.get("spans"):
        try:
            a0, a1 = parse_date(m["axis"]["start"]), parse_date(m["axis"]["end"])
            days = (a1 - a0).days
            # width unknown here without layout constants; report duration-based hint
            hug = []
            for s in m["spans"]:
                dur = (parse_date(s["to"]) - parse_date(s["from"])).days
                # heuristic: <120 days is a short bar likely to need left-hug labels
                if dur < 120 and text_w(s["label_text"], FS_LABEL) > 60:
                    hug.append(s["id"])
            if hug:
                lines.append(f"short-bar labels likely hugging left edge: {', '.join(hug)} (expected, per rule)")
        except Exception:
            pass

    # order-of-magnitude count sanity (compare with the source)
    count_lines, count_mismatch = _count_check(m)
    lines += count_lines

    # arrow-direction review (flow / relation): reversing a from/to renders
    # silently wrong, so surface the direction for the human checkpoint.
    dir_review = _direction_review(m)
    if dir_review:
        lines += dir_review

    # extraction discipline + checkpoint gate
    notes = _extraction_notes(m)
    unc = prov.get("uncertainties", [])
    confirmed = (m.get("checkpoint") or {}).get("confirmed") is True
    checkpoint = (not confirmed) and (bool(unc) or bool(prov.get("emphasis_note"))
                                      or count_mismatch)
    for n in notes:
        lines.append(f"! {n}")
    if unc and not confirmed:
        lines.append("uncertainties to confirm with user:")
        for u in unc:
            lines.append(f"  - {u}")
    if checkpoint:
        lines.append(">> CHECKPOINT REQUIRED: confirm the read/emphasis with the user "
                     "before delivering a final (see extraction-guide.md).")
    print("\n".join(lines))
    return {"elements": total, "red": red, "uncertainties": unc,
            "notes": notes, "checkpoint_required": checkpoint,
            "direction": _direction_data(m), "counts": _count_detail(m),
            "count_mismatch": count_mismatch}


def _direction_data(m):
    nodes = m.get("nodes") or []
    edges = m.get("edges") or []
    if not (nodes and edges):
        return None
    ids = [n["id"] for n in nodes]
    indeg = {i: 0 for i in ids}
    outdeg = {i: 0 for i in ids}
    for e in edges:
        if e.get("from") in outdeg:
            outdeg[e["from"]] += 1
        if e.get("to") in indeg:
            indeg[e["to"]] += 1
    sources = [i for i in ids if indeg[i] == 0 and outdeg[i] > 0]
    sinks = [i for i in ids if outdeg[i] == 0 and indeg[i] > 0]
    isolated = [i for i in ids if indeg[i] == 0 and outdeg[i] == 0]
    return {"sources": sources, "sinks": sinks, "isolated": isolated}


def _direction_review(m):
    d = _direction_data(m)
    if not d:
        return []
    title = {n["id"]: n.get("title", n["id"]) for n in m["nodes"]}
    out = ["-- arrow-direction review (confirm at checkpoint) --"]
    out.append("  entry points (no incoming): " + (", ".join(title[i] for i in d["sources"]) or "(none!)"))
    out.append("  end points (no outgoing): " + (", ".join(title[i] for i in d["sinks"]) or "(none!)"))
    if d["isolated"]:
        out.append("  ! isolated (no edges): " + ", ".join(title[i] for i in d["isolated"]))
    if not d["sources"]:
        out.append("  ! no entry point — every node has an incoming edge; a reversed arrow or a cycle is likely")
    if not d["sinks"]:
        out.append("  ! no end point — every node has an outgoing edge; a reversed arrow or a cycle is likely")
    out.append("  edges (A -> B): " + "; ".join(f"{title[e['from']]}→{title[e['to']]}" for e in m["edges"]))
    return out



def _quiet_broken_pipe():
    """`… | head` closes the pipe early; without this the script ends on a
    traceback, which looks like a crash to anyone reading the terminal."""
    import signal
    try:
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (AttributeError, ValueError):
        pass

if __name__ == "__main__":
    _quiet_broken_pipe()
    report(load_map(sys.argv[1]))
