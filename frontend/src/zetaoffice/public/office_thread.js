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
let saveSeq = 0;   // names the MEMFS temp file for each export (save) round-trip

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

// Diagnostic (#66): read the resolved UI locale from the config so we can tell
// whether the injected zh-CN langpack + ooLocale override actually took effect.
// ooLocale='zh-CN' but English menus => resources/path issue; ooLocale=''/'en-US'
// => the override .xcd was not applied (need a different mechanism).
function readConfigLocale() {
  try {
    const provider = context.getServiceManager().createInstanceWithContext(
      'com.sun.star.configuration.ConfigurationProvider', context);
    const access = provider.createInstanceWithArguments(
      'com.sun.star.configuration.ConfigurationAccess',
      [mkProp('nodepath', '/org.openoffice.Setup/L10N')]);
    const out = {};
    try { out.ooLocale = access.getByName('ooLocale'); } catch (e) { out.ooLocaleErr = errStr(e); }
    try { out.sysLocale = access.getByName('ooSetupSystemLocale'); } catch (e) {}
    return out;
  } catch (e) { return { err: errStr(e) }; }
}

// LibreOffice import filter names by extension — used only for the stream-load
// fallback (private:stream has no URL extension to auto-detect a filter from).
const IMPORT_FILTERS = {
  docx: 'MS Word 2007 XML', doc: 'MS Word 97',
  xlsx: 'Calc MS Excel 2007 XML', xls: 'MS Excel 97',
  pptx: 'Impress MS PowerPoint 2007 XML', ppt: 'MS PowerPoint 97',
  rtf: 'Rich Text Format', txt: 'Text',
  odt: 'writer8', ods: 'calc8', odp: 'impress8',
};

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

// #104 variable fields: a NAMED bookmark spanning the inserted value marks a
// "document field" bound to a (scope, varName) variable — the WPS 域 semantics
// VariablePanel expects, on LibreOffice. The binding is encoded in the bookmark
// NAME (scope + hex-encoded UTF-8 varName, name-safe chars only) so it survives
// a docx save/reload — bookmarks round-trip through OOXML; no custom XML part
// needed. (Word caps bookmark names at 40 chars, so a long CJK varName may get
// mangled if the file is edited in Word itself; LibreOffice round-trips it.)
const VAR_FIELD_PREFIX = '__ai_var_';
let varFieldSeq = 0;
function hexUtf8(s) {
  const bytes = unescape(encodeURIComponent(String(s))); // UTF-8 byte string
  let out = '';
  for (let i = 0; i < bytes.length; i++) out += ('0' + bytes.charCodeAt(i).toString(16)).slice(-2);
  return out;
}
function unhexUtf8(h) {
  let bytes = '';
  for (let i = 0; i + 1 < h.length; i += 2) bytes += String.fromCharCode(parseInt(h.substr(i, 2), 16));
  try { return decodeURIComponent(escape(bytes)); } catch (e) { return bytes; }
}
function newVarFieldName(scope, varName) {
  const bms = xModel.getBookmarks();
  let name;
  do { name = VAR_FIELD_PREFIX + scope + '_' + hexUtf8(varName) + '_' + (++varFieldSeq); }
  while (bms.hasByName(name)); // seq resets per session; skip names already in the doc
  return name;
}
function parseVarFieldName(name) {
  if (String(name).indexOf(VAR_FIELD_PREFIX) !== 0) return null;
  const parts = String(name).slice(VAR_FIELD_PREFIX.length).split('_'); // [scope, hex, seq]
  if (parts.length !== 3 || !parts[0]) return null;
  return { scope: parts[0], varName: unhexUtf8(parts[1]) };
}

