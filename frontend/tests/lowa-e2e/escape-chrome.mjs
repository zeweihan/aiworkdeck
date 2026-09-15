// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真引擎回归：Esc 不许让顶层窗口退出全屏；上下文工具栏不许随上下文冒出来。
//
// 两条都是 v0.44.1 macOS 真机走查的报告（v0.44.1 真机走查 D1 / D2）：
//   D1 按 Esc → 画布里出现「未命名 1 — ZetaOffice Writer」标题栏的小窗。根因不在
//      DOM 层：真实键盘事件根本到不了引擎（本文件第 2 步的反向断言守着），是我们
//      自己那条 ui_command escape 派发的 .uno:Escape 把全屏帧退出了全屏。
//   D2 光标进表格单元格 → 底部冒出整条原生表格工具栏。真名是 tableobjectbar，
//      过去根本不在 CHROME_URLS 里；而且对一条还没创建的元素调 hideElement 不留痕，
//      必须 createElement 之后再 hide。
import assert from 'node:assert/strict'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
let failures = 0
const check = (label, ok, detail = '') => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (detail ? '  — ' + detail : ''))
  if (!ok) failures++
}
try {
  const page = await openEditor(browser)
  page.on('pageerror', e => console.error('PAGE ERROR:', e.message))
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  const settle = (ms = 1200) => new Promise(r => setTimeout(r, ms))
  // 顶层窗口的几何 = 「还在全屏吗」的可观测量：退出全屏后容器窗口不再跟画布同尺寸
  // （真机与无头都实测过：800x576 → 785x691），标题栏就是那时画出来的。
  const geometry = async () => {
    const r = await exec('get_cursor_rect', {})
    assert.ok(r && r.success && r.winPx && r.nativeCaret, 'get_cursor_rect 要能回读窗口几何: ' + JSON.stringify(r).slice(0, 200))
    return JSON.stringify({ win: r.winPx, frame: { w: r.nativeCaret.frameWidth, h: r.nativeCaret.frameHeight } })
  }

  await exec('insert_at_cursor', { text: '第一条 甲方与乙方签订本协议。' })
  const baseline = await geometry()
  console.log('baseline geometry ' + baseline)

  // 1) 用户路径：覆盖层聚焦时按真 Esc
  await page.focus('input[data-lo-ime]')
  await page.keyboard.press('Escape')
  await settle()
  check('按 Esc 之后顶层窗口仍是全屏（没冒出标题栏小窗）', (await geometry()) === baseline, await geometry())

  // 2) 宿主发起的那条派发（工具栏 / 覆盖层都走它）
  await exec('ui_command', { name: 'escape' })
  await settle()
  check('ui_command escape 之后顶层窗口仍是全屏', (await geometry()) === baseline, await geometry())

  // 3) 反向断言：DOM 键盘事件到不了引擎（覆盖层失焦时按 Esc 什么都不该发生）。
  //    这一条守住 D1 的根因判定——哪天引擎开始收 DOM 按键，它会红，提醒宿主层也得吞。
  await page.evaluate(() => document.querySelector('input[data-lo-ime]').blur())
  for (let i = 0; i < 3; i++) { await page.keyboard.press('Escape'); await settle(200) }
  await settle()
  check('覆盖层失焦时的真 Esc 到不了引擎（几何不变）', (await geometry()) === baseline, await geometry())

  // 4) Esc 的本职不许退步：取消选区
  await page.focus('input[data-lo-ime]')
  await exec('ui_command', { name: 'select_all' })
  check('前置：select_all 之后有选区', (await exec('get_selection', {})).hasSelection === true)
  await page.keyboard.press('Escape')
  await settle()
  const afterEsc = await exec('get_selection', {})
  check('Esc 仍然取消选区', afterEsc.hasSelection === false, JSON.stringify(afterEsc))
  check('取消选区之后窗口依旧全屏', (await geometry()) === baseline, await geometry())

  // 4b) 画布聚焦时的控制键：覆盖层在 document 捕获阶段代收（v0.44.1 真机走查 D8）。
  //     这一条同时是「不许双改」的守卫：如果引擎其实也在处理 DOM 按键，删除会掉两个字。
  await exec('goto', { type: 'end' })
  await exec('insert_at_cursor', { text: '甲乙丙' })
  const beforeDelete = (await exec('get_document_text', {})).paragraphs.map(x => x.text).join('\n')
  const focused = await page.evaluate(() => {
    const c = document.getElementById('qtcanvas')
    try { c.focus() } catch (e) {}
    return document.activeElement === c ? 'canvas' : (document.activeElement && document.activeElement.tagName) || '?'
  })
  await page.keyboard.press('Backspace')
  await settle()
  const afterDelete = (await exec('get_document_text', {})).paragraphs.map(x => x.text).join('\n')
  check('画布聚焦时的 Backspace 删掉恰好一个字（不是零个、也不是两个）',
    afterDelete.length === beforeDelete.length - 1,
    'activeElement=' + focused + ' 前「' + beforeDelete.slice(-6) + '」后「' + afterDelete.slice(-6) + '」')

  // 5) D2：自建工具栏挂上之后（EditorToolbar.bootstrap 调的就是这一条），
  //    光标进表格单元格不许冒出原生表格工具栏。
  //    藏掉原生 chrome 会改变文档窗口在画布里的落位（菜单栏/工具栏腾出来的 78px），
  //    所以这一步之后的「还在全屏吗」要跟藏完之后的基线比，不能跟藏之前的比。
  await exec('set_chrome', { menubar: false, statusbar: false, toolbars: false, rulers: false })
  await settle()
  const hiddenBaseline = await geometry()
  console.log('geometry with chrome hidden ' + hiddenBaseline)
  await exec('goto', { type: 'end' })
  await exec('insert_table', { rows: [['甲', '乙'], ['丙', '丁']] })
  let cell = null
  for (let i = 0; i < 14 && !cell; i++) {
    cell = (await exec('get_ui_state', {}))?.selection?.cellName || null
    if (!cell) await exec('move_cursor', { dir: 'up' })
  }
  check('前置：光标真的落在表格单元格里', !!cell, String(cell))
  await settle(1500)
  const vis = (await exec('set_chrome', {})).visible
  check('光标进表格后原生表格工具栏没冒出来（tableobjectbar）',
    vis.toolbars.tableobjectbar === false, JSON.stringify(vis.toolbars.tableobjectbar))
  check('导航工具栏也保持隐藏（navigationobjectbar）',
    vis.toolbars.navigationobjectbar === false, JSON.stringify(vis.toolbars.navigationobjectbar))
  check('两条主工具栏与菜单栏保持隐藏',
    vis.toolbars.standardbar === false && vis.toolbars.textobjectbar === false && vis.menubar === false,
    JSON.stringify(vis).slice(0, 200))

  // 藏完之后离开再回到表格（引擎会重新走一遍上下文切换）
  await exec('goto', { type: 'start' })
  await settle(600)
  cell = null
  for (let i = 0; i < 14 && !cell; i++) {
    cell = (await exec('get_ui_state', {}))?.selection?.cellName || null
    if (!cell) await exec('move_cursor', { dir: 'down' })
  }
  await settle(1200)
  check('再次进出表格仍然不冒出来',
    (await exec('set_chrome', {})).visible.toolbars.tableobjectbar === false, String(cell))

  // 6) 隐藏后功能不退步 + 逃生开关
  await exec('insert_at_cursor', { text: '戊' })
  const table = await exec('table_read', { name: '表格1' })
  check('隐藏上下文工具栏后单元格仍可编辑',
    JSON.stringify(table.cells || []).indexOf('戊') >= 0, JSON.stringify(table.cells))
  const back = await exec('set_chrome', { menubar: true, statusbar: true, toolbars: true, rulers: true })
  check('逃生开关仍能把原生菜单要回来', back.applied.menubar === true, JSON.stringify(back.applied.menubar))
  await exec('set_chrome', { menubar: false, statusbar: false, toolbars: false, rulers: false })
  await settle()
  const again = (await exec('set_chrome', {})).visible
  check('再藏一遍把每一条都藏回去了（可重入）',
    again.menubar === false && again.statusbar === false
      && Object.values(again.toolbars).every(v => v === false),
    JSON.stringify(again))
  // 顶层窗口（容器窗口）是否仍与画布同尺寸 = 是否还在全屏。文档窗口在画布里的
  // 落位会随 chrome 开关变（藏/显腾出来的那几十像素），所以只比容器窗口。
  const frameOf = (g) => JSON.parse(g).frame
  check('收尾：顶层窗口仍与画布同尺寸（还在全屏）',
    JSON.stringify(frameOf(await geometry())) === JSON.stringify(frameOf(hiddenBaseline)),
    await geometry())
  await page.screenshot({ path: '/tmp/awd-escape-chrome.png' })
} finally { await browser.close(); await new Promise(r => server.close(r)) }
if (failures) { console.error('\n' + failures + ' 条失败'); process.exit(1) }
console.log('\nPASS: Esc 不再让顶层窗口退出全屏；表格/导航上下文工具栏保持隐藏')
