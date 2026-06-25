/* SPDX-License-Identifier: MIT
 *
 * ZetaOffice spike — OFFICE WORKER thread (#39 Phase 0).
 *
 * This file is loaded INTO the LibreOffice WASM em-pthread worker via
 * Module.uno_scripts. Here `Module.zetajs` resolves to the zetajs UNO bridge
 * (on the MAIN thread it resolves to the thread *port* instead — see index.html).
 *
 * Patterns below are taken verbatim from allotropia/zetajs examples
 * (simple-examples + web-office). The four #39 acceptance probes are wired as
 * message handlers driven by buttons on the main thread.
 *
 * The deliverable of this spike is the VERDICT on the four probes, run on a real
 * device — not a green checkmark here. Lines marked VERIFY need a real run to
 * confirm against the current zetajs beta API.
 */
'use strict';

// zetajs environment (global for easier debugging in the worker dev-tools).
let zetajs, css;
let context, desktop, xModel, ctrl;
let anchorSeq = 0; // names hidden bookmarks used as stable location anchors (§0.2)
let docSeq = 0;    // names the MEMFS temp file for each loaded user document

function post(cmd, data) {
  zetajs.mainPort.postMessage(Object.assign({ cmd }, data || {}));
}
function log(msg) { post('log', { msg: String(msg) }); }
function errStr(e) {
  try { return zetajs.getAnyType(zetajs.catchUnoException(e)) + ' ' + (e && e.message || ''); }
  catch { return String(e && e.message || e); }
}
// Build a UNO PropertyValue the zetajs way (struct ctor takes a values object;
// unset members default). Used for loadComponentFromURL/storeToURL filter args.
function mkProp(name, value) { return new css.beans.PropertyValue({ Name: name, Value: value }); }

// §0.2 anchors: a bookmark spanning a text range is a STABLE location handle that
// moves with edits — the model-native replacement for fragile integer offsets.
const ANCHOR_PREFIX = '__ai_anchor_';
function anchorBookmark(range) {
  const name = ANCHOR_PREFIX + (++anchorSeq);
  const bm = xModel.createInstance('com.sun.star.text.Bookmark');
  bm.setName(name);
  xModel.getText().insertTextContent(range, bm, true); // bAbsorb: bookmark spans the range
  return name;
}
function anchorRange(name) {
  const bms = xModel.getBookmarks();
  if (!bms.hasByName(name)) return null;
  return bms.getByName(name).getAnchor(); // XTextRange
}

