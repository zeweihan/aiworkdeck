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

// ---- 修订作者署名 ----------------------------------------------------------
// 修订（redline）的作者名取自 UserProfile 配置（givenname+sn）。AI 发起的编辑
// 统一署名 AI_AUTHOR，用户本人的操作（IME 输入、快捷键）署用户名——同一引擎里
// 两种来源的修订在修订面板中可区分（用户需求）。execCommand 按 __agent 标记
// 在每条命令前切换；setRedlineAuthor 有同值短路，切换才真正写配置。
const AI_AUTHOR = 'AI Workdeck';
let humanAuthor = '';        // load_document 时由宿主传入（当前登录用户名）
let currentRedlineAuthor = null;
function setRedlineAuthor(name) {
  const n = String(name == null ? '' : name);
  if (currentRedlineAuthor === n) return;
  const provider = context.getServiceManager().createInstanceWithContext(
    'com.sun.star.configuration.ConfigurationProvider', context);
  const access = provider.createInstanceWithArguments(
    'com.sun.star.configuration.ConfigurationUpdateAccess',
    [mkProp('nodepath', '/org.openoffice.UserProfile/Data')]);
  access.replaceByName('givenname', n);
  access.replaceByName('sn', '');
  access.commitChanges();
  currentRedlineAuthor = n;
}