// ---- 拟人式原语 helpers（感知/验证回路） ------------------------------------
// Context around a range: the chars immediately before/after, read via a text
// cursor spawned FROM THE RANGE'S OWN XText (so matches inside table cells /
// frames read their local story, not the body text). This is what lets the AI
// tell five identical "甲方" hits apart — the WPS-era failure mode.
function contextAround(range, radius) {
  const n = Math.max(1, Math.min(Number(radius) || 50, 200));
  const out = { before: '', after: '' };
  try {
    const t = range.getText();
    const b = t.createTextCursorByRange(range.getStart());
    b.goLeft(n, true);
    out.before = b.getString();
    const a = t.createTextCursorByRange(range.getEnd());
    a.goRight(n, true);
    out.after = a.getString();
  } catch (e) { out.ctxErr = errStr(e); }
  return out;
}
// The paragraph text enclosing a range (start point), for match disambiguation
// and post-edit verification. XParagraphCursor via createTextCursorByRange.
function paragraphTextOf(range) {
  try {
    const t = range.getText();
    const cur = t.createTextCursorByRange(range.getStart());
    cur.gotoStartOfParagraph(false);
    cur.gotoEndOfParagraph(true);
    return cur.getString();
  } catch (e) { return null; }
}
// Enumerate body paragraphs, calling fn(el, index); fn returns true to stop.
function eachParagraph(fn) {
  const en = xModel.getText().createEnumeration();
  let i = 0;
  while (en.hasMoreElements()) {
    const el = en.nextElement();
    if (el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) {
      if (fn(el, i)) return;
      i++;
    }
  }
}
// Select a range THE WAY A HUMAN WOULD: the view cursor jumps there (the view
// scrolls to it) and the selection is visibly painted. gotoRange(range, false)
// makes the view cursor span the range — the standard "select the found hit"
// idiom. Falls back to ctrl.select if the view cursor balks (e.g. range in a
// story the view cursor can't enter).
function selectVisibly(range) {
  try { ctrl.getViewCursor().gotoRange(range, false); return true; }
  catch (e) { try { ctrl.select(range); return true; } catch (e2) { return false; } }
}
// Insert text at the view cursor, honoring '\n' as a PARAGRAPH BREAK.
// XText.insertString does NOT split paragraphs on '\n' (verified against the
// real engine: a multi-line insert landed as ONE paragraph), so multi-paragraph
// inserts must interleave insertControlCharacter(PARAGRAPH_BREAK).
function insertTextAtCursor(vc, text) {
  const xText = xModel.getText();
  const parts = String(text).split('\n');
  for (let i = 0; i < parts.length; i++) {
    if (i > 0) xText.insertControlCharacter(vc, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
    if (parts[i]) xText.insertString(vc, parts[i], false);
  }
}

// Fire a Writer UI command (.uno:*) on the current frame — the engine-native
// path for key-equivalent actions (Backspace/Delete), so revision-mode (redline)
// semantics are the engine's own. zetajs marshalling: create() takes context as
// first arg; the args sequence must be a plain Array (PR#107 rules).
function dispatchUno(url) {
  css.frame.DispatchHelper.create(context).executeDispatch(ctrl.getFrame(), url, '', 0, []);
}

// Desktop-keyboard parity set for the IME overlay's ui_command action — an
// ALLOWLIST map (name -> .uno: slot), deliberately NOT a raw dispatch
// passthrough. Toggles (bold/italic/underline) are the engine's own, so
// collapsed-cursor and mixed-selection semantics match the desktop app.
const UI_COMMANDS = {
  select_all: '.uno:SelectAll',
  bold: '.uno:Bold', italic: '.uno:Italic', underline: '.uno:Underline',
  line_start: '.uno:GoToStartOfLine', line_end: '.uno:GoToEndOfLine',
};

// Shared verification snapshot returned by mutating commands: where the cursor
// is now + the paragraph as it reads AFTER the edit ("改完看一眼").
function verifySnapshot() {
  try {
    const vc = ctrl.getViewCursor();
    return { paragraphAfterEdit: paragraphTextOf(vc), selectedText: vc.getString() };
  } catch (e) { return {}; }
}

// ---- 格式原语的取值映射 ------------------------------------------------------
const HIGHLIGHT_COLORS = { // CharHighlight RGB; 'none' clears (-1)
  yellow: 0xFFFF00, green: 0x00FF00, cyan: 0x00FFFF, magenta: 0xFF00FF,
  red: 0xFF0000, blue: 0x0000FF, gray: 0xC0C0C0, none: -1,
};
function parseColor(v, names) {
  if (v == null) return null;
  const s = String(v).trim().toLowerCase();
  if (names && s in names) return names[s];
  if (s === 'auto') return -1;
  const m = s.match(/^#?([0-9a-f]{6})$/);
  return m ? parseInt(m[1], 16) : null;
}
// Set a char property together with its Asian/Complex siblings so CJK runs
// (the product's main language) pick up the format too.
function setCharProp(ps, base, value) {
  ps.setPropertyValue(base, value);
  for (const sfx of ['Asian', 'Complex']) {
    try { ps.setPropertyValue(base + sfx, value); } catch (e) { /* not all props have siblings */ }
  }
}

// ---- boot: open a fresh BLANK Writer doc ----------------------------------
// Production: a brand-new / empty document must show a clean blank page (the
// host loads real bytes via load_document when the file has content). We do NOT
// seed scaffolding text here — earlier dev-prototype seed text would otherwise
// show through whenever a real load was skipped/failed, looking like the editor
// loaded the wrong content.
function bootDoc() {
  context = zetajs.getUnoComponentContext();
  desktop = css.frame.Desktop.create(context);
  xModel = desktop.loadComponentFromURL('private:factory/swriter', '_default', 0, []);
  ctrl = xModel.getCurrentController();
  try { ctrl.getFrame().getContainerWindow().FullScreen = true; } catch {}
  // RFC v2: revisions default ON — every edit (AI or typed) lands as a tracked
  // change the lawyer can accept/reject. Set once here (and on retarget) instead
  // of per-command, so no edit path can slip through untracked.
  try { xModel.setPropertyValue('RecordChanges', true); } catch {}

  installKeyHandler();
  post('ui_ready');
  log('空白文档就绪 swriter / blank doc ready');
  try { const loc = readConfigLocale(); log('UI locale 诊断 / config: ' + JSON.stringify(loc)); } catch (e) { log('UI locale 诊断失败: ' + errStr(e)); }
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
// Implements the SAME editor-agnostic actions that the agent command pipeline
// dispatches, via UNO — so frontend/src/composables/useLibreOfficeBridge.js can
// drive LibreOffice with the backend's existing commands. Each handler returns a
// plain result object; the dispatcher posts {cmd:'result', reqId, result} back.
// [verified] handlers use the Phase 0-proven primitives; [todo] are stubs.
// ==========================================================================
const EXEC = {
  // [verified] insert at the view cursor (append, not select) — see testInsertText.
  insert_at_cursor(p) {
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    insertTextAtCursor(vc, p.text || '');
    vc.collapseToEnd();
    return Object.assign({ success: true, inserted: String(p.text || '') }, verifySnapshot());
  },
  // [verified] replace selection if any, else insert at cursor. '\n' in the new
  // text becomes a paragraph break (insertTextAtCursor).
  replace_selection(p) {
    const vc = ctrl.getViewCursor();
    if ((vc.getString() || '').length > 0) vc.setString(''); // drop the selection (tracked)
    vc.collapseToEnd();
    insertTextAtCursor(vc, p.text || '');
    vc.collapseToEnd();
    return Object.assign({ success: true, text: String(p.text || '') }, verifySnapshot());
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
    while (hit !== null && ranges.length < 200) { ranges.push(hit); hit = xModel.findNext(hit, sd); }
    // Each match carries DISAMBIGUATION CONTEXT (chars before/after + enclosing
    // paragraph), so the AI can tell identical hits apart BEFORE editing — the
    // WPS-era "replaced the wrong one" class of bug dies here.
    const matches = ranges.map(function (r, i) {
      let anchorId = null;
      try { anchorId = anchorBookmark(r); } catch (e) {}
      const ctx = contextAround(r, 40);
      return {
        matchIndex: i, anchorId: anchorId, text: r.getString(),
        contextBefore: ctx.before, contextAfter: ctx.after,
        paragraph: (paragraphTextOf(r) || '').slice(0, 160),
      };
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
        if (i === idx) {
          selectVisibly(el); // 拟人：先跳到目标段落
          el.setString(String(p.newText || ''));
          return { success: true, index: idx, paragraphAfterEdit: el.getString().slice(0, 200) };
        }
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
    // 拟人：光标跳过去、视图滚过去、选区亮出来 — 用户看得见 AI 在操作哪里。
    if (!selectVisibly(range)) return { success: false, message: 'could not select anchor: ' + p.anchor };
    const ctx = contextAround(range, 40);
    return { success: true, anchor: p.anchor, text: range.getString(), contextBefore: ctx.before, contextAfter: ctx.after };
  },
  // [verified] §0.2 anchor-based replace, under RecordChanges (redline).
  replace_at_position(p) {
    if (!p.anchor) return { success: false, message: 'replace_at_position requires {anchor}; integer offsets unsupported (§0.2)' };
    xModel.setPropertyValue('RecordChanges', true);
    const range = anchorRange(String(p.anchor));
    if (!range) return { success: false, message: 'anchor not found: ' + p.anchor };
    selectVisibly(range); // 拟人：先看见目标再动手
    range.setString(String(p.newText || ''));
    // 验证回路：返回改动后所在段落的实际文本，AI 据此确认改对了。
    return Object.assign({ success: true, anchor: p.anchor, newText: String(p.newText || '') },
      { paragraphAfterEdit: (paragraphTextOf(range) || '').slice(0, 200) });
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
  // Backspace / Delete (IME overlay control keys) — routed through Writer's OWN
  // key dispatch (.uno:SwBackspace / .uno:Delete), NOT hand-rolled goLeft+
  // setString. WHY: RecordChanges is ON since boot, and setString('') on
  // pre-existing (non-own-insert) text only MARKS it as a delete redline — the
  // view cursor never advances past the marked char, so every further Backspace
  // re-selected the SAME char and deletion jammed after one press (headless-
  // verified). The engine dispatch owns revision semantics: mark + step past for
  // original text, hard-delete for own unaccepted inserts, selection-aware.
  delete_backward() {
    dispatchUno('.uno:SwBackspace');
    return { success: true };
  },
  delete_forward() {
    dispatchUno('.uno:Delete');
    return { success: true };
  },
  // Overlay shortcut keys (Cmd/Ctrl+A/B/I/U, Home/End) — see UI_COMMANDS.
  ui_command(p) {
    const url = UI_COMMANDS[String(p.name || '')];
    if (!url) return { success: false, message: 'ui_command not allowed: ' + (p.name || '') };
    dispatchUno(url);
    return { success: true, name: String(p.name) };
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
  // [verified-extend] LOAD the user's REAL document (Track D). Replaces the blank
  // boot doc with the bytes the host fetched (authed) from the backend. SAME
  // store→load→retarget mechanism perf_load proved, but the bytes are the user's
  // file. After loading we retarget the worker's model/controller so every
  // subsequent command (AI redline, IME insert, cursor nav) acts on the real
  // document. Two independent load strategies (MEMFS file, then private:stream)
  // so a single UNO-API quirk on a device can't blank the user's content.
  load_document(p) {
    const name = String(p && p.name || 'document.docx');
    const m = name.match(/\.([A-Za-z0-9]+)$/);
    const ext = (m ? m[1] : 'docx').toLowerCase();

    // Normalize the transported bytes. The host sends a Uint8Array; structured
    // clone across the relay/worker hops may surface it as Uint8Array /
    // ArrayBuffer / Array — accept all.
    const raw = p && p.bytes;
    let u8 = null;
    if (raw instanceof ArrayBuffer) u8 = new Uint8Array(raw);
    else if (raw && raw.buffer instanceof ArrayBuffer) u8 = new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength != null ? raw.byteLength : raw.length);
    else if (Array.isArray(raw)) u8 = new Uint8Array(raw);
    // Empty body = a brand-new / unsaved document (the backend streams 200 + 0
    // bytes for it). That is NOT an error: keep the blank boot doc as-is. The
    // host also guards this, but defend here too.
    if (!u8 || u8.length === 0) return { success: true, empty: true, name: name };
    // zeta.js marshals JS→UNO sequences ONLY from plain Arrays (translateToEmbind
    // gates on Array.isArray), and sequence<byte> elements are SIGNED — so go
    // through an Int8Array view, then Array.from. Passing the typed array itself
    // reaches embind unconverted: 'Cannot pass "80,75,…" as …'. (Int8Array is
    // the shape of RESULTS only, and only in some zetajs builds.)
    const bytes = Array.from(new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength));

    // Retarget the worker's model/controller onto a freshly-loaded component.
    const retarget = (loaded) => {
      xModel = loaded;
      ctrl = loaded.getCurrentController();
      try { ctrl.getFrame().getContainerWindow().FullScreen = true; } catch (e) {}
      try { installKeyHandler(); } catch (e) {}
      // Revisions default ON for the real document too (same as bootDoc).
      try { xModel.setPropertyValue('RecordChanges', true); } catch (e) {}
    };

    const errs = [];

    // Strategy 1: write bytes into MEMFS, load the file by URL (extension drives
    // filter auto-detection — same as perf_load's proven storeToURL/load path).
    try {
      const url = 'file:///tmp/ai_doc_' + (++docSeq) + '.' + ext;
      const sfa = css.ucb.SimpleFileAccess.create(context);
      try { if (sfa.exists(url)) sfa.kill(url); } catch (e) {}
      // zetajs named service constructors take the component context as the
      // implicit FIRST argument (zeta.js: `const context = arguments[0]`).
      // Omitting it made zetajs treat the byte sequence AS the context —
      // "context.getServiceManager is not a function" — so load_document never
      // succeeded on any engine until the host-side repro caught it.
      const stream = css.io.SequenceInputStream.createStreamFromSequence(context, bytes);
      sfa.writeFile(url, stream);
      try { stream.closeInput(); } catch (e) {}
      const loaded = desktop.loadComponentFromURL(url, '_default', 0, []);
      if (loaded) {
        retarget(loaded);
        log('load_document: 已加载真实文档「' + name + '」/ loaded (' + u8.length + ' bytes, via file)');
        return { success: true, name: name, bytes: u8.length, via: 'file' };
      }
      errs.push('file: loadComponentFromURL returned null');
    } catch (e) { errs.push('file: ' + errStr(e)); }

    // Strategy 2: load directly from an in-memory stream (no MEMFS write). Needs
    // an explicit import filter since there is no URL extension to detect from.
    try {
      const filter = IMPORT_FILTERS[ext];
      const stream2 = css.io.SequenceInputStream.createStreamFromSequence(context, bytes);
      const args = [mkProp('InputStream', stream2)];
      if (filter) args.push(mkProp('FilterName', filter));
      const loaded = desktop.loadComponentFromURL('private:stream', '_default', 0, args);
      if (loaded) {
        retarget(loaded);
        log('load_document: 已加载真实文档「' + name + '」/ loaded (' + u8.length + ' bytes, via stream)');
        return { success: true, name: name, bytes: u8.length, via: 'stream' };
      }
      errs.push('stream: loadComponentFromURL returned null');
    } catch (e) { errs.push('stream: ' + errStr(e)); }

    return { success: false, message: 'load_document failed: ' + errs.join(' | ') };
  },
  // [Track E] export the current document as bytes (host-initiated save — the
  // mirror image of load_document): storeToURL into MEMFS, read the bytes back,
  // hand them to the host, which persists them via the backend upload endpoint.
  // The filter names in IMPORT_FILTERS are the registry FilterNames, valid for
  // BOTH directions for these formats (e.g. 'MS Word 2007 XML' imports AND
  // exports .docx).
  export_document(p) {
    const name = String(p && p.name || 'document.docx');
    const m = name.match(/\.([A-Za-z0-9]+)$/);
    const ext = (m ? m[1] : 'docx').toLowerCase();
    const filter = IMPORT_FILTERS[ext];

    // Stream the store STRAIGHT INTO JS via a UNO XOutputStream implemented
    // here. Do NOT storeToURL a MEMFS file and read it back with Module.FS:
    // this office thread is an em-pthread whose JS-level FS is NOT the main
    // thread's file system (FS state lives on the main runtime; pthreads only
    // proxy at the syscall layer), so FS.readFile threw ENOENT on the device
    // (v0.3.1 real-machine report: save failed "No such file or directory").
    const chunks = [];
    let total = 0;
    const sink = zetajs.unoObject([css.io.XOutputStream], {
      writeBytes(seq) { // sequence<byte> -> Int8Array view
        const u8 = new Uint8Array(seq.buffer ? seq.buffer.slice(seq.byteOffset, seq.byteOffset + seq.byteLength) : seq);
        chunks.push(u8); total += u8.length;
      },
      flush() {},
      closeOutput() {},
    });
    const props = [mkProp('OutputStream', sink), mkProp('Overwrite', true)];
    if (filter) props.push(mkProp('FilterName', filter));
    // private:stream = "write to the OutputStream in the media descriptor" —
    // the standard LO idiom for exporting to memory (no filesystem involved).
    xModel.storeToURL('private:stream', props);
    saveSeq++;
    if (total === 0) return { success: false, message: 'export_document: store produced 0 bytes' };
    const u8 = new Uint8Array(total);
    let off = 0;
    for (const c of chunks) { u8.set(c, off); off += c.length; }
    log('export_document: 已导出「' + name + '」/ exported (' + u8.length + ' bytes, filter=' + (filter || 'auto') + ')');
    return { success: true, name: name, size: u8.length, bytes: u8 };
  },
  // [diagnostic #66] report the resolved UI locale (ooLocale) so the host/verify
  // panel can confirm whether the injected zh-CN langpack took effect.
  get_ui_lang() { return Object.assign({ success: true }, readConfigLocale()); },
  // [diagnostic] the font families the engine actually registered (device font
  // list). Ground truth for the CJK font-injection/alias chain — a family
  // missing here can only ever render via an alias to one that is present.
  list_fonts() {
    try {
      const toolkit = css.awt.Toolkit.create(context);
      const dev = toolkit.createScreenCompatibleDevice(0, 0);
      const fds = dev.getFontDescriptors();
      const names = {};
      for (let i = 0; i < fds.length; i++) { names[fds[i].Name] = true; }
      const families = Object.keys(names).sort();
      return { success: true, count: families.length, families: families };
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
  // ==================== 拟人式原语（感知 / 定位 / 格式 / 撤销） ====================
  // [感知] read the document as numbered paragraphs — the AI's "eyes". Windowed
  // (startParagraph + maxParagraphs, plus a char budget) so a 200-page contract
  // can be read in passes without blowing the tool-result size.
  get_document_text(p) {
    const start = Math.max(0, Number(p && p.startParagraph) || 0);
    const maxParas = Math.max(1, Math.min(Number(p && p.maxParagraphs) || 200, 500));
    const charBudget = 15000;
    const paragraphs = [];
    let total = 0, chars = 0, truncated = false;
    eachParagraph(function (el, i) {
      total = i + 1;
      if (i < start || paragraphs.length >= maxParas || chars >= charBudget) return false;
      const item = { index: i, text: el.getString() };
      try {
        const lvl = el.getPropertyValue('OutlineLevel') || 0;
        if (lvl > 0) { item.headingLevel = lvl; item.style = el.getPropertyValue('ParaStyleName') || ''; }
      } catch (e) {}
      chars += item.text.length;
      paragraphs.push(item);
      return false;
    });
    if (start + paragraphs.length < total) truncated = true;
    const r = { success: true, totalParagraphs: total, startParagraph: start, returned: paragraphs.length, paragraphs: paragraphs };
    if (truncated) { r.truncated = true; r.nextStartParagraph = start + paragraphs.length; }
    return r;
  },
  // [感知] what's around the cursor right now — selection, chars before/after,
  // and the enclosing paragraph ("看一眼手边").
  get_cursor_context(p) {
    const vc = ctrl.getViewCursor();
    const ctx = contextAround(vc, Number(p && p.radius) || 80);
    return {
      success: true, selectedText: vc.getString(), hasSelection: (vc.getString() || '').length > 0,
      before: ctx.before, after: ctx.after, paragraph: (paragraphTextOf(vc) || '').slice(0, 300),
    };
  },
  // [定位] select the Nth (0-based) body paragraph, visibly (view scrolls to it).
  select_paragraph(p) {
    const idx = Number(p.index) || 0;
    let found = null;
    eachParagraph(function (el, i) { if (i === idx) { found = el; return true; } return false; });
    if (!found) return { success: false, message: 'paragraph index out of range: ' + idx };
    if (!selectVisibly(found)) return { success: false, message: 'could not select paragraph ' + idx };
    return { success: true, index: idx, text: found.getString() };
  },
  // [定位] drop the cursor at the start/end edge of the current selection — the
  // human move for "insert BEFORE/AFTER this" (compose with insert_at_cursor).
  collapse_selection(p) {
    const vc = ctrl.getViewCursor();
    const to = String(p && p.to || 'end');
    if (to === 'start') vc.collapseToStart(); else vc.collapseToEnd();
    return { success: true, to: to };
  },
  // [编辑] delete exactly what is selected (tracked). The anthropomorphic delete:
  // select first (user sees what's about to go), then cut.
  delete_selection() {
    const vc = ctrl.getViewCursor();
    const had = (vc.getString() || '');
    if (had.length === 0) return { success: false, message: 'nothing selected — select_paragraph / set_selection first' };
    vc.setString('');
    return Object.assign({ success: true, deletedText: had.slice(0, 200), deletedChars: had.length }, verifySnapshot());
  },
  // [格式] character formatting on the CURRENT selection (select first, then
  // format — same order a human works in). Any subset of the params applies;
  // booleans false/'none' explicitly clear. CJK-safe via Asian/Complex siblings.
  format_selection(p) {
    const vc = ctrl.getViewCursor();
    if ((vc.getString() || '').length === 0) return { success: false, message: 'nothing selected — select first, then format' };
    const applied = {};
    if (p.bold != null) { setCharProp(vc, 'CharWeight', p.bold ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL); applied.bold = !!p.bold; }
    if (p.italic != null) { setCharProp(vc, 'CharPosture', p.italic ? css.awt.FontSlant.ITALIC : css.awt.FontSlant.NONE); applied.italic = !!p.italic; }
    if (p.underline != null) { vc.setPropertyValue('CharUnderline', p.underline ? css.awt.FontUnderline.SINGLE : css.awt.FontUnderline.NONE); applied.underline = !!p.underline; }
    if (p.strikeout != null) { vc.setPropertyValue('CharStrikeout', p.strikeout ? css.awt.FontStrikeout.SINGLE : css.awt.FontStrikeout.NONE); applied.strikeout = !!p.strikeout; }
    if (p.highlight != null) {
      const c = parseColor(p.highlight, HIGHLIGHT_COLORS);
      if (c == null) return { success: false, message: 'bad highlight color: ' + p.highlight + ' (use yellow/green/cyan/magenta/red/blue/gray/none or #RRGGBB)' };
      vc.setPropertyValue('CharHighlight', c); applied.highlight = String(p.highlight);
    }
    if (p.color != null) {
      const c = parseColor(p.color, { auto: -1 });
      if (c == null) return { success: false, message: 'bad color: ' + p.color + ' (use #RRGGBB or auto)' };
      vc.setPropertyValue('CharColor', c); applied.color = String(p.color);
    }
    if (p.fontSize != null) { setCharProp(vc, 'CharHeight', Number(p.fontSize)); applied.fontSize = Number(p.fontSize); }
    if (p.fontName != null) { setCharProp(vc, 'CharFontName', String(p.fontName)); applied.fontName = String(p.fontName); }
    if (Object.keys(applied).length === 0) return { success: false, message: 'no format params given' };
    return { success: true, applied: applied, selectedText: vc.getString().slice(0, 100) };
  },
  // [格式] paragraph-level formatting on the selection's paragraph(s): alignment
  // and/or paragraph style. headingLevel 1-9 maps to the programmatic style name
  // ('Heading N', valid regardless of UI language); 0 = back to body ('Standard').
  set_paragraph_format(p) {
    const vc = ctrl.getViewCursor();
    const applied = {};
    if (p.alignment != null) {
      const m = { left: css.style.ParagraphAdjust.LEFT, right: css.style.ParagraphAdjust.RIGHT, center: css.style.ParagraphAdjust.CENTER, justify: css.style.ParagraphAdjust.BLOCK };
      const v = m[String(p.alignment).toLowerCase()];
      if (v == null) return { success: false, message: 'bad alignment: ' + p.alignment + ' (left/right/center/justify)' };
      vc.setPropertyValue('ParaAdjust', v); applied.alignment = String(p.alignment);
    }
    let styleName = p.styleName != null ? String(p.styleName) : null;
    if (p.headingLevel != null) {
      const lvl = Number(p.headingLevel);
      styleName = lvl >= 1 && lvl <= 9 ? 'Heading ' + lvl : 'Standard';
    }
    if (styleName != null) { vc.setPropertyValue('ParaStyleName', styleName); applied.styleName = styleName; }
    if (Object.keys(applied).length === 0) return { success: false, message: 'no paragraph format params given' };
    return Object.assign({ success: true, applied: applied }, verifySnapshot());
  },
  // [撤销] the human safety net — back out the last step(s) when a verify shows
  // the edit landed wrong. Steps clamp at 20.
  undo(p) {
    const um = xModel.getUndoManager();
    const want = Math.max(1, Math.min(Number(p && p.steps) || 1, 20));
    let done = 0;
    for (; done < want; done++) {
      try { um.undo(); } catch (e) { break; } // empty stack ends the loop
    }
    return Object.assign({ success: done > 0, undone: done }, done > 0 ? verifySnapshot() : { message: 'nothing to undo' });
  },
  redo(p) {
    const um = xModel.getUndoManager();
    const want = Math.max(1, Math.min(Number(p && p.steps) || 1, 20));
    let done = 0;
    for (; done < want; done++) {
      try { um.redo(); } catch (e) { break; }
    }
    return Object.assign({ success: done > 0, redone: done }, done > 0 ? verifySnapshot() : { message: 'nothing to redo' });
  },
  // ---- #104 文档变量域原语（VariablePanel via the getWps adapter) ------------
  // list every variable field in the document: {fields: [{id, scope, varName, text}]}
  var_list() {
    const bms = xModel.getBookmarks();
    const names = (bms.getElementNames && bms.getElementNames()) || [];
    const fields = [];
    for (let i = 0; i < names.length; i++) {
      const meta = parseVarFieldName(names[i]);
      if (!meta) continue;
      let text = '';
      try { text = bms.getByName(names[i]).getAnchor().getString(); } catch (e) {}
      fields.push({ id: names[i], scope: meta.scope, varName: meta.varName, text: text });
    }
    return { success: true, count: fields.length, fields: fields };
  },
  // insert {text} at the view cursor, marked as a field of (scope, name).
  var_insert(p) {
    const name = String(p.name || '').trim();
    if (!name) return { success: false, message: 'var_insert requires {name}' };
    const scope = String(p.scope || 'D');
    // a paragraph break inside the span would split the bookmark, so field
    // values are inline-only: newlines flatten to spaces
    const value = String(p.text == null ? '' : p.text).replace(/[\r\n]+/g, ' ');
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    const xText = vc.getText(); // the story the cursor is in (body / table cell / frame)
    const cur = xText.createTextCursorByRange(vc.getEnd());
    xText.insertString(cur, value, true); // bAbsorb: cur now spans the inserted value
    const id = newVarFieldName(scope, name);
    const bm = xModel.createInstance('com.sun.star.text.Bookmark');
    bm.setName(id);
    xText.insertTextContent(cur, bm, true);
    try { vc.gotoRange(cur.getEnd(), false); } catch (e) {}
    return Object.assign({ success: true, id: id, scope: scope, varName: name, text: value }, verifySnapshot());
  },
  // replace the text span of field {id} with {text}, keeping the marker alive.
  var_update(p) {
    const id = String(p.id || '');
    const bms = xModel.getBookmarks();
    if (!id || !bms.hasByName(id)) return { success: false, message: 'variable field not found: ' + id };
    if (!parseVarFieldName(id)) return { success: false, message: 'not a variable field: ' + id };
    const value = String(p.text == null ? '' : p.text).replace(/[\r\n]+/g, ' ');
    const bm = bms.getByName(id);
    const anchor = bm.getAnchor();
    const xText = anchor.getText();
    const cur = xText.createTextCursorByRange(anchor);
    xText.removeTextContent(bm); // setString over the span would swallow the marker; re-add below
    cur.setString(value);
    const bm2 = xModel.createInstance('com.sun.star.text.Bookmark');
    bm2.setName(id);
    xText.insertTextContent(cur, bm2, true);
    return { success: true, id: id, text: value, paragraphAfterEdit: (paragraphTextOf(cur) || '').slice(0, 200) };
  },
  // ---- #79 债务清偿：WPS 实例绑定能力的 LibreOffice 等价原语 ----------------
  // 选区↔文件超链接关联（拖拽关联）、网核证据标记（书签+超链接）、图片插入。
  // read the hyperlink URL on the current selection (empty string if none) —
  // the host uses it to REUSE an existing linkKey instead of stacking a new one.
  get_selection_hyperlink() {
    const sel = ctrl.getSelection();
    let range = null;
    try {
      if (sel && typeof sel.getByIndex === 'function' && sel.getCount() > 0) range = sel.getByIndex(0);
    } catch (e) {}
    if (!range) return { success: true, url: '', hasSelection: false };
    let url = '';
    try {
      const xText = range.getText();
      const cur = xText.createTextCursorByRange(range);
      const v = cur.getPropertyValue('HyperLinkURL');
      if (typeof v === 'string') url = v;
    } catch (e) {} // mixed/none over the span → treat as no link
    return { success: true, url: url, hasSelection: (range.getString() || '').length > 0, text: range.getString() };
  },
  // set a hyperlink on the current (visible) selection — the WPS-era
  // setHyperlinkAtRange, minus integer offsets: the selection IS the range.
  set_selection_hyperlink(p) {
    const url = String(p.url || '');
    if (!url) return { success: false, message: 'set_selection_hyperlink requires {url}' };
    const sel = ctrl.getSelection();
    let range = null;
    try {
      if (sel && typeof sel.getByIndex === 'function' && sel.getCount() > 0) range = sel.getByIndex(0);
    } catch (e) {}
    const text = range ? (range.getString() || '') : '';
    if (!range || !text.length) return { success: false, message: 'no selection to hyperlink' };
    const xText = range.getText();
    const cur = xText.createTextCursorByRange(range);
    cur.setPropertyValue('HyperLinkURL', url);
    return { success: true, text: text, url: url };
  },
  // insert {text} at the view cursor wrapped in a named bookmark, optionally
  // hyperlinked — the WPS-era insertEvidenceLink/insertTextWithBookmark (网核
  // 证据标记). Same insert shape as var_insert (bookmark spans the inserted run).
  insert_link_with_bookmark(p) {
    const text = String(p.text || '');
    if (!text) return { success: false, message: 'insert_link_with_bookmark requires {text}' };
    const requested = String(p.bookmarkName || 'MARK_' + Date.now()).replace(/[^A-Za-z0-9_]/g, '_');
    const url = String(p.url || '');
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    const xText = vc.getText();
    const cur = xText.createTextCursorByRange(vc.getEnd());
    xText.insertString(cur, text, true); // bAbsorb: cur spans the inserted text
    if (url) cur.setPropertyValue('HyperLinkURL', url);
    const bms = xModel.getBookmarks();
    let name = requested, n = 0;
    while (bms.hasByName(name)) name = requested + '_' + (++n);
    const bm = xModel.createInstance('com.sun.star.text.Bookmark');
    bm.setName(name);
    xText.insertTextContent(cur, bm, true);
    try { vc.gotoRange(cur.getEnd(), false); } catch (e) {}
    return Object.assign({ success: true, bookmarkName: name, text: text, url: url }, verifySnapshot());
  },
  // read the hyperlink URL at the (collapsed) view cursor — the click-to-open
  // seam: LO WASM does NOT surface hyperlink activation (no window.open, real-
  // machine verified on v0.7.1), so the editor page listens for canvas clicks
  // and asks the worker what link the cursor landed in. Non-collapsed cursor
  // (drag-selection / double-click) returns '' on purpose — only a plain
  // positioning click opens a link.
  get_hyperlink_at_cursor() {
    const vc = ctrl.getViewCursor();
    try { if ((vc.getString() || '').length > 0) return { success: true, url: '' }; } catch (e) {}
    let url = '';
    try {
      const v = vc.getPropertyValue('HyperLinkURL');
      if (typeof v === 'string') url = v;
    } catch (e) {}
    if (!url) {
      // cursor may sit at the run boundary: peek one char to the right
      try {
        const xText = vc.getText();
        const cur = xText.createTextCursorByRange(vc.getStart());
        if (cur.goRight(1, true)) {
          const v2 = cur.getPropertyValue('HyperLinkURL');
          if (typeof v2 === 'string') url = v2;
        }
      } catch (e) {}
    }
    return { success: true, url: url };
  },
  // insert an image (data URL / raw base64) at the view cursor — the WPS-era
  // insertImage. Bytes go JS→UNO through SequenceInputStream (same signed-Array
  // marshalling as load_document), GraphicProvider decodes, TextGraphicObject
  // anchors AS_CHARACTER at the cursor. Oversized images are scaled to page width.
  insert_image(p) {
    const dataUrl = String(p.dataUrl || p.base64 || '');
    const m = dataUrl.match(/^data:[^,]*;base64,(.*)$/s);
    const b64 = m ? m[1] : dataUrl;
    if (!b64) return { success: false, message: 'insert_image requires {dataUrl|base64}' };
    let bin;
    try { bin = atob(b64.replace(/\s+/g, '')); } catch (e) { return { success: false, message: 'invalid base64: ' + errStr(e) }; }
    if (!bin.length) return { success: false, message: 'empty image data' };
    const u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    // signed plain Array — the ONLY shape zeta.js marshals into sequence<byte>
    const bytes = Array.from(new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength));
    const stream = css.io.SequenceInputStream.createStreamFromSequence(context, bytes);
    const gp = css.graphic.GraphicProvider.create(context);
    const graphic = gp.queryGraphic([mkProp('InputStream', stream)]);
    if (!graphic) return { success: false, message: 'GraphicProvider could not decode the image' };
    const img = xModel.createInstance('com.sun.star.text.TextGraphicObject');
    img.setPropertyValue('Graphic', graphic);
    img.setPropertyValue('AnchorType', css.text.TextContentAnchorType.AS_CHARACTER);
    // natural size (1/100 mm), fall back to pixels @96dpi; cap to ~15cm text width
    let w = 0, h = 0;
    try { const sz = graphic.getPropertyValue('Size100thMM'); w = sz.Width || 0; h = sz.Height || 0; } catch (e) {}
    if (!w || !h) {
      try { const px = graphic.getPropertyValue('SizePixel'); w = Math.round((px.Width || 0) * 2540 / 96); h = Math.round((px.Height || 0) * 2540 / 96); } catch (e) {}
    }
    if (w > 0 && h > 0) {
      const MAX_W = 15000;
      if (w > MAX_W) { h = Math.round(h * MAX_W / w); w = MAX_W; }
      img.setPropertyValue('Width', w);
      img.setPropertyValue('Height', h);
    }
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    vc.getText().insertTextContent(vc, img, false);
    try { vc.collapseToEnd(); } catch (e) {}
    return { success: true, bytes: u8.length, width: w, height: h };
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
