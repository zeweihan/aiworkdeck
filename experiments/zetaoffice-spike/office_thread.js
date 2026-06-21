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

function post(cmd, data) {
  zetajs.mainPort.postMessage(Object.assign({ cmd }, data || {}));
}
function log(msg) { post('log', { msg: String(msg) }); }
function errStr(e) {
  try { return zetajs.getAnyType(zetajs.catchUnoException(e)) + ' ' + (e && e.message || ''); }
  catch { return String(e && e.message || e); }
}

// ---- boot: open a fresh Writer doc seeded with Chinese + English ----------
function bootDoc() {
  context = zetajs.getUnoComponentContext();
  desktop = css.frame.Desktop.create(context);
  xModel = desktop.loadComponentFromURL('private:factory/swriter', '_default', 0, []);
  ctrl = xModel.getCurrentController();
  try { ctrl.getFrame().getContainerWindow().FullScreen = true; } catch {}

  const xText = xModel.getText();
  const cur = xText.createTextCursor();
  cur.setString('AI Workdeck × LibreOffice WASM 原型 / prototype.\n'
    + '请在此用系统输入法输入中文，观察候选与上屏 / Type Chinese here with your IME.\n'
    + 'Search target: LibreOffice — used by the redline probe.\n');

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

// ---- message loop --------------------------------------------------------
Module.zetajs.then(function (pZetajs) {
  zetajs = pZetajs;
  css = zetajs.uno.com.sun.star;
  zetajs.mainPort.onmessage = function (e) {
    switch (e.data.cmd) {
      case 'selection': testSelection(); break;
      case 'redline': testRedline(); break;
      case 'perf': testPerf(Number(e.data.pages) || 50); break;
      default: log('unknown cmd: ' + e.data.cmd);
    }
  };
  bootDoc();
  post('thr_running');
});