// ---- 应用配色：深绿画布上漂浮浅色纸页（产品 UI 深色化） ---------------------
// 纸页周围背景（AppBackground）与纸色（DocColor）是 LO 应用程序颜色，存在
// /org.openoffice.Office.UI/ColorScheme 注册表里，用与 setRedlineAuthor 相同的
// ConfigurationUpdateAccess 机制运行时写入——不重烧引擎，工具栏保留引擎默认浅色。
// 应用级配置，boot 写一次即可（load_document retarget 换的是文档模型，不影响）。
// 失败静默降级：引擎保持默认白底，宿主侧深色壳依然成立，不阻塞 boot。
const APP_BACKGROUND_COLOR = 0x1D3A29; // 画布深绿（对齐官网原型）
const DOC_PAPER_COLOR = 0xFCFBF8;      // 纸页暖白
function applyAppColorScheme() {
  try {
    const provider = context.getServiceManager().createInstanceWithContext(
      'com.sun.star.configuration.ConfigurationProvider', context);
    const access = provider.createInstanceWithArguments(
      'com.sun.star.configuration.ConfigurationUpdateAccess',
      [mkProp('nodepath', '/org.openoffice.Office.UI/ColorScheme')]);
    // 层级：ColorScheme/CurrentColorScheme（字符串）指向 ColorScheme/ColorSchemes
    // 集合里的方案节点；方案节点下每个 UI 元素（AppBackground/DocColor/…）是一个
    // 组节点，含 int 属性 Color。默认注册表里方案节点可能不存在（惰性创建），
    // 缺则经集合节点的 XSingleServiceFactory 建一个再写。
    let cur = '';
    try { cur = String(access.getByName('CurrentColorScheme') || ''); } catch (e) {}
    if (!cur) cur = 'LibreOffice';
    const schemes = access.getByName('ColorSchemes');
    if (!schemes.hasByName(cur)) schemes.insertByName(cur, schemes.createInstance());
    const scheme = schemes.getByName(cur);
    scheme.getByName('AppBackground').replaceByName('Color', APP_BACKGROUND_COLOR);
    scheme.getByName('DocColor').replaceByName('Color', DOC_PAPER_COLOR);
    try { access.replaceByName('CurrentColorScheme', cur); } catch (e) {}
    access.commitChanges();
    log('应用配色已写入 / app color scheme applied (' + cur + '): AppBackground=#1D3A29 DocColor=#FCFBF8');
  } catch (e) {
    console.warn('[office_thread] 应用配色写入失败，保持引擎默认浅色 / color scheme write failed: ' + errStr(e));
    log('应用配色写入失败（降级为默认浅色）/ color scheme failed: ' + errStr(e));
  }
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
function pad2(n) { return (n < 10 ? '0' : '') + n; }
// ---- 审阅面板（修订/批注）的取址助手 ---------------------------------------
// redline 枚举没有按索引取的接口，面板的 index 就是枚举顺序（每次操作后宿主
// 会重新拉清单，处置一条后索引会前移——这是刻意的：面板即时刷新即可）。
function redlineAt(index) {
  const want = Number(index);
  if (!(want >= 0)) return null;
  try {
    const en = xModel.getRedlines().createEnumeration();
    let i = 0;
    while (en.hasMoreElements()) { const r = en.nextElement(); if (i++ === want) return r; }
  } catch (e) {}
  return null;
}
function countRedlines() {
  let n = 0;
  try { const en = xModel.getRedlines().createEnumeration(); while (en.hasMoreElements()) { en.nextElement(); n++; } } catch (e) {}
  return n;
}
// 把视图光标摆到 .uno:Accept/RejectTrackedChange 能命中的位置。**两种修订
// 类型要求相反的摆法**（真机探针逐一试出来的，别凭直觉改）：
//   - 插入型：文本在正文流里，光标必须**跨选**整个区间才命中；
//   - 删除型：页边模式下删除文本不在正文流，必须**塌陷**到区间起点；跨选
//     反而落进那段隐藏文本、dispatch 打空。
// 摆错不会报错——dispatch 静默不生效，甚至凭空多出一条空插入修订，所以调用
// 方（resolve_revision）一律用条数变化复核。
function selectRedlineRange(r, forDispatch) {
  try {
    const rs = r.getPropertyValue('RedlineStart'), re = r.getPropertyValue('RedlineEnd');
    if (!rs || !re) return false;
    let isDelete = false;
    try { isDelete = String(r.getPropertyValue('RedlineType')) === 'Delete'; } catch (e) {}
    const vc = ctrl.getViewCursor();
    vc.gotoRange(rs, false);
    if (!(forDispatch && isDelete)) vc.gotoRange(re, true);
    return true;
  } catch (e) { return false; }
}
function countComments() {
  let n = 0;
  try {
    const en = xModel.getTextFields().createEnumeration();
    while (en.hasMoreElements()) {
      const f = en.nextElement();
      if (f.supportsService && f.supportsService('com.sun.star.text.textfield.Annotation')) n++;
    }
  } catch (e) {}
  return n;
}
function commentAt(index) {
  const want = Number(index);
  if (!(want >= 0)) return null;
  try {
    const en = xModel.getTextFields().createEnumeration();
    let i = 0;
    while (en.hasMoreElements()) {
      const f = en.nextElement();
      if (!(f.supportsService && f.supportsService('com.sun.star.text.textfield.Annotation'))) continue;
      if (i++ === want) return f;
    }
  } catch (e) {}
  return null;
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

// ---- 最小修订颗粒度 (minimal redline granularity) ---------------------------
// range.setString(newText) under RecordChanges marks the WHOLE range deleted
// and the WHOLE new text inserted — so a one-char edit in a clause reads as
// "删整段、加整段". minimalEdits computes a char-level minimal edit script
// (common prefix/suffix trim + bounded LCS) and applyMinimalRedline applies
// ONLY the differing runs, so the redline the user reviews is 删"爱"加"恨",
// not 删"我爱你"加"我恨你".
// Returns [{start, delLen, insText}] in OLD-string coordinates, ordered
// RIGHT-TO-LEFT (descending start) so applying them in order never shifts the
// offsets of the edits still pending.
function minimalEdits(oldStr, newStr) {
  const oLen = oldStr.length, nLen = newStr.length;
  let p = 0;
  const maxP = Math.min(oLen, nLen);
  while (p < maxP && oldStr.charCodeAt(p) === newStr.charCodeAt(p)) p++;
  let s = 0;
  while (s < maxP - p && oldStr.charCodeAt(oLen - 1 - s) === newStr.charCodeAt(nLen - 1 - s)) s++;
  const oMid = oldStr.slice(p, oLen - s), nMid = newStr.slice(p, nLen - s);
  if (!oMid && !nMid) return [];
  // Pure insert/delete, or a middle too big for the DP (500x500 chars — far
  // beyond any single clause edit): one contiguous replace of the trimmed
  // middle is already minimal enough.
  if (!oMid || !nMid || oMid.length * nMid.length > 250000) {
    return [{ start: p, delLen: oMid.length, insText: nMid }];
  }
  // LCS DP over the trimmed middle (lengths <= 500, so Uint16 lengths are safe).
  const m = oMid.length, n = nMid.length, W = n + 1;
  const dp = new Uint16Array((m + 1) * W);
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      dp[i * W + j] = oMid.charCodeAt(i - 1) === nMid.charCodeAt(j - 1)
        ? dp[(i - 1) * W + (j - 1)] + 1
        : Math.max(dp[(i - 1) * W + j], dp[i * W + (j - 1)]);
    }
  }
  // Backtrack from the end, coalescing adjacent del+ins into single replaces.
  const edits = [];
  let i = m, j = n, curDel = 0, curIns = '';
  const flush = function (atOld) {
    if (curDel || curIns) { edits.push({ start: p + atOld, delLen: curDel, insText: curIns }); curDel = 0; curIns = ''; }
  };
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oMid.charCodeAt(i - 1) === nMid.charCodeAt(j - 1)) {
      flush(i); i--; j--;
    } else if (j > 0 && (i === 0 || dp[i * W + (j - 1)] >= dp[(i - 1) * W + j])) {
      curIns = nMid.charAt(j - 1) + curIns; j--;
    } else {
      curDel++; i--;
    }
  }
  flush(0);
  // Cleanup: a SINGLE stray equal char sandwiched between two edits (LCS 在中文
  // 里常捞到巧合的"的/、"之类) makes choppy, confusing redlines — fold it into
  // one combined edit. edits is descending; [k+1] is the LEFT neighbor.
  for (let k = 0; k + 1 < edits.length; ) {
    const right = edits[k], left = edits[k + 1];
    const gap = right.start - (left.start + left.delLen);
    if (gap >= 0 && gap <= 1) {
      left.insText = left.insText + oldStr.slice(left.start + left.delLen, right.start) + right.insText;
      left.delLen = left.delLen + gap + right.delLen;
      edits.splice(k, 1);
    } else k++;
  }
  return edits;
}
// Apply newText onto range as char-granular tracked edits. Returns true when
// handled (caller must NOT also setString), false when the caller should fall
// back to its existing whole-range path. Callers ensure RecordChanges is on.
function applyMinimalRedline(range, newText) {
  let oldText;
  try { oldText = String(range.getString()); } catch (e) { return false; }
  const txt = String(newText == null ? '' : newText);
  // Fallbacks: paragraph breaks (getString/goRight 对段界的计数口径不一) and
  // surrogate pairs (goRight steps by engine chars, not JS code units).
  if (/[\r\n\uD800-\uDFFF]/.test(oldText) || /[\r\n\uD800-\uDFFF]/.test(txt)) return false;
  if (oldText === txt) return true; // 无差异：不留任何修订痕迹
  if (!oldText.length) return false; // pure insert — caller's path is fine
  const edits = minimalEdits(oldText, txt);
  if (edits.length === 1 && edits[0].delLen === oldText.length && edits[0].start === 0) return false; // 全量替换，没有更细的
  let applied = 0;
  try {
    const t = range.getText();
    // Collapsed at range start; edits run right-to-left, so text left of each
    // remaining edit — including this position — never moves.
    const startCur = t.createTextCursorByRange(range.getStart());
    for (let k = 0; k < edits.length; k++) {
      const e = edits[k];
      const cur = t.createTextCursorByRange(startCur.getStart());
      if (e.start > 0 && !cur.goRight(e.start, false)) throw new Error('goRight(' + e.start + ') failed');
      if (e.delLen > 0) {
        if (!cur.goRight(e.delLen, true)) throw new Error('goRight select(' + e.delLen + ') failed');
        cur.setString(e.insText); // 删差异段 + 插新段，一条替换型修订
      } else if (e.insText) {
        t.insertString(cur, e.insText, false);
      }
      applied++;
    }
    return true;
  } catch (e) {
    log('applyMinimalRedline: ' + errStr(e) + ' (applied ' + applied + '/' + edits.length + ')');
    // Nothing applied yet -> safe to let the caller setString the whole range.
    // Partially applied -> falling back would DOUBLE-EDIT; report handled and
    // let the caller's paragraphAfterEdit verification loop catch any residue.
    return applied > 0;
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
  // (2nd sweep) the rest of the desktop key surface — all engine-verified:
  line_break: '.uno:InsertLinebreak',                     // Shift+Enter 软回车
  word_left: '.uno:GoToPrevWord', word_right: '.uno:GoToNextWord',
  word_left_sel: '.uno:WordLeftSel', word_right_sel: '.uno:WordRightSel',
  line_start_sel: '.uno:StartOfLineSel', line_end_sel: '.uno:EndOfLineSel',
  escape: '.uno:Escape',                                  // 取消选区
  page_up: '.uno:PageUp', page_down: '.uno:PageDown',
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

// ---- 标准格式（house style）与 markdown 剥离写入 -----------------------------
// 用户规范：正文中文楷体_GB2312 / 西文 Arial、段前 0 磅段后 18 磅、行距最小值
// 16 磅、首行缩进 2 字符、全文两端对齐、黑色；主标题 16 磅加粗居中；其余标题与
// 正文同款但加粗；表格 Grid 实线 1.5 磅、10 号字、单元格段前后 0.2 行、行距最小
// 值 12 磅、首行加粗且水平垂直居中、其余行垂直居中水平居左（纯数字居右）；紧跟
// 表格的第一个段落段前 18 磅。写入文档一律剥离 markdown 标记（#、**、|、- 等），
// 转成真实格式。引擎是 LO 24.2.8（26.2 才有 MD4C 原生 markdown 导入，搬不动），
// 所以在 worker 内做行级转换；后端 write_docx 走 flexmark（DocxStyleHelper 对齐
// 同一套规范），两条生成路径规范一致。
function ptToMm100(pt) { return Math.round(Number(pt) * 2540 / 72); }
// UNO 枚举值读回归一化：LO 对部分枚举型属性（ParaAdjust 等）getPropertyValue 返回
// 裸 short 数字，而 css.* 枚举成员是 embind 枚举对象（带 .value）——恒等比较必须
// 先归一到数字，否则 set 成功也"读回不等"（e2e 组 14 实锤）。
function unoEnumVal(v) {
  if (v != null && typeof v === 'object' && typeof v.value === 'number') return v.value;
  return v;
}
function enumEq(a, b) { return unoEnumVal(a) === unoEnumVal(b); }
// short 型属性（VertOrient/OutlineLevel 等）必须传带类型的 Any：裸 JS number 会被
// 编组成 long，严格的 UNO setter（>>= sal_Int16）直接拒绝且被 try 吞掉。
function shortAny(n) { return new zetajs.Any(zetajs.type.short, Number(n)); }
const HOUSE = {
  fontWestern: 'Arial', fontAsian: '楷体_GB2312',
  bodyPt: 12, titlePt: 16,
  spaceAfterMm: ptToMm100(18),       // 段后 18 磅
  lineMinMm: ptToMm100(16),          // 行距最小值 16 磅
  indentChars: 2,                    // 首行缩进 2 字符（按正文字号折算）
  tablePt: 10,
  tableParaSpaceMm: ptToMm100(2.4),  // 单元格段前后 0.2 行 ≈ 2.4 磅
  tableLineMinMm: ptToMm100(12),     // 单元格行距最小值 12 磅
  tableBorderMm: 53,                 // 1.5 磅 ≈ 0.53mm（BorderLine2.LineWidth 单位 1/100mm）
  afterTableBeforeMm: ptToMm100(18), // 表格后首段段前 18 磅
};
// 把标准字符属性设到一个 property set（视图光标/文本光标/段落/单元格文本）上。
// 只动传入 opts 声明的维度；weight 每次都设（run 级粗体开关需要确定性）。
function applyHouseChar(ps, opts) {
  const o = opts || {};
  try { ps.setPropertyValue('CharFontName', HOUSE.fontWestern); } catch (e) {}
  try { ps.setPropertyValue('CharFontNameAsian', HOUSE.fontAsian); } catch (e) {}
  try { ps.setPropertyValue('CharFontNameComplex', HOUSE.fontWestern); } catch (e) {}
  try { setCharProp(ps, 'CharHeight', o.sizePt || HOUSE.bodyPt); } catch (e) {}
  if (!o.keepWeight) {
    try { setCharProp(ps, 'CharWeight', o.bold ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL); } catch (e) {}
  }
  try { ps.setPropertyValue('CharColor', 0x000000); } catch (e) {}
}
// 标准段落属性。kind: 'title' | 'body' | 'heading' | 'list' | 'tableCell'。
// heading 与 body 同款（规范：小标题与正文一样但加粗），title/list/tableCell 不缩进。
function applyHousePara(ps, kind, opts) {
  const o = opts || {};
  const isTitle = kind === 'title';
  const isCell = kind === 'tableCell';
  try { ps.setPropertyValue('ParaAdjust', isTitle ? css.style.ParagraphAdjust.CENTER : css.style.ParagraphAdjust.BLOCK); } catch (e) {}
  try { ps.setPropertyValue('ParaTopMargin', isCell ? HOUSE.tableParaSpaceMm : (o.afterTable ? HOUSE.afterTableBeforeMm : 0)); } catch (e) {}
  try { ps.setPropertyValue('ParaBottomMargin', isCell ? HOUSE.tableParaSpaceMm : HOUSE.spaceAfterMm); } catch (e) {}
  try {
    ps.setPropertyValue('ParaLineSpacing', new css.style.LineSpacing({
      Mode: css.style.LineSpacingMode.MINIMUM,
      Height: isCell ? HOUSE.tableLineMinMm : HOUSE.lineMinMm,
    }));
  } catch (e) {}
  const indent = (isTitle || isCell || kind === 'list') ? 0 : ptToMm100(HOUSE.indentChars * HOUSE.bodyPt);
  try { ps.setPropertyValue('ParaFirstLineIndent', indent); } catch (e) {}
  if (!isCell) {
    try { ps.setPropertyValue('ParaLeftMargin', 0); ps.setPropertyValue('ParaRightMargin', 0); } catch (e) {}
  }
}
// markdown 行内标记 → 带样式 run 列表：**粗体**、*斜体*、`代码`（剥壳）、[文字](url)。
// 下划线变体（_、__）在法律文本里误伤率高，故意不识别。
const INLINE_MD_RE = /(\*\*[^*\n]+\*\*|\*[^*\n]+\*|`[^`\n]+`|\[[^\]\n]+\]\([^)\s]+\))/g;
function parseInlineRuns(s) {
  const runs = [];
  const parts = String(s).split(INLINE_MD_RE);
  for (let i = 0; i < parts.length; i++) {
    const t = parts[i];
    if (!t) continue;
    let m;
    if ((m = t.match(/^\*\*([^*\n]+)\*\*$/))) runs.push({ text: m[1], bold: true });
    else if ((m = t.match(/^\*([^*\n]+)\*$/))) runs.push({ text: m[1], italic: true });
    else if ((m = t.match(/^`([^`\n]+)`$/))) runs.push({ text: m[1] });
    else if ((m = t.match(/^\[([^\]\n]+)\]\(([^)\s]+)\)$/))) runs.push({ text: m[1], url: m[2] });
    else runs.push({ text: t });
  }
  return runs;
}
function stripInlineMd(s) {
  return parseInlineRuns(s).map(function (r) { return r.text; }).join('');
}
// 在视图光标处写入一串带样式 run（打字模型：用临时文本光标 bAbsorb 插入后对
// 选中区间设属性，确定性优于依赖 automatic char props）。
function writeStyledRuns(vc, runs, kindOpts) {
  const xText = vc.getText();
  const o = kindOpts || {};
  for (let i = 0; i < runs.length; i++) {
    const run = runs[i];
    if (!run.text) continue;
    const cur = xText.createTextCursorByRange(vc.getEnd());
    xText.insertString(cur, run.text, true); // bAbsorb: cur 跨住刚插入的文本
    applyHouseChar(cur, { sizePt: o.sizePt, bold: o.bold || !!run.bold });
    try { setCharProp(cur, 'CharPosture', run.italic ? css.awt.FontSlant.ITALIC : css.awt.FontSlant.NONE); } catch (e) {}
    if (run.url) { try { cur.setPropertyValue('HyperLinkURL', run.url); } catch (e) {} }
    try { vc.gotoRange(cur.getEnd(), false); } catch (e) {}
  }
}
// 行内模式插入：剥离 markdown 标记（行首 #/>、行内 **/*/`/链接），粗体斜体转成
// 真格式，但**不动**字体/字号/颜色——往已有文档里插内容要沿用现场格式，不能把
// house 字体强加进人家的文档。多行按段落分隔符处理。
function writeInlineRuns(vc, runs) {
  const xText = vc.getText();
  for (let i = 0; i < runs.length; i++) {
    const run = runs[i];
    if (!run.text) continue;
    const cur = xText.createTextCursorByRange(vc.getEnd());
    xText.insertString(cur, run.text, true);
    if (run.bold) { try { setCharProp(cur, 'CharWeight', css.awt.FontWeight.BOLD); } catch (e) {} }
    if (run.italic) { try { setCharProp(cur, 'CharPosture', css.awt.FontSlant.ITALIC); } catch (e) {} }
    if (run.url) { try { cur.setPropertyValue('HyperLinkURL', run.url); } catch (e) {} }
    try { vc.gotoRange(cur.getEnd(), false); } catch (e) {}
  }
}
function insertInlineStyled(vc, text) {
  const xText = vc.getText();
  const lines = String(text).split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (i > 0) xText.insertControlCharacter(vc, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
    const isHeading = /^\s*#{1,6}\s+/.test(lines[i]);
    const line = lines[i].replace(/^\s*#{1,6}\s+/, '').replace(/^\s*>\s?/, '');
    if (!line) continue;
    const runs = parseInlineRuns(line);
    // 原 # 标题行降级为"整行加粗"（规范：小标题与正文一样但加粗）
    if (isHeading) for (let k = 0; k < runs.length; k++) runs[k].bold = true;
    writeInlineRuns(vc, runs);
  }
}
// 文本里是否带 markdown 标记（插入路径按此决定走剥离转换还是原样插入）
const MD_MARKER_RE = /(\*\*[^*\n]+\*\*|\*[^*\n]+\*|`[^`\n]+`|\[[^\]\n]+\]\([^)\s]+\)|^\s*#{1,6}\s|^\s*>\s)/m;

// 编号规则预设。preset: 'bullet' | 'decimal' | 'chinese' | 'multilevel'。
// replaceByIndex 收 sequence<PropertyValue>（zetajs 编组：纯 Array of PropertyValue）。
function makeNumberingRules(preset) {
  // 常量组带数值兜底：zetajs 常量组个别项缺失时 undefined 进 PropertyValue 会编组失败
  const NT = css.style.NumberingType || {};
  const ARABIC = NT.ARABIC != null ? NT.ARABIC : 4;
  const CHAR_SPECIAL = NT.CHAR_SPECIAL != null ? NT.CHAR_SPECIAL : 6;
  const NUMBER_LOWER_ZH = NT.NUMBER_LOWER_ZH != null ? NT.NUMBER_LOWER_ZH : 15;
  const rules = xModel.createInstance('com.sun.star.text.NumberingRules');
  const count = Math.min(rules.getCount(), 9);
  for (let lvl = 0; lvl < count; lvl++) {
    const props = [];
    if (preset === 'bullet') {
      props.push(mkProp('NumberingType', CHAR_SPECIAL));
      props.push(mkProp('BulletChar', '•'));
    } else if (preset === 'chinese') {
      props.push(mkProp('NumberingType', NUMBER_LOWER_ZH));
      props.push(mkProp('Suffix', '、')); // 一、二、
    } else if (preset === 'multilevel') {
      props.push(mkProp('NumberingType', ARABIC));
      props.push(mkProp('Suffix', '.'));
      if (lvl > 0) props.push(mkProp('ParentNumbering', lvl + 1)); // 1.1 / 1.1.1
    } else { // decimal
      props.push(mkProp('NumberingType', ARABIC));
      props.push(mkProp('Suffix', '.'));
    }
    props.push(mkProp('LeftMargin', ptToMm100(18) * (lvl + 1)));
    props.push(mkProp('FirstLineOffset', -ptToMm100(18)));
    rules.replaceByIndex(lvl, props);
  }
  return rules;
}
// 定位光标/选区所在表格（或按 tableIndex 取第 N 张）。不在表格内返回 null。
function currentTextTable(p) {
  if (p && p.tableIndex != null) {
    const ts = xModel.getTextTables();
    const i = Number(p.tableIndex);
    if (i >= 0 && i < ts.getCount()) return ts.getByIndex(i);
    return null;
  }
  try {
    const t = ctrl.getViewCursor().getPropertyValue('TextTable');
    if (t) return t;
  } catch (e) {}
  return null;
}
// 单元格名工具（A1..Z9、AA1..）：markdown 表格列数很小，两位字母够用。
function cellName(col, row) {
  let name = '';
  if (col >= 26) { name += String.fromCharCode(65 + Math.floor(col / 26) - 1); col = col % 26; }
  name += String.fromCharCode(65 + col);
  return name + (row + 1);
}
const NUMERIC_CELL_RE = /^[-+（(]?[\d][\d,.，%．]*[%）)]?$/;
// 给一张表设标准边框（Grid 实线，全部内外框线同宽）。widthMm 单位 1/100mm。
function applyTableBorders(table, widthMm, color) {
  const solid = (css.table.BorderLineStyle && css.table.BorderLineStyle.SOLID != null)
    ? css.table.BorderLineStyle.SOLID : 0;
  const bl = new css.table.BorderLine2({
    Color: color == null ? 0x000000 : color,
    InnerLineWidth: 0, OuterLineWidth: 0, LineDistance: 0,
    LineStyle: solid,
    LineWidth: widthMm,
  });
  const tb = new css.table.TableBorder2({
    TopLine: bl, BottomLine: bl, LeftLine: bl, RightLine: bl,
    HorizontalLine: bl, VerticalLine: bl,
    IsTopLineValid: true, IsBottomLineValid: true, IsLeftLineValid: true,
    IsRightLineValid: true, IsHorizontalLineValid: true, IsVerticalLineValid: true,
    Distance: 0, IsDistanceValid: false,
  });
  table.setPropertyValue('TableBorder2', tb);
}
// 按标准格式刷一张已存在的表：字号/段距/行距/垂直居中/首行加粗居中/数字居右/边框。
// keepWeight: 非首行不动原有粗体（apply_house_style 走这里，避免抹掉当事人加粗）。
function styleTableStandard(table, opts) {
  const o = opts || {};
  const headerRows = o.headerRows != null ? o.headerRows : 1;
  try { applyTableBorders(table, HOUSE.tableBorderMm, 0x000000); } catch (e) { log('表格边框设置失败 / borders: ' + errStr(e)); }
  const names = table.getCellNames();
  for (let i = 0; i < names.length; i++) {
    const cell = table.getCellByName(names[i]);
    const rowIdx = parseInt(String(names[i]).replace(/^[A-Z]+/, ''), 10) - 1;
    const isHeader = rowIdx < headerRows;
    try { cell.setPropertyValue('VertOrient', shortAny(css.text.VertOrientation.CENTER)); } catch (e) {}
    try {
      const ct = cell.createTextCursor();
      ct.gotoStart(false);
      ct.gotoEnd(true);
      applyHousePara(ct, 'tableCell');
      applyHouseChar(ct, { sizePt: HOUSE.tablePt, bold: isHeader, keepWeight: !isHeader && o.keepWeight });
      const text = (cell.getString() || '').trim();
      const adjust = isHeader ? css.style.ParagraphAdjust.CENTER
        : (NUMERIC_CELL_RE.test(text) ? css.style.ParagraphAdjust.RIGHT : css.style.ParagraphAdjust.LEFT);
      ct.setPropertyValue('ParaAdjust', adjust);
    } catch (e) {}
  }
}
// 在视图光标处插入一张按标准格式排好的表。rows: string[][]。返回表对象。
function insertStyledTable(rows, headerRows) {
  const nRows = rows.length;
  let nCols = 1;
  for (let i = 0; i < nRows; i++) nCols = Math.max(nCols, rows[i].length);
  const vc = ctrl.getViewCursor();
  vc.collapseToEnd();
  const table = xModel.createInstance('com.sun.star.text.TextTable');
  table.initialize(nRows, nCols);
  vc.getText().insertTextContent(vc, table, false);
  for (let r = 0; r < nRows; r++) {
    for (let c = 0; c < nCols; c++) {
      const raw = rows[r][c] != null ? String(rows[r][c]) : '';
      try { table.getCellByName(cellName(c, r)).setString(stripInlineMd(raw)); } catch (e) {}
    }
  }
  styleTableStandard(table, { headerRows: headerRows });
  // 光标移到表后第一个段落。不能用 table.getAnchor().getEnd()——真机实证它落进
  // A1 单元格，后续段落全写进表格里（e2e 组 15 抓到的 bug）。改为按表名在正文
  // 枚举里找到该表，跳到它后面的第一个段落（在光标所在空段插表时 Writer 会把
  // 该空段留在表后，所以段落必然存在）。
  cursorToParagraphAfterTable(table);
  return table;
}
function cursorToParagraphAfterTable(table) {
  let name = '';
  try { name = table.getName(); } catch (e) {}
  const vc = ctrl.getViewCursor();
  let found = false, placed = false;
  try {
    const en = xModel.getText().createEnumeration();
    while (en.hasMoreElements()) {
      const el = en.nextElement();
      if (found && el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) {
        vc.gotoRange(el.getStart(), false);
        placed = true;
        break;
      }
      if (!found && el.supportsService && el.supportsService('com.sun.star.text.TextTable')) {
        try { if (el.getName() === name) found = true; } catch (e) {}
      }
    }
  } catch (e) {}
  if (!placed) { try { vc.gotoEnd(false); } catch (e) {} }
}

// ---- Calc（电子表格 sheet_*）原语 helpers ------------------------------------
// 引擎含 Calc 模块（probe_modules 实锤），xlsx 经 load_document 正常打开，但
// doc_* 一族全走 xModel.getText()（Writer 专属），在 Calc 文档上必然失败——
// sheet_* 是 Calc 文档的对等原语集。文档类型守卫集中在 resolveSheet。
function isCalcDoc() {
  try { return !!(xModel && xModel.supportsService && xModel.supportsService('com.sun.star.sheet.SpreadsheetDocument')); }
  catch (e) { return false; }
}
const NOT_SPREADSHEET_MSG = '当前打开的不是电子表格文档：sheet_* 原语仅对 xlsx/xls 生效。Word 文档请用 doc_* 原语；要操作表格文件请先用 doc_open_file 打开它。';
// 解析 sheet 参数（工作表名或 0 开始的序号；缺省 = 当前活动工作表）。
// 返回 {sheet} 或 {error}。命中非活动工作表时切为活动（拟人：用户看得见操作在哪张表）。
function resolveSheet(p) {
  if (!isCalcDoc()) return { error: NOT_SPREADSHEET_MSG };
  const sheets = xModel.getSheets();
  let sheet = null;
  const want = p && p.sheet != null && String(p.sheet).trim() !== '' ? String(p.sheet).trim() : null;
  if (want == null) {
    try { sheet = ctrl.getActiveSheet(); } catch (e) {}
    if (!sheet) sheet = sheets.getByIndex(0);
  } else if (/^\d+$/.test(want)) {
    const i = Number(want);
    if (i >= sheets.getCount()) return { error: '工作表序号越界: ' + i + '（共 ' + sheets.getCount() + ' 张，0 开始）' };
    sheet = sheets.getByIndex(i);
  } else {
    if (!sheets.hasByName(want)) return { error: '工作表不存在: ' + want };
    sheet = sheets.getByName(want);
  }
  try {
    const active = ctrl.getActiveSheet();
    if (!active || active.getName() !== sheet.getName()) ctrl.setActiveSheet(sheet);
  } catch (e) {}
  return { sheet: sheet };
}
function sheetRange(sheet, rangeStr) {
  try { return sheet.getCellRangeByName(String(rangeStr)); } catch (e) { return null; }
}
function colLetterOf(n) { // 0 -> A, 25 -> Z, 26 -> AA
  let s = '';
  n = Number(n);
  do { s = String.fromCharCode(65 + (n % 26)) + s; n = Math.floor(n / 26) - 1; } while (n >= 0);
  return s;
}
function sheetRangeName(addr) {
  const a = colLetterOf(addr.StartColumn) + (addr.StartRow + 1);
  const b = colLetterOf(addr.EndColumn) + (addr.EndRow + 1);
  return a === b ? a : a + ':' + b;
}
function usedRangeAddress(sheet) {
  const cur = sheet.createCursor();
  cur.gotoStartOfUsedArea(false);
  cur.gotoEndOfUsedArea(true);
  return cur.getRangeAddress();
}
// 单元格读值归一：空→''，数值→number，公式→数值结果给 number、文本结果给 string。
// FormulaResultType 是 long 常量组（FormulaResult.VALUE=1），走 unoEnumVal 兜底。
function readCellOut(cell) {
  const T = css.table.CellContentType;
  const t = cell.getType();
  if (enumEq(t, T.EMPTY)) return '';
  if (enumEq(t, T.VALUE)) return cell.getValue();
  if (enumEq(t, T.FORMULA)) {
    // 出错的公式格 getValue() 返回 0、结果类型也可能标 VALUE——先查错误码，
    // 否则解析失败的公式在读回里伪装成 0，AI 无从发现（组 17 抽查实证）。
    try { const err = cell.getError(); if (err) return cell.getString() || ('Err:' + err); } catch (e) {}
    try {
      const FR_VALUE = (css.sheet && css.sheet.FormulaResult && css.sheet.FormulaResult.VALUE != null) ? css.sheet.FormulaResult.VALUE : 1;
      if (unoEnumVal(cell.getPropertyValue('FormulaResultType')) === unoEnumVal(FR_VALUE)) return cell.getValue();
    } catch (e) {}
  }
  return cell.getString();
}
// 写入时把"长得像数字"的字符串落成数值（同 NUMERIC_CELL_RE 的 house 口径，但
// 更严格：前导 0 的编号（如 '001'）保持文本，避免证照号/编号被吞前导零）。
const SHEET_NUMERIC_RE = /^-?(0|[1-9]\d*)(\.\d+)?$/;
// 列标解析：'B'→1、'AA'→26；也接受 1 开始的数字串（'2'→1）。返回 0 开始的列号，无效返回 -1。
function colIndexOf(s) {
  const t = String(s == null ? '' : s).trim().toUpperCase();
  if (/^\d+$/.test(t)) { const n = Number(t); return n >= 1 ? n - 1 : -1; }
  if (!/^[A-Z]{1,3}$/.test(t)) return -1;
  let n = 0;
  for (let i = 0; i < t.length; i++) n = n * 26 + (t.charCodeAt(i) - 64);
  return n - 1;
}
let cfStyleSeq = 0; // 条件格式的"命中样式"（CellStyle）命名序号
// Excel 习惯公式 → setFormula 的 API 文法（真机实证：组 17 抽查）：参数分隔符
// 必须是分号（逗号 Err:508），跨表引用必须是 Sheet.A1（Sheet!A1 报 #NAME?
// Err:525）。AI 与用户都按 Excel 习惯写逗号和 '!'，在此归一化——只动双引号
// 字符串字面量之外的字符（Calc 转义引号是成对 ""，相邻翻转两次天然兼容）。
// 代价：Excel 数组字面量的逗号列分隔与 Calc 交集操作符 '!' 会被误转，两者在
// AI 生成的公式里出现率趋近于零，换取最高频的多参数函数全部可用。
function normalizeFormula(f) {
  let out = '';
  let inStr = false;
  for (let i = 0; i < f.length; i++) {
    const ch = f.charAt(i);
    if (ch === '"') { inStr = !inStr; out += ch; continue; }
    if (!inStr && ch === ',') { out += ';'; continue; }
    if (!inStr && ch === '!') { out += '.'; continue; }
    out += ch;
  }
  return out;
}

// ---- 流式写入状态机：markdown 行级解析 → 标准格式落字 ------------------------
// stream_insert 攒字节、按完整行消费；stream_flush 收尾（写掉尾行/尾表、复位）。
// 状态在 worker 内（宿主只透传 token），文档换人/换流由宿主在 open_sync 时
// stream_flush({discard:true}) 硬复位。
const STREAM = { active: false, buf: '', table: null, afterTable: false, allowTitle: false, wroteTitle: false, listRules: null, listPreset: null };
function streamReset(discard) {
  STREAM.active = false; STREAM.buf = ''; STREAM.table = null; STREAM.afterTable = false;
  STREAM.allowTitle = false; STREAM.wroteTitle = false; STREAM.listRules = null; STREAM.listPreset = null;
  if (!discard) { /* 占位：正常收尾无额外动作 */ }
}
function streamEnsureActive() {
  if (STREAM.active) return;
  streamReset(true);
  STREAM.active = true;
  // 文档近乎空白才允许把第一个 # 当主标题；往已有文档续写时所有标题一律小标题样式
  let len = 0;
  try { len = (xModel.getText().getString() || '').trim().length; } catch (e) {}
  STREAM.allowTitle = len < 5;
}
function streamTableFlush() {
  const t = STREAM.table;
  STREAM.table = null;
  if (!t || !t.rows.length) return;
  try { insertStyledTable(t.rows, t.sawSep ? 1 : 0); STREAM.afterTable = true; }
  catch (e) { log('流式建表失败 / stream table: ' + errStr(e)); }
}
function streamParagraph(runs, kind, headingLevel) {
  const vc = ctrl.getViewCursor();
  vc.collapseToEnd();
  const xText = vc.getText();
  applyHousePara(vc, kind, { afterTable: STREAM.afterTable });
  STREAM.afterTable = false;
  if (headingLevel != null) { try { vc.setPropertyValue('OutlineLevel', shortAny(headingLevel)); } catch (e) {} }
  const sizePt = kind === 'title' ? HOUSE.titlePt : HOUSE.bodyPt;
  writeStyledRuns(vc, runs, { sizePt: sizePt, bold: kind === 'title' || kind === 'heading' });
  xText.insertControlCharacter(vc, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
  vc.collapseToEnd();
  return vc;
}
function streamListItem(text, ordered, level) {
  const preset = ordered ? 'decimal' : 'bullet';
  if (!STREAM.listRules || STREAM.listPreset !== preset) {
    try { STREAM.listRules = makeNumberingRules(preset); STREAM.listPreset = preset; }
    catch (e) { STREAM.listRules = null; STREAM.listPreset = null; }
  }
  const vc = streamParagraph(parseInlineRuns(text), 'list', null);
  // 段落分隔符已插入，编号属性要落在"刚写完的那一段"——退回一段再设
  if (STREAM.listRules) {
    try {
      const cur = vc.getText().createTextCursorByRange(vc.getStart());
      cur.gotoPreviousParagraph(false);
      cur.setPropertyValue('NumberingRules', STREAM.listRules);
      cur.setPropertyValue('NumberingLevel', Math.max(0, Math.min(Number(level) || 0, 8)));
    } catch (e) {}
  }
}
function streamWriteLine(line) {
  const isTableRow = /^\s*\|.*\|\s*$/.test(line);
  if (isTableRow) {
    if (!STREAM.table) STREAM.table = { rows: [], sawSep: false };
    const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(function (s) { return s.trim(); });
    const isSep = cells.length > 0 && cells.every(function (c) { return /^:?-+:?$/.test(c); });
    if (isSep) STREAM.table.sawSep = true;
    else STREAM.table.rows.push(cells);
    return;
  }
  if (STREAM.table) streamTableFlush();
  const trimmed = line.trim();
  if (!trimmed) { STREAM.listRules = null; STREAM.listPreset = null; return; } // 规范：没有额外空行
  if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) return; // 水平分隔线丢弃
  let m;
  if ((m = trimmed.match(/^(#{1,6})\s+(.*)$/))) {
    STREAM.listRules = null; STREAM.listPreset = null;
    const level = m[1].length;
    const text = m[2].replace(/\s*#+\s*$/, '');
    if (level === 1 && STREAM.allowTitle && !STREAM.wroteTitle) {
      STREAM.wroteTitle = true;
      streamParagraph(parseInlineRuns(text), 'title', null);
    } else {
      streamParagraph(parseInlineRuns(text), 'heading', Math.min(level, 9));
    }
    return;
  }
  const indentM = line.match(/^(\s*)/);
  const level = Math.min(Math.floor((indentM ? indentM[1].length : 0) / 2), 8);
  if ((m = trimmed.match(/^[-*+]\s+(.*)$/))) { streamListItem(m[1], false, level); return; }
  if ((m = trimmed.match(/^\d+[.)]\s+(.*)$/))) { streamListItem(m[1], true, level); return; }
  STREAM.listRules = null; STREAM.listPreset = null;
  if ((m = trimmed.match(/^>\s?(.*)$/))) { streamParagraph(parseInlineRuns(m[1]), 'body', null); return; }
  streamParagraph(parseInlineRuns(trimmed), 'body', null);
}

// LO 7.1+ (tdf#34355): tracked DELETIONS render in the page margin next to the
// changed-line mark instead of inline strikethrough — the body stays readable
// (original text + colored insertions only). REQUIRES engine >= 24.2.8-zhcn-r3:
// stock LO paints the margin text left of the anchor's frame, which inside a
// table is the CELL — deleted text landed on the neighboring cell's content.
// r3 carries our frmpaint.cxx patch anchoring at the table frame's left edge
// (desktop/lowa-build/patches). This is a VIEW setting on the controller, not
// the model, so it must be re-applied whenever the controller changes (boot
// AND load_document retarget).
function showDeletionsInMargin() {
  try { ctrl.getViewSettings().setPropertyValue('ShowChangesInMargin', true); }
  catch (e) { log('ShowChangesInMargin 设置失败 / failed: ' + errStr(e)); }
}

// ---- boot: open a fresh BLANK Writer doc ----------------------------------
// Production: a brand-new / empty document must show a clean blank page (the
// host loads real bytes via load_document when the file has content). We do NOT
// seed scaffolding text here — earlier dev-prototype seed text would otherwise
// show through whenever a real load was skipped/failed, looking like the editor
// loaded the wrong content.
function bootDoc() {
  context = zetajs.getUnoComponentContext();
  applyAppColorScheme(); // 首帧前写入，避免白底闪一下再变深
  desktop = css.frame.Desktop.create(context);
  xModel = desktop.loadComponentFromURL('private:factory/swriter', '_default', 0, []);
  ctrl = xModel.getCurrentController();
  try { ctrl.getFrame().getContainerWindow().FullScreen = true; } catch {}
  // RFC v2: revisions default ON — every edit (AI or typed) lands as a tracked
  // change the lawyer can accept/reject. Set once here (and on retarget) instead
  // of per-command, so no edit path can slip through untracked.
  try { xModel.setPropertyValue('RecordChanges', true); } catch {}
  showDeletionsInMargin();

  installKeyHandler();
  try { installModifyListener(xModel); } catch (e) { log('XModifyListener 安装失败 / install failed: ' + errStr(e)); }
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

// ---- autosave: forward document modify events to the editor page ---------
// XModifyBroadcaster fires `modified` on every document change regardless of
// origin (canvas typing, IME overlay commit, AI agent command) — the one seam
// that sees them all. The editor page throttles and relays the signal to the
// host, which debounce-saves (LibreOfficeEditor.autoSave). isModified() filters
// out the broadcast a later setModified(false) would emit.
function installModifyListener(model) {
  const listener = zetajs.unoObject([css.util.XModifyListener], {
    modified() { try { if (model.isModified()) post('modified'); } catch (e) { /* ignore */ } },
    disposing() {},
  });
  model.addModifyListener(listener);
  log('XModifyListener 已装 / installed — 文档修改将上报宿主触发自动保存');
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
  // 带 markdown 标记的文本走剥离转换（**→真粗体、行首 # 剥掉），字体沿用现场格式；
  // 纯文本走原路径不动。
  insert_at_cursor(p) {
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    const text = String(p.text || '');
    if (MD_MARKER_RE.test(text)) insertInlineStyled(vc, text);
    else insertTextAtCursor(vc, text);
    vc.collapseToEnd();
    return Object.assign({ success: true, inserted: text }, verifySnapshot());
  },
  // [verified] replace selection if any, else insert at cursor. '\n' in the new
  // text becomes a paragraph break (insertTextAtCursor).
  replace_selection(p) {
    const vc = ctrl.getViewCursor();
    // 最小修订颗粒度：选区与新文本只差几个字时，只对差异字符落修订。仅在修订
    // 模式开启时启用——RecordChanges 关闭意味着调用方要的是硬替换（如测试 reset）。
    let rcOn = false; try { rcOn = !!xModel.getPropertyValue('RecordChanges'); } catch (e) {}
    if (rcOn && (vc.getString() || '').length > 0 && applyMinimalRedline(vc, p.text || '')) {
      vc.collapseToEnd();
      return Object.assign({ success: true, text: String(p.text || '') }, verifySnapshot());
    }
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
      selectVisibly(hit); // 拟人：视图滚到正在修订的位置，用户看得见改在哪
      if (!applyMinimalRedline(hit, String(p.replaceText || ''))) hit.setString(String(p.replaceText || ''));
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
    // 上限 50（原 200）：每个匹配要插一个书签 + 6 次光标遍历，全在 office 线程上
    // 同步执行，200 个匹配足以把 Qt 事件循环冻住好几秒（修订期假死的贡献因素）。
    // 消歧一个目标用不到 50 个候选；批量替换走 find_replace。
    const ranges = [];
    let hit = xModel.findFirst(sd);
    while (hit !== null && ranges.length < 50) { ranges.push(hit); hit = xModel.findNext(hit, sd); }
    const truncated = hit !== null;
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
    const res = { success: true, count: matches.length, matches: matches };
    if (truncated) { res.truncated = true; res.note = '匹配超过 50 个，仅返回前 50——请用更长的关键词缩小范围，或用 find_replace 批量处理'; }
    return res;
  },
  // [verified-extend] replace the Nth (0-based) match under RecordChanges.
  replace_nth_match(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(String(p.findText || ''));
    const idx = Number(p.matchIndex) || 0;
    let hit = xModel.findFirst(sd), i = 0;
    while (hit !== null) {
      if (i === idx) {
        selectVisibly(hit); // 拟人：先滚到目标位置再动手
        if (!applyMinimalRedline(hit, String(p.replaceText || ''))) hit.setString(String(p.replaceText || ''));
        return { success: true, replacedIndex: idx };
      }
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
      if (i === idx) {
        selectVisibly(hit); // 拟人：先滚到目标位置再删
        hit.setString('');
        return { success: true, deletedIndex: idx };
      }
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
    while (hit !== null) {
      selectVisibly(hit); // 拟人：视图滚到正在删除的位置
      hit.setString('');
      n++;
      if (!all) break;
      hit = xModel.findNext(hit, sd);
    }
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
          if (!applyMinimalRedline(el, String(p.newText || ''))) el.setString(String(p.newText || ''));
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
  // [感知] contract clause map — group paragraphs into legal clauses by their
  // NUMBERING TEXT (第X条 / 第X章 / 一、), NOT by Word heading styles: contracts
  // rarely use heading styles, which is why get_outline sees nothing there and
  // the model degraded to counting paragraphs/lines as "clauses".
  get_clauses() {
    const RE_TIAO  = /^\s*第[一二三四五六七八九十百千零〇0-9１-９]+\s*条/;       // 第X条 — the clause proper
    const RE_ZHANG = /^\s*第[一二三四五六七八九十百千零〇0-9１-９]+\s*[章节编]/;  // 第X章/节/编 — section above clauses
    const RE_ENUM  = /^\s*[一二三四五六七八九十]{1,3}\s*、/;                      // 一、 二、 — top-level enum many contracts use
    const marks = []; let total = 0;
    eachParagraph(function (el, i) {
      total = i + 1;
      const t = el.getString() || '';
      let type = null;
      if (RE_TIAO.test(t)) type = 'tiao';
      else if (RE_ZHANG.test(t)) type = 'zhang';
      else if (RE_ENUM.test(t)) type = 'enum';
      if (type) marks.push({ index: i, type: type, text: t });
      return false;
    });
    // Granularity: 第X条 wins when present (一、 then is usually a sub-item);
    // otherwise fall back to the 一、 enum as the clause level. 章/节 always kept.
    const hasTiao = marks.some(function (m) { return m.type === 'tiao'; });
    const boundaries = marks.filter(function (m) {
      return m.type === 'zhang' || m.type === (hasTiao ? 'tiao' : 'enum');
    });
    const clauses = [];
    for (let k = 0; k < boundaries.length && clauses.length < 300; k++) {
      const b = boundaries[k];
      const end = (k + 1 < boundaries.length) ? boundaries[k + 1].index - 1 : total - 1;
      const line = b.text.trim();
      const re = b.type === 'tiao' ? RE_TIAO : (b.type === 'zhang' ? RE_ZHANG : RE_ENUM);
      const m = line.match(re);
      clauses.push({
        no: (m ? m[0] : line.slice(0, 8)).trim(),
        type: b.type,
        title: line.slice(0, 60),
        startParagraph: b.index,
        endParagraph: end,
        paragraphCount: end - b.index + 1,
      });
    }
    const firstBoundary = boundaries.length ? boundaries[0].index : total;
    return {
      success: true, totalParagraphs: total, clauseCount: clauses.length,
      granularity: hasTiao ? '第X条' : (boundaries.length ? '一、二、…' : 'none'),
      // 0..preambleParagraphs-1 = 首部（合同名称/当事人信息），不属于任何条款
      preambleParagraphs: firstBoundary,
      clauses: clauses,
    };
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
    if (!applyMinimalRedline(range, String(p.newText || ''))) range.setString(String(p.newText || ''));
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
    // 宿主随文档带来当前登录用户名：用户本人编辑的修订署名（AI 的署 AI Workdeck）
    if (p && p.authorName != null) humanAuthor = String(p.authorName);
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
      // The listener is per-model — the freshly-loaded component needs its own.
      try { installModifyListener(xModel); } catch (e) { log('XModifyListener 安装失败 / install failed: ' + errStr(e)); }
      // Revisions default ON for the real document too (same as bootDoc).
      try { xModel.setPropertyValue('RecordChanges', true); } catch (e) {}
      showDeletionsInMargin();
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
  // [diagnostic] which application modules the engine build actually contains —
  // private:factory/<module> returns null when that module was compiled out.
  // Ground truth for "can this engine open pptx/xlsx at all".
  probe_modules() {
    const out = {};
    for (const m of ['swriter', 'scalc', 'simpress', 'sdraw']) {
      try {
        const c = desktop.loadComponentFromURL('private:factory/' + m, '_blank', 0, [mkProp('Hidden', true)]);
        out[m] = !!c;
        try { if (c) c.close(false); } catch (e) { try { c.dispose(); } catch (e2) {} }
      } catch (e) { out[m] = 'error: ' + errStr(e); }
    }
    return Object.assign({ success: true }, out);
  },
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
  // [格式] paragraph-level formatting on the selection's paragraph(s): alignment,
  // paragraph style, line spacing, space before/after, indents. headingLevel 1-9
  // maps to the programmatic style name ('Heading N', valid regardless of UI
  // language); 0 = back to body ('Standard').
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
    // 行距：single / 1.5 / double（固定倍数），proportional（百分比），
    // atLeast（最小值，磅），exactly（固定值，磅）
    if (p.lineSpacingMode != null) {
      const mode = String(p.lineSpacingMode);
      const v = Number(p.lineSpacingValue) || 0;
      const M = css.style.LineSpacingMode;
      let ls = null;
      if (mode === 'single') ls = { Mode: M.PROP, Height: 100 };
      else if (mode === '1.5' || mode === 'oneHalf') ls = { Mode: M.PROP, Height: 150 };
      else if (mode === 'double') ls = { Mode: M.PROP, Height: 200 };
      else if (mode === 'proportional') { if (v <= 0) return { success: false, message: 'proportional 行距需要 lineSpacingValue（百分比，如 120）' }; ls = { Mode: M.PROP, Height: Math.round(v) }; }
      else if (mode === 'atLeast') { if (v <= 0) return { success: false, message: 'atLeast 行距需要 lineSpacingValue（磅）' }; ls = { Mode: M.MINIMUM, Height: ptToMm100(v) }; }
      else if (mode === 'exactly') { if (v <= 0) return { success: false, message: 'exactly 行距需要 lineSpacingValue（磅）' }; ls = { Mode: M.FIX, Height: ptToMm100(v) }; }
      else return { success: false, message: 'bad lineSpacingMode: ' + mode + ' (single/1.5/double/proportional/atLeast/exactly)' };
      vc.setPropertyValue('ParaLineSpacing', new css.style.LineSpacing(ls));
      applied.lineSpacing = mode + (v > 0 ? ':' + v : '');
    }
    if (p.spaceBeforePt != null) { vc.setPropertyValue('ParaTopMargin', ptToMm100(p.spaceBeforePt)); applied.spaceBeforePt = Number(p.spaceBeforePt); }
    if (p.spaceAfterPt != null) { vc.setPropertyValue('ParaBottomMargin', ptToMm100(p.spaceAfterPt)); applied.spaceAfterPt = Number(p.spaceAfterPt); }
    // 首行缩进按"字符"数折算：以光标处字号为一个字符宽（CJK 方块字口径）
    if (p.firstLineIndentChars != null) {
      let chPt = HOUSE.bodyPt;
      try { const h = vc.getPropertyValue('CharHeight'); if (h > 0) chPt = h; } catch (e) {}
      vc.setPropertyValue('ParaFirstLineIndent', ptToMm100(Number(p.firstLineIndentChars) * chPt));
      applied.firstLineIndentChars = Number(p.firstLineIndentChars);
    } else if (p.firstLineIndentPt != null) {
      vc.setPropertyValue('ParaFirstLineIndent', ptToMm100(p.firstLineIndentPt));
      applied.firstLineIndentPt = Number(p.firstLineIndentPt);
    }
    if (p.leftIndentPt != null) { vc.setPropertyValue('ParaLeftMargin', ptToMm100(p.leftIndentPt)); applied.leftIndentPt = Number(p.leftIndentPt); }
    if (p.rightIndentPt != null) { vc.setPropertyValue('ParaRightMargin', ptToMm100(p.rightIndentPt)); applied.rightIndentPt = Number(p.rightIndentPt); }
    if (Object.keys(applied).length === 0) return { success: false, message: 'no paragraph format params given' };
    return Object.assign({ success: true, applied: applied }, verifySnapshot());
  },
  // [格式] 给选区所在段落设置编号/项目符号。preset: bullet（•）/ decimal（1. 2.）/
  // chinese（一、二、）/ multilevel（1. → 1.1 → 1.1.1）/ none（去掉编号）。
  set_numbering(p) {
    const preset = String(p.preset || 'decimal');
    if (preset === 'none') { dispatchUno('.uno:RemoveBullets'); return Object.assign({ success: true, preset: 'none' }, verifySnapshot()); }
    const lvl = Math.max(1, Math.min(Number(p.level) || 1, 9)) - 1;
    let rules = null;
    try { rules = makeNumberingRules(preset); } catch (e) {
      // NumberingRules 编组兜底：bullet/decimal 退回引擎默认列表命令
      if (preset === 'bullet') { dispatchUno('.uno:DefaultBullet'); return Object.assign({ success: true, preset: preset, via: 'uno-default' }, verifySnapshot()); }
      if (preset === 'decimal') { dispatchUno('.uno:DefaultNumbering'); return Object.assign({ success: true, preset: preset, via: 'uno-default' }, verifySnapshot()); }
      return { success: false, message: 'set_numbering failed: ' + errStr(e) };
    }
    const vc = ctrl.getViewCursor();
    vc.setPropertyValue('NumberingRules', rules);
    vc.setPropertyValue('NumberingLevel', lvl);
    return Object.assign({ success: true, preset: preset, level: lvl + 1 }, verifySnapshot());
  },
  // [格式] 表格格式：边框/字号/首行加粗居中/单元格垂直对齐/数字居右/列宽/行高。
  // 不传 tableIndex 时作用于光标所在表格。applyStandard=true 一键套标准表格式
  //（Grid 1.5 磅、10 号、段前后 0.2 行、首行加粗居中、垂直居中、数字居右）。
  format_table(p) {
    const table = currentTextTable(p);
    if (!table) return { success: false, message: '光标不在表格内，且未指定有效 tableIndex（0 开始）' };
    const applied = {};
    if (p.applyStandard) {
      styleTableStandard(table, { headerRows: p.firstRowBold === false ? 0 : 1 });
      applied.standard = true;
    }
    if (p.borderWidthPt != null) {
      const color = p.borderColor != null ? parseColor(p.borderColor, { black: 0 }) : 0;
      try { applyTableBorders(table, ptToMm100(p.borderWidthPt), color == null ? 0 : color); applied.borderWidthPt = Number(p.borderWidthPt); }
      catch (e) { return { success: false, message: '边框设置失败: ' + errStr(e) }; }
    }
    const names = table.getCellNames();
    if (p.fontSizePt != null || p.cellVerticalAlign != null || p.firstRowBold != null) {
      const vmap = { top: css.text.VertOrientation.TOP, center: css.text.VertOrientation.CENTER, bottom: css.text.VertOrientation.BOTTOM };
      for (let i = 0; i < names.length; i++) {
        const cell = table.getCellByName(names[i]);
        const rowIdx = parseInt(String(names[i]).replace(/^[A-Z]+/, ''), 10) - 1;
        if (p.cellVerticalAlign != null) {
          const v = vmap[String(p.cellVerticalAlign).toLowerCase()];
          if (v == null) return { success: false, message: 'bad cellVerticalAlign: ' + p.cellVerticalAlign + ' (top/center/bottom)' };
          try { cell.setPropertyValue('VertOrient', shortAny(v)); } catch (e) {}
        }
        try {
          const ct = cell.createTextCursor();
          ct.gotoStart(false); ct.gotoEnd(true);
          if (p.fontSizePt != null) setCharProp(ct, 'CharHeight', Number(p.fontSizePt));
          if (p.firstRowBold != null && rowIdx === 0) setCharProp(ct, 'CharWeight', p.firstRowBold ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL);
        } catch (e) {}
      }
      if (p.fontSizePt != null) applied.fontSizePt = Number(p.fontSizePt);
      if (p.cellVerticalAlign != null) applied.cellVerticalAlign = String(p.cellVerticalAlign);
      if (p.firstRowBold != null) applied.firstRowBold = !!p.firstRowBold;
    }
    // 列宽（百分比数组，如 [20,50,30]，个数必须等于列数）
    if (p.columnWidthsPercent != null) {
      try {
        const pct = Array.isArray(p.columnWidthsPercent) ? p.columnWidthsPercent.map(Number) : String(p.columnWidthsPercent).split(/[,，\s]+/).filter(Boolean).map(Number);
        const seps = table.getPropertyValue('TableColumnSeparators');
        const nCols = (seps ? seps.length : 0) + 1;
        if (pct.length !== nCols) return { success: false, message: '列宽个数(' + pct.length + ')与表格列数(' + nCols + ')不符' };
        const relSum = table.getPropertyValue('TableColumnRelativeSum');
        const out = [];
        let acc = 0;
        const total = pct.reduce(function (a, b) { return a + b; }, 0);
        for (let i = 0; i < pct.length - 1; i++) {
          acc += pct[i];
          out.push(new css.text.TableColumnSeparator({ Position: Math.round(relSum * acc / total), IsVisible: true }));
        }
        table.setPropertyValue('TableColumnSeparators', out);
        applied.columnWidthsPercent = pct;
      } catch (e) { return { success: false, message: '列宽设置失败: ' + errStr(e) }; }
    }
    // 行高（磅）。rowHeightRule: 'min'（最小值，默认）| 'exact'（固定值）
    if (p.rowHeightPt != null) {
      try {
        const rows = table.getRows();
        const h = ptToMm100(p.rowHeightPt);
        const exact = String(p.rowHeightRule || 'min') === 'exact';
        for (let i = 0; i < rows.getCount(); i++) {
          const row = rows.getByIndex(i);
          row.setPropertyValue('IsAutoHeight', !exact);
          row.setPropertyValue('Height', h);
        }
        applied.rowHeightPt = Number(p.rowHeightPt);
      } catch (e) { return { success: false, message: '行高设置失败: ' + errStr(e) }; }
    }
    if (Object.keys(applied).length === 0) return { success: false, message: 'no table format params given' };
    let tname = ''; try { tname = table.getName(); } catch (e) {}
    return { success: true, table: tname, applied: applied };
  },
  // [插入] 在光标处插入一张按标准格式排好的表。rows: string[][]（第一行默认表头）。
  insert_table(p) {
    const rows = p.rows;
    if (!Array.isArray(rows) || !rows.length || !Array.isArray(rows[0])) {
      return { success: false, message: 'insert_table requires {rows: string[][]}' };
    }
    if (rows.length > 200 || rows[0].length > 20) return { success: false, message: '表格过大（上限 200 行 × 20 列）' };
    const headerRows = p.headerRow === false ? 0 : 1;
    let table;
    try { table = insertStyledTable(rows, headerRows); }
    catch (e) { return { success: false, message: 'insert_table failed: ' + errStr(e) }; }
    let tname = ''; try { tname = table.getName(); } catch (e) {}
    return Object.assign({ success: true, table: tname, rows: rows.length, cols: rows[0].length }, verifySnapshot());
  },
  // [感知] 读取光标/选区处的字符+段落+表格格式——"先看清现状再动手"的眼睛。
  get_formatting() {
    const vc = ctrl.getViewCursor();
    const out = { success: true };
    const ch = {};
    try { ch.fontWestern = vc.getPropertyValue('CharFontName'); } catch (e) {}
    try { ch.fontAsian = vc.getPropertyValue('CharFontNameAsian'); } catch (e) {}
    try { ch.sizePt = vc.getPropertyValue('CharHeight'); } catch (e) {}
    try { ch.bold = vc.getPropertyValue('CharWeight') > 100; } catch (e) {}
    try { ch.italic = !enumEq(vc.getPropertyValue('CharPosture'), css.awt.FontSlant.NONE); } catch (e) {}
    try { ch.underline = !enumEq(vc.getPropertyValue('CharUnderline'), css.awt.FontUnderline.NONE); } catch (e) {}
    try { ch.strikeout = !enumEq(vc.getPropertyValue('CharStrikeout'), css.awt.FontStrikeout.NONE); } catch (e) {}
    try { const c = vc.getPropertyValue('CharColor'); ch.color = c === -1 ? 'auto' : '#' + ('000000' + (c >>> 0).toString(16)).slice(-6); } catch (e) {}
    try { const h = vc.getPropertyValue('CharHighlight'); ch.highlight = h === -1 ? 'none' : '#' + ('000000' + (h >>> 0).toString(16)).slice(-6); } catch (e) {}
    out.character = ch;
    const pa = {};
    try { pa.styleName = vc.getPropertyValue('ParaStyleName'); } catch (e) {}
    try {
      const a = vc.getPropertyValue('ParaAdjust');
      const A = css.style.ParagraphAdjust;
      pa.alignment = enumEq(a, A.CENTER) ? 'center' : enumEq(a, A.RIGHT) ? 'right'
        : (enumEq(a, A.BLOCK) || enumEq(a, A.STRETCH)) ? 'justify' : 'left';
    } catch (e) {}
    try {
      const ls = vc.getPropertyValue('ParaLineSpacing');
      if (ls) {
        const M = css.style.LineSpacingMode;
        if (enumEq(ls.Mode, M.PROP)) pa.lineSpacing = { mode: 'proportional', percent: ls.Height };
        else if (enumEq(ls.Mode, M.MINIMUM)) pa.lineSpacing = { mode: 'atLeast', valuePt: Math.round(ls.Height * 72 / 2540 * 10) / 10 };
        else if (enumEq(ls.Mode, M.FIX)) pa.lineSpacing = { mode: 'exactly', valuePt: Math.round(ls.Height * 72 / 2540 * 10) / 10 };
        else pa.lineSpacing = { mode: 'leading', valuePt: Math.round(ls.Height * 72 / 2540 * 10) / 10 };
      }
    } catch (e) {}
    try { pa.spaceBeforePt = Math.round(vc.getPropertyValue('ParaTopMargin') * 72 / 2540 * 10) / 10; } catch (e) {}
    try { pa.spaceAfterPt = Math.round(vc.getPropertyValue('ParaBottomMargin') * 72 / 2540 * 10) / 10; } catch (e) {}
    try { pa.firstLineIndentPt = Math.round(vc.getPropertyValue('ParaFirstLineIndent') * 72 / 2540 * 10) / 10; } catch (e) {}
    try { pa.leftIndentPt = Math.round(vc.getPropertyValue('ParaLeftMargin') * 72 / 2540 * 10) / 10; } catch (e) {}
    try { pa.rightIndentPt = Math.round(vc.getPropertyValue('ParaRightMargin') * 72 / 2540 * 10) / 10; } catch (e) {}
    try { pa.outlineLevel = vc.getPropertyValue('OutlineLevel') || 0; } catch (e) {}
    try { pa.isNumbered = !!vc.getPropertyValue('NumberingIsNumber'); } catch (e) {}
    out.paragraph = pa;
    try {
      const table = currentTextTable(null);
      if (table) {
        const t = { name: table.getName() };
        try { t.rows = table.getRows().getCount(); } catch (e) {}
        try { t.cols = table.getColumns().getCount(); } catch (e) {}
        try { const cell = vc.getPropertyValue('Cell'); if (cell) t.cell = cell.getPropertyValue('CellName'); } catch (e) {}
        out.table = t;
      }
    } catch (e) {}
    try { out.selectedText = (vc.getString() || '').slice(0, 80); } catch (e) {}
    return out;
  },
  // [格式] 全文应用标准格式（用户规范见 HOUSE 注释）：字体/字号/颜色/两端对齐/
  // 段距/行距/首行缩进；首段短文本视为主标题（16 磅粗居中）；标题段（OutlineLevel
  // 或 Heading 样式）整段加粗；表格套标准表格式；表格后首段段前 18 磅。
  // 正文段落不动既有加粗/斜体（当事人名称等手工强调不能被抹掉）。
  apply_house_style() {
    const en = xModel.getText().createEnumeration();
    let idx = 0, paras = 0, tables = 0, titled = false, prevWasTable = false;
    while (en.hasMoreElements() && idx < 5000) {
      const el = en.nextElement();
      if (el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) {
        const text = (el.getString() || '').trim();
        let kind = 'body';
        if (!titled && text) {
          titled = true;
          if (idx === 0 && text.length <= 60) kind = 'title';
        }
        if (kind !== 'title') {
          try {
            const lvl = el.getPropertyValue('OutlineLevel') || 0;
            const style = el.getPropertyValue('ParaStyleName') || '';
            if (lvl > 0 || /^(Heading|标题)/.test(style)) kind = 'heading';
          } catch (e) {}
        }
        applyHousePara(el, kind, { afterTable: prevWasTable });
        applyHouseChar(el, {
          sizePt: kind === 'title' ? HOUSE.titlePt : HOUSE.bodyPt,
          bold: kind === 'title' || kind === 'heading',
          keepWeight: kind === 'body',
        });
        prevWasTable = false;
        paras++;
      } else if (el.supportsService && el.supportsService('com.sun.star.text.TextTable')) {
        try { styleTableStandard(el, { headerRows: 1, keepWeight: true }); tables++; } catch (e) {}
        prevWasTable = true;
      }
      idx++;
    }
    return Object.assign({ success: true, paragraphs: paras, tables: tables }, verifySnapshot());
  },
  // [流式] markdown 剥离 + 标准格式落字（doc_start_stream 管线的落字端）。
  // 攒到完整行才消费；尾部残行等 stream_flush。
  stream_insert(p) {
    streamEnsureActive();
    STREAM.buf += String(p.text || '');
    const nl = STREAM.buf.lastIndexOf('\n');
    if (nl === -1) return { success: true, buffered: STREAM.buf.length };
    const lines = STREAM.buf.slice(0, nl).split('\n');
    STREAM.buf = STREAM.buf.slice(nl + 1);
    for (let i = 0; i < lines.length; i++) streamWriteLine(lines[i]);
    return { success: true, lines: lines.length, buffered: STREAM.buf.length };
  },
  // [流式] 收尾：写掉尾行/尾表并复位。{discard:true} = 只复位不落字（换文档前硬清）。
  stream_flush(p) {
    if (p && p.discard) { streamReset(true); return { success: true, discarded: true }; }
    if (!STREAM.active) return { success: true, idle: true };
    const tail = STREAM.buf;
    STREAM.buf = '';
    if (tail.trim() || STREAM.table) streamWriteLine(tail);
    if (STREAM.table) streamTableFlush();
    streamReset(false);
    return Object.assign({ success: true }, verifySnapshot());
  },
  // [插入] 在指定标题段落下方插入内容（后端 doc_insert_under_heading 一直派发此
  // action，此前 worker 未实现、白名单未收录，静默失败——本次补齐）。内容走
  // 行内 markdown 剥离（保留粗体/斜体语义），字体字号沿用插入点现场格式。
  insert_under_heading(p) {
    const headingText = String(p.headingText || '').trim();
    if (!headingText) return { success: false, message: 'insert_under_heading requires {headingText}' };
    let target = null;
    eachParagraph(function (el) {
      const t = (el.getString() || '').trim();
      if (t && (t === headingText || t.indexOf(headingText) !== -1)) { target = el; return true; }
      return false;
    });
    if (!target) return { success: false, message: '未找到标题: ' + headingText };
    if (!selectVisibly(target)) return { success: false, message: '无法定位标题段落' };
    const vc = ctrl.getViewCursor();
    vc.collapseToEnd();
    const xText = vc.getText();
    xText.insertControlCharacter(vc, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
    // 新段落不继承标题的大纲级别/样式
    try { vc.setPropertyValue('ParaStyleName', 'Standard'); } catch (e) {}
    try { vc.setPropertyValue('OutlineLevel', 0); } catch (e) {}
    insertInlineStyled(vc, String(p.content || ''));
    return Object.assign({ success: true, heading: headingText }, verifySnapshot());
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
  // ---- #104 文档变量域原语（VariablePanel via the getEditor adapter) ------------
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
  // [批注] add a Word comment (annotation) on the text at an anchorId — the
  // channel for explanatory notes: 解释/说明类文字不进正文，挂批注。Engine-
  // native path (same precedent as the PR#164 delete keys): select the anchor
  // range, then dispatch .uno:InsertAnnotation with Text/Author args. The
  // API alternative (createInstance Annotation + insertTextContent bAbsorb)
  // was probe-tested on this build: it throws a spurious RuntimeException from
  // the post-attach view code AND exports only a point commentReference; the
  // dispatch is clean and exports a true RANGE comment (commentRangeStart/End
  // spanning the target text), which is what Word shows attached to the run.
  // Date is stamped by the engine; author comes from the dispatch arg.
  add_comment(p) {
    const anchorId = String(p.anchor || '');
    const comment = String(p.comment || '');
    if (!anchorId) return { success: false, message: 'add_comment requires {anchor} (anchorId from find_text_locations)' };
    if (!comment) return { success: false, message: 'add_comment requires {comment}' };
    const range = anchorRange(anchorId);
    if (!range) return { success: false, message: 'anchor not found: ' + anchorId };
    const annotatedText = (range.getString() || '').slice(0, 120);
    // 拟人：选中并滚动到被批注的位置——选区同时就是批注的附着区间
    if (!selectVisibly(range)) return { success: false, message: 'could not select anchor: ' + anchorId };
    css.frame.DispatchHelper.create(context).executeDispatch(
      ctrl.getFrame(), '.uno:InsertAnnotation', '', 0,
      [mkProp('Text', comment), mkProp('Author', AI_AUTHOR)]);
    return {
      success: true, anchor: anchorId, author: AI_AUTHOR, comment: comment,
      annotatedText: annotatedText,
      paragraph: (paragraphTextOf(range) || '').slice(0, 200),
    };
  },
  // ---- [审阅面板] 修订与批注的清单 / 定位 / 逐条处置 --------------------
  // 页边显示解决了「删除文本压正文」，但同一表格行多格删除仍会在页边同高互叠，
  // 且页边小字读不到作者/时间。审阅面板（宿主右栏）用下面这组原语驱动：列出、
  // 点击定位、逐条接受/拒绝——修订的权威视图从页边挪进面板。
  list_revisions(p) {
    const limit = Math.max(1, Math.min(500, Number(p && p.limit) || 200));
    const out = [];
    try {
      const en = xModel.getRedlines().createEnumeration();
      while (en.hasMoreElements() && out.length < limit) {
        const r = en.nextElement();
        const it = { index: out.length };
        try { it.type = r.getPropertyValue('RedlineType'); } catch (e) {}
        try { it.author = r.getPropertyValue('RedlineAuthor'); } catch (e) {}
        try { it.comment = r.getPropertyValue('RedlineComment'); } catch (e) {}
        try {
          const d = r.getPropertyValue('RedlineDateTime');
          if (d) it.date = d.Year + '-' + pad2(d.Month) + '-' + pad2(d.Day) + ' ' + pad2(d.Hours) + ':' + pad2(d.Minutes);
        } catch (e) {}
        // 删除型在页边模式下文本收进 redline 对象（getString 可取）；插入型的
        // 文本只在正文流里，要靠 RedlineStart/End 区间取。两路都试。
        try { if (typeof r.getString === 'function') it.text = String(r.getString() || ''); } catch (e) {}
        try {
          const rs = r.getPropertyValue('RedlineStart'), re = r.getPropertyValue('RedlineEnd');
          if (rs && re) {
            if (!it.text) {
              const rc = rs.getText().createTextCursorByRange(rs);
              rc.gotoRange(re, true);
              it.text = String(rc.getString() || '');
            }
            const pc = rs.getText().createTextCursorByRange(rs);
            try { pc.gotoStartOfParagraph(false); pc.gotoEndOfParagraph(true); it.paragraph = String(pc.getString() || '').slice(0, 120); } catch (e) {}
            // 表格内的修订：面板要标出来（页边互叠的正是这一类）
            try { it.inTable = !!rs.getPropertyValue('Cell'); } catch (e) { it.inTable = false; }
          }
        } catch (e) {}
        it.text = String(it.text || '').slice(0, 120);
        out.push(it);
      }
    } catch (e) { return { success: false, message: errStr(e) }; }
    return { success: true, count: out.length, revisions: out };
  },
  goto_revision(p) {
    const r = redlineAt(p && p.index);
    if (!r) return { success: false, message: 'no revision at index ' + (p && p.index) };
    return selectRedlineRange(r)
      ? { success: true, index: Number(p.index), selected: String(ctrl.getViewCursor().getString() || '') }
      : { success: false, message: 'could not select revision range' };
  },
  // 逐条处置。**光标摆放是硬要求**（真机探针实证）：视图光标必须跨过 redline
  // 区间——插入型这样才选中正文里的新增文本，删除型（页边模式下正文流里是
  // 零宽）退化成定位到起点，两者都能被 dispatch 命中。摆错位置（collapse 到
  // 起点再右移、或用 selectVisibly 传区间游标）会让 dispatch 打空，甚至凭空
  // 多出一条空插入修订。
  resolve_revision(p) {
    const action = String((p && p.action) || 'accept').toLowerCase();
    if (action !== 'accept' && action !== 'reject') return { success: false, message: "action must be accept|reject" };
    const r = redlineAt(p && p.index);
    if (!r) return { success: false, message: 'no revision at index ' + (p && p.index) };
    if (!selectRedlineRange(r, true)) return { success: false, message: 'could not select revision range' };
    const before = countRedlines();
    css.frame.DispatchHelper.create(context).executeDispatch(
      ctrl.getFrame(), action === 'accept' ? '.uno:AcceptTrackedChange' : '.uno:RejectTrackedChange', '', 0, []);
    const after = countRedlines();
    // dispatch 不报错也可能没命中——用条数变化确认，别对用户谎报成功
    return after < before
      ? { success: true, index: Number(p.index), action: action, remaining: after }
      : { success: false, message: '修订未被处置（引擎未命中该条）', remaining: after };
  },
  resolve_all_revisions(p) {
    const action = String((p && p.action) || 'accept').toLowerCase();
    if (action !== 'accept' && action !== 'reject') return { success: false, message: "action must be accept|reject" };
    const before = countRedlines();
    css.frame.DispatchHelper.create(context).executeDispatch(
      ctrl.getFrame(), action === 'accept' ? '.uno:AcceptAllTrackedChanges' : '.uno:RejectAllTrackedChanges', '', 0, []);
    return { success: true, action: action, resolved: before - countRedlines(), remaining: countRedlines() };
  },
  list_comments(p) {
    const limit = Math.max(1, Math.min(500, Number(p && p.limit) || 200));
    const out = [];
    try {
      const en = xModel.getTextFields().createEnumeration();
      while (en.hasMoreElements() && out.length < limit) {
        const f = en.nextElement();
        if (!(f.supportsService && f.supportsService('com.sun.star.text.textfield.Annotation'))) continue;
        const it = { index: out.length };
        try { it.author = f.getPropertyValue('Author'); } catch (e) {}
        try { it.content = String(f.getPropertyValue('Content') || '').slice(0, 500); } catch (e) {}
        try {
          const d = f.getPropertyValue('DateTimeValue');
          if (d) it.date = d.Year + '-' + pad2(d.Month) + '-' + pad2(d.Day) + ' ' + pad2(d.Hours) + ':' + pad2(d.Minutes);
        } catch (e) {}
        try { it.resolved = !!f.getPropertyValue('Resolved'); } catch (e) { it.resolved = false; }
        try {
          const a = f.getAnchor();
          it.anchorText = String(a.getString() || '').slice(0, 80);
          it.paragraph = (paragraphTextOf(a) || '').slice(0, 120);
        } catch (e) {}
        out.push(it);
      }
    } catch (e) { return { success: false, message: errStr(e) }; }
    return { success: true, count: out.length, comments: out };
  },
  goto_comment(p) {
    const f = commentAt(p && p.index);
    if (!f) return { success: false, message: 'no comment at index ' + (p && p.index) };
    try { return selectVisibly(f.getAnchor()) ? { success: true, index: Number(p.index) } : { success: false, message: 'could not select anchor' }; }
    catch (e) { return { success: false, message: errStr(e) }; }
  },
  set_comment_resolved(p) {
    const f = commentAt(p && p.index);
    if (!f) return { success: false, message: 'no comment at index ' + (p && p.index) };
    try {
      f.setPropertyValue('Resolved', !!(p && p.resolved));
      return { success: true, index: Number(p.index), resolved: !!f.getPropertyValue('Resolved') };
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
  // 删除批注：removeTextContent 是正路。**dispose() 不能信**——它在本引擎上
  // 既不抛异常也不真的移除批注字段（真机实证），照着它的返回值报成功就是骗
  // 用户。两条路都跑完还要用条数复核。
  delete_comment(p) {
    const f = commentAt(p && p.index);
    if (!f) return { success: false, message: 'no comment at index ' + (p && p.index) };
    const before = countComments();
    const errs = [];
    // 主路：定位到批注锚点后派发 .uno:DeleteComment，**Id 参数（批注的 Name）
    // 必传**——真机实证不带 Id 会静默打空（引擎按 Id 找批注，不认光标位置）。
    // 与 add_comment 走 .uno:InsertAnnotation 同一先例。
    // 前提：文档必须是可见的（.uno:DeleteComment 找的是引擎里的活动批注窗口，
    // Hidden 打开的文档没有这些窗口，删除会静默失败——e2e 因此专门用可见文档）。
    // 已解决的批注同理不再是活动窗口，先取消解决态再删。
    try { if (f.getPropertyValue('Resolved')) f.setPropertyValue('Resolved', false); } catch (e) {}
    try { selectVisibly(f.getAnchor()); } catch (e) { errs.push(errStr(e)); }
    try {
      let name = '';
      try { name = String(f.getPropertyValue('Name') || ''); } catch (e) { errs.push(errStr(e)); }
      css.frame.DispatchHelper.create(context).executeDispatch(
        ctrl.getFrame(), '.uno:DeleteComment', '', 0, [mkProp('Id', name)]);
    } catch (e) { errs.push(errStr(e)); }
    if (countComments() === before) {
      try { f.getAnchor().getText().removeTextContent(f); } catch (e) { errs.push(errStr(e)); }
    }
    const after = countComments();
    return after < before
      ? { success: true, index: Number(p.index), remaining: after }
      : { success: false, message: '批注未被删除' + (errs.length ? '：' + errs.join(' | ') : ''), remaining: after };
  },
  // [diagnostic] 修订记录清单（类型/作者/文本片段）。后端 doc_debug_revisions
  // 一直派发 debug_revisions，worker 此前未实现（一律返回 not implemented）；
  // 补上后同时作为修订署名（AI Workdeck / 用户名）的验证探针。
  debug_revisions() {
    const out = [];
    try {
      const en = xModel.getRedlines().createEnumeration();
      while (en.hasMoreElements() && out.length < 200) {
        const r = en.nextElement();
        const item = {};
        try { item.type = r.getPropertyValue('RedlineType'); } catch (e) {}
        try { item.author = r.getPropertyValue('RedlineAuthor'); } catch (e) {}
        try { item.comment = r.getPropertyValue('RedlineComment'); } catch (e) {}
        try { if (typeof r.getString === 'function') item.text = String(r.getString() || '').slice(0, 80); } catch (e) {}
        // 行内显示（ShowChangesInMargin=false）下删除文本留在正文流里，redline
        // 自身 getString() 抛 RuntimeException（页边模式才把文本收进 redline）——
        // 回退用 RedlineStart/End 区间从正文取；Insert 型两种模式都走这条。
        if (item.text == null) {
          try {
            const rs = r.getPropertyValue('RedlineStart'), re = r.getPropertyValue('RedlineEnd');
            if (rs && re) {
              const rcur = rs.getText().createTextCursorByRange(rs);
              rcur.gotoRange(re, true);
              item.text = String(rcur.getString() || '').slice(0, 80);
            }
          } catch (e) {}
        }
        out.push(item);
      }
    } catch (e) { return { success: false, message: errStr(e) }; }
    return { success: true, count: out.length, redlines: out };
  },
  // [第 2 期 版本对比] 当前文档（新版）与 baseBytes（旧版）比较，产出修订标记。
  // 探针（lowa-e2e 组 13）已实证方向：产出「旧到新」的删/插修订，正文停在新版。
  // 比较产生的修订署名统一为「版本对比」，与人工/AI 修订区分开。
  // 比较完成后把文档切只读（.uno:EditDoc 关编辑模式）——这是展示用文档，
  // 宿主（VersionCompareTab）没有任何保存路径，只读是第二道保险。
  compare_document(p) {
    const raw = p && p.baseBytes;
    let u8 = null;
    if (raw instanceof ArrayBuffer) u8 = new Uint8Array(raw);
    else if (raw && raw.buffer instanceof ArrayBuffer) u8 = new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength);
    else if (Array.isArray(raw)) u8 = new Uint8Array(raw);
    if (!u8 || u8.length === 0) return { success: false, stage: 'input', message: 'baseBytes empty' };
    const bytes = Array.from(new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength));
    const url = 'file:///tmp/awd_base_cmp.docx';
    try {
      const sfa = css.ucb.SimpleFileAccess.create(context);
      try { if (sfa.exists(url)) sfa.kill(url); } catch (e) {}
      const stream = css.io.SequenceInputStream.createStreamFromSequence(context, bytes);
      sfa.writeFile(url, stream);
      try { stream.closeInput(); } catch (e) {}
    } catch (e) { return { success: false, stage: 'memfs', message: errStr(e) }; }
    try { setRedlineAuthor('版本对比'); } catch (e) {}
    try {
      css.frame.DispatchHelper.create(context).executeDispatch(
        ctrl.getFrame(), '.uno:CompareDocuments', '', 0, [mkProp('URL', url)]);
    } catch (e) { return { success: false, stage: 'dispatch', message: errStr(e) }; }
    let count = 0;
    try {
      const en = xModel.getRedlines().createEnumeration();
      while (en.hasMoreElements() && count < 10000) { en.nextElement(); count++; }
    } catch (e) {}
    try {
      css.frame.DispatchHelper.create(context).executeDispatch(
        ctrl.getFrame(), '.uno:EditDoc', '', 0, []);
    } catch (e) {}
    return { success: true, redlineCount: count };
  },
  // ==================== Calc 电子表格原语（sheet_*） ====================
  // 与 doc_* 平行的 xlsx 操作面。Calc 没有 Writer 的修订（redline）机制，写入
  // 即生效；安全网是 undo（Calc 的 UndoManager 同样可用）与后端文档检查点。
  // [表格·看] 工作表清单 + 每张表的已用区域。打开 xlsx 后的第一步。
  sheet_get_overview() {
    if (!isCalcDoc()) return { success: false, message: NOT_SPREADSHEET_MSG };
    const sheets = xModel.getSheets();
    let activeName = '';
    try { activeName = ctrl.getActiveSheet().getName(); } catch (e) {}
    const out = [];
    const n = Math.min(sheets.getCount(), 50);
    for (let i = 0; i < n; i++) {
      const s = sheets.getByIndex(i);
      const item = { index: i, name: s.getName(), active: s.getName() === activeName };
      try {
        const addr = usedRangeAddress(s);
        item.usedRange = sheetRangeName(addr);
        item.rows = addr.EndRow - addr.StartRow + 1;
        item.cols = addr.EndColumn - addr.StartColumn + 1;
      } catch (e) { item.usedRangeErr = errStr(e); }
      out.push(item);
    }
    return { success: true, sheetCount: sheets.getCount(), activeSheet: activeName, sheets: out };
  },
  // [表格·看] 读取区域单元格值。数值/公式结果返回 number（日期是序列数），公式串
  // 另列在 formulas。range 缺省 = 该表已用区域。超上限窗口化返回，提示分块读。
  sheet_read_range(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const sheet = r0.sheet;
    let range;
    if (p && p.range) {
      range = sheetRange(sheet, p.range);
      if (!range) return { success: false, message: '无效的区域: ' + p.range + '（应为 A1 或 A1:D20 形式）' };
    } else {
      try { range = sheet.getCellRangeByName(sheetRangeName(usedRangeAddress(sheet))); }
      catch (e) { return { success: false, message: '读取已用区域失败: ' + errStr(e) }; }
    }
    const addr = range.getRangeAddress();
    const nRows = addr.EndRow - addr.StartRow + 1;
    const nCols = addr.EndColumn - addr.StartColumn + 1;
    const MAX_CELLS = 2000;
    const colCap = Math.min(nCols, 100);
    const rowCap = Math.min(nRows, Math.max(1, Math.floor(MAX_CELLS / colCap)));
    const rows = [];
    const formulas = [];
    for (let r = 0; r < rowCap; r++) {
      const row = [];
      for (let c = 0; c < colCap; c++) {
        const cell = range.getCellByPosition(c, r);
        row.push(readCellOut(cell));
        try {
          if (enumEq(cell.getType(), css.table.CellContentType.FORMULA) && formulas.length < 100) {
            formulas.push({ cell: colLetterOf(addr.StartColumn + c) + (addr.StartRow + r + 1), formula: cell.getFormula() });
          }
        } catch (e) {}
      }
      rows.push(row);
    }
    const res = { success: true, sheet: sheet.getName(), range: sheetRangeName(addr), rows: rows };
    if (formulas.length) res.formulas = formulas;
    if (rowCap < nRows || colCap < nCols) {
      res.truncated = true;
      res.note = '区域超过上限（' + MAX_CELLS + ' 格），只返回前 ' + rowCap + ' 行 × ' + colCap + ' 列，请缩小 range 分块读取';
    }
    return res;
  },
  // [表格·写] 从 startCell 起按二维数组批量写入。number/数字样式字符串落数值，
  // '=' 开头落公式，其余落文本；null 跳过不动，'' 清空该格。写完选中写过的区域
  // 并回读首行做验证回路。
  sheet_write_cells(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const sheet = r0.sheet;
    const rows = p && p.rows;
    if (!Array.isArray(rows) || !rows.length) return { success: false, message: 'sheet_write_cells requires {rows: any[][]}' };
    if (rows.length > 500) return { success: false, message: '一次最多写 500 行，请分批写入' };
    const anchor = sheetRange(sheet, String(p.startCell || 'A1'));
    if (!anchor) return { success: false, message: '无效的起始单元格: ' + p.startCell };
    const a0 = anchor.getRangeAddress();
    let nCols = 1;
    for (let i = 0; i < rows.length; i++) if (Array.isArray(rows[i])) nCols = Math.max(nCols, rows[i].length);
    if (nCols > 100) return { success: false, message: '一次最多写 100 列，请分批写入' };
    let written = 0;
    const formulaCells = []; // 写完统一验错：解析失败/求值出错的公式要报给 AI 自纠
    for (let r = 0; r < rows.length; r++) {
      const line = Array.isArray(rows[r]) ? rows[r] : [rows[r]];
      for (let c = 0; c < line.length; c++) {
        const v = line[c];
        if (v == null) continue;
        const cell = sheet.getCellByPosition(a0.StartColumn + c, a0.StartRow + r);
        if (typeof v === 'number') cell.setValue(v);
        else if (typeof v === 'boolean') cell.setValue(v ? 1 : 0);
        else {
          const s = String(v);
          if (s.charAt(0) === '=') {
            cell.setFormula(normalizeFormula(s));
            formulaCells.push({ cell: cell, name: colLetterOf(a0.StartColumn + c) + (a0.StartRow + r + 1), input: s });
          }
          else if (SHEET_NUMERIC_RE.test(s.trim())) cell.setValue(Number(s.trim()));
          else cell.setString(s);
        }
        written++;
      }
    }
    const formulaErrors = [];
    for (let i = 0; i < formulaCells.length && formulaErrors.length < 20; i++) {
      try {
        const err = formulaCells[i].cell.getError();
        if (err) formulaErrors.push({ cell: formulaCells[i].name, formula: formulaCells[i].input, errorCode: err, display: formulaCells[i].cell.getString() });
      } catch (e) {}
    }
    const wrote = sheetRangeName({
      StartColumn: a0.StartColumn, StartRow: a0.StartRow,
      EndColumn: a0.StartColumn + nCols - 1, EndRow: a0.StartRow + rows.length - 1,
    });
    try { ctrl.select(sheet.getCellRangeByName(wrote)); } catch (e) {} // 拟人：用户看到写入落点
    const firstRowAfterWrite = [];
    try {
      const vr = sheet.getCellRangeByName(wrote);
      for (let c = 0; c < Math.min(nCols, 10); c++) firstRowAfterWrite.push(readCellOut(vr.getCellByPosition(c, 0)));
    } catch (e) {}
    const res = { success: true, sheet: sheet.getName(), range: wrote, cellsWritten: written, firstRowAfterWrite: firstRowAfterWrite };
    if (formulaErrors.length) {
      res.formulaErrors = formulaErrors;
      res.note = formulaErrors.length + ' 个公式出错（引擎为 LibreOffice 24.2：不支持 XLOOKUP 等新函数，用 VLOOKUP 或 INDEX+MATCH 改写；函数名必须是英文）。请修正后用 sheet_write_cells 重写这些单元格。';
    }
    return res;
  },
  // [表格·选] 选中一个区域（视图滚过去、选区亮出来——用户看得见 AI 在操作哪里）。
  sheet_select_range(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return { success: false, message: '无效的区域: ' + (p.range || '(空)') };
    try { ctrl.select(range); } catch (e) { return { success: false, message: '选中失败: ' + errStr(e) }; }
    const addr = range.getRangeAddress();
    return {
      success: true, sheet: r0.sheet.getName(), range: sheetRangeName(addr),
      topLeftText: String(range.getCellByPosition(0, 0).getString() || ''),
    };
  },
  // [表格·格式] 区域格式：字体/字号/加粗/斜体/下划线/字色/底色/水平垂直对齐/
  // 自动换行/数字格式。CJK 走 Asian/Complex 姊妹属性（setCharProp）。
  sheet_format_cells(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return { success: false, message: '无效的区域: ' + (p.range || '(空)') };
    const applied = {};
    if (p.bold != null) { setCharProp(range, 'CharWeight', p.bold ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL); applied.bold = !!p.bold; }
    if (p.italic != null) { setCharProp(range, 'CharPosture', p.italic ? css.awt.FontSlant.ITALIC : css.awt.FontSlant.NONE); applied.italic = !!p.italic; }
    if (p.underline != null) { range.setPropertyValue('CharUnderline', p.underline ? css.awt.FontUnderline.SINGLE : css.awt.FontUnderline.NONE); applied.underline = !!p.underline; }
    if (p.fontSize != null) { setCharProp(range, 'CharHeight', Number(p.fontSize)); applied.fontSize = Number(p.fontSize); }
    if (p.fontName != null && String(p.fontName) !== '') { setCharProp(range, 'CharFontName', String(p.fontName)); applied.fontName = String(p.fontName); }
    if (p.color != null) {
      const c = parseColor(p.color, { auto: -1 });
      if (c == null) return { success: false, message: 'bad color: ' + p.color + ' (use #RRGGBB or auto)' };
      range.setPropertyValue('CharColor', c); applied.color = String(p.color);
    }
    if (p.background != null) {
      const c = parseColor(p.background, { none: -1 });
      if (c == null) return { success: false, message: 'bad background: ' + p.background + ' (use #RRGGBB or none)' };
      range.setPropertyValue('CellBackColor', c); applied.background = String(p.background);
    }
    if (p.hAlign != null) {
      const m = { left: css.table.CellHoriJustify.LEFT, center: css.table.CellHoriJustify.CENTER, right: css.table.CellHoriJustify.RIGHT, standard: css.table.CellHoriJustify.STANDARD };
      const v = m[String(p.hAlign).toLowerCase()];
      if (v == null) return { success: false, message: 'bad hAlign: ' + p.hAlign + ' (left/center/right/standard)' };
      range.setPropertyValue('HoriJustify', v); applied.hAlign = String(p.hAlign);
    }
    if (p.vAlign != null) {
      // VertJustify 声明为 long（CellVertJustify2 常量 0..3）；个别引擎仍按
      // short 校验，失败退 shortAny（PR#107 的 short 型属性教训）。
      const m = { standard: 0, top: 1, center: 2, bottom: 3 };
      const v = m[String(p.vAlign).toLowerCase()];
      if (v == null) return { success: false, message: 'bad vAlign: ' + p.vAlign + ' (top/center/bottom/standard)' };
      try { range.setPropertyValue('VertJustify', v); }
      catch (e) { range.setPropertyValue('VertJustify', shortAny(v)); }
      applied.vAlign = String(p.vAlign);
    }
    if (p.wrap != null) { range.setPropertyValue('IsTextWrapped', !!p.wrap); applied.wrap = !!p.wrap; }
    if (p.numberFormat != null && String(p.numberFormat) !== '') {
      const fmt = String(p.numberFormat);
      try {
        const formats = xModel.getNumberFormats();
        const loc = new css.lang.Locale({ Language: '', Country: '', Variant: '' });
        let key = formats.queryKey(fmt, loc, false);
        if (key === -1) key = formats.addNew(fmt, loc);
        range.setPropertyValue('NumberFormat', key);
        applied.numberFormat = fmt;
      } catch (e) { return { success: false, message: '数字格式无效: ' + fmt + ' — ' + errStr(e) }; }
    }
    if (Object.keys(applied).length === 0) return { success: false, message: 'no format params given' };
    try { ctrl.select(range); } catch (e) {} // 拟人：让用户看到被格式化的区域
    return { success: true, sheet: r0.sheet.getName(), range: sheetRangeName(range.getRangeAddress()), applied: applied };
  },
  // [表格·格式] 区域边框。preset: all（内外全部）/ outer（仅外框，内线清除）/
  // none（全部清除）。同 Writer 表格走 TableBorder2（typedef 编组依赖 vendored
  // zeta.js 的 TYPEDEF 分支，见 zetajs 编组硬规则）。
  sheet_set_borders(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return { success: false, message: '无效的区域: ' + (p.range || '(空)') };
    const preset = String(p.preset || 'all').toLowerCase();
    if (['all', 'outer', 'none'].indexOf(preset) === -1) return { success: false, message: 'bad preset: ' + preset + ' (all/outer/none)' };
    const widthPt = Number(p.widthPt) || 0.75;
    const widthMm = ptToMm100(widthPt);
    const color = p.color != null ? parseColor(p.color, { black: 0 }) : 0;
    if (color == null) return { success: false, message: 'bad color: ' + p.color + ' (use #RRGGBB)' };
    const solid = (css.table.BorderLineStyle && css.table.BorderLineStyle.SOLID != null) ? css.table.BorderLineStyle.SOLID : 0;
    const mk = function (on) {
      return new css.table.BorderLine2({
        Color: color, InnerLineWidth: 0, OuterLineWidth: 0, LineDistance: 0,
        LineStyle: solid, LineWidth: on ? widthMm : 0,
      });
    };
    const outer = mk(preset !== 'none');
    const inner = mk(preset === 'all');
    const tb = new css.table.TableBorder2({
      TopLine: outer, BottomLine: outer, LeftLine: outer, RightLine: outer,
      HorizontalLine: inner, VerticalLine: inner,
      IsTopLineValid: true, IsBottomLineValid: true, IsLeftLineValid: true,
      IsRightLineValid: true, IsHorizontalLineValid: true, IsVerticalLineValid: true,
      Distance: 0, IsDistanceValid: false,
    });
    try { range.setPropertyValue('TableBorder2', tb); } catch (e) { return { success: false, message: '边框设置失败: ' + errStr(e) }; }
    return {
      success: true, sheet: r0.sheet.getName(), range: sheetRangeName(range.getRangeAddress()),
      preset: preset, widthPt: preset === 'none' ? 0 : widthPt,
    };
  },
  // [表格·格式] 行高列宽：作用于 range 覆盖到的整行/整列。autoFit* 优先于定值。
  sheet_set_row_col(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return { success: false, message: '无效的区域: ' + (p.range || '(空)') };
    const applied = {};
    try {
      if (p.autoFitRows || p.rowHeightPt != null) {
        const rows = range.getRows();
        for (let i = 0; i < rows.getCount(); i++) {
          const row = rows.getByIndex(i);
          if (p.autoFitRows) row.setPropertyValue('OptimalHeight', true);
          else { row.setPropertyValue('OptimalHeight', false); row.setPropertyValue('Height', ptToMm100(p.rowHeightPt)); }
        }
        if (p.autoFitRows) applied.autoFitRows = true; else applied.rowHeightPt = Number(p.rowHeightPt);
      }
      if (p.autoFitCols || p.colWidthPt != null) {
        const cols = range.getColumns();
        for (let i = 0; i < cols.getCount(); i++) {
          const col = cols.getByIndex(i);
          if (p.autoFitCols) col.setPropertyValue('OptimalWidth', true);
          else col.setPropertyValue('Width', ptToMm100(p.colWidthPt));
        }
        if (p.autoFitCols) applied.autoFitCols = true; else applied.colWidthPt = Number(p.colWidthPt);
      }
    } catch (e) { return { success: false, message: '行高列宽设置失败: ' + errStr(e) }; }
    if (Object.keys(applied).length === 0) return { success: false, message: 'no params given (rowHeightPt/colWidthPt/autoFitRows/autoFitCols)' };
    return { success: true, sheet: r0.sheet.getName(), range: sheetRangeName(range.getRangeAddress()), applied: applied };
  },
  // [表格·结构] 工作表管理：add/rename/delete/move。add/move 的 position 是
  // 0 开始的目标位置；delete 拒绝删除最后一张表。操作后返回最新工作表清单。
  sheet_manage_sheets(p) {
    if (!isCalcDoc()) return { success: false, message: NOT_SPREADSHEET_MSG };
    const sheets = xModel.getSheets();
    const op = String(p.op || '').toLowerCase();
    const name = p.name != null ? String(p.name).trim() : '';
    try {
      if (op === 'add') {
        if (!name) return { success: false, message: 'add 需要 {name}（新工作表名）' };
        if (sheets.hasByName(name)) return { success: false, message: '工作表已存在: ' + name };
        const pos = p.position != null ? Math.max(0, Math.min(Number(p.position), sheets.getCount())) : sheets.getCount();
        sheets.insertNewByName(name, pos);
        try { ctrl.setActiveSheet(sheets.getByName(name)); } catch (e) {}
      } else if (op === 'rename') {
        const newName = p.newName != null ? String(p.newName).trim() : '';
        if (!name || !newName) return { success: false, message: 'rename 需要 {name, newName}' };
        if (!sheets.hasByName(name)) return { success: false, message: '工作表不存在: ' + name };
        if (sheets.hasByName(newName)) return { success: false, message: '目标名已存在: ' + newName };
        sheets.getByName(name).setName(newName);
      } else if (op === 'delete') {
        if (!name) return { success: false, message: 'delete 需要 {name}' };
        if (!sheets.hasByName(name)) return { success: false, message: '工作表不存在: ' + name };
        if (sheets.getCount() <= 1) return { success: false, message: '不能删除最后一张工作表' };
        sheets.removeByName(name);
      } else if (op === 'move') {
        if (!name) return { success: false, message: 'move 需要 {name}' };
        if (!sheets.hasByName(name)) return { success: false, message: '工作表不存在: ' + name };
        if (p.position == null) return { success: false, message: 'move 需要 {position}（0 开始的目标位置）' };
        sheets.moveByName(name, Math.max(0, Math.min(Number(p.position), sheets.getCount())));
      } else {
        return { success: false, message: 'bad op: ' + op + ' (add/rename/delete/move)' };
      }
    } catch (e) { return { success: false, message: '工作表操作失败: ' + errStr(e) }; }
    const list = [];
    for (let i = 0; i < Math.min(sheets.getCount(), 50); i++) list.push(sheets.getByIndex(i).getName());
    return { success: true, op: op, sheets: list };
  },
  // [表格·结构] 插入/删除整行整列。op: insert_rows/delete_rows/insert_cols/
  // delete_cols；start 是 1 开始的行号或列标（'3' / 'B'）；count 默认 1。
  // 插入发生在 start 位置之前（即新行/列占据 start 的位置）。
  sheet_edit_rows_cols(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const sheet = r0.sheet;
    const op = String(p.op || '').toLowerCase();
    const count = Math.max(1, Math.min(Number(p.count) || 1, 100));
    const isRow = op === 'insert_rows' || op === 'delete_rows';
    const isCol = op === 'insert_cols' || op === 'delete_cols';
    if (!isRow && !isCol) return { success: false, message: 'bad op: ' + op + ' (insert_rows/delete_rows/insert_cols/delete_cols)' };
    let idx;
    if (isRow) {
      idx = /^\d+$/.test(String(p.start || '').trim()) ? Number(String(p.start).trim()) - 1 : -1;
      if (idx < 0) return { success: false, message: '行号无效: ' + p.start + '（1 开始的数字）' };
    } else {
      idx = colIndexOf(p.start);
      if (idx < 0) return { success: false, message: '列标无效: ' + p.start + "（如 'B' 或 1 开始的数字）" };
    }
    try {
      const coll = isRow ? sheet.getRows() : sheet.getColumns();
      if (op.indexOf('insert') === 0) coll.insertByIndex(idx, count);
      else coll.removeByIndex(idx, count);
    } catch (e) { return { success: false, message: '行列操作失败: ' + errStr(e) }; }
    return { success: true, op: op, start: String(p.start), count: count, sheet: sheet.getName() };
  },
  // [表格·结构] 合并/取消合并单元格区域（XMergeable）。合并后内容以左上格为准。
  sheet_merge_cells(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return { success: false, message: '无效的区域: ' + (p.range || '(空)') };
    const merge = p.merge !== false;
    try { range.merge(merge); } catch (e) { return { success: false, message: '合并操作失败: ' + errStr(e) }; }
    try { ctrl.select(range); } catch (e) {}
    // 验证回路：XMergeable.getIsMerged() 读回实际状态（'IsMerged' 属性不存在）
    let isMerged = merge;
    try { isMerged = !!range.getIsMerged(); } catch (e) {}
    return { success: true, range: sheetRangeName(range.getRangeAddress()), merged: isMerged };
  },
  // [表格·结构] 区域排序。byColumn 是列标（'B'，须在区域内），ascending 默认
  // true，hasHeader 默认 true（首行不参与排序）。
  // 为什么不走 XSortable.sort()：SortFields 需要 sequence<TableSortField>，而
  // 引擎 WASM 没为该类型预编 embind 序列构造器（getEmbindSequenceCtor 直接抛，
  // 裸 Array 又被猜成 sequence<any> 被 Calc 静默忽略——组 19 真机实证两条路都
  // 死）。改为 worker 内读值 → JS 排 → 回写：值与公式文本随行整体移动；代价是
  // 单元格格式不随行移动、公式相对引用不重定位（数值表主流场景无影响）。
  sheet_sort_range(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return { success: false, message: '无效的区域: ' + (p.range || '(空)') };
    const addr = range.getRangeAddress();
    const nRows = addr.EndRow - addr.StartRow + 1;
    const nCols = addr.EndColumn - addr.StartColumn + 1;
    if (nRows * nCols > 5000) return { success: false, message: '排序区域过大（上限 5000 格），请缩小范围' };
    const byCol = p.byColumn != null ? colIndexOf(p.byColumn) : addr.StartColumn;
    if (byCol < addr.StartColumn || byCol > addr.EndColumn) {
      return { success: false, message: '排序列 ' + p.byColumn + ' 不在区域 ' + sheetRangeName(addr) + ' 内' };
    }
    const asc = p.ascending !== false;
    const headerOfs = p.hasHeader !== false ? 1 : 0;
    const keyOfs = byCol - addr.StartColumn;
    const T = css.table.CellContentType;
    // 读出待排行（每格记类型，回写时保真：公式回 setFormula、数值回 setValue）
    const dataRows = [];
    for (let r = headerOfs; r < nRows; r++) {
      const line = [];
      for (let c = 0; c < nCols; c++) {
        const cell = range.getCellByPosition(c, r);
        const t = cell.getType();
        if (enumEq(t, T.EMPTY)) line.push({ k: 'e' });
        else if (enumEq(t, T.FORMULA)) line.push({ k: 'f', f: cell.getFormula(), sort: readCellOut(cell) });
        else if (enumEq(t, T.VALUE)) line.push({ k: 'v', v: cell.getValue() });
        else line.push({ k: 's', s: cell.getString() });
      }
      dataRows.push(line);
    }
    const keyOf = function (line) {
      const it = line[keyOfs];
      if (!it || it.k === 'e') return null; // 空值恒排最后（Calc 口径）
      if (it.k === 'v') return it.v;
      if (it.k === 'f') return it.sort;
      return it.s;
    };
    dataRows.sort(function (a, b) {
      const ka = keyOf(a), kb = keyOf(b);
      if (ka === null && kb === null) return 0;
      if (ka === null) return 1;
      if (kb === null) return -1;
      const na = typeof ka === 'number', nb = typeof kb === 'number';
      let cmp;
      if (na && nb) cmp = ka - kb;
      else if (na !== nb) cmp = na ? -1 : 1; // 数值排在文本前（Calc 升序口径）
      else cmp = String(ka).localeCompare(String(kb), 'zh');
      return asc ? cmp : -cmp;
    });
    try {
      for (let r = 0; r < dataRows.length; r++) {
        for (let c = 0; c < nCols; c++) {
          const cell = range.getCellByPosition(c, headerOfs + r);
          const it = dataRows[r][c];
          if (it.k === 'v') cell.setValue(it.v);
          else if (it.k === 'f') cell.setFormula(it.f);
          else if (it.k === 's') cell.setString(it.s);
          else cell.setString('');
        }
      }
    } catch (e) { return { success: false, message: '排序回写失败: ' + errStr(e) }; }
    try { ctrl.select(range); } catch (e) {}
    // 验证回路：回读排序列前几个值
    const sample = [];
    try {
      for (let r = headerOfs; r < Math.min(nRows, headerOfs + 5); r++) {
        sample.push(readCellOut(range.getCellByPosition(keyOfs, r)));
      }
    } catch (e) {}
    return { success: true, range: sheetRangeName(addr), byColumn: colLetterOf(byCol), ascending: asc, sortedSample: sample };
  },
  // [表格·结构] 自动筛选开关。经命名数据库区域（DatabaseRanges）实现——确定性
  // 的 set 语义，比 .uno:DataFilterAutoFilter 的 toggle 可靠。range 缺省 = 已用区域。
  sheet_set_autofilter(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const sheet = r0.sheet;
    let addr;
    if (p.range) {
      const range = sheetRange(sheet, p.range);
      if (!range) return { success: false, message: '无效的区域: ' + p.range };
      addr = range.getRangeAddress();
    } else {
      try { addr = usedRangeAddress(sheet); } catch (e) { return { success: false, message: '读取已用区域失败: ' + errStr(e) }; }
    }
    const enabled = p.enabled !== false;
    const dbName = '__awd_af_' + addr.Sheet;
    try {
      const dbs = xModel.getPropertyValue('DatabaseRanges');
      if (dbs.hasByName(dbName)) {
        try { dbs.getByName(dbName).setPropertyValue('AutoFilter', false); } catch (e) {}
        dbs.removeByName(dbName);
      }
      if (enabled) {
        dbs.addNewByName(dbName, addr);
        dbs.getByName(dbName).setPropertyValue('AutoFilter', true);
      }
    } catch (e) { return { success: false, message: '筛选设置失败: ' + errStr(e) }; }
    return { success: true, range: sheetRangeName(addr), autoFilter: enabled };
  },
  // [表格·结构] 冻结窗格（XViewFreezable）：冻结前 rows 行 / cols 列；0,0 取消。
  sheet_freeze_panes(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const rows = Math.max(0, Number(p.rows) || 0);
    const cols = Math.max(0, Number(p.cols) || 0);
    try {
      // 取消必须走 splitAtPosition(0,0)：freezeAtPosition(0,0) 在引擎里仍算
      // 冻结态（hasFrozenPanes 保持 true，组 19 真机实证）。
      if (rows === 0 && cols === 0) ctrl.splitAtPosition(0, 0);
      else ctrl.freezeAtPosition(cols, rows);
    } catch (e) { return { success: false, message: '冻结窗格失败: ' + errStr(e) }; }
    let frozen = null;
    try { frozen = ctrl.hasFrozenPanes(); } catch (e) {}
    return { success: true, rows: rows, cols: cols, hasFrozenPanes: frozen };
  },
  // [表格·结构] 条件格式：满足条件的单元格套指定外观（底色/字色/加粗）。
  // rule: greater/greaterEqual/less/lessEqual/equal/notEqual/between/notBetween/
  // formula；clear=true 清除区域的全部条件格式。命中外观落在自建 CellStyle 上
  //（__awd_cf_N），set 语义：每次调用替换该区域现有条件格式（确定性优先）。
  sheet_conditional_format(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return { success: false, message: r0.error };
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return { success: false, message: '无效的区域: ' + (p.range || '(空)') };
    if (p.clear) {
      try {
        const cf0 = range.getPropertyValue('ConditionalFormat');
        cf0.clear();
        range.setPropertyValue('ConditionalFormat', cf0);
      } catch (e) { return { success: false, message: '清除条件格式失败: ' + errStr(e) }; }
      return { success: true, range: sheetRangeName(range.getRangeAddress()), cleared: true };
    }
    const OPS = {
      greater: 'GREATER', greaterequal: 'GREATER_EQUAL', less: 'LESS', lessequal: 'LESS_EQUAL',
      equal: 'EQUAL', notequal: 'NOT_EQUAL', between: 'BETWEEN', notbetween: 'NOT_BETWEEN', formula: 'FORMULA',
    };
    const opKey = OPS[String(p.rule || '').toLowerCase().replace(/[_-]/g, '')];
    if (!opKey) return { success: false, message: 'bad rule: ' + p.rule + ' (greater/greaterEqual/less/lessEqual/equal/notEqual/between/notBetween/formula)' };
    if (p.value1 == null || String(p.value1) === '') return { success: false, message: '条件格式需要 value1（数值或公式）' };
    if ((opKey === 'BETWEEN' || opKey === 'NOT_BETWEEN') && (p.value2 == null || String(p.value2) === '')) {
      return { success: false, message: 'between/notBetween 需要 value2' };
    }
    // 命中外观 = 一个专用 CellStyle（条件格式只认样式名，不收散属性）
    let styleName;
    try {
      const styles = xModel.getStyleFamilies().getByName('CellStyles');
      do { styleName = '__awd_cf_' + (++cfStyleSeq); } while (styles.hasByName(styleName));
      const style = xModel.createInstance('com.sun.star.style.CellStyle');
      styles.insertByName(styleName, style);
      if (p.background != null) {
        const c = parseColor(p.background, { none: -1 });
        if (c == null) return { success: false, message: 'bad background: ' + p.background };
        style.setPropertyValue('CellBackColor', c);
      }
      if (p.color != null) {
        const c = parseColor(p.color, { auto: -1 });
        if (c == null) return { success: false, message: 'bad color: ' + p.color };
        style.setPropertyValue('CharColor', c);
      }
      if (p.bold) style.setPropertyValue('CharWeight', css.awt.FontWeight.BOLD);
      if (p.background == null && p.color == null && !p.bold) {
        return { success: false, message: '条件格式需要至少一项外观（background/color/bold）' };
      }
    } catch (e) { return { success: false, message: '条件格式样式创建失败: ' + errStr(e) }; }
    try {
      const cf = range.getPropertyValue('ConditionalFormat');
      cf.clear();
      const entry = [
        mkProp('Operator', css.sheet.ConditionOperator[opKey]),
        mkProp('Formula1', normalizeFormula(String(p.value1))),
        mkProp('StyleName', styleName),
      ];
      if (p.value2 != null && String(p.value2) !== '') entry.push(mkProp('Formula2', normalizeFormula(String(p.value2))));
      cf.addNew(entry);
      range.setPropertyValue('ConditionalFormat', cf);
      const readBack = range.getPropertyValue('ConditionalFormat');
      return {
        success: true, range: sheetRangeName(range.getRangeAddress()),
        rule: String(p.rule), entries: readBack.getCount(), styleName: styleName,
      };
    } catch (e) { return { success: false, message: '条件格式设置失败: ' + errStr(e) }; }
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
    const p = params || {};
    // 修订署名切换：AI 命令（宿主打 __agent 标记）→ AI Workdeck；其余（IME 输入
    // 等用户本人操作）→ 用户名。失败不阻断命令本身（降级为引擎默认作者）。
    try { setRedlineAuthor(p.__agent ? AI_AUTHOR : humanAuthor); }
    catch (e) { log('修订作者设置失败 / redline author failed: ' + errStr(e)); }
    const fn = EXEC[action];
    result = fn ? fn(p) : { success: false, message: 'not implemented in LibreOffice worker yet: ' + action };
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