// ---- boot: open a fresh Writer doc seeded with Chinese + English ----------
function bootDoc() {
  context = zetajs.getUnoComponentContext();
  desktop = css.frame.Desktop.create(context);
  xModel = desktop.loadComponentFromURL('private:factory/swriter', '_default', 0, []);
  ctrl = xModel.getCurrentController();
  try { ctrl.getFrame().getContainerWindow().FullScreen = true; } catch {}

  // Seed REAL paragraphs (PARAGRAPH_BREAK), not '\n' line breaks within one
  // paragraph — so paragraph-indexed commands (get_paragraph/modify_paragraph/
  // get_outline) are meaningfully testable.
  const xText = xModel.getText();
  const cur = xText.createTextCursor();
  const lines = [
    'AI Workdeck × LibreOffice WASM 原型 / prototype.',
    '请在此用系统输入法输入中文，观察候选与上屏 / Type Chinese here with your IME.',
    'Search target: LibreOffice — used by the redline probe.'
  ];
  for (let i = 0; i < lines.length; i++) {
    xText.insertString(cur, lines[i], false);
    if (i < lines.length - 1) xText.insertControlCharacter(cur, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
  }

  installKeyHandler();
  post('ui_ready');
  log('文档就绪 swriter / doc ready');
}

// ---- probe 1: 中文 IME diagnostics --------------------------------------
// XKeyHandler reports physical key events. The OPEN QUESTION (#39) is whether
// IME *composition* (candidate selection -> commit) reaches the canvas at all,
// or is swallowed by Qt/emscripten. The main thread also logs DOM
// compositionstart/update/end on the canvas; compare the two streams.
function installKeyHandler() {
  const handler = zetajs.unoObject([css.awt.XKeyHandler], {
    keyPressed(e) { post('key', { phase: 'pressed', code: e.KeyCode, ch: e.KeyChar }); return false; },
    keyReleased(e) { post('key', { phase: 'released', code: e.KeyCode, ch: e.KeyChar }); return false; }
  });
  ctrl.addKeyHandler(handler);  // VERIFY: addKeyHandler (NOT addKeyListener)
  log('XKeyHandler 已装 / installed — 打中文看 key 日志（IME 合成可能绕过 XKeyHandler，这正是要验证的）');
}

// ---- probe 2: selection via UNO (model-native, NO text offset) -----------
function testSelection() {
  try {
    const sel = ctrl.getSelection();   // XSelectionSupplier -> usually an XIndexAccess of text ranges
    const out = [];
    if (sel && typeof sel.getCount === 'function') {
      for (let i = 0; i < sel.getCount(); i++) out.push(sel.getByIndex(i).getString());
    } else if (sel && typeof sel.getString === 'function') {
      out.push(sel.getString());
    }
    log('选区 / selection [' + out.length + ']: ' + JSON.stringify(out));
  } catch (e) { log('selection ERROR: ' + errStr(e)); }
}

// ---- probe 3: tracked change (redline) via model-native search -----------
// This is the RFC v2 thesis in code: replace text found by XSearchable
// (createSearchDescriptor + findFirst/findNext) — NOT by plain-text offset —
// with RecordChanges ON, so every edit lands as a tracked change.
function testRedline() {
  try {
    xModel.setPropertyValue('RecordChanges', true);  // RFC: revisions should default ON
    log('RecordChanges = ' + xModel.getPropertyValue('RecordChanges'));
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString('LibreOffice');
    let hit = xModel.findFirst(sd), n = 0;
    while (hit !== null && n < 100) {
      hit.setString('LibreOffice〔AI 修订 / redline〕');
      hit = xModel.findNext(hit, sd);
      n++;
    }
    log('redline: 经 findFirst/findNext 替换 ' + n + ' 处 / replaced ' + n
      + ' match(es). 看 canvas 是否出现修订标记 / check canvas for tracked-change marks.');
  } catch (e) { log('redline ERROR: ' + errStr(e)); }
}

// ---- probe 4: performance — generate N pages and time it -----------------
function testPerf(pages) {
  try {
    const t0 = performance.now();
    const xText = xModel.getText();
    const cur = xText.createTextCursor();
    for (let p = 0; p < pages; p++) {
      for (let l = 0; l < 28; l++) {
        xText.insertString(cur,
          '第' + (p + 1) + '页 第' + (l + 1) + '行 合同条款示例 / contract clause sample line.\n', false);
      }
      try { cur.setPropertyValue('BreakType', css.style.BreakType.PAGE_AFTER); } catch {}  // VERIFY enum
    }
    const ms = Math.round(performance.now() - t0);
    log('perf/generate: ' + pages + ' 页耗时 ' + ms + ' ms（仅写入；渲染/滚动流畅度需肉眼）');
  } catch (e) { log('perf ERROR: ' + errStr(e)); }
}

// ---- probe 5: insert CJK via UNO (NO IME) --------------------------------
// Separates two questions that "can't type Chinese" conflates:
//   (a) can LibreOffice WASM store + RENDER Chinese?  (this probe)
//   (b) can you TYPE Chinese via the system IME?      (Qt5-WASM input path)
// Qt-for-WebAssembly (Qt5) has little/no IME support upstream, so (b) likely
// fails. If THIS probe makes 中文 appear on the canvas, then the migration's
// IME answer is a custom JS-composition -> UNO-insert bridge (we control UNO),
// not Qt's input. If Chinese shows as boxes/tofu, it's a deeper font problem.
function testInsertText(text) {
  try {
    const t = text || '中文渲染测试 中華人民共和國 ABC 123';
    const xText = xModel.getText();
    let vc = null;
    try { vc = ctrl.getViewCursor(); } catch {}
    if (vc) {
      // NOT setString(): that REPLACES the cursor's range and leaves the inserted
      // text SELECTED, so the next insert overwrites it (reported bug). Use
      // insertString(range, str, bAbsorb=false): inserts at the cursor and leaves
      // the cursor collapsed AFTER the text, so consecutive inserts append and
      // nothing stays selected. collapseToEnd() first clears any prior selection
      // (e.g. from a click-drag) so we append rather than overwrite.
      try { vc.collapseToEnd(); } catch (e) {}
      xText.insertString(vc, t, false);
      try { vc.collapseToEnd(); } catch (e) {}
      log('inserttext: 经 UNO insertString 在光标处插入「' + t + '」(追加、不选中)。');
    } else {
      xText.insertString(xText.getEnd(), '\n' + t, false);
      log('inserttext: 经 UNO XText.insertString 追加「' + t + '」(无 ViewCursor 回退)。');
    }
  } catch (e) { log('inserttext ERROR: ' + errStr(e)); }
}

// ==========================================================================
// Editor executor command contract (Epic #43 task ④, worker side).
// Implements the SAME editor-agnostic actions that useWpsBridge.executeCommand
// dispatches, via UNO — so frontend/src/composables/useLibreOfficeBridge.js can
// drive LibreOffice with the backend's existing commands. Each handler returns a
// plain result object; the dispatcher posts {cmd:'result', reqId, result} back.
// [verified] handlers use the Phase 0-proven primitives; [todo] are stubs.
// ==========================================================================
const EXEC = {
  // [verified] insert at the view cursor (append, not select) — see testInsertText.
  insert_at_cursor(p) {
    const xText = xModel.getText();
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    xText.insertString(vc, String(p.text || ''), false);
    vc.collapseToEnd();
    return { success: true, inserted: String(p.text || '') };
  },
  // [verified] replace selection if any, else insert at cursor.
  replace_selection(p) {
    const xText = xModel.getText();
    const vc = ctrl.getViewCursor();
    const t = String(p.text || '');
    // bAbsorb=true replaces the cursor's spanned text; for a collapsed cursor it inserts.
    xText.insertString(vc, t, vc.getString().length > 0);
    vc.collapseToEnd();
    return { success: true, text: t };
  },
  // [verified] model-native search + redline (RFC §0.2: no integer offsets).
  find_replace(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(String(p.findText || ''));
    const all = p.replaceAll !== false;
    let hit = xModel.findFirst(sd), n = 0;
    while (hit !== null) {
      hit.setString(String(p.replaceText || ''));
      n++;
      if (!all) break;
      hit = xModel.findNext(hit, sd);
    }
    return { success: true, replaced: n, recordChanges: true };
  },
  // [verified] current selection text (anchor, not integer offset).
  get_selection() {
    const sel = ctrl.getSelection();
    let text = '';
    try {
      if (sel && typeof sel.getByIndex === 'function' && sel.getCount() > 0) text = sel.getByIndex(0).getString();
      else if (sel && typeof sel.getString === 'function') text = sel.getString();
    } catch (e) {}
    return { success: true, text: text, hasSelection: text.length > 0 };
  },
  // [verified] locate matches via XSearchable; return count + texts (anchors held
  // worker-side in the real impl — NOT integer offsets exposed to the model).
  // [verified] locate matches and return STABLE ANCHORS (bookmark ids), not
  // integer offsets (§0.2). Downstream set_selection/replace_at_position take
  // these anchorIds. Collect ranges first, then bookmark, so anchoring doesn't
  // perturb the findNext iteration.
  find_text_locations(p) {
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(String(p.keyword || ''));
    try { sd.setPropertyValue('SearchCaseSensitive', !!p.matchCase); } catch (e) {}
    const ranges = [];
    let hit = xModel.findFirst(sd);
    while (hit !== null && ranges.length < 500) { ranges.push(hit); hit = xModel.findNext(hit, sd); }
    const matches = ranges.map(function (r) {
      let anchorId = null;
      try { anchorId = anchorBookmark(r); } catch (e) {}
      return { anchorId: anchorId, text: r.getString() };
    });
    return { success: true, count: matches.length, matches: matches };
  },
  // [verified-extend] replace the Nth (0-based) match under RecordChanges.
  replace_nth_match(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(String(p.findText || ''));
    const idx = Number(p.matchIndex) || 0;
    let hit = xModel.findFirst(sd), i = 0;
    while (hit !== null) {
      if (i === idx) { hit.setString(String(p.replaceText || '')); return { success: true, replacedIndex: idx }; }
      hit = xModel.findNext(hit, sd); i++;
    }
    return { success: false, message: 'match index out of range: ' + idx + ' (found ' + i + ')' };
  },
  // [verified-extend] delete the Nth match (replace with empty) under RecordChanges.
  delete_match(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(String(p.findText || ''));
    const idx = Number(p.matchIndex) || 0;
    let hit = xModel.findFirst(sd), i = 0;
    while (hit !== null) {
      if (i === idx) { hit.setString(''); return { success: true, deletedIndex: idx }; }
      hit = xModel.findNext(hit, sd); i++;
    }
    return { success: false, message: 'match index out of range: ' + idx };
  },
  // [verified-extend] delete all/first occurrences under RecordChanges.
  delete_text(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(String(p.text || ''));
    const all = p.deleteAll !== false;
    let hit = xModel.findFirst(sd), n = 0;
    while (hit !== null) { hit.setString(''); n++; if (!all) break; hit = xModel.findNext(hit, sd); }
    return { success: true, deleted: n };
  },
  // [verified-extend] read the Nth (0-based) paragraph's text.
  get_paragraph(p) {
    const idx = Number(p.index) || 0;
    const en = xModel.getText().createEnumeration();
    let i = 0;
    while (en.hasMoreElements()) {
      const el = en.nextElement();
      if (el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) {
        if (i === idx) return { success: true, index: idx, text: el.getString() };
        i++;
      }
    }
    return { success: false, message: 'paragraph index out of range: ' + idx + ' (count ' + i + ')' };
  },
  // [verified-extend] modify the Nth paragraph's text under RecordChanges.
  modify_paragraph(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const idx = Number(p.index) || 0;
    const en = xModel.getText().createEnumeration();
    let i = 0;
    while (en.hasMoreElements()) {
      const el = en.nextElement();
      if (el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) {
        if (i === idx) { el.setString(String(p.newText || '')); return { success: true, index: idx }; }
        i++;
      }
    }
    return { success: false, message: 'paragraph index out of range: ' + idx };
  },
  // [verified-extend] outline = paragraphs carrying a heading style / outline level.
  get_outline() {
    const en = xModel.getText().createEnumeration();
    const outline = []; let i = 0;
    while (en.hasMoreElements()) {
      const el = en.nextElement();
      if (el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) {
        let lvl = 0, style = '';
        try { style = el.getPropertyValue('ParaStyleName') || ''; } catch (e) {}
        try { lvl = el.getPropertyValue('OutlineLevel') || 0; } catch (e) {}
        if (lvl > 0 || /^(Heading|标题)/.test(style)) outline.push({ paragraphIndex: i, level: lvl || 1, style: style, text: el.getString() });
        i++;
      }
    }
    return { success: true, count: outline.length, outline: outline };
  },
  // [verified-extend] move the view cursor to doc start/end (line/para/bookmark nav = TODO anchor).
  goto(p) {
    const vc = ctrl.getViewCursor();
    const type = String(p.type || '');
    if (type === 'start') vc.gotoStart(false);
    else if (type === 'end') vc.gotoEnd(false);
    else return { success: false, message: 'goto type not supported yet: ' + type + ' (anchor-based nav TODO, §0.2)' };
    return { success: true, type: type };
  },
  // [verified] §0.2 anchor-based selection. Takes {anchor} (a bookmark id from
  // find_text_locations), NOT integer offsets — those are rejected on purpose.
  set_selection(p) {
    if (!p.anchor) return { success: false, message: 'set_selection requires {anchor}; integer offsets unsupported (§0.2)' };
    const range = anchorRange(String(p.anchor));
    if (!range) return { success: false, message: 'anchor not found: ' + p.anchor };
    ctrl.select(range);
    return { success: true, anchor: p.anchor, text: range.getString() };
  },
  // [verified] §0.2 anchor-based replace, under RecordChanges (redline).
  replace_at_position(p) {
    if (!p.anchor) return { success: false, message: 'replace_at_position requires {anchor}; integer offsets unsupported (§0.2)' };
    xModel.setPropertyValue('RecordChanges', true);
    const range = anchorRange(String(p.anchor));
    if (!range) return { success: false, message: 'anchor not found: ' + p.anchor };
    range.setString(String(p.newText || ''));
    return { success: true, anchor: p.anchor };
  },
  // [verified-extend] insert a paragraph break at the view cursor (Enter key in
  // the IME overlay routes here — the overlay's single-line <input> can't make a
  // newline itself). Append, leave cursor collapsed after the break.
  insert_paragraph() {
    const xText = xModel.getText();
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    xText.insertControlCharacter(vc, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
    vc.collapseToEnd();
    return { success: true };
  },
  // [spike] move the view cursor (arrow keys in the IME overlay route here — the
  // overlay's single-line <input> would otherwise navigate the empty input, not
  // the document). left/right are text-flow steps (XTextCursor.goLeft/goRight),
  // up/down are visual lines (XViewCursor.goUp/goDown). extend=true (Shift+arrow)
  // grows the selection. No content change, so no RecordChanges.
  move_cursor(p) {
    const vc = ctrl.getViewCursor();
    const dir = String(p.dir || '');
    const ex = !!p.extend;
    switch (dir) {
      case 'left': vc.goLeft(1, ex); break;
      case 'right': vc.goRight(1, ex); break;
      case 'up': vc.goUp(1, ex); break;
      case 'down': vc.goDown(1, ex); break;
      default: return { success: false, message: 'move_cursor: unsupported dir: ' + dir };
    }
    return { success: true, dir: dir, extend: ex };
  },
  // [spike] delete one char before the cursor (Backspace in the IME overlay). If
  // text is selected, delete the selection; else extend one char left and clear.
  // Inherits the current RecordChanges state (tracked if tracking is on).
  delete_backward() {
    const vc = ctrl.getViewCursor();
    if ((vc.getString() || '').length === 0) vc.goLeft(1, true); // select char to the left
    const had = (vc.getString() || '').length;
    if (had === 0) return { success: true, deleted: 0 };          // at doc start: nothing to delete
    vc.setString('');
    return { success: true, deleted: had };
  },
  // [spike] Phase B: raw measurements for mapping the view cursor to canvas
  // pixels. We DELIBERATELY return primitives (not a final px rect) so the
  // mm->px formula can be calibrated on the JS side without restarting LOWA.
  //   - pos: XTextViewCursor.getPosition() -> awt.Point in 1/100 mm. The ORIGIN
  //     (page top-left vs visible-area top-left) is the open question under
  //     WASM; probe it empirically (short doc = no scroll hides the difference).
  //   - charHeightPt: CharHeight (points) at the cursor -> caret height.
  //   - zoom: ZoomValue (%) from the view settings.
  //   - winPx: the Qt component window rect (px) — the canvas surface bounds.
  // Each field is independently try/caught so a missing API degrades, not throws.
  get_cursor_rect() {
    const out = { success: true };
    let vc = null;
    try { vc = ctrl.getViewCursor(); } catch (e) { out.vcErr = errStr(e); }
    if (vc) {
      try { const pt = vc.getPosition(); out.pos = { X: pt.X, Y: pt.Y }; } catch (e) { out.posErr = errStr(e); }
      try { out.charHeightPt = vc.getPropertyValue('CharHeight'); } catch (e) { out.charHeightErr = errStr(e); }
      try { out.collapsed = (vc.getString() || '').length === 0; } catch (e) {}
    }
    try { out.zoom = ctrl.getViewSettings().getPropertyValue('ZoomValue'); } catch (e) { out.zoomErr = errStr(e); }
    try {
      const r = ctrl.getFrame().getComponentWindow().getPosSize();
      out.winPx = { X: r.X, Y: r.Y, W: r.Width, H: r.Height };
    } catch (e) { out.winErr = errStr(e); }
    // SCROLL-AWARE origin (Phase B): getPosition() is in document coords (from the
    // page top), so after the view scrolls the click-derived offset goes stale.
    // The view data carries the scrolled origin — VisibleLeft/Top (or ViewLeft/Top)
    // — so the overlay can subtract it and follow the cursor WITHOUT re-clicking.
    // We return the whole bag verbatim: which field tracks scroll AND its unit
    // (1/100 mm vs twips) is the open question to confirm on a real device, then
    // bake CURSOR_MAP.viewDataToMm accordingly.
    try {
      const vd = xModel.getViewData && xModel.getViewData();
      if (vd && typeof vd.getByIndex === 'function' && vd.getCount() > 0) {
        const seq = vd.getByIndex(0);
        const view = {};
        if (seq && seq.length) for (let i = 0; i < seq.length; i++) {
          const pv = seq[i];
          if (pv && pv.Name != null) view[pv.Name] = pv.Value;
        }
        out.viewData = view;
      }
    } catch (e) { out.viewDataErr = errStr(e); }
    return out;
  },
  // [spike] probe 4b: LOAD performance of a 50-page docx (#56 余项). The existing
  // testPerf times GENERATION; this times the docx IMPORT path: generate N pages,
  // store to a .docx in MEMFS, then loadComponentFromURL it (replacing the visible
  // doc) and time the load. Reports gen/save/load ms + page count. loadMs covers
  // model load + initial layout trigger; Qt repaint/scroll smoothness still needs
  // eyes on a real device. Heavy — may stress a headless sandbox (reboot if it
  // hangs); the authoritative numbers come from a real machine.
  perf_load(p) {
    const pages = Number(p.pages) || 50;
    const r = { success: true, pages: pages };
    try {
      const xText = xModel.getText();
      const cur = xText.createTextCursor();
      cur.gotoEnd(false);
      const tGen = performance.now();
      for (let pg = 0; pg < pages; pg++) {
        for (let l = 0; l < 28; l++) {
          xText.insertString(cur, '第' + (pg + 1) + '页 第' + (l + 1) + '行 合同条款示例 / contract clause sample line.', false);
          xText.insertControlCharacter(cur, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
        }
        try { cur.setPropertyValue('BreakType', css.style.BreakType.PAGE_AFTER); } catch (e) {}
      }
      r.genMs = Math.round(performance.now() - tGen);

      const url = 'file:///tmp/perf_' + pages + '.docx';
      const tSave = performance.now();
      xModel.storeToURL(url, [mkProp('FilterName', 'MS Word 2007 XML')]);
      r.saveMs = Math.round(performance.now() - tSave);

      const tLoad = performance.now();
      const loaded = desktop.loadComponentFromURL(url, '_default', 0, []);
      r.loadMs = Math.round(performance.now() - tLoad);

      // The loaded docx now owns the frame; retarget the worker's model/controller
      // so subsequent commands act on what's actually displayed.
      xModel = loaded;
      ctrl = loaded.getCurrentController();
      try { const vc = ctrl.getViewCursor(); vc.gotoEnd(false); r.pageCount = vc.getPage(); }
      catch (e) { r.pageErr = errStr(e); }
    } catch (e) { r.success = false; r.message = errStr(e); }
    return r;
  },
  // [verified-extend] LOAD the user's REAL document (Track D). Replaces the
  // seeded prototype with the bytes the host fetched (authed) from the backend.
  // SAME store→load→retarget mechanism perf_load proved, but the bytes are the
  // user's file (written into MEMFS via UNO SimpleFileAccess) instead of a
  // generated docx. After loading we retarget the worker's model/controller so
  // every subsequent command (AI redline, IME insert, cursor nav) acts on the
  // real document, not the prototype.
  load_document(p) {
    const name = String(p && p.name || 'document.docx');
    const m = name.match(/\.([A-Za-z0-9]+)$/);
    const ext = (m ? m[1] : 'docx').toLowerCase();

    // Normalize the transported bytes to the Int8Array a UNO sequence<byte>
    // expects (zetajs maps sequence<byte> -> Int8Array). The host sends a
    // Uint8Array; structured clone across the relay/worker hops may surface it
    // as Uint8Array / ArrayBuffer / Array — accept all.
    const raw = p && p.bytes;
    let u8 = null;
    if (raw instanceof ArrayBuffer) u8 = new Uint8Array(raw);
    else if (raw && raw.buffer instanceof ArrayBuffer) u8 = new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength != null ? raw.byteLength : raw.length);
    else if (Array.isArray(raw)) u8 = new Uint8Array(raw);
    if (!u8 || u8.length === 0) return { success: false, message: 'load_document: empty/invalid bytes' };
    const bytes = new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength);

    try {
      // Write the bytes into MEMFS, then load that file. UNO file IO to
      // file:///tmp is the same path perf_load's storeToURL/loadComponentFromURL
      // proved works under WASM.
      const url = 'file:///tmp/ai_doc_' + (++docSeq) + '.' + ext;
      const sfa = css.ucb.SimpleFileAccess.create(context);
      try { if (sfa.exists(url)) sfa.kill(url); } catch (e) {}
      const stream = css.io.SequenceInputStream.createStreamFromSequence(bytes);
      sfa.writeFile(url, stream);
      try { stream.closeInput(); } catch (e) {}

      // '_default' replaces the visible (modified) prototype frame — same target
      // and empty filter args (extension-based auto-detect) as perf_load.
      const loaded = desktop.loadComponentFromURL(url, '_default', 0, []);
      if (!loaded) return { success: false, message: 'loadComponentFromURL returned null for ' + url };

      // Retarget so all subsequent UNO commands act on the loaded document.
      xModel = loaded;
      ctrl = loaded.getCurrentController();
      try { ctrl.getFrame().getContainerWindow().FullScreen = true; } catch (e) {}
      try { installKeyHandler(); } catch (e) {}

      log('load_document: 已加载真实文档「' + name + '」/ loaded real document (' + u8.length + ' bytes)');
      return { success: true, name: name, url: url, bytes: u8.length };
    } catch (e) {
      return { success: false, message: errStr(e) };
    }
  },
  // housekeeping: drop the hidden anchor bookmarks.
  clear_anchors() {
    const bms = xModel.getBookmarks();
    const names = (bms.getElementNames && bms.getElementNames()) || [];
    const xText = xModel.getText();
    let n = 0;
    for (let i = 0; i < names.length; i++) {
      if (names[i].indexOf(ANCHOR_PREFIX) === 0) {
        try { xText.removeTextContent(bms.getByName(names[i])); n++; } catch (e) {}
      }
    }
    return { success: true, cleared: n };
  },
};

function execCommand(reqId, action, params) {
  let result;
  try {
    const fn = EXEC[action];
    result = fn ? fn(params || {}) : { success: false, message: 'not implemented in LibreOffice worker yet: ' + action };
  } catch (e) {
    result = { success: false, message: errStr(e) };
  }
  post('result', { reqId: reqId, result: result });
}

// ---- message loop --------------------------------------------------------
Module.zetajs.then(function (pZetajs) {
  zetajs = pZetajs;
  css = zetajs.uno.com.sun.star;
  zetajs.mainPort.onmessage = function (e) {
    switch (e.data.cmd) {
      case 'exec': execCommand(e.data.reqId, e.data.action, e.data.params); break;
      case 'selection': testSelection(); break;
      case 'redline': testRedline(); break;
      case 'perf': testPerf(Number(e.data.pages) || 50); break;
      case 'inserttext': testInsertText(e.data.text); break;
      default: log('unknown cmd: ' + e.data.cmd);
    }
  };
  bootDoc();
  post('thr_running');
});
