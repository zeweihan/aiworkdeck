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

function post(cmd, data) {
  zetajs.mainPort.postMessage(Object.assign({ cmd }, data || {}));
}
function log(msg) { post('log', { msg: String(msg) }); }
function errStr(e) {
  try { return zetajs.getAnyType(zetajs.catchUnoException(e)) + ' ' + (e && e.message || ''); }
  catch { return String(e && e.message || e); }
}

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
