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
const AI_AUTHOR = 'AI WorkDeck';
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
// ---- 段落索引缓存（dev-board#108 G3）------------------------------------------
// 每次 get_document_text / get_paragraph 都从头枚举 900+ 段是 O(n)（150 页实测
// 2-5s/次，AI 逐页读一遍报告 = 每页付一次全扫）。这里一次枚举把每段的 XTextRange
// （段落对象本身，引擎里是带标记的活对象，内容变了它跟着变）存进数组，之后按
// 下标 O(1) 取。失效规则：(1) modified 监听器——任何文档改动都会触发，这是主闸；
// (2) 索引绑定 xModel 引用，换文档（load_document / 测试探针直接换 xModel）自然
// 重建；(3) 写原语末尾的 verifySnapshot 再补一刀。段落被删后旧对象会抛异常，
// withParaIndex 捕到就重建一次再读，仍失败才冒泡。
const paraIndex = { model: null, ranges: null, total: 0 };
function invalidateParaIndex() { paraIndex.ranges = null; paraIndex.total = 0; paraIndex.model = null; }
function buildParaIndex() {
  const ranges = [];
  const en = xModel.getText().createEnumeration();
  while (en.hasMoreElements()) {
    const el = en.nextElement();
    if (el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) ranges.push(el);
  }
  paraIndex.ranges = ranges; paraIndex.total = ranges.length; paraIndex.model = xModel;
  return paraIndex;
}
function getParaIndex() { return (paraIndex.ranges && paraIndex.model === xModel) ? paraIndex : buildParaIndex(); }
// fn(index) 只许做读操作——段落对象失效抛异常时会重建索引再调一次 fn。
function withParaIndex(fn) {
  try { return fn(getParaIndex()); }
  catch (e) { invalidateParaIndex(); return fn(getParaIndex()); }
}
// 取第 idx（0 基）段，顺手 getString 验活；越界返回 null。写原语先用它拿段落，
// 再在 withParaIndex 之外改，免得重试把副作用做两遍。
function paraAt(idx) {
  return withParaIndex(function (ix) {
    const el = ix.ranges[idx];
    if (!el) return null;
    el.getString();
    return el;
  });
}
// Enumerate body paragraphs, calling fn(el, index); fn returns true to stop.
// 走索引缓存：fn 里只做读（见 withParaIndex）。
function eachParagraph(fn) {
  withParaIndex(function (ix) {
    for (let i = 0; i < ix.total; i++) { if (fn(ix.ranges[i], i)) return; }
  });
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
// 两个文本区间的起点是否重合——list_revisions 用它判断相邻修订是否首尾相接。
// 跨 XText（正文 vs 表格单元格）比较时引擎抛 IllegalArgumentException：那本来
// 也不算相接，吞掉当 false。
function rangeStartsEqual(a, b) {
  if (!a || !b) return false;
  try { return a.getText().compareRegionStarts(a, b) === 0; } catch (e) { return false; }
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
// ref 接受两种形状：数字 index（枚举顺序，ReviewPanel 既有用法）或字符串 id
// （批注的 Name 属性——AI 工具面用它替代 index，因为 index 在任何一条批注被
// 处置/删除后就会整体前移，同一会话里两次调用之间不可靠）。
function commentAt(ref) {
  if (typeof ref === 'string' && ref !== '' && !/^\d+$/.test(ref)) {
    try {
      const en = xModel.getTextFields().createEnumeration();
      while (en.hasMoreElements()) {
        const f = en.nextElement();
        if (!(f.supportsService && f.supportsService('com.sun.star.text.textfield.Annotation'))) continue;
        try { if (String(f.getPropertyValue('Name')) === ref) return f; } catch (e) {}
      }
    } catch (e) {}
    return null;
  }
  const want = Number(ref);
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
function commentIdOf(f) {
  try { return String(f.getPropertyValue('Name') || ''); } catch (e) { return ''; }
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

// 缩放上下限。LO 自身允许 20%..600%，越界写进去引擎会自己夹，但夹之前会先按
// 非法值重排一次版；在 JS 侧先夹住，捏合手势连发时不至于抖。
const ZOOM_MIN = 20, ZOOM_MAX = 600;

// LO 自己的 chrome（自建工具栏要关掉的那些）。singlemode-* 是选中表格/图片时
// 自动冒出来的上下文工具栏——逐项关的时候必须连它们一起关，否则一选中表格就
// 又钻出一条老气的工具栏。真机枚举所得（LayoutManager.getElements）。
const CHROME_URLS = {
  menubar: 'private:resource/menubar/menubar',
  statusbar: 'private:resource/statusbar/statusbar',
  toolbars: [
    'private:resource/toolbar/standardbar',
    'private:resource/toolbar/textobjectbar',
    'private:resource/toolbar/findbar',
    'private:resource/toolbar/singlemode-ole',
    'private:resource/toolbar/singlemode-draw',
    'private:resource/toolbar/singlemode-form',
    'private:resource/toolbar/singlemode-text',
    'private:resource/toolbar/singlemode-frame',
    'private:resource/toolbar/singlemode-media',
    'private:resource/toolbar/singlemode-table',
    'private:resource/toolbar/singlemode-graphic',
    'private:resource/toolbar/singlemode-drawtext',
    'private:resource/toolbar/singlemode-annotation',
    'private:resource/toolbar/singlemode-printpreview',
  ],
};

// Desktop-keyboard parity set for the IME overlay's ui_command action — an
// ALLOWLIST map (name -> .uno: slot), deliberately NOT a raw dispatch
// passthrough. Toggles (bold/italic/underline) are the engine's own, so
// collapsed-cursor and mixed-selection semantics match the desktop app.
//
// 自建工具栏（见 docs/superpowers/specs/2026-08-14-editor-chrome-self-built-toolbar.md）
// 的按钮同样走这张表。**白名单是安全边界，不许改成任意 .uno: 透传**——那等于把
// 宿主 DOM 变成引擎的任意命令通道。新增按钮就在这里加一行。
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
  // ---- [P1 自建工具栏] 字符格式 ----
  strikeout: '.uno:Strikeout',
  superscript: '.uno:SuperScript', subscript: '.uno:SubScript',
  // 字号增大/减小**不走 .uno:Grow/.uno:Shrink**——本引擎上这两个槽是哑弹，
  // 派发不报错也不改 CharHeight（真机实证 12→12）。宿主用 get_ui_state 读到
  // 当前字号再调 format_selection{sizePt} 自己步进，别留成点了没反应的按钮。
  clear_formatting: '.uno:ResetAttributes',               // 清除直接格式
  case_upper: '.uno:ChangeCaseToUpper', case_lower: '.uno:ChangeCaseToLower',
  // ---- [P1 自建工具栏] 段落格式 ----
  align_left: '.uno:LeftPara', align_center: '.uno:CenterPara',
  align_right: '.uno:RightPara', align_justify: '.uno:JustifyPara',
  indent_more: '.uno:IncrementIndent', indent_less: '.uno:DecrementIndent',
  bullet_list: '.uno:DefaultBullet', number_list: '.uno:DefaultNumbering',
  // ---- [P1 自建工具栏] 插入 / 视图 ----
  page_break: '.uno:InsertPagebreak',
  formatting_marks: '.uno:ControlCodes',                  // 显示/隐藏格式标记
};

// Shared verification snapshot returned by mutating commands: where the cursor
// is now + the paragraph as it reads AFTER the edit ("改完看一眼").
function verifySnapshot() {
  invalidateParaIndex(); // 写原语刚改过文档，段落索引不可信（modified 监听器之外再补一刀）
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
// 一次 XMultiPropertySet.setPropertyValues 代替 N 次 setPropertyValue：全文格式化
// 的成本就是 UNO 往返次数（920 段 x 十几个属性 + 30 表 x 60 格），批写把往返数砍到
// 约 1/10。对象不支持或任一属性被拒就返回 false，调用方退回逐个写的老路径
//（逐个写本身幂等，重复一遍不会改坏）。
function setPropsBatch(ps, names, values) {
  if (!ps || typeof ps.setPropertyValues !== 'function') return false;
  try { ps.setPropertyValues(names, values); return true; } catch (e) { return false; }
}
function applyHouseChar(ps, opts) {
  const o = opts || {};
  const names = ['CharFontName', 'CharFontNameAsian', 'CharFontNameComplex',
    'CharHeight', 'CharHeightAsian', 'CharHeightComplex', 'CharColor'];
  const values = [HOUSE.fontWestern, HOUSE.fontAsian, HOUSE.fontWestern,
    o.sizePt || HOUSE.bodyPt, o.sizePt || HOUSE.bodyPt, o.sizePt || HOUSE.bodyPt, 0x000000];
  if (!o.keepWeight) {
    const w = o.bold ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL;
    names.push('CharWeight', 'CharWeightAsian', 'CharWeightComplex');
    values.push(w, w, w);
  }
  if (setPropsBatch(ps, names, values)) return;
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
  {
    const indent0 = (isTitle || isCell || kind === 'list') ? 0 : ptToMm100(HOUSE.indentChars * HOUSE.bodyPt);
    const names = ['ParaAdjust', 'ParaTopMargin', 'ParaBottomMargin', 'ParaLineSpacing', 'ParaFirstLineIndent'];
    const values = [
      isTitle ? css.style.ParagraphAdjust.CENTER : css.style.ParagraphAdjust.BLOCK,
      isCell ? HOUSE.tableParaSpaceMm : (o.afterTable ? HOUSE.afterTableBeforeMm : 0),
      isCell ? HOUSE.tableParaSpaceMm : HOUSE.spaceAfterMm,
      new css.style.LineSpacing({ Mode: css.style.LineSpacingMode.MINIMUM, Height: isCell ? HOUSE.tableLineMinMm : HOUSE.lineMinMm }),
      indent0,
    ];
    if (!isCell) { names.push('ParaLeftMargin', 'ParaRightMargin'); values.push(0, 0); }
    if (setPropsBatch(ps, names, values)) return;
  }
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

// ---- Writer 表格单元格级原语（doc_table_*）helpers ---------------------------
// insert_table/format_table 是"整张表"粒度；这一组是"改一格/加一行"粒度，
// 模型据此把既有合同附表改成想要的样子。定位与校验集中在 resolveWriterTable，
// 失败一律带上"表格张数/行列数"这类可行动信息（模型据此换参数重试）。
const NOT_TEXT_DOC_MSG = '当前打开的不是 Word 文档：doc_table_* 原语仅对 doc/docx 生效。电子表格的表格请用 sheet_* 原语。';
function isWriterDoc() {
  try { return !!(xModel && xModel.supportsService && xModel.supportsService('com.sun.star.text.TextDocument')); }
  catch (e) { return false; }
}
// 定位一张 Writer 表格：tableName / tableIndex（0 开始）显式指定，缺省用光标所在表。
// 返回 {table, name, index, count} 或 {error}。
function resolveWriterTable(p) {
  if (!isWriterDoc()) return { error: NOT_TEXT_DOC_MSG };
  let tables;
  try { tables = xModel.getTextTables(); } catch (e) { return { error: '读取文档表格失败: ' + errStr(e) }; }
  const count = tables.getCount();
  const wantName = p && p.tableName != null && String(p.tableName).trim() !== '' ? String(p.tableName).trim() : null;
  let table = null;
  if (wantName) {
    if (!tables.hasByName(wantName)) return { error: '表格不存在: ' + wantName + '（文档共 ' + count + ' 张表，可用 doc_table_read 传 tableIndex 逐张确认）' };
    table = tables.getByName(wantName);
  } else if (p && p.tableIndex != null && String(p.tableIndex) !== '') {
    const i = Number(p.tableIndex);
    if (!(i >= 0) || i >= count) return { error: '表格序号越界: ' + p.tableIndex + '（文档共 ' + count + ' 张表，序号 0 开始）' };
    table = tables.getByIndex(i);
  } else {
    table = currentTextTable(null); // 光标所在表
    if (!table) {
      return { error: count
        ? '光标不在表格内：请传 tableIndex 指定第几张表（0 开始，文档共 ' + count + ' 张表）'
        : '文档中没有表格' };
    }
  }
  let name = '';
  try { name = table.getName(); } catch (e) {}
  let index = -1;
  for (let i = 0; i < count; i++) {
    try { if (tables.getByIndex(i).getName() === name) { index = i; break; } } catch (e) {}
  }
  return { table: table, name: name, index: index, count: count };
}
// 表格行列数。合并/拆分过的表 getColumns 只报"网格列"，不代表每行都有那么多格。
function tableDims(table) {
  try { return { rows: table.getRows().getCount(), cols: table.getColumns().getCount() }; }
  catch (e) { return { error: '读取表格行列数失败: ' + errStr(e) }; }
}
// "B2" -> {col:1, row:1, name:'B2'}（列字母不分大小写，行号 1 开始）。非法返回 null。
function parseCellRef(ref) {
  const m = /^([A-Za-z]+)(\d+)$/.exec(String(ref == null ? '' : ref).trim());
  if (!m) return null;
  const letters = m[1].toUpperCase();
  let col = 0;
  for (let i = 0; i < letters.length; i++) col = col * 26 + (letters.charCodeAt(i) - 64);
  const row = Number(m[2]);
  if (col < 1 || row < 1) return null;
  return { col: col - 1, row: row - 1, name: letters + row };
}
// 列定位：字母（A/B/AA，不分大小写）或 1 开始的列号。返回 0 开始的下标，非法返回 null。
function parseColumnRef(v) {
  const s = String(v == null ? '' : v).trim();
  if (!s) return null;
  if (/^\d+$/.test(s)) { const n = Number(s); return n >= 1 ? n - 1 : null; }
  if (!/^[A-Za-z]+$/.test(s)) return null;
  const letters = s.toUpperCase();
  let col = 0;
  for (let i = 0; i < letters.length; i++) col = col * 26 + (letters.charCodeAt(i) - 64);
  return col - 1;
}
// 统一的失败返回：error 供后端桥（前端 handleEditorCommand 只回传 result.error，
// 后端据此拼 {"error": ...}，ToolResult.success() 才判得出失败）；message 保持
// worker 既有约定（e2e 与日志都读它）。两者同文。
function tableFail(msg) { return { success: false, error: msg, message: msg }; }
function recordChangesOn() {
  try { return !!xModel.getPropertyValue('RecordChanges'); } catch (e) { return false; }
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
// 绝对引用形式的单元格坐标（'$A$1'），命名区域公式拼接用。
function absCellRef(col, row) { return '$' + colLetterOf(col) + '$' + (row + 1); }
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

// ---- Impress（演示文稿 slide_*）原语 helpers --------------------------------
// 引擎自 r4 起含 Impress 模块（doc-editor.md 待 r4 验收更新口径）；pptx/odp 经
// load_document 打开后由 Impress 承载，doc_*（xModel.getText()）/sheet_*
// （xModel.getSheets()）在演示文稿上必然失败——slide_* 是对等原语集。文档类型
// 守卫集中在 resolvePage/resolveShape（同 resolveSheet/resolveWriterTable 口径）。
// 设计依据：docs/superpowers/specs/2026-08-07-impress-bridge-design.md §4/§4.3。
function isImpressDoc() {
  try { return !!(xModel && xModel.supportsService && xModel.supportsService('com.sun.star.presentation.PresentationDocument')); }
  catch (e) { return false; }
}
const NOT_PRESENTATION_MSG = '当前打开的不是演示文稿：slide_* 原语仅对 pptx/ppt/odp 生效。Word 文档请用 doc_* 原语，表格请用 sheet_* 原语；要操作演示文稿请先用 doc_open_file 打开它。';
// 当前文档内核类型——get_doc_kind 诊断 action 与 load_document 返回值共用，宿主
// 据此按 kind 隐藏「审阅」按钮/ReviewPanel（Calc/Impress 都没有修订机制）。
function docKindOf() {
  try { if (isWriterDoc()) return 'writer'; } catch (e) {}
  try { if (isCalcDoc()) return 'calc'; } catch (e) {}
  try { if (isImpressDoc()) return 'impress'; } catch (e) {}
  return 'unknown';
}
// 统一失败返回：同时写 error（后端桥 handleEditorCommand 只回传 result.error）
// 与 message（worker 既有约定），两者同文——同 tableFail 口径（doc_table_* 已踩过）。
function slideFail(msg) { return { success: false, error: msg, message: msg }; }

// 位置/尺寸对外一律磅（pt），UNO 的 Position/Size 值结构体单位是 1/100 mm。
const PT_PER_HMM = 1 / 35.28;
function hmmToPt(hmm) { return Math.round(Number(hmm) * PT_PER_HMM * 100) / 100; }

// slideNumber 1 开始。命中后顺手 setCurrentPage（拟人：与 resolveSheet 切活动表
// 同一口径，操作在哪页要让用户看得见）。返回 {page, index} 或 {error}。
function resolvePage(p) {
  if (!isImpressDoc()) return { error: NOT_PRESENTATION_MSG };
  let pages;
  try { pages = xModel.getDrawPages(); } catch (e) { return { error: '读取幻灯片列表失败: ' + errStr(e) }; }
  const count = pages.getCount();
  const want = p && p.slideNumber != null ? Number(p.slideNumber) : NaN;
  if (!Number.isFinite(want) || want < 1 || want > count) {
    return { error: '页码越界: ' + (p && p.slideNumber) + '（共 ' + count + ' 页，1 开始）' };
  }
  const index = want - 1;
  let page;
  try { page = pages.getByIndex(index); } catch (e) { return { error: '定位幻灯片失败: ' + errStr(e) }; }
  try {
    if (ctrl && ctrl.getCurrentPage && ctrl.setCurrentPage) {
      const cur = ctrl.getCurrentPage();
      if (!cur || !unoSameDrawPage(cur, page)) ctrl.setCurrentPage(page);
    }
  } catch (e) {}
  return { page: page, index: index };
}
// XDrawPage 没有稳定的等值比较；退回按 Number 属性比对（GenericDrawPage.Number 只读）。
function unoSameDrawPage(a, b) {
  try { return a.getPropertyValue('Number') === b.getPropertyValue('Number'); } catch (e) { return a === b; }
}
// 用 .uno:MovePage* 把"当前页"从 fromIndex 挪到 toIndex（同一份 count 快照下，均
// 0-based）。调用前必须已 ctrl.setCurrentPage() 到源页。XDrawPages 无直接 move
// API（GenericDrawPage.Number 只读），只能走已验证可靠的 .uno: 派发——同「删除键
// 必须走 .uno: 调度」PR#164/166 一条经验；slide_move_page 与 slide_add_page 的
// 新页落点纠偏共用本函数。
// 定位 page 当前在 xModel.getDrawPages() 里的下标（0-based），找不到返回 -1、
// 顺带把当前总页数带出（out.total）。
function locatePageIndex(page, out) {
  try {
    const pages = xModel.getDrawPages();
    const total = pages.getCount();
    if (out) out.total = total;
    for (let i = 0; i < total; i++) {
      let cand; try { cand = pages.getByIndex(i); } catch (e) { continue; }
      if (unoSameDrawPage(cand, page)) return i;
    }
  } catch (e) {}
  return -1;
}
// 把 page 逐步移动到 toIndex（0-based），每步之间用 Number 属性核对是否真的挪动
// 了一步再继续——.uno:MovePageUp/Down 偶发不生效，不能假设一次 dispatch 必定
// 生效一步。不处理"挪进真正最后一页"这个特殊情形（见 movePageTo）。
function movePageStepwise(page, toIndex, maxSteps) {
  const cap = maxSteps || 50;
  let curIndex = -1;
  for (let steps = 0; steps <= cap; steps++) {
    const loc = {}; curIndex = locatePageIndex(page, loc);
    if (curIndex === -1) return -1;
    if (curIndex === toIndex || steps === cap) return curIndex;
    try {
      if (ctrl && ctrl.setCurrentPage) ctrl.setCurrentPage(page);
      let cur = null;
      try { cur = ctrl && ctrl.getCurrentPage ? ctrl.getCurrentPage() : null; } catch (e) {}
      if (!cur || !unoSameDrawPage(cur, page)) return -2;
      dispatchUno(curIndex < toIndex ? '.uno:MovePageDown' : '.uno:MovePageUp');
    } catch (e) { return curIndex; }
  }
  return curIndex;
}
// 把 page 移动到 toIndex（0-based）。真机实测（r4）：无论用 .uno:MovePageDown
// 连续派发，还是改派发 .uno:MovePageLast，把一页"挪进真正的最后一页"这最后一步
// 都会卡住不动（e2e 组 22 实锤：4 页文档从 index0 出发，无论哪种方式，都停在
// index2 到不了 index3）——但反方向没有这个毛病：.uno:MovePageUp 把"当前真正
// 排在最后的那一页"往前挪一步，观察不到同款卡死。于是目标是真正最后一页时改用
// 交换法：先把 page 挪到倒数第二（这一段是常规、非边界移动，走 movePageStepwise
// 已验证可靠），再把此刻排在最后的那一页用 MovePageUp 往前挪一步——等价于把
// page 换到了最后。返回 page 实际最终落点 index（-1/-2 = 失败，同 movePageStepwise
// 的错误码）。
function movePageTo(page, toIndex, maxSteps) {
  const loc0 = {}; const curIndex = locatePageIndex(page, loc0);
  if (curIndex === -1) return -1;
  const total = loc0.total;
  if (toIndex < 0 || toIndex >= total) return curIndex;
  if (toIndex !== total - 1) return movePageStepwise(page, toIndex, maxSteps);
  const nearLast = total - 2;
  if (nearLast >= 0 && curIndex !== nearLast) {
    const got = movePageStepwise(page, nearLast, maxSteps);
    if (got !== nearLast) return got; // 半路已失败，如实返回不再继续
  } else if (nearLast < 0) {
    return curIndex; // 只有 1 页，没有"最后一页"这个位移概念
  }
  let lastPageObj = null;
  try { lastPageObj = xModel.getDrawPages().getByIndex(total - 1); } catch (e) { return nearLast; }
  if (unoSameDrawPage(lastPageObj, page)) return total - 1; // 已经就是最后一页
  try {
    if (ctrl && ctrl.setCurrentPage) ctrl.setCurrentPage(lastPageObj);
    let cur = null;
    try { cur = ctrl && ctrl.getCurrentPage ? ctrl.getCurrentPage() : null; } catch (e) {}
    if (!cur || !unoSameDrawPage(cur, lastPageObj)) return nearLast;
    dispatchUno('.uno:MovePageUp');
  } catch (e) { return nearLast; }
  const loc1 = {}; return locatePageIndex(page, loc1);
}
// 未命名形状补一个稳定名 __awd_shape_N（页内下标），此后所有原语按名定位，
// 避免用会因增删漂移的 index。
function ensureShapeNames(page) {
  const n = page.getCount();
  // 真机实测（r4）：标题占位符形状会在页面经历其它结构性操作（加形状/加文本框
  // 等）后被引擎悄悄重建（对象换了个新的、未命名），若此时它恰好落在某个"之前
  // 已经把这个数字用掉"的下标上，单纯按当前下标拼名字会撞车——两个不同的形状
  // 拿到同一个 __awd_shape_N，按名定位（resolveShape）会取到错的那个（e2e 组 22
  // 实锤：矩形形状的名字后来指向了标题形状）。先收集页面上已占用的名字集合，
  // 分配新名字时跳过已占用的，保证同一页内任何时刻名字唯一。
  const used = new Set();
  for (let i = 0; i < n; i++) {
    let shape; try { shape = page.getByIndex(i); } catch (e) { continue; }
    let name = ''; try { name = shape.getName ? shape.getName() : ''; } catch (e) {}
    if (name) used.add(name);
  }
  let counter = 0;
  for (let i = 0; i < n; i++) {
    let shape; try { shape = page.getByIndex(i); } catch (e) { continue; }
    let name = ''; try { name = shape.getName ? shape.getName() : ''; } catch (e) {}
    if (name) continue;
    let candidate;
    do { candidate = '__awd_shape_' + (counter++); } while (used.has(candidate));
    try { shape.setName(candidate); used.add(candidate); } catch (e) {}
  }
}
// 形状文字：大多数形状（文本框/标题/占位符）实现 drawing.Text；线条/连接符/表格
// 外壳等不实现，统一 try/catch 归零而非抛错，读取面据此判断"这个形状没有文字"。
function shapeText(shape) {
  try { return shape.getText().getString() || ''; } catch (e) { return ''; }
}
// 鸭子类型表格判定：真机实测（r4，e2e 组 23）——刚用
// createInstance('com.sun.star.drawing.TableShape') + page.add() 建出来的表格
// 形状，经 page.getByIndex() 重新取到的引用上 supportsService('com.sun.star.
// drawing.TableShape') 报 false，但它的 Model 属性货真价实是一张能读写行列的
// XTable（slide_add_table 内部用同一属性建表已验证）。service 名判定在这个引擎
// 构建上对新建表格不可靠，改成直接尝试取 Model 并鸭子类型检查 getRows/
// getColumns 方法是否存在——不依赖 service 字符串，也不关心它为什么不可靠。
function isTableShape(shape) {
  try {
    const m = shape.getPropertyValue('Model');
    return !!(m && typeof m.getRows === 'function' && typeof m.getColumns === 'function');
  } catch (e) { return false; }
}
// 形状分类，仅用于 slide_get_overview/slide_get_page 的展示与 slide_replace_text
// 的表格识别；不影响文字读写路径本身（那条走 getText() 统一处理，与分类无关）。
function shapeKind(shape) {
  try {
    if (shape.supportsService('com.sun.star.presentation.TitleTextShape')) return 'title';
    if (shape.supportsService('com.sun.star.presentation.OutlineTextShape') ||
        shape.supportsService('com.sun.star.presentation.SubtitleTextShape') ||
        shape.supportsService('com.sun.star.presentation.NotesTextShape')) return 'placeholder';
    if (shape.supportsService('com.sun.star.drawing.TableShape') || isTableShape(shape)) return 'table';
    if (shape.supportsService('com.sun.star.drawing.GraphicObjectShape')) return 'image';
    if (shape.supportsService('com.sun.star.drawing.TextShape')) return 'text';
    if (shape.supportsService('com.sun.star.drawing.RectangleShape')) return 'rectangle';
    if (shape.supportsService('com.sun.star.drawing.EllipseShape')) return 'ellipse';
    if (shape.supportsService('com.sun.star.drawing.LineShape')) return 'line';
    if (shape.supportsService('com.sun.star.drawing.GroupShape')) return 'group';
  } catch (e) {}
  return 'other';
}
// 按 shapeName（XNamed.Name）或 matchText（子串匹配文字）定位形状。返回
// {shape, index, name} 或 {error}。调用前应已 ensureShapeNames(page)。
function resolveShape(page, p) {
  const n = page.getCount();
  const wantName = p && p.shapeName != null && String(p.shapeName).trim() !== '' ? String(p.shapeName).trim() : null;
  const wantText = p && p.matchText != null && String(p.matchText).trim() !== '' ? String(p.matchText).trim() : null;
  if (wantName) {
    for (let i = 0; i < n; i++) {
      let shape; try { shape = page.getByIndex(i); } catch (e) { continue; }
      let name = ''; try { name = shape.getName(); } catch (e) {}
      if (name === wantName) return { shape: shape, index: i, name: name };
    }
    return { error: '形状不存在: ' + wantName + '（该页共 ' + n + ' 个形状，可用 slide_get_page 查看）' };
  }
  if (wantText) {
    for (let i = 0; i < n; i++) {
      let shape; try { shape = page.getByIndex(i); } catch (e) { continue; }
      const text = shapeText(shape);
      if (text && text.indexOf(wantText) !== -1) {
        let name = ''; try { name = shape.getName(); } catch (e) {}
        return { shape: shape, index: i, name: name };
      }
    }
    return { error: '未找到包含文字的形状: ' + wantText };
  }
  return { error: '缺少 shapeName 或 matchText 参数' };
}
// 备注页正文形状定位。r4 真机验证发现：pptx 经 oox 过滤器导入后，备注页上的
// 占位符形状（缩略图/备注正文/页码）一律只 supportsService('presentation.Shape')
// 这个通用服务，具体子类型（NotesTextShape/SlideNumberShape 等）不会出现在
// supportsService/getSupportedServiceNames 里——原语按 NotesTextShape 判定会
// 对真实 pptx 100% 落空（swriter/scalc 的 isWriterDoc/isCalcDoc 靠 xModel 顶层
// 服务判型不受影响，这是 sd 占位符子类型独有的坑）。三级回退定位：
//  1) 具体服务判定（若未来某个 LO 版本/ODP 原生文档确实实现了它，优先信）；
//  2) 按名字（"Notes Placeholder" 是 PowerPoint/python-pptx 备注母版模板的默认
//     英文命名，与文档 UI 语言无关，比 PlaceholderText 的本地化提示文字稳）；
//  3) 兜底：排除已知非备注占位符（缩略图/页码/日期/页脚/页眉）后，取支持
//     drawing.Text 的形状里面积最大的那个（备注正文框通常占据版面主体）。
function findNotesTextShape(notesPage) {
  if (!notesPage) return null;
  const n = notesPage.getCount();
  for (let i = 0; i < n; i++) {
    let shape; try { shape = notesPage.getByIndex(i); } catch (e) { continue; }
    try { if (shape.supportsService('com.sun.star.presentation.NotesTextShape')) return shape; } catch (e) {}
  }
  const NON_NOTES_NAME = /slide image|slide number|date placeholder|footer placeholder|header placeholder/i;
  for (let i = 0; i < n; i++) {
    let shape; try { shape = notesPage.getByIndex(i); } catch (e) { continue; }
    let name = ''; try { name = shape.getName ? shape.getName() : ''; } catch (e) {}
    if (/notes/i.test(name) && !NON_NOTES_NAME.test(name)) return shape;
  }
  let best = null, bestArea = -1;
  for (let i = 0; i < n; i++) {
    let shape; try { shape = notesPage.getByIndex(i); } catch (e) { continue; }
    let name = ''; try { name = shape.getName ? shape.getName() : ''; } catch (e) {}
    if (NON_NOTES_NAME.test(name)) continue;
    let hasText = false; try { hasText = shape.supportsService('com.sun.star.drawing.Text'); } catch (e) {}
    if (!hasText) continue;
    let size = null; try { size = shape.getSize(); } catch (e) {}
    const area = size ? Number(size.Width) * Number(size.Height) : 0;
    if (area > bestArea) { bestArea = area; best = shape; }
  }
  return best;
}
// 备注页文字：找到备注正文形状后读回其文字（找不到则视为无备注，不是错误）。
function notesPageText(notesPage) {
  const shape = findNotesTextShape(notesPage);
  return shape ? shapeText(shape) : '';
}
// 版式名最佳努力映射（AutoLayout 是 short 常量，未在本次调研中逐值核对 idl，
// 仅覆盖几个常被引用的值；命中不了就回退成 null，前端/模型仍能用数字 layout
// 字段）。r4 真机验证时如与实际枚举不符，直接改这张表，不影响原语契约。
const AUTO_LAYOUT_NAMES = { 0: '标题页', 1: '标题+内容', 19: '仅标题', 20: '空白' };
function layoutNameOf(layout) {
  if (layout == null) return null;
  const n = Number(layout);
  return Object.prototype.hasOwnProperty.call(AUTO_LAYOUT_NAMES, n) ? AUTO_LAYOUT_NAMES[n] : null;
}

// ---- Impress Phase 3（格式与表格）helpers -----------------------------------
// 设计依据：docs/superpowers/specs/2026-08-07-impress-bridge-design.md §4.2（原语 16-20）。
// 形状文字格式读回：取首字符的字符属性作为整个形状的代表值（同 get_formatting
// 读光标处格式的口径，不逐字符扫描）——slide_get_page 的 format 字段用它填充，
// 供 slide_format_text 的调用方核实真实生效值，而不是只信 setter 自己的回声。
// 无文字的形状返回 null。
function shapeCharFormat(shape) {
  let xText; try { xText = shape.getText(); } catch (e) { return null; }
  let full = ''; try { full = xText.getString() || ''; } catch (e) {}
  if (!full) return null;
  let cur;
  try { cur = xText.createTextCursor(); cur.gotoStart(false); cur.goRight(1, true); }
  catch (e) { return null; }
  const out = {};
  try { out.fontName = cur.getPropertyValue('CharFontName'); } catch (e) {}
  try { out.fontSize = cur.getPropertyValue('CharHeight'); } catch (e) {}
  try { out.bold = cur.getPropertyValue('CharWeight') > 100; } catch (e) {}
  try { out.italic = !enumEq(cur.getPropertyValue('CharPosture'), css.awt.FontSlant.NONE); } catch (e) {}
  try { out.underline = !enumEq(cur.getPropertyValue('CharUnderline'), css.awt.FontUnderline.NONE); } catch (e) {}
  try { out.strikethrough = !enumEq(cur.getPropertyValue('CharStrikeout'), css.awt.FontStrikeout.NONE); } catch (e) {}
  try { const c = cur.getPropertyValue('CharColor'); out.color = c === -1 ? 'auto' : '#' + ('000000' + (c >>> 0).toString(16)).slice(-6); } catch (e) {}
  try {
    const a = cur.getPropertyValue('ParaAdjust');
    const A = css.style.ParagraphAdjust;
    out.alignment = enumEq(a, A.CENTER) ? 'center' : enumEq(a, A.RIGHT) ? 'right'
      : (enumEq(a, A.BLOCK) || enumEq(a, A.STRETCH)) ? 'justify' : 'left';
  } catch (e) {}
  return out;
}
// 定位一张 Impress 表格形状：shapeName 显式指定；缺省要求该页恰好一张表格形状
// （Impress 没有"光标所在表"的等价概念，不能像 resolveWriterTable 那样回退）。
// 返回 {table, shapeName} 或 {error}。调用前应已 resolvePage(p) 拿到 page。
function resolveSlideTable(page, p) {
  ensureShapeNames(page);
  const wantName = p && p.shapeName != null && String(p.shapeName).trim() !== '' ? String(p.shapeName).trim() : null;
  if (wantName) {
    const rs = resolveShape(page, { shapeName: wantName });
    if (rs.error) return { error: rs.error };
    if (shapeKind(rs.shape) !== 'table') return { error: '形状不是表格: ' + rs.name + '（用 slide_get_page 确认哪个形状是表格）' };
    let table; try { table = rs.shape.getPropertyValue('Model'); } catch (e) { return { error: '读取表格模型失败: ' + errStr(e) }; }
    if (!table) return { error: '表格模型不可用（引擎未返回 Model）' };
    return { table: table, shapeName: rs.name };
  }
  const n = page.getCount();
  let found = null, foundName = '', count = 0;
  for (let i = 0; i < n; i++) {
    let shape; try { shape = page.getByIndex(i); } catch (e) { continue; }
    if (shapeKind(shape) === 'table') {
      count++;
      if (!found) { found = shape; try { foundName = shape.getName(); } catch (e) {} }
    }
  }
  if (count === 0) return { error: '该页没有表格形状（用 slide_add_table 先建一张，或传 shapeName 指定其它页的表格）' };
  if (count > 1) return { error: '该页有 ' + count + ' 张表格，请传 shapeName 指定操作哪一张' };
  let table; try { table = found.getPropertyValue('Model'); } catch (e) { return { error: '读取表格模型失败: ' + errStr(e) }; }
  if (!table) return { error: '表格模型不可用（引擎未返回 Model）' };
  return { table: table, shapeName: foundName };
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
  try { installSelectionListener(ctrl); } catch (e) { log('XSelectionChangeListener 安装失败 / install failed: ' + errStr(e)); }
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
// 导出期间抑制 modified 上报：storeToURL 会把文档标成已修改，见 export_document。
let exportInFlight = false;

// XModifyBroadcaster fires `modified` on every document change regardless of
// origin (canvas typing, IME overlay commit, AI agent command) — the one seam
// that sees them all. The editor page throttles and relays the signal to the
// host, which debounce-saves (LibreOfficeEditor.autoSave). isModified() filters
// out the broadcast a later setModified(false) would emit.
function installModifyListener(model) {
  const listener = zetajs.unoObject([css.util.XModifyListener], {
    // exportInFlight：storeToURL 自己会把文档标成 modified（见 export_document）。
    // 不挡掉的话每次自动保存都会引出一次 modified，宿主据此再排一次保存——
    // 实测形成每 3 秒一轮的「保存→modified→保存」死循环，整份 docx 反复上传，
    // 且 export 是全文档同步序列化，会周期性冻住 Qt 事件循环。
    modified() { invalidateParaIndex(); try { if (exportInFlight) return; if (model.isModified()) post('modified'); } catch (e) { /* ignore */ } },
    disposing() {},
  });
  model.addModifyListener(listener);
  log('XModifyListener 已装 / installed — 文档修改将上报宿主触发自动保存');
}

// ---- 自建工具栏：选区变化上报 -------------------------------------------
// 工具栏的激活态（B 是否高亮、当前字体字号样式对齐）必须跟着光标走，而 LO 不会
// 主动把「选区变了」推出来。装在 controller 上（XSelectionSupplier），每次触发
// 就给宿主发一个信号，宿主据此重读 get_ui_state。
//
// **这条通道只覆盖「选区」类变化**：真机实测扩选/全选/选中段落每次都触发，但
// **纯光标移动（塌陷选区左右挪）基本不触发**。所以编辑器页还会在画布 mouseup
// 和覆盖层转发控制键之后各补一发——两路合起来才盖得住用户的实际操作。
function installSelectionListener(controller) {
  const listener = zetajs.unoObject([css.view.XSelectionChangeListener], {
    selectionChanged() { try { post('sel_changed'); } catch (e) { /* ignore */ } },
    disposing() {},
  });
  controller.addSelectionChangeListener(listener);
  log('XSelectionChangeListener 已装 / installed — 选区变化将上报宿主刷新工具栏');
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

// ---- 脚注/尾注 helper --------------------------------------------------------
// com.sun.star.text.Footnote 服务同时承载脚注与尾注——由 IsEndnote 属性区分
// （常见 LO/OOo 宏写法）；该服务本身实现 XText，footnote.setString() 直接写
// 注释正文。锚点插在光标处，可选 anchorId 复用既有 anchorRange() 精确定位。
function insertFootnoteImpl(p, isEndnote) {
  if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
  const text = p && p.text != null ? String(p.text) : '';
  if (!text) return tableFail((isEndnote ? 'insert_endnote' : 'insert_footnote') + ' requires {text}');
  const anchorId = p && p.anchor ? String(p.anchor) : '';
  const vc = ctrl.getViewCursor();
  if (anchorId) {
    const r = anchorRange(anchorId);
    if (!r) return tableFail('anchor not found: ' + anchorId);
    try { vc.gotoRange(r.getEnd(), false); } catch (e) { return tableFail('无法定位锚点: ' + errStr(e)); }
  } else {
    vc.collapseToEnd();
  }
  let note;
  try {
    note = xModel.createInstance('com.sun.star.text.Footnote');
  } catch (e) { return tableFail('创建' + (isEndnote ? '尾注' : '脚注') + '失败: ' + errStr(e)); }
  if (isEndnote) {
    try { note.setPropertyValue('IsEndnote', true); }
    catch (e) { return tableFail('引擎不支持尾注（IsEndnote 属性设置失败）: ' + errStr(e)); }
  }
  try {
    vc.getText().insertTextContent(vc, note, false);
    note.setString(text);
  } catch (e) { return tableFail('插入' + (isEndnote ? '尾注' : '脚注') + '失败: ' + errStr(e)); }
  try { vc.collapseToEnd(); } catch (e) {}
  let label = '';
  try { label = String(note.getLabel() || ''); } catch (e) {}
  return { success: true, text: text, label: label, endnote: !!isEndnote, anchor: anchorId || null };
}

// ==========================================================================
// Editor executor command contract (Epic #43 task ④, worker side).
// Implements the SAME editor-agnostic actions that the agent command pipeline
// dispatches, via UNO — so frontend/src/composables/useLibreOfficeBridge.js can
// drive LibreOffice with the backend's existing commands. Each handler returns a
// plain result object; the dispatcher posts {cmd:'result', reqId, result} back.
// [verified] handlers use the Phase 0-proven primitives; [todo] are stubs.
// ---- 长命令的分批 / 进度 / 取消（dev-board#108 G2）------------------------------
// office 线程是单事件循环：一条 20s 的 find_replace 会把后面所有命令（自动保存的
// export、IME 的 ui_command）全排队，宿主也只能干等。批量命令每处理一批就
// post 一次 progress 并 await 一个宏任务，让排队的命令和宿主的 cancel 有机会
// 插进来。取消是协作式的：宿主 executeCommand('cancel', {reqId}) 置位，批间检查。
// 批间别的命令可能切换修订作者（用户 IME 输入署用户名），所以每批开头重新
// 设回本命令的作者。
const CANCELLED = Object.create(null);   // reqId -> true
function yieldMacrotask() { return new Promise(function (r) { setTimeout(r, 0); }); }
function postProgress(p, done, total) { if (p && p.__reqId) post('progress', { reqId: p.__reqId, done: done, total: total }); }
// 批量改稿期间锁住控制器与动作锁：否则每一条修订 / 每一个属性写入都让 Writer
// 重排版+重绘一次，150 命中实测每处 130-200ms 大头在这里，不在 LCS。锁在一批之内，
// 批间解开让视图刷一次、让排队的 export 能正常序列化。
function lockModel() {
  try { xModel.lockControllers(); } catch (e) {}
  try { xModel.addActionLock(); } catch (e) {}
}
function unlockModel() {
  try { xModel.removeActionLock(); } catch (e) {}
  try { xModel.unlockControllers(); } catch (e) {}
}
// 批间让路：返回 true = 已被取消，调用方应停下并返回 {cancelled:true}。
// 调用方在批内持有 lockModel()；这里先解锁再让路，回来再上锁。
async function batchBreak(p, done, total) {
  unlockModel();
  postProgress(p, done, total);
  await yieldMacrotask();
  if (p && p.__reqId && CANCELLED[p.__reqId]) return true;
  try { setRedlineAuthor(p && p.__agent ? AI_AUTHOR : humanAuthor); } catch (e) {}
  lockModel();
  return false;
}

// ==========================================================================
const EXEC = {
  // [取消] 宿主对一条在飞的批量命令喊停（reqId 来自 progress 消息）。只置位，
  // 真正停下来要等那条命令跑到下一个批间检查点。
  cancel(p) {
    const id = String(p && p.reqId || '');
    if (!id) return tableFail('cancel: reqId required');
    CANCELLED[id] = true;
    return { success: true, reqId: id };
  },
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
  // 150 页实测 137-216ms/命中，大头是每命中滚视图 + UNO 往返：现在先 findAll 收齐
  // 命中（都是活的 XTextRange，改前面的不影响后面的），只在结束时滚到首处；
  // 命中 > 50 时按 30 一批，批间发 progress / 检查 cancel（见 batchBreak）。
  async find_replace(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(String(p.findText || ''));
    // matchCase 是后加的可选项（自建查找替换面板要它）；不传时行为与从前一致。
    try { sd.setPropertyValue('SearchCaseSensitive', !!p.matchCase); } catch (e) {}
    const all = p.replaceAll !== false;
    const replaceText = String(p.replaceText || '');
    const hits = [];
    if (all) {
      const found = xModel.findAll(sd);
      const cnt = found ? found.getCount() : 0;
      for (let i = 0; i < cnt; i++) hits.push(found.getByIndex(i));
    } else {
      const first = xModel.findFirst(sd);
      if (first !== null) hits.push(first);
    }
    const total = hits.length;
    const BATCH = 30, batched = total > 50;
    let n = 0, cancelled = false;
    lockModel();
    try {
      for (let i = 0; i < total; i++) {
        if (batched && i > 0 && i % BATCH === 0) {
          if (await batchBreak(p, n, total)) { cancelled = true; break; }
        }
        const hit = hits[i];
        if (!applyMinimalRedline(hit, replaceText)) hit.setString(replaceText);
        n++;
      }
    } finally { unlockModel(); }
    if (batched) postProgress(p, n, total);
    if (hits[0]) selectVisibly(hits[0]); // 拟人：结束时视图停在第一处改动
    invalidateParaIndex();
    const r = { success: true, replaced: n, total: total, recordChanges: true };
    if (cancelled) { r.cancelled = true; r.done = n; }
    return r;
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
    const el = paraAt(idx);
    if (!el) return { success: false, message: 'paragraph index out of range: ' + idx + ' (count ' + getParaIndex().total + ')' };
    return { success: true, index: idx, text: el.getString() };
  },
  // [verified-extend] modify the Nth paragraph's text under RecordChanges.
  modify_paragraph(p) {
    xModel.setPropertyValue('RecordChanges', true);
    const idx = Number(p.index) || 0;
    const el = paraAt(idx);
    if (!el) return { success: false, message: 'paragraph index out of range: ' + idx };
    selectVisibly(el); // 拟人：先跳到目标段落
    if (!applyMinimalRedline(el, String(p.newText || ''))) el.setString(String(p.newText || ''));
    const after = el.getString().slice(0, 200);
    invalidateParaIndex();
    return { success: true, index: idx, paragraphAfterEdit: after };
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
  // 自建查找栏的「上一个/下一个」。**不用 find_text_locations**——那条路每个匹配
  // 插一个书签当锚点，书签会跟着文档存进 docx，用户只是搜个词不该在文件里留下
  // 一堆书签。这里全程用 findFirst/findNext 枚举，只动视图光标。
  //
  // 上一个：UNO 没有 findPrevious，所以按文档序收齐全部匹配（上限 500，再多的
  // 文档里逐个跳也没意义），再按当前光标位置挑前一个/后一个，到头绕回。
  find_navigate(p) {
    const kw = String((p && p.keyword) || '');
    if (!kw) return tableFail('find_navigate requires {keyword}');
    const dir = String((p && p.direction) || 'next').toLowerCase();
    const sd = xModel.createSearchDescriptor();
    sd.setSearchString(kw);
    try { sd.setPropertyValue('SearchCaseSensitive', !!(p && p.matchCase)); } catch (e) {}
    const all = [];
    let hit = null;
    try { hit = xModel.findFirst(sd); } catch (e) { return tableFail('查找失败: ' + errStr(e)); }
    while (hit !== null && all.length < 500) {
      all.push(hit);
      try { hit = xModel.findNext(hit, sd); } catch (e) { hit = null; }
    }
    if (!all.length) return { success: true, found: false, total: 0 };
    // 光标当前落在第几个匹配之后（compareRegionStarts(A,B)：A 在 B 前返回 1，
    // 相等返回 0）。跨 XText（表格单元格 vs 正文）会抛，跳过即可——那种匹配
    // 只是定位不到"当前在第几个"，不影响能跳过去。
    //
    // 起点相同要分两种情形，混为一谈会差一个：
    //   - 光标**塌陷**停在某处匹配的起点（如刚 goto 文首，而文首正好是匹配）：
    //     这一处还**没被访问过**，next 就应该落在它身上；
    //   - 光标**带选区**且起点相同：说明它就是刚被选中的当前这一处，next 要跳下一个。
    const vc = ctrl.getViewCursor();
    let collapsed = true;
    try { collapsed = (vc.getString() || '').length === 0; } catch (e) {}
    let curIdx = -1;
    for (let i = 0; i < all.length; i++) {
      try {
        const c = all[i].getText().compareRegionStarts(all[i], vc);
        if (c > 0) curIdx = i;                       // 严格在光标前 = 已越过
        else if (c === 0 && !collapsed) curIdx = i;  // 起点相同且选中着 = 当前这一处
      } catch (e) {}
    }
    const n = all.length;
    const target = dir === 'prev'
      ? (curIdx <= 0 ? n - 1 : curIdx - 1)
      : (curIdx < 0 ? 0 : (curIdx + 1) % n);
    if (!selectVisibly(all[target])) return tableFail('无法选中该匹配');
    return { success: true, found: true, index: target + 1, total: n, truncated: n >= 500 };
  },
  // 视图缩放（触控板捏合 / Cmd+加减号 / 工具栏缩放控件）。
  // {value} 给绝对百分比，{delta} 给相对增量；不传就只读回当前值。
  // ZoomType 必须先切 BY_VALUE——停在「适合页宽」这类自动模式时，引擎会按窗口
  // 重算 ZoomValue，写进去的数当场被覆盖。两个属性都是 sal_Int16，裸 number 会
  // 编组成 long 被严格 setter 拒绝（且常被 try 吞掉），必须走 shortAny。
  set_zoom(p) {
    let vs = null;
    try { vs = ctrl.getViewSettings(); } catch (e) { return { success: false, message: 'no view settings: ' + errStr(e) }; }
    let cur = 100;
    try { cur = Number(vs.getPropertyValue('ZoomValue')) || 100; } catch (e) {}
    const hasValue = p && p.value != null;
    const hasDelta = p && p.delta != null;
    if (!hasValue && !hasDelta) return { success: true, zoom: cur };
    let next = hasValue ? Number(p.value) : cur + Number(p.delta);
    if (!isFinite(next)) return { success: false, message: 'set_zoom: value/delta 不是数字' };
    next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, Math.round(next)));
    try { vs.setPropertyValue('ZoomType', shortAny(css.view.DocumentZoomType.BY_VALUE)); } catch (e) {}
    try { vs.setPropertyValue('ZoomValue', shortAny(next)); }
    catch (e) { return { success: false, message: 'set_zoom 失败: ' + errStr(e) }; }
    let applied = next;
    try { applied = Number(vs.getPropertyValue('ZoomValue')) || next; } catch (e) {}
    return { success: true, zoom: applied };
  },
  // ---- [P1 自建工具栏] 状态回读 / 样式清单 / chrome 开关 -------------------
  // 工具栏的激活态（B 是否高亮、当前字体字号样式对齐、能不能撤销）一次拿全。
  // 高频调用（选区事件 + 400ms 聚焦轮询），实测整套读一遍约 6ms——每个字段各自
  // try/catch，缺一个不影响其余，绝不因为某个属性在当前上下文不存在就整体失败。
  get_ui_state() {
    const out = { success: true };
    let vc = null;
    try { vc = ctrl.getViewCursor(); } catch (e) { return { success: false, message: errStr(e) }; }
    const ch = {};
    try { ch.bold = vc.getPropertyValue('CharWeight') > 100; } catch (e) {}
    try { ch.italic = !enumEq(vc.getPropertyValue('CharPosture'), css.awt.FontSlant.NONE); } catch (e) {}
    try { ch.underline = !enumEq(vc.getPropertyValue('CharUnderline'), css.awt.FontUnderline.NONE); } catch (e) {}
    try { ch.strikeout = !enumEq(vc.getPropertyValue('CharStrikeout'), css.awt.FontStrikeout.NONE); } catch (e) {}
    try {
      const esc = Number(vc.getPropertyValue('CharEscapement')) || 0;
      ch.superscript = esc > 0; ch.subscript = esc < 0;
    } catch (e) {}
    try { ch.font = vc.getPropertyValue('CharFontName'); } catch (e) {}
    try { ch.fontAsian = vc.getPropertyValue('CharFontNameAsian'); } catch (e) {}
    try { ch.sizePt = vc.getPropertyValue('CharHeight'); } catch (e) {}
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
    try { pa.outlineLevel = vc.getPropertyValue('OutlineLevel') || 0; } catch (e) {}
    // 列表种类：工具栏要分别高亮「项目符号」和「编号」两个按钮。NumberingIsNumber
    // 只说明在不在列表里，具体是符号还是数字要看规则第 0 级的 NumberingType。
    try {
      pa.inList = !!vc.getPropertyValue('NumberingIsNumber');
      if (pa.inList) {
        pa.listKind = 'list';   // 读不出细分时的保守取值
        const rules = vc.getPropertyValue('NumberingRules');
        if (rules && rules.getCount && rules.getCount() > 0) {
          const lvl = rules.getByIndex(0);
          let nt = null;
          for (let i = 0; i < (lvl.length || 0); i++) if (lvl[i].Name === 'NumberingType') nt = Number(lvl[i].Value);
          const NT = css.style.NumberingType;
          if (nt != null) pa.listKind = (nt === NT.CHAR_SPECIAL || nt === NT.BITMAP) ? 'bullet' : 'number';
        }
      } else pa.listKind = 'none';
    } catch (e) {}
    out.paragraph = pa;
    const view = {};
    try { view.zoom = ctrl.getViewSettings().getPropertyValue('ZoomValue'); } catch (e) {}
    try { view.recordChanges = !!xModel.getPropertyValue('RecordChanges'); } catch (e) {}
    out.view = view;
    const sel = {};
    try { sel.collapsed = (vc.getString() || '').length === 0; } catch (e) {}
    // 光标所在单元格（如 "B2"）。工具栏的表格组要靠它算出当前行/列，才能做
    // 「在上方插入行」「删除本列」这类相对操作——table_* 原语收的是 1 起的
    // 行号与列字母/列号，没有「当前位置」的概念。
    try {
      const cell = vc.getPropertyValue('Cell');
      sel.inTable = !!cell;
      if (cell) { try { sel.cellName = String(cell.getPropertyValue('CellName') || ''); } catch (e) {} }
    } catch (e) { sel.inTable = false; }
    out.selection = sel;
    // 撤销/重做可用性。UndoManager 不是属性（getPropertyValue 抛
    // UnknownPropertyException，真机验过），要走 XUndoManagerSupplier 的方法。
    // 实在拿不到就不给字段——宿主据此让两个按钮常亮，宁可多点一下也别灰掉能用的功能。
    try {
      const um = xModel.getUndoManager();
      const u = {};
      try { u.canUndo = !!um.isUndoPossible(); } catch (e) {}
      try { u.canRedo = !!um.isRedoPossible(); } catch (e) {}
      if (u.canUndo != null || u.canRedo != null) out.undo = u;
    } catch (e) { out.undoErr = errStr(e); }
    return out;
  },
  // 段落样式清单（样式下拉）。getElementNames() 给的是**英文程序名**
  // （Standard / Heading 1），中文界面要显示的是 DisplayName——两者都带回，
  // set_style 认的仍是程序名。
  list_styles(p) {
    if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
    const kind = String((p && p.kind) || 'paragraph').toLowerCase();
    const familyName = kind === 'character' ? 'CharacterStyles' : 'ParagraphStyles';
    try {
      const fam = xModel.getStyleFamilies().getByName(familyName);
      const names = fam.getElementNames();
      const out = [];
      for (let i = 0; i < (names.length || 0); i++) {
        const it = { name: String(names[i]) };
        try {
          const st = fam.getByName(names[i]);
          try { it.display = String(st.getPropertyValue('DisplayName') || it.name); } catch (e) { it.display = it.name; }
          try { it.inUse = !!st.isInUse(); } catch (e) {}
        } catch (e) { it.display = it.name; }
        out.push(it);
      }
      return { success: true, kind: kind, count: out.length, styles: out };
    } catch (e) { return tableFail('读取样式库失败: ' + errStr(e)); }
  },
  // LO 自己的 chrome 开关（自建工具栏上线后默认全关，设置里留逃生开关）。
  // {all:false} 走 LayoutManager.setVisible(false) 一刀切——这样上下文工具栏
  // （选中表格/图片时自动冒出来的 singlemode-*）也不会再钻出来；逐项开关用
  // {menubar/toolbars/statusbar/rulers}。
  // **hideElement() 的返回值恒为 false，不代表失败**（真机实证），一律用
  // isElementVisible() 复核后如实回报。
  set_chrome(p) {
    const req = p || {};
    const out = { success: true, applied: {} };
    let lm = null;
    try { lm = ctrl.getFrame().getPropertyValue('LayoutManager'); }
    catch (e) { return { success: false, message: 'LayoutManager 不可达: ' + errStr(e) }; }
    if (!lm) return { success: false, message: 'LayoutManager 为空' };
    const visible = (url) => { try { return !!lm.isElementVisible(url); } catch (e) { return null; } };
    // 显示这一侧要**复核 + 重试**：藏过全套（含 11 条 singlemode-* 上下文工具栏）
    // 之后，单纯 showElement 有时恢复不出来（真机实证）。逃生开关是「体验不能
    // 退步」的兜底保证，不能靠一次调用碰运气——先补 createElement 重建元素，
    // 再把整个 LayoutManager 打开，每一步都用 isElementVisible 复核。
    const setOne = (url, on) => {
      if (!on) {
        try { lm.hideElement(url); } catch (e) {}
        return visible(url);
      }
      try { lm.showElement(url); } catch (e) {}
      if (visible(url) === true) return true;
      try { lm.createElement(url); lm.showElement(url); } catch (e) {}
      if (visible(url) === true) return true;
      try { lm.setVisible(true); lm.showElement(url); } catch (e) {}
      return visible(url);
    };
    // 不带任何字段 = 只读查询：回报当前各处可见性。宿主用它复核「藏了之后
    // 真的没再冒出来」（上下文工具栏是选中表格/图片时由引擎自己拉起来的）。
    const anyField = ['all', 'menubar', 'statusbar', 'toolbars', 'rulers'].some((k) => req[k] != null);
    if (!anyField) {
      const cur = { toolbars: {} };
      try { cur.all = !!lm.isVisible(); } catch (e) {}
      try { cur.menubar = !!lm.isElementVisible(CHROME_URLS.menubar); } catch (e) {}
      try { cur.statusbar = !!lm.isElementVisible(CHROME_URLS.statusbar); } catch (e) {}
      for (let i = 0; i < CHROME_URLS.toolbars.length; i++) {
        const u = CHROME_URLS.toolbars[i];
        try { cur.toolbars[u.slice(u.lastIndexOf('/') + 1)] = !!lm.isElementVisible(u); } catch (e) {}
      }
      try {
        const vs = ctrl.getViewSettings();
        cur.rulers = { ShowHoriRuler: !!vs.getPropertyValue('ShowHoriRuler'), ShowVertRuler: !!vs.getPropertyValue('ShowVertRuler') };
      } catch (e) {}
      return { success: true, visible: cur };
    }
    if (req.all != null) {
      try { lm.setVisible(!!req.all); } catch (e) { out.allErr = errStr(e); }
      try { out.applied.all = !!lm.isVisible(); } catch (e) {}
    }
    if (req.menubar != null) out.applied.menubar = setOne(CHROME_URLS.menubar, !!req.menubar);
    if (req.statusbar != null) out.applied.statusbar = setOne(CHROME_URLS.statusbar, !!req.statusbar);
    if (req.toolbars != null) {
      const states = {};
      for (let i = 0; i < CHROME_URLS.toolbars.length; i++) {
        const u = CHROME_URLS.toolbars[i];
        states[u.slice(u.lastIndexOf('/') + 1)] = setOne(u, !!req.toolbars);
      }
      out.applied.toolbars = states;
    }
    if (req.rulers != null) {
      const vs = ctrl.getViewSettings();
      const r = {};
      for (const k of ['ShowHoriRuler', 'ShowVertRuler']) {
        try { vs.setPropertyValue(k, !!req.rulers); } catch (e) {}
        try { r[k] = !!vs.getPropertyValue(k); } catch (e) {}
      }
      out.applied.rulers = r;
    }
    return out;
  },
  // 修订开关（工具栏的「修订」按钮）。RecordChanges 是文档属性，直接写比派发
  // .uno:TrackChanges 可靠——后者是切换语义，宿主想设成确定状态就得先读再判。
  set_track_changes(p) {
    if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
    if (p && p.on != null) {
      try { xModel.setPropertyValue('RecordChanges', !!p.on); }
      catch (e) { return tableFail('设置修订开关失败: ' + errStr(e)); }
    }
    let on = null;
    try { on = !!xModel.getPropertyValue('RecordChanges'); } catch (e) {}
    return { success: true, recordChanges: on };
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
    // 宿主随文档带来当前登录用户名：用户本人编辑的修订署名（AI 的署 AI WorkDeck）
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
    if (!u8 || u8.length === 0) return { success: true, empty: true, name: name, kind: docKindOf() };
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
      // 选区监听是 per-controller 的，换文档同样要重装（漏了工具栏就再也不刷新）
      try { installSelectionListener(ctrl); } catch (e) { log('XSelectionChangeListener 安装失败 / install failed: ' + errStr(e)); }
      // RecordChanges/ShowChangesInMargin 是 Writer 专属（Calc/Impress 没有该属性）——
      // 此前对非 Writer 文档也无条件调用，靠 try/catch 兜住但会白抛异常 + 打噪声日志
      // （Impress 场景尤其误导：看起来像"修订功能坏了"）。改成前置类型判定。
      if (isWriterDoc()) {
        // Revisions default ON for the real document too (same as bootDoc).
        try { xModel.setPropertyValue('RecordChanges', true); } catch (e) {}
        showDeletionsInMargin();
      }
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
        return { success: true, name: name, bytes: u8.length, via: 'file', kind: docKindOf() };
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
        return { success: true, name: name, bytes: u8.length, via: 'stream', kind: docKindOf() };
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
    // storeToURL 会把文档标成 modified（实测：导出完 isModified() 恒为 true）。
    // 不复原的话宿主收到 modified 又排一次自动保存，形成保存死循环，所以这里
    // 既要在导出期间闭掉上报，也要把标志恢复成导出前的值。
    // 导出是同步的、跑在这条 office 线程上，期间不可能有用户编辑挤进来，
    // 复原不会吃掉真实修改；宿主侧的 dirty 是另一套独立记账，也不受影响。
    const wasModified = (() => { try { return !!xModel.isModified(); } catch (e) { return false; } })();
    exportInFlight = true;
    try {
      xModel.storeToURL('private:stream', props);
    } finally {
      exportInFlight = false;
      try { if (!!xModel.isModified() !== wasModified) xModel.setModified(wasModified); } catch (e) { /* 只读文档等场景可能拒绝，忽略 */ }
    }
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
  // 走段落索引缓存：O(窗口) 而非 O(全文)，total 直接取索引长度。
  get_document_text(p) {
    const start = Math.max(0, Number(p && p.startParagraph) || 0);
    const maxParas = Math.max(1, Math.min(Number(p && p.maxParagraphs) || 200, 500));
    const charBudget = 15000;
    return withParaIndex(function (ix) {
      const total = ix.total;
      const paragraphs = [];
      let chars = 0;
      for (let i = start; i < total && paragraphs.length < maxParas && chars < charBudget; i++) {
        const el = ix.ranges[i];
        const item = { index: i, text: el.getString() };
        try {
          const lvl = el.getPropertyValue('OutlineLevel') || 0;
          if (lvl > 0) { item.headingLevel = lvl; item.style = el.getPropertyValue('ParaStyleName') || ''; }
        } catch (e) {}
        chars += item.text.length;
        paragraphs.push(item);
      }
      const r = { success: true, totalParagraphs: total, startParagraph: start, returned: paragraphs.length, paragraphs: paragraphs };
      if (start + paragraphs.length < total) { r.truncated = true; r.nextStartParagraph = start + paragraphs.length; }
      return r;
    });
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
    const found = paraAt(idx);
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
  // [表格·感知] 读一张表为二维数组——"改一格"的前置：模型得先看见表长什么样。
  // 定位：tableName / tableIndex（0 开始），缺省用光标所在表。
  table_read(p) {
    const r = resolveWriterTable(p);
    if (r.error) return tableFail(r.error);
    const dims = tableDims(r.table);
    if (dims.error) return tableFail(dims.error);
    const maxRows = Math.min(dims.rows, Math.max(1, Number(p.maxRows) || 200));
    const maxCols = Math.min(dims.cols, Math.max(1, Number(p.maxCols) || 30));
    const cells = [];
    let missing = 0;
    for (let rw = 0; rw < maxRows; rw++) {
      const line = [];
      for (let c = 0; c < maxCols; c++) {
        let v = null;
        try { const cell = r.table.getCellByName(cellName(c, rw)); if (cell) v = String(cell.getString()); } catch (e) {}
        if (v === null) missing++;
        line.push(v === null ? '' : v);
      }
      cells.push(line);
    }
    const out = {
      success: true, table: r.name, tableIndex: r.index, tables: r.count,
      rows: dims.rows, cols: dims.cols, cells: cells,
      truncated: maxRows < dims.rows || maxCols < dims.cols,
    };
    // 合并/拆分过的单元格不叫 A1 这种名字，按网格取会取空——明说，否则模型会
    // 以为那些格真是空的，然后 table_set_cell 打在不存在的名字上。
    if (missing) out.note = '有 ' + missing + ' 个网格位置按 A1 式单元格名取不到（表格存在合并或拆分单元格），这些位置以空串返回；对它们做单元格级修改会失败。';
    return out;
  },
  // [表格·改] 改一格文本。修订模式下走字符级最小修订（同 replace_selection 的口径），
  // 只有差异字符落修订，而不是整格删了重打。
  table_set_cell(p) {
    const r = resolveWriterTable(p);
    if (r.error) return tableFail(r.error);
    const ref = parseCellRef(p.cell);
    if (!ref) return tableFail('单元格定位非法: ' + p.cell + '（应形如 B2：列字母 + 行号，行号 1 开始）');
    const dims = tableDims(r.table);
    let cell = null;
    try { cell = r.table.getCellByName(ref.name); } catch (e) {}
    if (!cell) {
      return tableFail('单元格不存在: ' + ref.name + '（表格「' + r.name + '」共 '
        + (dims.rows || '?') + ' 行 × ' + (dims.cols || '?') + ' 列；合并或拆分过的格子不叫这种名字，先用 doc_table_read 看清）');
    }
    const text = String(p.text == null ? '' : p.text);
    let oldText = '';
    try { oldText = String(cell.getString()); } catch (e) {}
    const rcOn = recordChangesOn();
    let via = 'setString';
    try {
      if (rcOn && oldText.length) {
        const cur = cell.createTextCursor();
        cur.gotoStart(false);
        cur.gotoEnd(true);
        if (applyMinimalRedline(cur, text)) via = 'minimalRedline';
        else cell.setString(text);
      } else {
        cell.setString(text);
      }
    } catch (e) { return tableFail('写入单元格失败: ' + errStr(e)); }
    // 拟人：把视图光标停在刚改的格里，用户看得见改在哪，后续原语也能省掉 tableIndex。
    try { ctrl.getViewCursor().gotoRange(cell.getStart(), false); } catch (e) {}
    return {
      success: true, table: r.name, tableIndex: r.index, cell: ref.name,
      oldText: oldText, text: text, via: via, recordChanges: rcOn,
    };
  },
  // [表格·改] 插入行。position = 1 开始的行号，新行插在该行之前；缺省追加到表尾。
  table_add_row(p) {
    const r = resolveWriterTable(p);
    if (r.error) return tableFail(r.error);
    const dims = tableDims(r.table);
    if (dims.error) return tableFail(dims.error);
    const count = Math.max(1, Math.min(Number(p.count) || 1, 100));
    let at = dims.rows;
    if (p.position != null && String(p.position) !== '') {
      const n = Number(p.position);
      if (!(n >= 1) || n > dims.rows + 1 || Math.floor(n) !== n) {
        return tableFail('行号越界: ' + p.position + '（表格共 ' + dims.rows + ' 行；position 取 1..' + (dims.rows + 1) + '，新行插在该行之前，缺省追加到表尾）');
      }
      at = n - 1;
    }
    try { r.table.getRows().insertByIndex(at, count); }
    catch (e) { return tableFail('插入行失败: ' + errStr(e)); }
    const after = tableDims(r.table);
    if (after.error || after.rows !== dims.rows + count) {
      return tableFail('插入行未生效（行数 ' + dims.rows + ' → ' + (after.rows == null ? '?' : after.rows) + '）');
    }
    return {
      success: true, table: r.name, tableIndex: r.index,
      insertedAt: at + 1, count: count, rows: after.rows, cols: after.cols,
    };
  },
  // [表格·改] 删除行。position = 1 开始的行号，count 连删几行。
  // 修订口径（真机实测 LO 24.2）：XTableRows.removeByIndex 走 API 路线**直接删除，
  // 不留删除修订**（RecordChanges 开着也一样，redlineDelta=0）——安全网是 doc_undo
  // 与文档检查点，不是修订面板。生效判定仍按"行数变化 OR 修订条数变化"双口径，
  // 以防将来引擎改成落修订；落成修订时 trackedAsRevision=true。
  table_delete_row(p) {
    const r = resolveWriterTable(p);
    if (r.error) return tableFail(r.error);
    const dims = tableDims(r.table);
    if (dims.error) return tableFail(dims.error);
    if (p.position == null || String(p.position) === '') return tableFail('缺少 position（要删的行号，1 开始）');
    const n = Number(p.position);
    if (!(n >= 1) || n > dims.rows || Math.floor(n) !== n) {
      return tableFail('行号越界: ' + p.position + '（表格共 ' + dims.rows + ' 行，行号 1 开始）');
    }
    const count = Math.max(1, Number(p.count) || 1);
    if (n - 1 + count > dims.rows) {
      return tableFail('要删的行数超出表格范围（从第 ' + n + ' 行起删 ' + count + ' 行，表格只有 ' + dims.rows + ' 行）');
    }
    if (count >= dims.rows) return tableFail('不能删掉表格的全部行——表格至少要留一行；要整张表删掉请改用其它方式');
    const rlBefore = countRedlines();
    try { r.table.getRows().removeByIndex(n - 1, count); }
    catch (e) { return tableFail('删除行失败: ' + errStr(e)); }
    const after = tableDims(r.table);
    const removed = after.error ? 0 : dims.rows - after.rows;
    const rlDelta = countRedlines() - rlBefore;
    if (removed !== count && rlDelta <= 0) {
      return tableFail('删除行未生效（行数仍为 ' + (after.rows == null ? '?' : after.rows) + '，也没有产生删除修订）');
    }
    return {
      success: true, table: r.name, tableIndex: r.index,
      deletedAt: n, count: count, removedRows: removed,
      rows: after.rows, cols: after.cols,
      recordChanges: recordChangesOn(), redlineDelta: rlDelta,
      trackedAsRevision: removed !== count && rlDelta > 0,
    };
  },
  // [表格·改] 插入列。position = 列字母（A/B/AA）或 1 开始的列号，新列插在该列之前；
  // 缺省追加到最右。
  table_add_col(p) {
    const r = resolveWriterTable(p);
    if (r.error) return tableFail(r.error);
    const dims = tableDims(r.table);
    if (dims.error) return tableFail(dims.error);
    const count = Math.max(1, Math.min(Number(p.count) || 1, 20));
    let at = dims.cols;
    if (p.position != null && String(p.position) !== '') {
      const c = parseColumnRef(p.position);
      if (c == null || c > dims.cols) {
        return tableFail('列定位非法或越界: ' + p.position + '（表格共 ' + dims.cols + ' 列；position 用列字母如 B 或 1 开始的列号，新列插在该列之前，缺省追加到最右）');
      }
      at = c;
    }
    try { r.table.getColumns().insertByIndex(at, count); }
    catch (e) { return tableFail('插入列失败: ' + errStr(e)); }
    const after = tableDims(r.table);
    if (after.error || after.cols !== dims.cols + count) {
      return tableFail('插入列未生效（列数 ' + dims.cols + ' → ' + (after.cols == null ? '?' : after.cols) + '）；合并过单元格的表格按列插入常被引擎拒绝');
    }
    return {
      success: true, table: r.name, tableIndex: r.index,
      insertedAt: colLetterOf(at), count: count, rows: after.rows, cols: after.cols,
    };
  },
  // [表格·改] 删除列。position = 列字母或 1 开始的列号，count 连删几列。
  // 与删除行同样的修订口径（API 路线直接删除，不留修订痕迹）。
  table_delete_col(p) {
    const r = resolveWriterTable(p);
    if (r.error) return tableFail(r.error);
    const dims = tableDims(r.table);
    if (dims.error) return tableFail(dims.error);
    if (p.position == null || String(p.position) === '') return tableFail('缺少 position（要删的列，列字母如 B 或 1 开始的列号）');
    const c = parseColumnRef(p.position);
    if (c == null || c >= dims.cols) {
      return tableFail('列定位非法或越界: ' + p.position + '（表格共 ' + dims.cols + ' 列，列字母 A 起 / 列号 1 起）');
    }
    const count = Math.max(1, Number(p.count) || 1);
    if (c + count > dims.cols) {
      return tableFail('要删的列数超出表格范围（从第 ' + colLetterOf(c) + ' 列起删 ' + count + ' 列，表格只有 ' + dims.cols + ' 列）');
    }
    if (count >= dims.cols) return tableFail('不能删掉表格的全部列——表格至少要留一列');
    const rlBefore = countRedlines();
    try { r.table.getColumns().removeByIndex(c, count); }
    catch (e) { return tableFail('删除列失败: ' + errStr(e)); }
    const after = tableDims(r.table);
    const removed = after.error ? 0 : dims.cols - after.cols;
    const rlDelta = countRedlines() - rlBefore;
    if (removed !== count && rlDelta <= 0) {
      return tableFail('删除列未生效（列数仍为 ' + (after.cols == null ? '?' : after.cols) + '，也没有产生删除修订）；合并过单元格的表格按列删除常被引擎拒绝');
    }
    return {
      success: true, table: r.name, tableIndex: r.index,
      deletedAt: colLetterOf(c), count: count, removedCols: removed,
      rows: after.rows, cols: after.cols,
      recordChanges: recordChangesOn(), redlineDelta: rlDelta,
      trackedAsRevision: removed !== count && rlDelta > 0,
    };
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
  // 150 页实测 >30s：按 500 个顶层元素一批，批间 await 一个宏任务（自动保存的
  // export / 宿主的 cancel 有机会插进来），不再有 5000 元素硬顶；truncated 永远
  // 返回（只有被取消时才为 true）。
  async apply_house_style(p) {
    const en = xModel.getText().createEnumeration();
    const BATCH = 500;
    let idx = 0, paras = 0, tables = 0, titled = false, prevWasTable = false, cancelled = false;
    lockModel();
    try {
    while (en.hasMoreElements()) {
      if (idx > 0 && idx % BATCH === 0) {
        if (await batchBreak(p, idx, 0)) { cancelled = true; break; }
      }
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
    } finally { unlockModel(); }
    if (idx > BATCH) postProgress(p, idx, idx);
    const r = Object.assign({ success: true, paragraphs: paras, tables: tables, truncated: cancelled }, verifySnapshot());
    if (cancelled) { r.cancelled = true; r.done = idx; }
    return r;
  },
  // [流式] markdown 剥离 + 标准格式落字（doc_start_stream 管线的落字端）。
  // 攒到完整行才消费；尾部残行等 stream_flush。HOUSE 是 Writer 排版语义
  // （首行缩进 2 字符/段后 18 磅），在幻灯片/表格上无意义——遇到非 Writer 文档
  // 直接报错，让模型改走 slide_*/sheet_*，不悄悄把 markdown 正文糊进错误的文档模型。
  stream_insert(p) {
    if (!isWriterDoc()) return tableFail('doc_start_stream 仅支持 Word 文档：当前文档不是 Writer 文档。电子表格请用 sheet_* 原语，演示文稿请用 slide_* 原语逐处编辑。');
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
    invalidateParaIndex();
    return Object.assign({ success: done > 0, undone: done }, done > 0 ? verifySnapshot() : { message: 'nothing to undo' });
  },
  redo(p) {
    const um = xModel.getUndoManager();
    const want = Math.max(1, Math.min(Number(p && p.steps) || 1, 20));
    let done = 0;
    for (; done < want; done++) {
      try { um.redo(); } catch (e) { break; }
    }
    invalidateParaIndex();
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
  // [Word 二期] AI 工具面的超链接原语——与 set_selection_hyperlink（host-initiated，
  // 靠"当前选区"）不同，这里直接吃 anchor 参数，一次 worker 往返完成"找到锚点→
  // 选中→设置"，走的是 replace_at_position（doc_replace_at_anchor 的下发目标）
  // 同一先例：AI 工具面每次调用都是独立请求，两次请求之间"当前选区"不可靠，
  // 必须靠锚点自己定位，不能依赖调用前谁选中了什么。
  set_hyperlink_at_anchor(p) {
    if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
    const url = String((p && p.url) || '');
    if (!url) return tableFail('set_hyperlink_at_anchor requires {url}');
    if (!/^https?:\/\//i.test(url)) return tableFail('url 仅支持 http/https: ' + url);
    const anchorId = p && p.anchor ? String(p.anchor) : '';
    if (!anchorId) return tableFail('set_hyperlink_at_anchor requires {anchor}');
    const range = anchorRange(anchorId);
    if (!range) return tableFail('anchor not found: ' + anchorId);
    if (!selectVisibly(range)) return tableFail('could not select anchor: ' + anchorId);
    const text = range.getString() || '';
    try {
      const xText = range.getText();
      const cur = xText.createTextCursorByRange(range);
      cur.setPropertyValue('HyperLinkURL', url);
    } catch (e) { return tableFail('设置超链接失败: ' + errStr(e)); }
    return { success: true, anchor: anchorId, text: text, url: url };
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
  // 用户在工具栏上给**当前选区**加批注。与 add_comment 的差别有两处，所以不能
  // 复用：① 没有 anchorId（用户是选中文字直接点按钮，不走 find_text_locations）；
  // ② 署名必须是用户本人，不是 AI WorkDeck。派发路线仍是已验证的
  // `.uno:InsertAnnotation`——API 路线（addAnnotation）会抛虚假异常且只批注锚点。
  add_comment_at_selection(p) {
    if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
    const comment = String((p && p.comment) || '');
    if (!comment) return tableFail('add_comment_at_selection requires {comment}');
    const vc = ctrl.getViewCursor();
    const annotatedText = String(vc.getString() || '');
    if (!annotatedText.length) return tableFail('请先选中要批注的文字');
    const author = humanAuthor || '';
    const before = countComments();
    css.frame.DispatchHelper.create(context).executeDispatch(
      ctrl.getFrame(), '.uno:InsertAnnotation', '', 0,
      [mkProp('Text', comment), mkProp('Author', author)]);
    // 派发不报错也可能没命中——用批注条数变化确认，别对用户谎报成功
    const after = countComments();
    if (after <= before) return tableFail('批注未被插入（引擎未命中）');
    return {
      success: true, author: author, comment: comment,
      annotatedText: annotatedText.slice(0, 120), count: after,
    };
  },
  // ---- [Word 二期] 结构面：脚注/尾注、页眉页脚、分页/分节符、样式 -----------
  // 文档能力矩阵（4.2 节）桌面端待办：批注/修订/超链接/图片是"包一层 AI 工具面
  // 就够"，这一组是真正的新 worker 实现。
  insert_footnote(p) { return insertFootnoteImpl(p, false); },
  insert_endnote(p) { return insertFootnoteImpl(p, true); },
  // 首节页眉/页脚文本 + 对齐。只处理文档的第一个（也是最常见情形下唯一的）页面
  // 样式——按节差异化页眉页脚不在本次范围内（法律文件极少见，与 office_addin
  // 侧 edit_header_footer"只处理首节"同一简化）。UNO 没有直接的"当前生效页面
  // 样式"查询点：把视图光标挪到文档开头读 PageStyleName 是宏录制的标准取法
  // （内部名恒为英文如 'Standard'，UI 显示才随 zh-CN 语言包本地化，因此不受
  // 语言包影响）。
  edit_header_footer(p) {
    if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
    const target = String((p && p.target) || 'header').toLowerCase();
    if (target !== 'header' && target !== 'footer') return tableFail("target must be 'header' or 'footer'");
    const text = p && p.text != null ? String(p.text) : null;
    if (text == null) return tableFail('edit_header_footer requires {text}');
    const ALIGN_MAP = { left: css.style.ParagraphAdjust.LEFT, right: css.style.ParagraphAdjust.RIGHT, center: css.style.ParagraphAdjust.CENTER, justify: css.style.ParagraphAdjust.BLOCK };
    let alignValue = null;
    if (p && p.align != null) {
      alignValue = ALIGN_MAP[String(p.align).toLowerCase()];
      // 先校验参数、后落笔——避免"对齐值非法"时页眉页脚文本已经写了一半
      if (alignValue == null) return tableFail('bad align: ' + p.align + ' (left/right/center/justify)');
    }
    let styleName = '';
    try {
      const vc = ctrl.getViewCursor();
      const savedRange = vc.getStart();
      vc.gotoStart(false);
      styleName = String(vc.getPropertyValue('PageStyleName') || '');
      vc.gotoRange(savedRange, false);
    } catch (e) { return tableFail('无法定位页面样式: ' + errStr(e)); }
    if (!styleName) return tableFail('无法确定文档的页面样式');
    let pageStyle;
    try { pageStyle = xModel.getStyleFamilies().getByName('PageStyles').getByName(styleName); }
    catch (e) { return tableFail('读取页面样式失败: ' + errStr(e)); }
    const isOnProp = target === 'header' ? 'HeaderIsOn' : 'FooterIsOn';
    const textProp = target === 'header' ? 'HeaderText' : 'FooterText';
    try {
      pageStyle.setPropertyValue(isOnProp, true);
      const xt = pageStyle.getPropertyValue(textProp);
      xt.setString(text);
      if (alignValue != null) {
        const en = xt.createEnumeration();
        while (en.hasMoreElements()) {
          const para = en.nextElement();
          if (para.supportsService && para.supportsService('com.sun.star.text.Paragraph')) {
            try { para.setPropertyValue('ParaAdjust', alignValue); } catch (e) {}
          }
        }
      }
    } catch (e) { return tableFail('设置' + (target === 'header' ? '页眉' : '页脚') + '失败: ' + errStr(e)); }
    return { success: true, target: target, pageStyle: styleName, text: text };
  },
  // 分页符/分节符（Word 语义的"下一页"分节符）。页内插入而非追加：先在光标处
  // 打一个段落断（把光标后的内容推到新段），再给这个新段打上 BreakType（纯分页）
  // 或 PageDescName（分节——沿用当前页面样式起一个新的"页面样式序列"，这是
  // UNO/ODF 与 Word 分节机制互通的标准写法，export 到 docx 落成真正的 <w:sectPr>）。
  insert_break(p) {
    if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
    const breakType = String((p && p.breakType) || 'page').toLowerCase();
    if (breakType !== 'page' && breakType !== 'sectionnext') return tableFail("breakType must be 'page' or 'sectionNext'");
    const vc = ctrl.getViewCursor();
    const xText = vc.getText();
    vc.collapseToEnd(); // 塌陷选区，避免连带删掉已选中的文本
    let styleName = '';
    if (breakType === 'sectionnext') {
      try { styleName = String(vc.getPropertyValue('PageStyleName') || ''); } catch (e) {}
      if (!styleName) return tableFail('无法确定当前页面样式，无法插入分节符');
    }
    try {
      xText.insertControlCharacter(vc, css.text.ControlCharacter.PARAGRAPH_BREAK, false);
      if (breakType === 'page') vc.setPropertyValue('BreakType', css.style.BreakType.PAGE_BEFORE);
      else vc.setPropertyValue('PageDescName', styleName);
    } catch (e) { return tableFail('插入' + (breakType === 'page' ? '分页符' : '分节符') + '失败: ' + errStr(e)); }
    return { success: true, breakType: breakType === 'page' ? 'page' : 'sectionNext' };
  },
  // 应用既有样式（段落样式 ParaStyleName / 字符样式 CharStyleName）。与
  // doc_format_selection / doc_set_paragraph_format 同一约定：不接 anchor 参数，
  // 作用于"当前选区/光标"——AI 侧先 doc_select_anchor / doc_select_paragraph
  // 选中目标，再调用本原语，与那两个格式化原语的调用序列完全一致，不必再引入
  // 第二套"直接传 anchor"的调用方式徒增分叉。
  set_style(p) {
    if (!isWriterDoc()) return tableFail(NOT_TEXT_DOC_MSG);
    const kind = String((p && p.kind) || 'paragraph').toLowerCase();
    if (kind !== 'paragraph' && kind !== 'character') return tableFail("kind must be 'paragraph' or 'character'");
    const styleName = p && p.styleName ? String(p.styleName) : '';
    if (!styleName) return tableFail('set_style requires {styleName}');
    const familyName = kind === 'paragraph' ? 'ParagraphStyles' : 'CharacterStyles';
    let family;
    try { family = xModel.getStyleFamilies().getByName(familyName); }
    catch (e) { return tableFail('读取样式库失败: ' + errStr(e)); }
    if (!family.hasByName(styleName)) {
      const names = (family.getElementNames && family.getElementNames()) || [];
      return tableFail('样式不存在: ' + styleName + '（可用样式：' + names.slice(0, 20).join('、')
        + (names.length > 20 ? ' 等共 ' + names.length + ' 个' : '') + '）');
    }
    const vc = ctrl.getViewCursor();
    if (kind === 'character' && (vc.getString() || '').length === 0) {
      return tableFail('未选中文本：字符样式需要先选中要套样式的文字（doc_select_anchor / doc_select_paragraph）');
    }
    const propName = kind === 'paragraph' ? 'ParaStyleName' : 'CharStyleName';
    try { vc.setPropertyValue(propName, styleName); }
    catch (e) { return tableFail('应用样式失败: ' + errStr(e)); }
    return { success: true, kind: kind, styleName: styleName, text: (vc.getString() || '').slice(0, 200) };
  },
  // ---- [审阅面板] 修订与批注的清单 / 定位 / 逐条处置 --------------------
  // 页边显示解决了「删除文本压正文」，但同一表格行多格删除仍会在页边同高互叠，
  // 且页边小字读不到作者/时间。审阅面板（宿主右栏）用下面这组原语驱动：列出、
  // 点击定位、逐条接受/拒绝——修订的权威视图从页边挪进面板。
  list_revisions(p) {
    const limit = Math.max(1, Math.min(500, Number(p && p.limit) || 200));
    const out = [];
    let prevEnd = null;   // 上一条的 RedlineEnd，用来判「首尾相接」（见 contiguous）
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
        let curEnd = null;
        try {
          const rs = r.getPropertyValue('RedlineStart'), re = r.getPropertyValue('RedlineEnd');
          if (rs && re) {
            curEnd = re;
            // 与上一条首尾相接？审阅面板据此把「连按 Backspace 产生的一串单字
            // 删除」并成一条卡片（用户反馈：一个字一条记录很不科学）。页边模式下
            // 删除文本被移出正文流，一串连续删除的区间会塌到同一个正文位置，正好
            // 命中这个判据；同段落里相隔很远的两处删除则不会被误并。
            it.contiguous = rangeStartsEqual(prevEnd, rs);
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
        prevEnd = curEnd;
        it.text = String(it.text || '').slice(0, 120);
        out.push(it);
      }
    } catch (e) { return tableFail(errStr(e)); }
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
    if (action !== 'accept' && action !== 'reject') return tableFail("action must be accept|reject");
    const r = redlineAt(p && p.index);
    if (!r) return tableFail('no revision at index ' + (p && p.index));
    if (!selectRedlineRange(r, true)) return tableFail('could not select revision range');
    const before = countRedlines();
    css.frame.DispatchHelper.create(context).executeDispatch(
      ctrl.getFrame(), action === 'accept' ? '.uno:AcceptTrackedChange' : '.uno:RejectTrackedChange', '', 0, []);
    const after = countRedlines();
    // dispatch 不报错也可能没命中——用条数变化确认，别对用户谎报成功
    return after < before
      ? { success: true, index: Number(p.index), action: action, remaining: after }
      : Object.assign(tableFail('修订未被处置（引擎未命中该条）'), { remaining: after });
  },
  resolve_all_revisions(p) {
    const action = String((p && p.action) || 'accept').toLowerCase();
    if (action !== 'accept' && action !== 'reject') return tableFail("action must be accept|reject");
    const before = countRedlines();
    css.frame.DispatchHelper.create(context).executeDispatch(
      ctrl.getFrame(), action === 'accept' ? '.uno:AcceptAllTrackedChanges' : '.uno:RejectAllTrackedChanges', '', 0, []);
    const after = countRedlines(); // 前后各数一次（原先结束时数了两遍 O(N)）
    return { success: true, action: action, resolved: before - after, remaining: after };
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
        try { it.id = commentIdOf(f); } catch (e) {}
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
    } catch (e) { return tableFail(errStr(e)); }
    return { success: true, count: out.length, comments: out };
  },
  goto_comment(p) {
    const f = commentAt(p && p.index);
    if (!f) return { success: false, message: 'no comment at index ' + (p && p.index) };
    try { return selectVisibly(f.getAnchor()) ? { success: true, index: Number(p.index) } : { success: false, message: 'could not select anchor' }; }
    catch (e) { return { success: false, message: errStr(e) }; }
  },
  // AI 工具面（doc_resolve_comment）与 ReviewPanel（{index}）共用本原语：ref 优先
  // 取 id（commentAt 按 Name 定位，跨调用稳定），没有 id 才退回 index。
  set_comment_resolved(p) {
    const ref = p && (p.id != null && p.id !== '' ? p.id : p.index);
    const f = commentAt(ref);
    if (!f) return tableFail('no comment at ' + ref);
    try {
      f.setPropertyValue('Resolved', !!(p && p.resolved));
      return { success: true, id: commentIdOf(f), resolved: !!f.getPropertyValue('Resolved') };
    } catch (e) { return tableFail(errStr(e)); }
  },
  // 删除批注：removeTextContent 是正路。**dispose() 不能信**——它在本引擎上
  // 既不抛异常也不真的移除批注字段（真机实证），照着它的返回值报成功就是骗
  // 用户。两条路都跑完还要用条数复核。
  delete_comment(p) {
    const ref = p && (p.id != null && p.id !== '' ? p.id : p.index);
    const f = commentAt(ref);
    if (!f) return tableFail('no comment at ' + ref);
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
      const name = commentIdOf(f);
      css.frame.DispatchHelper.create(context).executeDispatch(
        ctrl.getFrame(), '.uno:DeleteComment', '', 0, [mkProp('Id', name)]);
    } catch (e) { errs.push(errStr(e)); }
    if (countComments() === before) {
      try { f.getAnchor().getText().removeTextContent(f); } catch (e) { errs.push(errStr(e)); }
    }
    const after = countComments();
    return after < before
      ? { success: true, remaining: after }
      : Object.assign(tableFail('批注未被删除' + (errs.length ? '：' + errs.join(' | ') : '')), { remaining: after });
  },
  // [批注回复] 是否有真正的原生线程回复（LO 的 ParentId/ParentName 一类属性）
  // 在这个引擎版本上待查证——vendored zeta.js 未见相关 typedef，无法在不接
  // 真机的情况下确认属性名。保守做法：复用已验证可靠的 .uno:InsertAnnotation
  // 路径（与 add_comment 同一先例），在父批注同一锚点区间上追加一条新批注，
  // 内容前缀"回复 {父批注作者}："保证语义可读；随后 best-effort 尝试挂
  // ParentId/ParentName 两个候选属性名，成功与否都不影响主流程（失败静默吞掉，
  // 因为这只是锦上添花的原生线程标记，不是功能是否可用的判据）。
  reply_comment(p) {
    const ref = p && (p.id != null && p.id !== '' ? p.id : p.index);
    const parent = commentAt(ref);
    if (!parent) return tableFail('未找到要回复的批注: ' + ref);
    const text = String((p && p.text) || '');
    if (!text) return tableFail('reply_comment requires {text}');
    let parentAuthor = '';
    const parentId = commentIdOf(parent);
    try { parentAuthor = String(parent.getPropertyValue('Author') || ''); } catch (e) {}
    let anchor;
    try { anchor = parent.getAnchor(); } catch (e) { return tableFail('无法定位批注锚点: ' + errStr(e)); }
    if (!selectVisibly(anchor)) return tableFail('无法选中批注锚点');
    const before = {};
    try {
      const en = xModel.getTextFields().createEnumeration();
      while (en.hasMoreElements()) {
        const f = en.nextElement();
        if (f.supportsService && f.supportsService('com.sun.star.text.textfield.Annotation')) before[commentIdOf(f)] = true;
      }
    } catch (e) {}
    const replyContent = (parentAuthor ? ('回复 ' + parentAuthor + '：') : '') + text;
    css.frame.DispatchHelper.create(context).executeDispatch(
      ctrl.getFrame(), '.uno:InsertAnnotation', '', 0,
      [mkProp('Text', replyContent), mkProp('Author', AI_AUTHOR)]);
    let newField = null, newName = '';
    try {
      const en2 = xModel.getTextFields().createEnumeration();
      while (en2.hasMoreElements()) {
        const f = en2.nextElement();
        if (!(f.supportsService && f.supportsService('com.sun.star.text.textfield.Annotation'))) continue;
        const nm = commentIdOf(f);
        if (nm && !before[nm]) { newField = f; newName = nm; }
      }
    } catch (e) {}
    if (!newField) return tableFail('回复未生效（引擎未产生新批注）');
    try { newField.setPropertyValue('ParentId', parentId); } catch (e) {}
    try { newField.setPropertyValue('ParentName', parentId); } catch (e) {}
    return { success: true, id: newName, parentId: parentId, author: AI_AUTHOR, text: text };
  },
  // [diagnostic] 修订记录清单（类型/作者/文本片段）。后端 doc_debug_revisions
  // 一直派发 debug_revisions，worker 此前未实现（一律返回 not implemented）；
  // 补上后同时作为修订署名（AI WorkDeck / 用户名）的验证探针。
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
  // ---- [Calc 二期] 批注 / 数据验证 / 图表 / 搜索 / 命名区域 / 保护 / 分组 / 透视表 ----
  // 文档能力矩阵（4.2 节）Excel 待办：此前 sheet_* 对这批能力零支持。
  // [表格·批注] 单元格批注（XSheetAnnotations）。Calc 批注没有 Word 那种线程
  // 回复/解决态概念——本组只做增/查/删三件事，不做 reply/resolve，工具描述里
  // 已向模型说明这条与 Word 批注的差异。
  sheet_add_comment(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    const cellStr = p && p.cell ? String(p.cell).trim() : '';
    const text = p && p.text != null ? String(p.text) : '';
    if (!cellStr) return tableFail('sheet_add_comment requires {cell}（如 "B2"）');
    if (!text) return tableFail('sheet_add_comment requires {text}');
    const cellRange = sheetRange(sheet, cellStr);
    if (!cellRange) return tableFail('无效的单元格: ' + cellStr);
    const addr = cellRange.getRangeAddress();
    let annotations;
    try { annotations = sheet.getAnnotations(); } catch (e) { return tableFail('获取批注集合失败: ' + errStr(e)); }
    let anno;
    try {
      anno = annotations.insertNew(
        new css.table.CellAddress({ Sheet: addr.Sheet, Column: addr.StartColumn, Row: addr.StartRow }), text);
    } catch (e) { return tableFail('新建批注失败: ' + errStr(e)); }
    let author = '', date = '';
    try { author = String(anno.getAuthor() || ''); } catch (e) {}
    try { date = String(anno.getDate() || ''); } catch (e) {}
    try { ctrl.select(cellRange); } catch (e) {}
    return {
      success: true, cell: colLetterOf(addr.StartColumn) + (addr.StartRow + 1),
      sheet: sheet.getName(), author: author, date: date, text: text,
    };
  },
  sheet_get_comments(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    let annotations;
    try { annotations = sheet.getAnnotations(); } catch (e) { return tableFail('获取批注集合失败: ' + errStr(e)); }
    const out = [];
    try {
      const n = annotations.getCount();
      for (let i = 0; i < n; i++) {
        const anno = annotations.getByIndex(i);
        const pos = anno.getPosition();
        const item = { index: i, cell: colLetterOf(pos.Column) + (pos.Row + 1) };
        try { item.author = String(anno.getAuthor() || ''); } catch (e) {}
        try { item.date = String(anno.getDate() || ''); } catch (e) {}
        try { item.text = String(anno.getString() || '').slice(0, 500); } catch (e) {}
        out.push(item);
      }
    } catch (e) { return tableFail(errStr(e)); }
    return { success: true, sheet: sheet.getName(), count: out.length, comments: out };
  },
  sheet_delete_comment(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    const cellStr = p && p.cell ? String(p.cell).trim() : '';
    if (!cellStr) return tableFail('sheet_delete_comment requires {cell}（如 "B2"）');
    const cellRange = sheetRange(sheet, cellStr);
    if (!cellRange) return tableFail('无效的单元格: ' + cellStr);
    const addr = cellRange.getRangeAddress();
    let annotations;
    try { annotations = sheet.getAnnotations(); } catch (e) { return tableFail('获取批注集合失败: ' + errStr(e)); }
    let idx = -1;
    try {
      const n = annotations.getCount();
      for (let i = 0; i < n; i++) {
        const pos = annotations.getByIndex(i).getPosition();
        if (pos.Column === addr.StartColumn && pos.Row === addr.StartRow) { idx = i; break; }
      }
    } catch (e) { return tableFail(errStr(e)); }
    if (idx < 0) return tableFail('单元格 ' + cellStr + ' 没有批注');
    try { annotations.removeByIndex(idx); } catch (e) { return tableFail('删除批注失败: ' + errStr(e)); }
    return { success: true, cell: colLetterOf(addr.StartColumn) + (addr.StartRow + 1), sheet: sheet.getName() };
  },
  // [表格·结构] 数据验证（TableValidation）。range 的 'Validation' 属性是一个
  // 属性集对象，改完必须整体 setPropertyValue 回 range 才生效（读出来改、不能
  // 就地改）。list 类型 value1 是逗号分隔候选值，落成带引号的 Calc 显式列表公式
  // （"a";"b";"c"）；wholeNumber/decimal/date/time/textLength 需要 operator +
  // value1（between/notBetween 再加 value2）；custom 的 value1 是校验公式本身。
  // clear=true 清除数据验证（Type 置回 ANY），忽略其他参数。
  sheet_set_data_validation(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const range = sheetRange(r0.sheet, String(p.range || ''));
    if (!range) return tableFail('无效的区域: ' + (p.range || '(空)'));
    if (p && p.clear) {
      try {
        const v0 = range.getPropertyValue('Validation');
        v0.setPropertyValue('Type', css.sheet.ValidationType.ANY);
        range.setPropertyValue('Validation', v0);
      } catch (e) { return tableFail('清除数据验证失败: ' + errStr(e)); }
      return { success: true, range: sheetRangeName(range.getRangeAddress()), cleared: true };
    }
    const TYPE_MAP = { list: 'LIST', wholenumber: 'WHOLE', whole: 'WHOLE', decimal: 'DECIMAL', date: 'DATE', time: 'TIME', textlength: 'TEXT_LEN', custom: 'CUSTOM' };
    const typeKey = TYPE_MAP[String(p && p.type || '').toLowerCase().replace(/[_-]/g, '')];
    if (!typeKey) return tableFail('bad type: ' + (p && p.type) + ' (list/wholeNumber/decimal/date/time/textLength/custom)');
    const OP_MAP = { greater: 'GREATER', greaterequal: 'GREATER_EQUAL', less: 'LESS', lessequal: 'LESS_EQUAL', equal: 'EQUAL', notequal: 'NOT_EQUAL', between: 'BETWEEN', notbetween: 'NOT_BETWEEN' };
    let formula1 = '', formula2 = '', operatorKey = null;
    if (typeKey === 'LIST') {
      const items = String((p && p.value1) || '').split(',').map(function (s) { return s.trim(); }).filter(function (s) { return s !== ''; });
      if (!items.length) return tableFail('list 类型的 value1 需要逗号分隔的候选值，如 "合规,不合规,待核查"');
      formula1 = items.map(function (v) { return '"' + v.replace(/"/g, '""') + '"'; }).join(';');
    } else if (typeKey === 'CUSTOM') {
      formula1 = (p && p.value1 != null) ? String(p.value1) : '';
      if (!formula1) return tableFail('custom 类型的 value1 需要一个返回布尔值的校验公式');
      formula1 = normalizeFormula(formula1);
    } else {
      operatorKey = OP_MAP[String((p && p.operator) || '').toLowerCase().replace(/[_-]/g, '')];
      if (!operatorKey) return tableFail('bad operator: ' + (p && p.operator) + ' (greater/greaterEqual/less/lessEqual/equal/notEqual/between/notBetween)');
      if (!p || p.value1 == null || String(p.value1) === '') return tableFail(typeKey + ' 类型需要 value1');
      formula1 = normalizeFormula(String(p.value1));
      if (operatorKey === 'BETWEEN' || operatorKey === 'NOT_BETWEEN') {
        if (p.value2 == null || String(p.value2) === '') return tableFail('between/notBetween 需要 value2');
        formula2 = normalizeFormula(String(p.value2));
      }
    }
    try {
      const v = range.getPropertyValue('Validation');
      v.setPropertyValue('Type', css.sheet.ValidationType[typeKey]);
      if (operatorKey) v.setPropertyValue('Operator', css.sheet.ConditionOperator[operatorKey]);
      v.setPropertyValue('Formula1', formula1);
      if (formula2) v.setPropertyValue('Formula2', formula2);
      v.setPropertyValue('IgnoreBlankCells', (p && p.allowBlank) !== false);
      if (p && p.showInputMessage) {
        v.setPropertyValue('ShowInputMessage', true);
        if (p.inputTitle != null) v.setPropertyValue('InputTitle', String(p.inputTitle));
        if (p.inputMessage != null) v.setPropertyValue('InputMessage', String(p.inputMessage));
      }
      if (p && p.errorMessage != null && String(p.errorMessage) !== '') {
        v.setPropertyValue('ShowErrorMessage', true);
        v.setPropertyValue('ErrorAlertStyle', css.sheet.ValidationAlertStyle.STOP);
        if (p.errorTitle != null) v.setPropertyValue('ErrorTitle', String(p.errorTitle));
        v.setPropertyValue('ErrorMessage', String(p.errorMessage));
      }
      range.setPropertyValue('Validation', v);
    } catch (e) { return tableFail('设置数据验证失败: ' + errStr(e)); }
    const res = { success: true, range: sheetRangeName(range.getRangeAddress()), type: String(p.type).toLowerCase(), formula1: formula1 };
    if (formula2) res.formula2 = formula2;
    return res;
  },
  // [表格·结构] 图表（XTableCharts）。起步做「建图表 + 选类型 + 标题」三件事：
  // 数据区域整体作为图表数据源，类型 column/bar/line/pie（column/bar 同用
  // BarDiagram，靠 Vertical 属性区分方向），标题经 HasMainTitle+Title 设置。
  // 复杂配置（多数据系列自定义、坐标轴样式等）不支持。放置位置固定在数据区域
  // 右侧一列，默认尺寸约 14cm×9cm——不接受自定义位置/尺寸参数，保持起步简单。
  sheet_add_chart(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    const range = sheetRange(sheet, String((p && p.range) || ''));
    if (!range) return tableFail('无效的数据区域: ' + ((p && p.range) || '(空)'));
    const addr = range.getRangeAddress();
    const TYPE_MAP = { column: { cls: 'Bar', vertical: true }, bar: { cls: 'Bar', vertical: false }, line: { cls: 'Line' }, pie: { cls: 'Pie' } };
    const typeInfo = TYPE_MAP[String((p && p.chartType) || 'column').toLowerCase()];
    if (!typeInfo) return tableFail('bad chartType: ' + (p && p.chartType) + ' (column/bar/line/pie)');
    let charts;
    try { charts = sheet.getCharts(); } catch (e) { return tableFail('获取图表集合失败: ' + errStr(e)); }
    const name = (p && p.name != null && String(p.name).trim()) ? String(p.name).trim() : ('Chart_' + Date.now());
    if (charts.hasByName(name)) return tableFail('图表已存在: ' + name);
    let rect;
    try {
      const anchorCol = Math.min(addr.EndColumn + 2, 1023);
      const anchorCell = sheet.getCellByPosition(anchorCol, addr.StartRow);
      const pos = anchorCell.getPropertyValue('Position');
      rect = new css.awt.Rectangle({ X: pos.X, Y: pos.Y, Width: ptToMm100(400), Height: ptToMm100(250) });
    } catch (e) { return tableFail('计算图表放置位置失败: ' + errStr(e)); }
    try {
      charts.addNewByName(name, rect, [addr], true, true);
    } catch (e) { return tableFail('创建图表失败: ' + errStr(e)); }
    const note = [];
    try {
      const embedded = charts.getByName(name).getEmbeddedObject();
      const diagram = embedded.createInstance('com.sun.star.chart.' + typeInfo.cls + 'Diagram');
      embedded.setDiagram(diagram);
      if (typeInfo.cls === 'Bar') {
        try { diagram.setPropertyValue('Vertical', !!typeInfo.vertical); } catch (e) { note.push('图表方向设置失败'); }
      }
      if (p && p.title) {
        try {
          embedded.setPropertyValue('HasMainTitle', true);
          embedded.getTitle().setString(String(p.title));
        } catch (e) { note.push('标题设置失败'); }
      }
    } catch (e) { note.push('图表类型/标题设置失败: ' + errStr(e)); }
    const res = {
      success: true, name: name, chartType: String((p && p.chartType) || 'column').toLowerCase(),
      range: sheetRangeName(addr), sheet: sheet.getName(),
    };
    if (p && p.title) res.title = String(p.title);
    if (note.length) res.note = note.join('；');
    return res;
  },
  // [表格·看] Excel 专用查找：遍历区域（缺省=已用区域）逐格比对字符串，不区分
  // 值来源（文本/数值/公式结果）。上限 50 条命中、20000 格扫描（超限要求缩小
  // range）。与 doc_find_text 分开——那是 Writer 专属，本原语只认 Calc 文档。
  sheet_search(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    const query = (p && p.query != null) ? String(p.query) : '';
    if (!query) return tableFail('sheet_search requires {query}');
    const matchCase = !!(p && p.matchCase);
    let addr;
    try {
      const range = (p && p.range) ? sheetRange(sheet, p.range) : null;
      addr = range ? range.getRangeAddress() : usedRangeAddress(sheet);
    } catch (e) { return tableFail('读取搜索区域失败: ' + errStr(e)); }
    const nRows = addr.EndRow - addr.StartRow + 1;
    const nCols = addr.EndColumn - addr.StartColumn + 1;
    const MAX_SCAN = 20000;
    if (nRows * nCols > MAX_SCAN) return tableFail('搜索区域过大（上限 ' + MAX_SCAN + ' 格），请缩小 range');
    const needle = matchCase ? query : query.toLowerCase();
    const hits = [];
    for (let r = 0; r < nRows && hits.length < 50; r++) {
      for (let c = 0; c < nCols && hits.length < 50; c++) {
        const cell = sheet.getCellByPosition(addr.StartColumn + c, addr.StartRow + r);
        const val = readCellOut(cell);
        const s = val == null ? '' : String(val);
        const hay = matchCase ? s : s.toLowerCase();
        if (hay.indexOf(needle) !== -1) {
          hits.push({ cell: colLetterOf(addr.StartColumn + c) + (addr.StartRow + r + 1), value: val });
        }
      }
    }
    return {
      success: true, sheet: sheet.getName(), range: sheetRangeName(addr), query: query,
      count: hits.length, hits: hits, truncated: hits.length >= 50,
    };
  },
  // [表格·结构] 工作簿级命名区域（XNamedRanges）。op: add（name+range+可选
  // sheet）/ remove（name）/ list（枚举全部）。add 落成绝对引用公式
  // '$Sheet1.$A$1:$C$10'，与 range 参数所在工作表绑定。
  sheet_define_name(p) {
    const op = String((p && p.op) || 'list').toLowerCase();
    let nr;
    try { nr = xModel.getPropertyValue('NamedRanges'); } catch (e) { return tableFail('读取命名区域集合失败: ' + errStr(e)); }
    if (op === 'list') {
      const out = [];
      try {
        const n = nr.getCount();
        for (let i = 0; i < n; i++) {
          const item = nr.getByIndex(i);
          out.push({ name: item.getName(), content: item.getContent() });
        }
      } catch (e) { return tableFail(errStr(e)); }
      return { success: true, names: out };
    }
    const name = (p && p.name) ? String(p.name).trim() : '';
    if (!name) return tableFail(op + ' 需要 {name}');
    if (op === 'remove') {
      if (!nr.hasByName(name)) return tableFail('命名区域不存在: ' + name);
      try { nr.removeByName(name); } catch (e) { return tableFail('删除命名区域失败: ' + errStr(e)); }
      return { success: true, op: 'remove', name: name };
    }
    if (op === 'add') {
      if (nr.hasByName(name)) return tableFail('命名区域已存在: ' + name);
      const r0 = resolveSheet(p);
      if (r0.error) return tableFail(r0.error);
      const range = sheetRange(r0.sheet, String((p && p.range) || ''));
      if (!range) return tableFail('无效的区域: ' + ((p && p.range) || '(空)'));
      const addr = range.getRangeAddress();
      const content = '$' + r0.sheet.getName() + '.' + absCellRef(addr.StartColumn, addr.StartRow)
        + ((addr.StartColumn !== addr.EndColumn || addr.StartRow !== addr.EndRow) ? ':' + absCellRef(addr.EndColumn, addr.EndRow) : '');
      try {
        nr.addNewByName(name, content, new css.table.CellAddress({ Sheet: addr.Sheet, Column: addr.StartColumn, Row: addr.StartRow }), 0);
      } catch (e) { return tableFail('新建命名区域失败: ' + errStr(e)); }
      return { success: true, op: 'add', name: name, range: content };
    }
    return tableFail('bad op: ' + op + ' (add/remove/list)');
  },
  // [表格·结构] 工作表保护（XProtectable）。密码可选（不传=无密码保护）；
  // 密码参数不落日志，返回值也不回显密码。
  sheet_protect_sheet(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    const action = String((p && p.action) || 'protect').toLowerCase();
    if (action !== 'protect' && action !== 'unprotect') return tableFail('bad action: ' + action + ' (protect/unprotect)');
    const password = (p && p.password != null) ? String(p.password) : '';
    try {
      if (action === 'protect') sheet.protect(password);
      else sheet.unprotect(password);
    } catch (e) { return tableFail((action === 'protect' ? '保护' : '取消保护') + '工作表失败: ' + errStr(e)); }
    let isProtected = null;
    try { isProtected = sheet.isProtected(); } catch (e) {}
    return { success: true, action: action, protected: isProtected };
  },
  // [表格·结构] 行列分组/大纲（XSheetOutline）。op: group/ungroup/show/hide；
  // orient: rows/cols（show/hide 不区分方向，直接对 range 生效）。
  sheet_group_rows_cols(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    const range = sheetRange(sheet, String((p && p.range) || ''));
    if (!range) return tableFail('无效的区域: ' + ((p && p.range) || '(空)'));
    const addr = range.getRangeAddress();
    const orientRaw = String((p && p.orient) || 'rows').toLowerCase();
    const isCols = orientRaw === 'cols' || orientRaw === 'columns';
    const isRows = orientRaw === 'rows';
    if (!isCols && !isRows) return tableFail('bad orient: ' + (p && p.orient) + ' (rows/cols)');
    const orientation = isCols ? css.table.TableOrientation.COLUMNS : css.table.TableOrientation.ROWS;
    const op = String((p && p.op) || 'group').toLowerCase();
    try {
      if (op === 'group') sheet.group(addr, orientation);
      else if (op === 'ungroup') sheet.ungroup(addr, orientation);
      else if (op === 'show') sheet.showDetail(addr);
      else if (op === 'hide') sheet.hideDetail(addr);
      else return tableFail('bad op: ' + op + ' (group/ungroup/show/hide)');
    } catch (e) { return tableFail('分组操作失败: ' + errStr(e)); }
    return { success: true, op: op, orient: isCols ? 'cols' : 'rows', range: sheetRangeName(addr) };
  },
  // [表格·结构] 数据透视表（XDataPilotTables），基础形态：行分组 + 单个数据
  // 字段求和。rowFields/dataField 必须与源区域首行表头文字完全一致（Calc 按
  // 字段名定位）。复杂布局（列字段、多数据字段、自定义汇总函数、筛选字段）不
  // 支持——起步只做「按行分组求和」这一种最常用形态。
  sheet_add_pivot_table(p) {
    const r0 = resolveSheet(p);
    if (r0.error) return tableFail(r0.error);
    const sheet = r0.sheet;
    const source = sheetRange(sheet, String((p && p.sourceRange) || ''));
    if (!source) return tableFail('无效的源数据区域: ' + ((p && p.sourceRange) || '(空)'));
    const srcAddr = source.getRangeAddress();
    const rowFieldNames = String((p && p.rowFields) || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    const dataFieldName = (p && p.dataField != null) ? String(p.dataField).trim() : '';
    if (!rowFieldNames.length) return tableFail('sheet_add_pivot_table requires {rowFields}（逗号分隔的表头字段名）');
    if (!dataFieldName) return tableFail('sheet_add_pivot_table requires {dataField}（求和字段的表头字段名）');
    let dpTables;
    try { dpTables = sheet.getDataPilotTables(); } catch (e) { return tableFail('获取数据透视表集合失败: ' + errStr(e)); }
    const name = (p && p.name != null && String(p.name).trim()) ? String(p.name).trim() : ('PivotTable_' + Date.now());
    if (dpTables.hasByName(name)) return tableFail('数据透视表已存在: ' + name);
    let desc;
    try {
      desc = dpTables.createDataPilotDescriptor();
      desc.setSourceRange(srcAddr);
    } catch (e) { return tableFail('创建透视表描述失败: ' + errStr(e)); }
    let fields;
    try { fields = desc.getDataPilotFields(); } catch (e) { return tableFail('获取字段集合失败: ' + errStr(e)); }
    const missing = [];
    try {
      rowFieldNames.forEach(function (fn) {
        if (!fields.hasByName(fn)) { missing.push(fn); return; }
        fields.getByName(fn).setPropertyValue('Orientation', css.sheet.DataPilotFieldOrientation.ROW);
      });
      if (!fields.hasByName(dataFieldName)) missing.push(dataFieldName);
      else {
        const df = fields.getByName(dataFieldName);
        df.setPropertyValue('Orientation', css.sheet.DataPilotFieldOrientation.DATA);
        df.setPropertyValue('Function', css.sheet.GeneralFunction.SUM);
      }
    } catch (e) { return tableFail('设置字段方向失败: ' + errStr(e)); }
    if (missing.length) return tableFail('源区域表头找不到字段: ' + missing.join('、') + '（字段名须与首行表头文字完全一致）');
    let outAddr;
    try {
      if (p && p.outputCell) {
        const outCell = sheetRange(sheet, String(p.outputCell));
        if (!outCell) return tableFail('无效的输出位置: ' + p.outputCell);
        const oa = outCell.getRangeAddress();
        outAddr = new css.table.CellAddress({ Sheet: oa.Sheet, Column: oa.StartColumn, Row: oa.StartRow });
      } else {
        const outCol = Math.min(srcAddr.EndColumn + 2, 1023);
        outAddr = new css.table.CellAddress({ Sheet: srcAddr.Sheet, Column: outCol, Row: srcAddr.StartRow });
      }
    } catch (e) { return tableFail('计算输出位置失败: ' + errStr(e)); }
    try {
      dpTables.insertNewByName(name, outAddr, desc);
    } catch (e) { return tableFail('插入数据透视表失败: ' + errStr(e)); }
    return {
      success: true, name: name, sourceRange: sheetRangeName(srcAddr),
      rowFields: rowFieldNames, dataField: dataFieldName, function: 'sum', sheet: sheet.getName(),
    };
  },
  // ==================== 演示文稿原语（slide_*，Phase 1） ====================
  // 与 doc_*/sheet_* 平行的 pptx/odp 操作面。Impress 没有 Writer 的修订（redline）
  // 机制，写入即生效；安全网是 doc_undo 与后端文档检查点（fileEffect=MODIFIED）。
  // [幻灯片·看] 每页页码/名称/版式/母版/标题/形状数/是否有备注/是否含表格。
  // 打开演示文稿后的第一步。
  slide_get_overview() {
    if (!isImpressDoc()) return slideFail(NOT_PRESENTATION_MSG);
    let pages;
    try { pages = xModel.getDrawPages(); } catch (e) { return slideFail('读取幻灯片列表失败: ' + errStr(e)); }
    const count = pages.getCount();
    const slides = [];
    for (let i = 0; i < count; i++) {
      let page; try { page = pages.getByIndex(i); } catch (e) { continue; }
      let name = ''; try { name = page.getName ? page.getName() : ''; } catch (e) {}
      let layout = null; try { layout = page.getPropertyValue('Layout'); } catch (e) {}
      let masterName = ''; try { masterName = page.getMasterPage().getName(); } catch (e) {}
      let shapeCount = 0; try { shapeCount = page.getCount(); } catch (e) {}
      let titleText = '';
      let hasTable = false;
      for (let s = 0; s < shapeCount; s++) {
        let shape; try { shape = page.getByIndex(s); } catch (e) { continue; }
        const kind = shapeKind(shape);
        if (kind === 'title' && !titleText) titleText = shapeText(shape);
        if (kind === 'table') hasTable = true;
      }
      let hasNotes = false;
      try { hasNotes = notesPageText(page.getNotesPage()).trim().length > 0; } catch (e) {}
      slides.push({
        number: i + 1, name: name, layout: layout, layoutName: layoutNameOf(layout),
        masterName: masterName, titleText: titleText, shapeCount: shapeCount,
        hasNotes: hasNotes, hasTable: hasTable,
      });
    }
    return { success: true, slideCount: count, slides: slides };
  },
  // [幻灯片·看] 单页明细：尺寸/版式/母版/备注 + 每个形状的名称/类型/位置尺寸(磅)/
  // 文字，表格形状另带行列数。未命名形状被分配稳定名 __awd_shape_N。
  slide_get_page(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const page = r0.page;
    ensureShapeNames(page);
    let width = 0, height = 0;
    try { width = hmmToPt(page.getPropertyValue('Width')); height = hmmToPt(page.getPropertyValue('Height')); } catch (e) {}
    let layout = null; try { layout = page.getPropertyValue('Layout'); } catch (e) {}
    let masterName = ''; try { masterName = page.getMasterPage().getName(); } catch (e) {}
    let notesText = ''; try { notesText = notesPageText(page.getNotesPage()); } catch (e) {}
    const n = page.getCount();
    const shapes = [];
    for (let i = 0; i < n; i++) {
      let shape; try { shape = page.getByIndex(i); } catch (e) { continue; }
      let name = ''; try { name = shape.getName(); } catch (e) {}
      const kind = shapeKind(shape);
      let pos = null, size = null;
      try { pos = shape.getPosition(); } catch (e) {}
      try { size = shape.getSize(); } catch (e) {}
      const item = {
        name: name, kind: kind,
        left: pos ? hmmToPt(pos.X) : null, top: pos ? hmmToPt(pos.Y) : null,
        width: size ? hmmToPt(size.Width) : null, height: size ? hmmToPt(size.Height) : null,
        text: shapeText(shape), isTable: kind === 'table',
      };
      if (item.isTable) {
        try {
          const table = shape.getPropertyValue('Model');
          item.rows = table.getRows().getCount();
          item.cols = table.getColumns().getCount();
        } catch (e) { item.tableErr = errStr(e); }
      } else {
        // [Phase 3] 格式读回：首字符代表值，供 slide_format_text 的调用方核实
        // 真实生效值（而非只信 setter 返回的 applied 回声）。表格形状不适用
        // （每格文字可各自不同，读整格没有意义），保持 null。
        item.format = shapeCharFormat(shape);
      }
      shapes.push(item);
    }
    return {
      success: true, number: r0.index + 1, width: width, height: height,
      layout: layout, layoutName: layoutNameOf(layout), masterName: masterName,
      notesText: notesText, shapes: shapes,
    };
  },
  // [幻灯片·看] 备注页（Speaker Notes）文字。不传 slideNumber 读取全篇。
  slide_read_notes(p) {
    if (!isImpressDoc()) return slideFail(NOT_PRESENTATION_MSG);
    let pages;
    try { pages = xModel.getDrawPages(); } catch (e) { return slideFail('读取幻灯片列表失败: ' + errStr(e)); }
    const count = pages.getCount();
    const want = p && p.slideNumber != null ? Number(p.slideNumber) : null;
    if (want != null && (!Number.isFinite(want) || want < 1 || want > count)) {
      return slideFail('页码越界: ' + p.slideNumber + '（共 ' + count + ' 页，1 开始）');
    }
    const from = want != null ? want - 1 : 0;
    const to = want != null ? want - 1 : count - 1;
    const notes = [];
    for (let i = from; i <= to; i++) {
      let page; try { page = pages.getByIndex(i); } catch (e) { continue; }
      let text = ''; try { text = notesPageText(page.getNotesPage()); } catch (e) {}
      notes.push({ slideNumber: i + 1, text: text });
    }
    return { success: true, notes: notes };
  },
  // [幻灯片·写] 整体覆盖指定页的备注文字。
  slide_write_notes(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const text = p && p.text != null ? String(p.text) : '';
    let notesPage;
    try { notesPage = r0.page.getNotesPage(); } catch (e) { return slideFail('获取备注页失败: ' + errStr(e)); }
    if (!notesPage) return slideFail('该幻灯片没有备注页');
    const noteShape = findNotesTextShape(notesPage);
    if (!noteShape) return slideFail('该幻灯片没有备注文本框（备注正文形状未找到）');
    let previousText = ''; try { previousText = shapeText(noteShape); } catch (e) {}
    try { noteShape.getText().setString(text); } catch (e) { return slideFail('写入备注失败: ' + errStr(e)); }
    return { success: true, slideNumber: r0.index + 1, previousText: previousText };
  },
  // [幻灯片·定位] 视图切到指定页，可选再选中某个形状——拟人：操作发生在哪要让
  // 用户看得见（同 resolveSheet 切活动表口径）。
  slide_goto(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    let selected = null;
    const wantShape = p && p.shapeName != null && String(p.shapeName).trim() !== '' ? String(p.shapeName).trim() : null;
    if (wantShape) {
      ensureShapeNames(r0.page);
      const rs = resolveShape(r0.page, { shapeName: wantShape });
      if (rs.error) return slideFail(rs.error);
      try {
        if (ctrl && ctrl.select) ctrl.select(rs.shape);
        selected = rs.name;
      } catch (e) { return slideFail('选中形状失败: ' + errStr(e)); }
    }
    return { success: true, slideNumber: r0.index + 1, selected: selected };
  },
  // [幻灯片·写] 整体覆盖指定形状的文字（文本框/标题/占位符）。
  slide_set_shape_text(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    if (!(p && p.shapeName)) return slideFail('缺少 shapeName 参数');
    ensureShapeNames(r0.page);
    const rs = resolveShape(r0.page, p);
    if (rs.error) return slideFail(rs.error);
    const text = p && p.text != null ? String(p.text) : '';
    let previousText = ''; try { previousText = shapeText(rs.shape); } catch (e) {}
    try { rs.shape.getText().setString(text); } catch (e) { return slideFail('写入形状文字失败: ' + errStr(e)); }
    return { success: true, previousText: previousText };
  },
  // [幻灯片·写] 跨页（或限定单页）查找替换文字，覆盖普通文本框与表格单元格。
  // 缺省只替换第一处命中即返回；all=true 替换全部匹配。
  slide_replace_text(p) {
    if (!isImpressDoc()) return slideFail(NOT_PRESENTATION_MSG);
    const searchText = p && p.searchText != null ? String(p.searchText) : '';
    if (!searchText) return slideFail('缺少 searchText 参数');
    const replaceText = p && p.replaceText != null ? String(p.replaceText) : '';
    const all = !!(p && p.all);
    let pages;
    try { pages = xModel.getDrawPages(); } catch (e) { return slideFail('读取幻灯片列表失败: ' + errStr(e)); }
    const count = pages.getCount();
    let from = 0, to = count - 1;
    if (p && p.slideNumber != null) {
      const want = Number(p.slideNumber);
      if (!Number.isFinite(want) || want < 1 || want > count) {
        return slideFail('页码越界: ' + p.slideNumber + '（共 ' + count + ' 页，1 开始）');
      }
      from = to = want - 1;
    }
    let replaced = 0;
    const hits = [];
    for (let pi = from; pi <= to; pi++) {
      let page; try { page = pages.getByIndex(pi); } catch (e) { continue; }
      ensureShapeNames(page);
      const n = page.getCount();
      for (let si = 0; si < n; si++) {
        let shape; try { shape = page.getByIndex(si); } catch (e) { continue; }
        const kind = shapeKind(shape);
        if (kind === 'table') {
          let table; try { table = shape.getPropertyValue('Model'); } catch (e) { continue; }
          let rows = 0, cols = 0;
          try { rows = table.getRows().getCount(); cols = table.getColumns().getCount(); } catch (e) { continue; }
          for (let r = 0; r < rows; r++) {
            for (let c = 0; c < cols; c++) {
              let cell; try { cell = table.getCellByPosition(c, r); } catch (e) { continue; }
              let cellText = ''; try { cellText = cell.getString() || ''; } catch (e) {}
              if (!cellText || cellText.indexOf(searchText) === -1) continue;
              const hitCount = cellText.split(searchText).length - 1;
              const newText = cellText.split(searchText).join(replaceText);
              try { cell.setString(newText); } catch (e) { continue; }
              replaced += hitCount;
              let name = ''; try { name = shape.getName(); } catch (e) {}
              hits.push({ slideNumber: pi + 1, shapeName: name });
              if (!all) return { success: true, replaced: replaced, hits: hits };
            }
          }
          continue;
        }
        const text = shapeText(shape);
        if (!text || text.indexOf(searchText) === -1) continue;
        const hitCount = text.split(searchText).length - 1;
        const newText = text.split(searchText).join(replaceText);
        try { shape.getText().setString(newText); } catch (e) { continue; }
        replaced += hitCount;
        let name = ''; try { name = shape.getName(); } catch (e) {}
        hits.push({ slideNumber: pi + 1, shapeName: name });
        if (!all) return { success: true, replaced: replaced, hits: hits };
      }
    }
    return { success: true, replaced: replaced, hits: hits };
  },
  // ==================== 演示文稿原语（slide_*，Phase 2：页与形状结构） ====================
  // 设计依据：docs/superpowers/specs/2026-08-07-impress-bridge-design.md §4.2（原语 8-15）。
  // [幻灯片·写] 插入新页。position（1 起，插到第 N 页之后；缺省末尾）+ 可选 layout
  // （AutoLayout short 常量）+ 可选 title/body（需要 layout 生成对应占位符才有地方落字，
  // 未显式给 layout 但给了 title/body 时默认套 layout=1「标题+内容」）。
  slide_add_page(p) {
    if (!isImpressDoc()) return slideFail(NOT_PRESENTATION_MSG);
    let pages;
    try { pages = xModel.getDrawPages(); } catch (e) { return slideFail('读取幻灯片列表失败: ' + errStr(e)); }
    const count = pages.getCount();
    let insertIndex; // 目标最终 0-based 落点，面向调用方语义不变
    if (p && p.position != null) {
      const want = Number(p.position);
      if (!Number.isFinite(want) || want < 1 || want > count) {
        return slideFail('position 越界: ' + p.position + '（共 ' + count + ' 页，1 开始，插到第 N 页之后）');
      }
      insertIndex = want; // 插到第 want 页之后 => 新页 0-based index = want
    } else {
      insertIndex = count; // 缺省末尾
    }
    // 真机实测（r4）：XDrawPages.insertNewByIndex(nIndex) 的落点在这个引擎构建上
    // 不总是 nIndex 本身——边界情形（nIndex===当前页数）与看似正常的中间索引都
    // 观察到过偏差（debug_impress_bare_append 探针 + e2e 组 22 实测均复现），死信
    // 任何具体传入索引都不可靠。改用唯一没有歧义的调用方式：永远插在最前面
    // （index 0，不存在"边界"这回事），定位到它后再用已验证可靠的 .uno:MovePage*
    // 挪到真正目标位置（与 slide_move_page 同一套实现，已过多组真机断言）。
    // 另外，文档已经历过一次插入后，同一文档上再插入可能连带清空某个"仅标题"
    // 版式（无内容占位符）既有页的标题占位符——与我们往新页里塞了什么完全无关
    // （裸调、不加任何内容也一样复现），疑似 r4 引擎对 AutoLayout 占位符的重算
    // 逻辑本身有 bug，规避不了触发条件；用"插入前拍全篇标题快照、插入后逐页核对
    // 补回"的自愈兜底，保证"新增一页"这个操作的净效果里不会有别的页丢内容。
    const beforeTitles = [];
    for (let i = 0; i < count; i++) {
      let pg; try { pg = pages.getByIndex(i); } catch (e) { beforeTitles.push(null); continue; }
      let t = null;
      for (let s = 0; s < pg.getCount(); s++) {
        let sh; try { sh = pg.getByIndex(s); } catch (e) { continue; }
        if (shapeKind(sh) === 'title') { try { t = shapeText(sh); } catch (e) {} break; }
      }
      beforeTitles.push(t);
    }
    try { pages.insertNewByIndex(0); } catch (e) { return slideFail('插入幻灯片失败: ' + errStr(e)); }
    let pages2;
    try { pages2 = xModel.getDrawPages(); } catch (e) { return slideFail('重新读取幻灯片列表失败: ' + errStr(e)); }
    const newCount = pages2.getCount();
    if (newCount !== count + 1) return slideFail('插入幻灯片后页数未增加，疑似失败');
    // 鲁棒定位新页：index 0 应当就是刚创建的空白页（形状数=0）；万一这个假设也不
    // 成立（引擎行为超出预期），退化为全篇扫描找空白页，找不到就明确报错，不要
    // 悄悄操作错的页。
    let newIndex = -1;
    try { if (pages2.getByIndex(0).getCount() === 0) newIndex = 0; } catch (e) {}
    if (newIndex === -1) {
      for (let i = 0; i < newCount; i++) {
        let cand; try { cand = pages2.getByIndex(i); } catch (e) { continue; }
        let n = -1; try { n = cand.getCount(); } catch (e) {}
        if (n === 0) { newIndex = i; break; }
      }
    }
    if (newIndex === -1) return slideFail('插入幻灯片后无法定位新页（未找到空白页）');
    let page;
    try { page = pages2.getByIndex(newIndex); } catch (e) { return slideFail('定位新页失败: ' + errStr(e)); }
    // 挪位放在自愈之前：自愈是在既有页上补形状，不改变页顺序，先做后做都不影响
    // movePageTo 的落点判定（它每步都重新核对 page 的实际位置，不依赖顺序假设）。
    if (newIndex !== insertIndex) {
      const finalIndex = movePageTo(page, insertIndex);
      if (finalIndex !== insertIndex) {
        return slideFail('新页未能挪到预期位置: 目标第 ' + (insertIndex + 1) + ' 页，实际落在第 ' + (finalIndex + 1) + ' 页');
      }
    }
    // 自愈核对：此刻新页已确定落在 insertIndex（要么本来就是，要么挪位已复核通过）。
    // "index i（i!==insertIndex）对应插入前 index：i<insertIndex 时是 i 本身，
    // 否则是 i-1"——因为其余既有页彼此之间的相对顺序全程不变，只是被新页插入的
    // 那个位置隔开，这条位置算术与新页具体经过几次挪位无关，只看它最终停在哪。
    try {
      const pagesFinal = xModel.getDrawPages();
      const finalCount = pagesFinal.getCount();
      for (let i = 0; i < finalCount; i++) {
        if (i === insertIndex) continue;
        const preIdx = i < insertIndex ? i : i - 1;
        if (preIdx < 0 || preIdx >= beforeTitles.length) continue;
        const expected = beforeTitles[preIdx];
        if (expected == null) continue; // 该页原本就没有标题占位符，不用管
        let pg; try { pg = pagesFinal.getByIndex(i); } catch (e) { continue; }
        let curTitle = null;
        for (let s = 0; s < pg.getCount(); s++) {
          let sh; try { sh = pg.getByIndex(s); } catch (e) { continue; }
          if (shapeKind(sh) === 'title') { try { curTitle = shapeText(sh); } catch (e) {} break; }
        }
        if (curTitle === expected) continue; // 完好
        try {
          const repairShape = xModel.createInstance('com.sun.star.presentation.TitleTextShape');
          pg.add(repairShape);
          let rpw = 720, rph = 540;
          try { rpw = Number(pg.getPropertyValue('Width')); rph = Number(pg.getPropertyValue('Height')); } catch (e) {}
          repairShape.setPosition(new css.awt.Point({ X: Math.round(rpw * 0.08), Y: Math.round(rph * 0.05) }));
          repairShape.setSize(new css.awt.Size({ Width: Math.round(rpw * 0.84), Height: Math.round(rph * 0.15) }));
          repairShape.getText().setString(expected);
        } catch (e) { /* 自愈失败不阻断主流程，已尽力保留原内容 */ }
      }
    } catch (e) { /* 自愈整体失败不阻断主流程 */ }
    // layout 只在调用方显式给出时才碰这个属性。title/body 走手工 createInstance
    // 真占位符服务类型（presentation.TitleTextShape / OutlineTextShape）直接
    // page.add()，**不经过 Layout 属性**——保留 slide_get_page/slide_get_overview
    // 靠 shapeKind() 识别 'title'/'placeholder' 的既有读取路径不用改；createInstance
    // 失败（真机未核实这两个服务在未设 Layout 时能否独立
    // 创建）时回退普通文本框，保证功能不因服务名不可用而整体失败。
    const layoutIn = p && p.layout != null ? Number(p.layout) : null;
    if (layoutIn != null) { try { page.setPropertyValue('Layout', shortAny(layoutIn)); } catch (e) {} }
    const wantTitle = p && p.title != null ? String(p.title) : null;
    const wantBody = p && p.body != null ? String(p.body) : null;
    let pw = 720, ph = 540; // 缺省 fallback（1/100mm），读不到页面尺寸时仍能给出合理位置
    try { pw = Number(page.getPropertyValue('Width')); ph = Number(page.getPropertyValue('Height')); } catch (e) {}
    function createPlaceholderShape(service) {
      try { const s = xModel.createInstance(service); page.add(s); return s; }
      catch (e) {
        try { const s2 = xModel.createInstance('com.sun.star.drawing.TextShape'); page.add(s2); return s2; }
        catch (e2) { return null; }
      }
    }
    if (wantTitle != null) {
      try {
        const titleShape = createPlaceholderShape('com.sun.star.presentation.TitleTextShape');
        if (titleShape) {
          titleShape.setPosition(new css.awt.Point({ X: Math.round(pw * 0.08), Y: Math.round(ph * 0.05) }));
          titleShape.setSize(new css.awt.Size({ Width: Math.round(pw * 0.84), Height: Math.round(ph * 0.15) }));
          titleShape.getText().setString(wantTitle);
          try {
            const cur = titleShape.getText().createTextCursor();
            cur.gotoStart(false); cur.gotoEnd(true);
            setCharProp(cur, 'CharHeight', 28);
            setCharProp(cur, 'CharWeight', css.awt.FontWeight.BOLD);
          } catch (e) {}
        }
      } catch (e) { /* 标题框创建失败不阻断整体插页 */ }
    }
    if (wantBody != null) {
      try {
        const bodyShape = createPlaceholderShape('com.sun.star.presentation.OutlineTextShape');
        if (bodyShape) {
          bodyShape.setPosition(new css.awt.Point({ X: Math.round(pw * 0.08), Y: Math.round(ph * 0.25) }));
          bodyShape.setSize(new css.awt.Size({ Width: Math.round(pw * 0.84), Height: Math.round(ph * 0.65) }));
          bodyShape.getText().setString(wantBody);
        }
      } catch (e) { /* 正文框创建失败不阻断整体插页 */ }
    }
    ensureShapeNames(page);
    try { if (ctrl && ctrl.setCurrentPage) ctrl.setCurrentPage(page); } catch (e) {}
    let layoutOut = null; try { layoutOut = page.getPropertyValue('Layout'); } catch (e) {}
    return { success: true, slideNumber: insertIndex + 1, layout: layoutOut };
  },
  // [幻灯片·写] 删除指定页。拒绝删到 0 页（至少保留一页）。
  slide_delete_page(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    let pages;
    try { pages = xModel.getDrawPages(); } catch (e) { return slideFail('读取幻灯片列表失败: ' + errStr(e)); }
    if (pages.getCount() <= 1) return slideFail('无法删除：演示文稿只剩最后一页');
    try { pages.remove(r0.page); } catch (e) { return slideFail('删除幻灯片失败: ' + errStr(e)); }
    return { success: true, slideCount: pages.getCount() };
  },
  // [幻灯片·写] 把指定页移到新位置。XDrawPages 只有 insert/remove、无直接 move API
  // （GenericDrawPage.Number 只读），走 .uno:MovePageUp/Down/First/Last 派发（同「删除键
  // 必须走 .uno: 调度」PR#164/166 一个经验），再用页在 getDrawPages() 里的实际下标
  // 双口径复核——不信 dispatch 本身的返回值。
  slide_move_page(p) {
    const r0 = resolvePage(p); // 顺手把 slideNumber 设为当前页，MovePage* 作用于当前页
    if (r0.error) return slideFail(r0.error);
    let pages;
    try { pages = xModel.getDrawPages(); } catch (e) { return slideFail('读取幻灯片列表失败: ' + errStr(e)); }
    const count = pages.getCount();
    const to = p && p.toPosition != null ? Number(p.toPosition) : NaN;
    if (!Number.isFinite(to) || to < 1 || to > count) {
      return slideFail('toPosition 越界: ' + (p && p.toPosition) + '（共 ' + count + ' 页，1 开始）');
    }
    const from = r0.index;
    const toIndex = to - 1;
    const finalIndex = movePageTo(r0.page, toIndex);
    if (finalIndex !== toIndex) {
      return slideFail('移动幻灯片未达预期位置: 目标第 ' + to + ' 页，实际落在第 ' + (finalIndex + 1) + ' 页');
    }
    return { success: true, from: from + 1, to: to };
  },
  // [幻灯片·写] 设置版式（AutoLayout short 常量）与/或母版（按名匹配 XMasterPagesSupplier）。
  // 至少给一个参数。
  slide_set_layout(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    if (!p || (p.layout == null && (p.masterName == null || String(p.masterName).trim() === ''))) {
      return slideFail('缺少 layout 或 masterName 参数（至少给一个）');
    }
    const page = r0.page;
    if (p.layout != null) {
      const layoutNum = Number(p.layout);
      if (!Number.isFinite(layoutNum)) return slideFail('layout 必须是数字（AutoLayout 常量）');
      try { page.setPropertyValue('Layout', shortAny(layoutNum)); } catch (e) { return slideFail('设置版式失败: ' + errStr(e)); }
    }
    if (p.masterName != null && String(p.masterName).trim() !== '') {
      const wantMaster = String(p.masterName).trim();
      let masters; try { masters = xModel.getMasterPages(); } catch (e) { return slideFail('读取母版列表失败: ' + errStr(e)); }
      let found = null;
      for (let i = 0; i < masters.getCount(); i++) {
        let m; try { m = masters.getByIndex(i); } catch (e) { continue; }
        let name = ''; try { name = m.getName(); } catch (e) {}
        if (name === wantMaster) { found = m; break; }
      }
      if (!found) return slideFail('母版不存在: ' + wantMaster);
      try { page.setMasterPage(found); } catch (e) { return slideFail('设置母版失败: ' + errStr(e)); }
    }
    let layoutOut = null; try { layoutOut = page.getPropertyValue('Layout'); } catch (e) {}
    let masterOut = ''; try { masterOut = page.getMasterPage().getName(); } catch (e) {}
    return { success: true, layout: layoutOut, layoutName: layoutNameOf(layoutOut), masterName: masterOut };
  },
  // [幻灯片·写] 插入文本框。位置尺寸缺省值（磅）：left/top=100, width=300, height=80。
  slide_add_text_box(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const text = p && p.text != null ? String(p.text) : '';
    const leftPt = p && p.left != null ? Number(p.left) : 100;
    const topPt = p && p.top != null ? Number(p.top) : 100;
    const widthPt = p && p.width != null ? Number(p.width) : 300;
    const heightPt = p && p.height != null ? Number(p.height) : 80;
    let shape;
    try { shape = xModel.createInstance('com.sun.star.drawing.TextShape'); }
    catch (e) { return slideFail('创建文本框失败: ' + errStr(e)); }
    try { r0.page.add(shape); } catch (e) { return slideFail('添加文本框到页面失败: ' + errStr(e)); }
    try {
      shape.setSize(new css.awt.Size({ Width: ptToMm100(widthPt), Height: ptToMm100(heightPt) }));
      shape.setPosition(new css.awt.Point({ X: ptToMm100(leftPt), Y: ptToMm100(topPt) }));
    } catch (e) { return slideFail('设置文本框位置尺寸失败: ' + errStr(e)); }
    try { shape.getText().setString(text); } catch (e) { return slideFail('写入文本框文字失败: ' + errStr(e)); }
    if (p && (p.fontSize != null || p.bold != null || p.color != null)) {
      try {
        const cur = shape.getText().createTextCursor();
        cur.gotoStart(false); cur.gotoEnd(true);
        if (p.fontSize != null) setCharProp(cur, 'CharHeight', Number(p.fontSize));
        if (p.bold != null) setCharProp(cur, 'CharWeight', p.bold ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL);
        if (p.color != null) { const c = parseColor(p.color); if (c != null) setCharProp(cur, 'CharColor', c); }
      } catch (e) {}
    }
    ensureShapeNames(r0.page);
    let name = ''; try { name = shape.getName(); } catch (e) {}
    return { success: true, shapeName: name };
  },
  // [幻灯片·写] 插入形状：矩形/椭圆/线条走对应 UNO 服务；三角形走 CustomShape +
  // CustomShapeGeometry（Type='triangle' 是 LO 内建的预设几何名，与形状库里的三角形
  // 同一条 preset）。位置尺寸缺省值（磅）：left/top=100, width=200, height=150。
  slide_add_shape(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const type = p && p.shapeType != null ? String(p.shapeType).trim().toLowerCase() : '';
    const SHAPE_SERVICE = {
      rectangle: 'com.sun.star.drawing.RectangleShape',
      ellipse: 'com.sun.star.drawing.EllipseShape',
      line: 'com.sun.star.drawing.LineShape',
    };
    const isTriangle = type === 'triangle';
    const service = isTriangle ? 'com.sun.star.drawing.CustomShape' : SHAPE_SERVICE[type];
    if (!service) return slideFail('未知 shapeType: ' + type + '（支持 rectangle/ellipse/triangle/line）');
    const leftPt = p && p.left != null ? Number(p.left) : 100;
    const topPt = p && p.top != null ? Number(p.top) : 100;
    const widthPt = p && p.width != null ? Number(p.width) : 200;
    const heightPt = p && p.height != null ? Number(p.height) : 150;
    let shape;
    try { shape = xModel.createInstance(service); } catch (e) { return slideFail('创建形状失败: ' + errStr(e)); }
    try { r0.page.add(shape); } catch (e) { return slideFail('添加形状到页面失败: ' + errStr(e)); }
    try {
      shape.setSize(new css.awt.Size({ Width: ptToMm100(widthPt), Height: ptToMm100(heightPt) }));
      shape.setPosition(new css.awt.Point({ X: ptToMm100(leftPt), Y: ptToMm100(topPt) }));
    } catch (e) { return slideFail('设置形状位置尺寸失败: ' + errStr(e)); }
    if (isTriangle) {
      try { shape.setPropertyValue('CustomShapeGeometry', [mkProp('Type', 'triangle')]); }
      catch (e) { return slideFail('设置三角形几何失败: ' + errStr(e)); }
    }
    if (p && p.fillColor != null) {
      const c = parseColor(p.fillColor);
      if (c != null) { try { shape.setPropertyValue('FillColor', c); } catch (e) {} }
    }
    if (p && p.text != null && String(p.text) !== '') {
      try { shape.getText().setString(String(p.text)); } catch (e) {}
    }
    ensureShapeNames(r0.page);
    let name = ''; try { name = shape.getName(); } catch (e) {}
    return { success: true, shapeName: name };
  },
  // [幻灯片·写] 按 shapeName 精确删除一个形状。
  slide_delete_shape(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    ensureShapeNames(r0.page);
    const rs = resolveShape(r0.page, p);
    if (rs.error) return slideFail(rs.error);
    try { r0.page.remove(rs.shape); } catch (e) { return slideFail('删除形状失败: ' + errStr(e)); }
    return { success: true, deleted: rs.name };
  },
  // [幻灯片·写] 移动/改尺寸一个形状（AI 逐步调排版）。left/top/width/height 均可选，
  // 缺省保留原值；返回 before/after 供调用方核对实际生效值。
  slide_set_shape_geometry(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    if (!(p && p.shapeName)) return slideFail('缺少 shapeName 参数');
    ensureShapeNames(r0.page);
    const rs = resolveShape(r0.page, p);
    if (rs.error) return slideFail(rs.error);
    const shape = rs.shape;
    let beforePos = null, beforeSize = null;
    try { beforePos = shape.getPosition(); beforeSize = shape.getSize(); } catch (e) {}
    const before = {
      left: beforePos ? hmmToPt(beforePos.X) : null, top: beforePos ? hmmToPt(beforePos.Y) : null,
      width: beforeSize ? hmmToPt(beforeSize.Width) : null, height: beforeSize ? hmmToPt(beforeSize.Height) : null,
    };
    if (p.width != null || p.height != null) {
      const w = p.width != null ? ptToMm100(Number(p.width)) : (beforeSize ? beforeSize.Width : 0);
      const h = p.height != null ? ptToMm100(Number(p.height)) : (beforeSize ? beforeSize.Height : 0);
      try { shape.setSize(new css.awt.Size({ Width: w, Height: h })); }
      catch (e) { return slideFail('设置形状尺寸失败: ' + errStr(e)); }
    }
    if (p.left != null || p.top != null) {
      const x = p.left != null ? ptToMm100(Number(p.left)) : (beforePos ? beforePos.X : 0);
      const y = p.top != null ? ptToMm100(Number(p.top)) : (beforePos ? beforePos.Y : 0);
      try { shape.setPosition(new css.awt.Point({ X: x, Y: y })); }
      catch (e) { return slideFail('设置形状位置失败: ' + errStr(e)); }
    }
    let afterPos = null, afterSize = null;
    try { afterPos = shape.getPosition(); afterSize = shape.getSize(); } catch (e) {}
    const after = {
      left: afterPos ? hmmToPt(afterPos.X) : null, top: afterPos ? hmmToPt(afterPos.Y) : null,
      width: afterSize ? hmmToPt(afterSize.Width) : null, height: afterSize ? hmmToPt(afterSize.Height) : null,
    };
    return { success: true, before: before, after: after };
  },
  // ==================== 演示文稿原语（slide_*，Phase 3：格式与表格） ====================
  // 设计依据同上 §4.2（原语表 16-20；slide_format_shape/slide_table_set_style 是
  // 任务方直接点名要补的两项，不在原 20 个原语表里，随 Phase 3 一并实现）。
  // [幻灯片·写] 设置形状文字的字体/字号/粗斜体/下划线/删除线/颜色/段落对齐。
  // 不传 anchorText 格式化整个形状文字；传了则只格式化第一处命中该子串的文字。
  slide_format_text(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    if (!(p && p.shapeName)) return slideFail('缺少 shapeName 参数（先用 slide_get_page 查看该页形状名）');
    ensureShapeNames(r0.page);
    const rs = resolveShape(r0.page, p);
    if (rs.error) return slideFail(rs.error);
    let xText; try { xText = rs.shape.getText(); } catch (e) { return slideFail('该形状不支持文字: ' + errStr(e)); }
    const anchorText = p && p.anchorText != null && String(p.anchorText) !== '' ? String(p.anchorText) : null;
    let cur;
    if (anchorText) {
      let full = ''; try { full = xText.getString() || ''; } catch (e) {}
      const idx = full.indexOf(anchorText);
      if (idx === -1) return slideFail('形状文字中未找到: ' + anchorText);
      try {
        cur = xText.createTextCursor();
        cur.gotoStart(false);
        if (idx > 0 && !cur.goRight(idx, false)) return slideFail('定位查找文字起点失败');
        if (!cur.goRight(anchorText.length, true)) return slideFail('选中查找文字失败');
      } catch (e) { return slideFail('定位查找文字失败: ' + errStr(e)); }
    } else {
      try { cur = xText.createTextCursor(); cur.gotoStart(false); cur.gotoEnd(true); }
      catch (e) { return slideFail('选中形状全部文字失败: ' + errStr(e)); }
      if ((cur.getString() || '').length === 0) return slideFail('该形状没有文字，无法格式化');
    }
    const applied = {};
    if (p.fontName != null) { setCharProp(cur, 'CharFontName', String(p.fontName)); applied.fontName = String(p.fontName); }
    if (p.fontSize != null) { setCharProp(cur, 'CharHeight', Number(p.fontSize)); applied.fontSize = Number(p.fontSize); }
    if (p.bold != null) { setCharProp(cur, 'CharWeight', p.bold ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL); applied.bold = !!p.bold; }
    if (p.italic != null) { setCharProp(cur, 'CharPosture', p.italic ? css.awt.FontSlant.ITALIC : css.awt.FontSlant.NONE); applied.italic = !!p.italic; }
    if (p.underline != null) {
      const U = css.awt.FontUnderline;
      const m = { none: U.NONE, single: U.SINGLE, double: U.DOUBLE, dotted: U.DOTTED, wave: U.WAVE };
      const v = m[String(p.underline).toLowerCase()];
      if (v == null) return slideFail('未知 underline: ' + p.underline + '（支持 none/single/double/dotted/wave）');
      try { cur.setPropertyValue('CharUnderline', v); applied.underline = String(p.underline).toLowerCase(); }
      catch (e) { return slideFail('设置下划线失败: ' + errStr(e)); }
    }
    if (p.strikethrough != null) {
      try { cur.setPropertyValue('CharStrikeout', p.strikethrough ? css.awt.FontStrikeout.SINGLE : css.awt.FontStrikeout.NONE); applied.strikethrough = !!p.strikethrough; }
      catch (e) { return slideFail('设置删除线失败: ' + errStr(e)); }
    }
    if (p.color != null) {
      const c = parseColor(p.color, { auto: -1 });
      if (c == null) return slideFail('颜色格式非法: ' + p.color + '（用 #RRGGBB 或 auto）');
      try { cur.setPropertyValue('CharColor', c); applied.color = String(p.color); }
      catch (e) { return slideFail('设置颜色失败: ' + errStr(e)); }
    }
    if (p.alignment != null) {
      const A = css.style.ParagraphAdjust;
      const m2 = { left: A.LEFT, right: A.RIGHT, center: A.CENTER, justify: A.BLOCK };
      const v2 = m2[String(p.alignment).toLowerCase()];
      if (v2 == null) return slideFail('未知 alignment: ' + p.alignment + '（支持 left/right/center/justify）');
      try { cur.setPropertyValue('ParaAdjust', v2); applied.alignment = String(p.alignment).toLowerCase(); }
      catch (e) { return slideFail('设置对齐失败: ' + errStr(e)); }
    }
    if (Object.keys(applied).length === 0) return slideFail('未给出任何格式参数');
    return { success: true, shapeName: rs.name, applied: applied };
  },
  // [幻灯片·写] 设置形状的填充色/边框颜色与粗细/透明度。noFill/noLine 取消填充/边框。
  slide_format_shape(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    if (!(p && p.shapeName)) return slideFail('缺少 shapeName 参数（先用 slide_get_page 查看该页形状名）');
    ensureShapeNames(r0.page);
    const rs = resolveShape(r0.page, p);
    if (rs.error) return slideFail(rs.error);
    const shape = rs.shape;
    // FillStyle/LineStyle 枚举名防御式取值（同 applyTableBorders 里 BorderLineStyle
    // 的既有口径）——本次调研未在真机上逐值核对 css.drawing 命名空间是否已在这个
    // 引擎构建里可用，取不到就退回 idl 已知的数值常量，不让整个原语因为枚举取值
    // 失败而报错。
    const FILL_STYLE = (css.drawing && css.drawing.FillStyle) || { NONE: 0, SOLID: 1 };
    const LINE_STYLE = (css.drawing && css.drawing.LineStyle) || { NONE: 0, SOLID: 1 };
    const applied = {};
    if (p.noFill === true) {
      try { shape.setPropertyValue('FillStyle', FILL_STYLE.NONE); applied.noFill = true; }
      catch (e) { return slideFail('取消填充失败: ' + errStr(e)); }
    } else if (p.fillColor != null) {
      const c = parseColor(p.fillColor);
      if (c == null) return slideFail('fillColor 格式非法: ' + p.fillColor + '（用 #RRGGBB）');
      try { shape.setPropertyValue('FillStyle', FILL_STYLE.SOLID); shape.setPropertyValue('FillColor', c); applied.fillColor = String(p.fillColor); }
      catch (e) { return slideFail('设置填充色失败: ' + errStr(e)); }
    }
    if (p.noLine === true) {
      try { shape.setPropertyValue('LineStyle', LINE_STYLE.NONE); applied.noLine = true; }
      catch (e) { return slideFail('取消边框失败: ' + errStr(e)); }
    } else if (p.lineColor != null) {
      const c = parseColor(p.lineColor);
      if (c == null) return slideFail('lineColor 格式非法: ' + p.lineColor + '（用 #RRGGBB）');
      try { shape.setPropertyValue('LineStyle', LINE_STYLE.SOLID); shape.setPropertyValue('LineColor', c); applied.lineColor = String(p.lineColor); }
      catch (e) { return slideFail('设置边框颜色失败: ' + errStr(e)); }
    }
    if (p.lineWidthPt != null) {
      try { shape.setPropertyValue('LineWidth', ptToMm100(Number(p.lineWidthPt))); applied.lineWidthPt = Number(p.lineWidthPt); }
      catch (e) { return slideFail('设置边框粗细失败: ' + errStr(e)); }
    }
    if (p.fillTransparency != null) {
      const t = Math.max(0, Math.min(100, Math.round(Number(p.fillTransparency))));
      try { shape.setPropertyValue('FillTransparence', shortAny(t)); applied.fillTransparency = t; }
      catch (e) { return slideFail('设置填充透明度失败: ' + errStr(e)); }
    }
    if (Object.keys(applied).length === 0) return slideFail('未给出任何样式参数（fillColor/noFill/lineColor/noLine/lineWidthPt/fillTransparency 至少给一个）');
    return { success: true, shapeName: rs.name, applied: applied };
  },
  // [幻灯片·写] 插入一张表格形状。rowsJson（后端已解析为二维数组）给出时按其行列数
  // 建表并写满；否则按 rows/cols（默认各 2）建空表。新建的 TableShape 默认行列数
  // 未知，统一按"当前行列数 vs 目标行列数"的差值调用 insertByIndex/removeByIndex
  // 补齐（与 doc_table_add_row/col 同一 XTableRows/XTableColumns 接口）。
  slide_add_table(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const rowsData = p && Array.isArray(p.rowsJson) ? p.rowsJson : null;
    if (rowsData != null && (!rowsData.length || !Array.isArray(rowsData[0]))) {
      return slideFail('rowsJson 必须是非空二维数组');
    }
    let rowCount, colCount;
    if (rowsData) { rowCount = rowsData.length; colCount = rowsData[0].length; }
    else {
      rowCount = p && p.rows != null ? Math.max(1, Math.round(Number(p.rows))) : 2;
      colCount = p && p.cols != null ? Math.max(1, Math.round(Number(p.cols))) : 2;
    }
    if (rowCount > 50 || colCount > 20) return slideFail('表格过大（上限 50 行 × 20 列）');
    let shape;
    try { shape = xModel.createInstance('com.sun.star.drawing.TableShape'); }
    catch (e) { return slideFail('创建表格形状失败: ' + errStr(e)); }
    try { r0.page.add(shape); } catch (e) { return slideFail('添加表格到页面失败: ' + errStr(e)); }
    const leftPt = p && p.left != null ? Number(p.left) : 100;
    const topPt = p && p.top != null ? Number(p.top) : 100;
    const widthPt = p && p.width != null ? Number(p.width) : 400;
    const heightPt = p && p.height != null ? Number(p.height) : 200;
    try {
      shape.setSize(new css.awt.Size({ Width: ptToMm100(widthPt), Height: ptToMm100(heightPt) }));
      shape.setPosition(new css.awt.Point({ X: ptToMm100(leftPt), Y: ptToMm100(topPt) }));
    } catch (e) { return slideFail('设置表格位置尺寸失败: ' + errStr(e)); }
    let table;
    try { table = shape.getPropertyValue('Model'); } catch (e) { return slideFail('读取表格模型失败: ' + errStr(e)); }
    if (!table) return slideFail('表格模型不可用（引擎未返回 Model）');
    try {
      const curRows = table.getRows().getCount();
      if (rowCount > curRows) table.getRows().insertByIndex(curRows, rowCount - curRows);
      else if (rowCount < curRows) table.getRows().removeByIndex(rowCount, curRows - rowCount);
      const curCols = table.getColumns().getCount();
      if (colCount > curCols) table.getColumns().insertByIndex(curCols, colCount - curCols);
      else if (colCount < curCols) table.getColumns().removeByIndex(colCount, curCols - colCount);
    } catch (e) { return slideFail('调整表格行列数失败: ' + errStr(e)); }
    const afterRows = table.getRows().getCount(), afterCols = table.getColumns().getCount();
    if (afterRows !== rowCount || afterCols !== colCount) {
      return slideFail('表格行列数调整未生效（目标 ' + rowCount + '×' + colCount + '，实际 ' + afterRows + '×' + afterCols + '）');
    }
    if (rowsData) {
      for (let rIdx = 0; rIdx < rowCount; rIdx++) {
        for (let cIdx = 0; cIdx < colCount; cIdx++) {
          const v = rowsData[rIdx][cIdx];
          try { table.getCellByPosition(cIdx, rIdx).setString(v == null ? '' : String(v)); } catch (e) {}
        }
      }
    }
    ensureShapeNames(r0.page);
    let name = ''; try { name = shape.getName(); } catch (e) {}
    return { success: true, shapeName: name, rows: rowCount, cols: colCount };
  },
  // [幻灯片·看] 读一张表格形状为二维数组。不传 shapeName 要求该页只有一张表格。
  slide_table_read(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const rt = resolveSlideTable(r0.page, p);
    if (rt.error) return slideFail(rt.error);
    let rows = 0, cols = 0;
    try { rows = rt.table.getRows().getCount(); cols = rt.table.getColumns().getCount(); }
    catch (e) { return slideFail('读取表格行列数失败: ' + errStr(e)); }
    const maxRows = Math.min(rows, Math.max(1, Number(p && p.maxRows) || 50));
    const maxCols = Math.min(cols, Math.max(1, Number(p && p.maxCols) || 20));
    const cells = [];
    for (let rIdx = 0; rIdx < maxRows; rIdx++) {
      const line = [];
      for (let cIdx = 0; cIdx < maxCols; cIdx++) {
        let v = ''; try { v = rt.table.getCellByPosition(cIdx, rIdx).getString() || ''; } catch (e) {}
        line.push(v);
      }
      cells.push(line);
    }
    return {
      success: true, shapeName: rt.shapeName, rows: rows, cols: cols, cells: cells,
      truncated: maxRows < rows || maxCols < cols,
    };
  },
  // [幻灯片·写] 改一张表格形状的一格文本。row/col 均 0 开始。不传 shapeName 要求
  // 该页只有一张表格。Impress 表格没有修订机制，写入直接生效。
  slide_table_set_cell(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const rt = resolveSlideTable(r0.page, p);
    if (rt.error) return slideFail(rt.error);
    if (p == null || p.row == null || p.col == null) return slideFail('缺少 row/col 参数（均 0 开始）');
    const rowIdx = Number(p.row), colIdx = Number(p.col);
    let rows = 0, cols = 0;
    try { rows = rt.table.getRows().getCount(); cols = rt.table.getColumns().getCount(); }
    catch (e) { return slideFail('读取表格行列数失败: ' + errStr(e)); }
    if (!Number.isFinite(rowIdx) || rowIdx < 0 || rowIdx >= rows) return slideFail('row 越界: ' + p.row + '（表格共 ' + rows + ' 行，0 开始）');
    if (!Number.isFinite(colIdx) || colIdx < 0 || colIdx >= cols) return slideFail('col 越界: ' + p.col + '（表格共 ' + cols + ' 列，0 开始）');
    let cell;
    try { cell = rt.table.getCellByPosition(colIdx, rowIdx); } catch (e) { return slideFail('定位单元格失败: ' + errStr(e)); }
    let previous = ''; try { previous = cell.getString() || ''; } catch (e) {}
    const text = p.text != null ? String(p.text) : '';
    try { cell.setString(text); } catch (e) { return slideFail('写入单元格失败: ' + errStr(e)); }
    return { success: true, shapeName: rt.shapeName, row: rowIdx, col: colIdx, previous: previous };
  },
  // [幻灯片·写] 表格整体样式：表头加粗/边框/列宽。三项互相独立、各自尽力而为——
  // Impress 表格的 UNO API 面比 Word/Calc 窄得多（本次实施调研没有查到 Impress
  // 表格单元格是否真的暴露 Top/BottomBorder 或列的 Width 属性），每项都用
  // try/catch 隔离，失败的项如实在返回值里报 applied:false 加原因，不装作成功。
  slide_table_set_style(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const rt = resolveSlideTable(r0.page, p);
    if (rt.error) return slideFail(rt.error);
    const table = rt.table;
    let rows = 0, cols = 0;
    try { rows = table.getRows().getCount(); cols = table.getColumns().getCount(); }
    catch (e) { return slideFail('读取表格行列数失败: ' + errStr(e)); }
    const result = { success: true, shapeName: rt.shapeName };
    let didAnything = false;

    if (p && p.headerBold != null) {
      didAnything = true;
      const want = !!p.headerBold;
      let ok = 0, fail = 0;
      for (let c = 0; c < cols; c++) {
        try {
          const cell = table.getCellByPosition(c, 0);
          let cur;
          try { cur = cell.createTextCursor(); }
          catch (e1) { cur = cell.getText().createTextCursor(); }
          cur.gotoStart(false); cur.gotoEnd(true);
          setCharProp(cur, 'CharWeight', want ? css.awt.FontWeight.BOLD : css.awt.FontWeight.NORMAL);
          ok++;
        } catch (e) { fail++; }
      }
      result.headerBold = { applied: ok > 0, cellsOk: ok, cellsFailed: fail };
      if (ok === 0) result.headerBold.note = '表格单元格文字光标获取失败（cell.createTextCursor / cell.getText().createTextCursor 均失败），表头加粗未生效';
    }

    if (p && (p.borderWidthPt != null || p.borderColor != null)) {
      didAnything = true;
      const widthMm = p.borderWidthPt != null ? ptToMm100(Number(p.borderWidthPt)) : ptToMm100(1);
      const colorNum = p.borderColor != null ? parseColor(p.borderColor, { black: 0 }) : 0;
      if (p.borderColor != null && colorNum == null) return slideFail('borderColor 格式非法: ' + p.borderColor + '（用 #RRGGBB）');
      const solid = (css.table.BorderLineStyle && css.table.BorderLineStyle.SOLID != null) ? css.table.BorderLineStyle.SOLID : 0;
      let bl;
      try { bl = new css.table.BorderLine2({ Color: colorNum == null ? 0 : colorNum, LineWidth: widthMm, LineStyle: solid }); }
      catch (e) { return slideFail('构建边框样式失败: ' + errStr(e)); }
      let ok = 0, fail = 0;
      for (let rIdx = 0; rIdx < rows; rIdx++) {
        for (let cIdx = 0; cIdx < cols; cIdx++) {
          try {
            const cell = table.getCellByPosition(cIdx, rIdx);
            cell.setPropertyValue('TopBorder', bl);
            cell.setPropertyValue('BottomBorder', bl);
            cell.setPropertyValue('LeftBorder', bl);
            cell.setPropertyValue('RightBorder', bl);
            ok++;
          } catch (e) { fail++; }
        }
      }
      result.borders = { applied: ok > 0, cellsOk: ok, cellsFailed: fail };
      if (ok === 0) result.borders.note = 'Impress 表格单元格未暴露 Top/Bottom/Left/RightBorder 属性（引擎不支持或本 API 路径不适用），边框设置未生效';
    }

    if (p && Array.isArray(p.columnWidthsPt)) {
      didAnything = true;
      let ok = 0, fail = 0;
      const n = Math.min(p.columnWidthsPt.length, cols);
      for (let i = 0; i < n; i++) {
        const wPt = Number(p.columnWidthsPt[i]);
        if (!Number.isFinite(wPt) || wPt <= 0) { fail++; continue; }
        try { table.getColumns().getByIndex(i).setPropertyValue('Width', ptToMm100(wPt)); ok++; }
        catch (e) { fail++; }
      }
      result.columnWidths = { applied: ok > 0, colsOk: ok, colsFailed: fail };
      if (ok === 0) result.columnWidths.note = 'Impress 表格列未暴露可写的 Width 属性（引擎不支持），列宽设置未生效';
    }

    if (!didAnything) return slideFail('未给出任何样式参数（headerBold / borderWidthPt+borderColor / columnWidthsPt 至少给一个）');
    return result;
  },
  // [幻灯片·写] 在指定页查找文字并加超链接。优先走与 Writer 的
  // set_hyperlink_at_anchor 同一实现口径：直接在选中范围上设 HyperLinkURL 字符
  // 属性。**真机实测（r4，e2e 组 23）：Impress 的 drawing.Text 不支持这个字符
  // 属性**（`UnknownPropertyException`，与 spec 调研阶段"drawing.Text 尽量复刻
  // Writer 功能"的预期不符，字符级超链接在这个引擎构建上不可行）——退回 spec
  // 备选方案：整个形状的交互动作 `OnClick=ClickAction.DOCUMENT` + `Bookmark=url`
  // （Impress UI「交互」面板同款机制），点击形状任意位置都会跳转，不再是"只有
  // 命中文字可点"这个更精确的粒度。返回值 via 字段区分两种情形（'HyperLinkURL'
  // 精确到字符 / 'shape-click-action' 整个形状降级），note 字段在降级时如实说明，
  // 不装作达成了字符级精度。只在非表格形状的文字里查找，命中第一处即返回。
  slide_set_hyperlink(p) {
    const r0 = resolvePage(p);
    if (r0.error) return slideFail(r0.error);
    const searchText = p && p.searchText != null ? String(p.searchText) : '';
    if (!searchText) return slideFail('缺少 searchText 参数');
    const url = p && p.url != null ? String(p.url) : '';
    if (!url) return slideFail('缺少 url 参数');
    if (!/^https?:\/\//i.test(url)) return slideFail('url 仅支持 http/https: ' + url);
    ensureShapeNames(r0.page);
    const n = r0.page.getCount();
    for (let i = 0; i < n; i++) {
      let shape; try { shape = r0.page.getByIndex(i); } catch (e) { continue; }
      if (shapeKind(shape) === 'table') continue; // 表格单元格内超链接本期不做
      let xText; try { xText = shape.getText(); } catch (e) { continue; }
      let full = ''; try { full = xText.getString() || ''; } catch (e) { continue; }
      const idx = full.indexOf(searchText);
      if (idx === -1) continue;
      let name = ''; try { name = shape.getName(); } catch (e) {}
      try {
        const cur = xText.createTextCursor();
        cur.gotoStart(false);
        if (idx > 0 && !cur.goRight(idx, false)) return slideFail('定位查找文字起点失败');
        if (!cur.goRight(searchText.length, true)) return slideFail('选中查找文字失败');
        cur.setPropertyValue('HyperLinkURL', url);
        return { success: true, shapeName: name, url: url, via: 'HyperLinkURL' };
      } catch (charErr) {
        try {
          const CA = (css.presentation && css.presentation.ClickAction) || { DOCUMENT: 6 };
          shape.setPropertyValue('OnClick', CA.DOCUMENT);
          shape.setPropertyValue('Bookmark', url);
          return {
            success: true, shapeName: name, url: url, via: 'shape-click-action',
            note: '该引擎的 Impress 文字不支持字符级超链接（HyperLinkURL 属性不存在），已回退为整个形状的点击交互动作——点击形状任意位置都会跳转，不只是命中文字',
          };
        } catch (shapeErr) {
          return slideFail('设置超链接失败（字符级与形状级均不支持）: ' + errStr(shapeErr));
        }
      }
    }
    return slideFail('未在该页任何非表格形状的文字中找到: ' + searchText);
  },
  // [诊断] 当前文档内核类型——host UI（审阅按钮等）按 kind 隐藏的判据。常规打开
  // 路径优先用 load_document 返回值里的 kind（省一次往返），本 action 供换文档
  // 之外的场景（如 e2e 探针）直接查询。
  get_doc_kind() {
    return { success: true, kind: docKindOf() };
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
  // 原语可以是 async（分批的 find_replace / apply_house_style）：返回 Promise 就等它，
  // 期间 worker 事件循环继续处理别的命令；同步原语路径与从前完全一样。
  const finish = function (result) {
    delete CANCELLED[reqId];
    post('result', { reqId: reqId, result: result });
  };
  let result;
  try {
    const p = params || {};
    p.__reqId = reqId; // 分批命令发 progress / 查 cancel 用
    // 修订署名切换：AI 命令（宿主打 __agent 标记）→ AI WorkDeck；其余（IME 输入
    // 等用户本人操作）→ 用户名。失败不阻断命令本身（降级为引擎默认作者）。
    try { setRedlineAuthor(p.__agent ? AI_AUTHOR : humanAuthor); }
    catch (e) { log('修订作者设置失败 / redline author failed: ' + errStr(e)); }
    const fn = EXEC[action];
    result = fn ? fn(p) : { success: false, message: 'not implemented in LibreOffice worker yet: ' + action };
  } catch (e) {
    result = { success: false, message: errStr(e) };
  }
  if (result && typeof result.then === 'function') {
    result.then(finish, function (e) { finish({ success: false, message: errStr(e) }); });
  } else {
    finish(result);
  }
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
