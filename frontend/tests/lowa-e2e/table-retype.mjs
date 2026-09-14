// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#627：在表格单元格里删掉已有文字后重新键入，字写不进去。
//
// 病灶是 insertTextAtCursor 用 xModel.getText()（正文 XText）去写一个属于单元格
// XText 的光标：正文 XText 只接受属于自己的区间，光标在单元格里时 insertString
// 抛 RuntimeException，IME 提交整条静默失败——文档里没有字，纸上自然也没有。
// 与删除路径、修订显示方式都无关（不删直接打也一样失败），删除只是用户撞上它的
// 场景：页边/气泡视图把删掉的字藏起来，单元格于是看着全空。
//
// 断言两头都要：新字既要进单元格文本（table_read）、又要真画在单元格里（像素）。
//
// 末尾一组是同族缺陷：anchorBookmark 用正文 XText 给单元格区间插锚点书签，同样抛，
// 于是 AI 的 find_text_locations → set_selection / replace_at_position 在表格里够不着。
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { PNG } from 'pngjs'
import { preflight, startServer, launchBrowser, loadPuppeteer, ORIGIN } from './_boot.mjs'

const AI = 'AI WorkDeck'
const USER = '用户甲'
const NAME = '李楠'
// 现场形态：整张工商信息表都是 AI 在修订态下写进去的（橙色下划线的插入修订）。
const ins = (id, text) => `<w:ins w:id="${id}" w:author="${AI}" w:date="2026-09-12T08:00:00Z"><w:r><w:t>${text}</w:t></w:r></w:ins>`
const insCell = (id, text) => `<w:tc><w:tcPr><w:tcW w:w="4000" w:type="dxa"/></w:tcPr><w:p>${ins(id, text)}</w:p></w:tc>`
const plainCell = text => `<w:tc><w:tcPr><w:tcW w:w="4000" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>${text}</w:t></w:r></w:p></w:tc>`
async function documentBytes() {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  const borders = '<w:tblBorders><w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/><w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/></w:tblBorders>'
  const tbl = `<w:tbl><w:tblPr><w:tblW w:w="8000" w:type="dxa"/>${borders}</w:tblPr><w:tr>${insCell(11, '法定代表人')}${insCell(12, NAME)}</w:tr><w:tr>${plainCell('注册资本')}${plainCell('壹仟万元')}</w:tr></w:tbl>`
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>${ins(10, '工商信息')}</w:p>${tbl}<w:p><w:r><w:t>表后正文</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`)
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}

preflight()
const server = await startServer({ extraFiles: Object.fromEntries(['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf'].map(f => ['/' + f, '/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/' + f])) })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1280, height: 900, deviceScaleFactor: 1 })
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  console.log('booting engine (~90s)...')
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  await page.addStyleTag({ content: '#verify,#vlog{display:none!important}' })
  const cdp = await page.createCDPSession()
  const exec = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const ok = async (action, params) => {
    const result = await exec(action, params)
    assert.equal(result.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const cells = async () => (await ok('table_read', { tableIndex: 0 })).cells
  const revisions = async () => (await ok('list_revisions')).revisions.map(r => r.type + '/' + r.author + '/' + r.text)

  // 单元格所在行的墨迹：以光标原生几何为准截一条带，数深色像素。文档按 200%
  // 显示（ZOOM）——100% 下一个汉字只有十来个深色像素，和闪烁的插入符一个量级，
  // 分辨不出「画了字」还是「插入符亮了」；200% 下两个汉字约 150 个，门槛 80 就
  // 既抓得住「一个字都没画」，又不会被插入符误判。
  const ZOOM = 200
  const inkBand = async () => {
    const caret = (await ok('get_cursor_rect')).nativeCaret
    assert.ok(caret, '需要原生光标几何来定位单元格墨迹带')
    const surface = await page.$eval('#qtcanvas', e => { const r = e.getBoundingClientRect(); return { left: r.left, top: r.top, width: r.width, height: r.height } })
    const scale = surface.width / caret.frameWidth
    const menu = Math.max(0, surface.height - caret.frameHeight * scale)
    const x = Math.max(0, Math.floor(surface.left + caret.x * scale - 110))
    const y = Math.max(0, Math.floor(surface.top + menu + caret.y * scale - 4))
    return { x, y, width: Math.min(220, 1280 - x), height: Math.min(Math.ceil(caret.height * scale + 10), 900 - y) }
  }
  // 无头 Chrome 在截图触发的 resize 之前不呈现引擎画布，头一张可能整片空白
  // （实测同一处 0 / 515 来回跳）。先丢一张热身、再取第二张，读到的才是真画面。
  const ink = async (clip) => {
    await page.screenshot({ clip })
    const png = PNG.sync.read(await page.screenshot({ clip }))
    let dark = 0
    for (let i = 0; i < png.data.length; i += 4) {
      if (png.data[i] * 0.3 + png.data[i + 1] * 0.59 + png.data[i + 2] * 0.11 < 150) dark++
    }
    return dark
  }

  const reload = async (mode) => {
    await ok('load_document', { name: 'table-retype.docx', bytes: await documentBytes(), authorName: USER })
    await ok('set_chrome', { all: false })
    await ok('set_revision_view', { mode })
    await ok('set_zoom', { value: ZOOM })
  }
  // 只在光标已经落在目标单元格里时调用。
  const typeIt = async (inputPath, text) => {
    if (inputPath === 'insert_at_cursor') return exec('insert_at_cursor', { text })
    await page.$eval('[data-lo-ime]', e => e.focus())
    await cdp.send('Input.imeSetComposition', { text: 'linan', selectionStart: 5, selectionEnd: 5 })
    await cdp.send('Input.insertText', { text })
    for (let i = 0; i < 30 && !(await cells())[0][1].includes(text); i++) await new Promise(r => setTimeout(r, 200))
    return { success: true, via: 'ime' }
  }

  // 复现矩阵：显示方式 × 删除路径 × 输入路径。
  for (const mode of ['all', 'balloons']) {
    for (const deletePath of ['backspace', 'selection']) {
      for (const inputPath of ['insert_at_cursor', 'ime']) {
        const label = mode + ' / ' + deletePath + ' / ' + inputPath
        await reload(mode)
        await ok('find_navigate', { keyword: NAME })   // 选中单元格里 AI 写的「李楠」
        if (deletePath === 'backspace') {
          await ok('collapse_selection', { to: 'end' })
          await ok('delete_backward')
          await ok('delete_backward')
        } else {
          await ok('delete_backward')                  // 带选区的一次删除
        }
        const deleted = await revisions()
        assert.ok(deleted.some(r => r.startsWith('Delete/' + USER + '/')), label + '：删除应记成用户的删除修订 ' + JSON.stringify(deleted))
        const clip = await inkBand()
        const before = await ink(clip)
        assert.ok(before > 0, label + '：画布未呈现，像素判定无意义')
        // 行内视图下被删的字仍留在正文流里，所以"单元格里有李楠"本身不构成
        // 证据——要的是这一格**比打字前多出**了这几个字。
        const wasCell = (await cells())[0][1]
        const typed = await typeIt(inputPath, NAME)
        assert.notEqual(typed.success, false, label + '：重新键入必须写得进去 ' + JSON.stringify(typed))
        const row = (await cells())[0]
        assert.ok(row[1].includes(NAME) && row[1].length === wasCell.length + NAME.length,
          label + '：新字必须落在单元格里，打字前 ' + JSON.stringify(wasCell) + ' 打字后 ' + JSON.stringify(row[1]))
        assert.ok((await revisions()).some(r => r === 'Insert/' + USER + '/' + NAME),
          label + '：新字应记成用户的插入修订 ' + JSON.stringify(await revisions()))
        // 排版是异步的，给它最多 3 秒把新字画出来再判。
        let after = 0
        for (let i = 0; i < 10 && (after = await ink(clip)) - before < 80; i++) await new Promise(r => setTimeout(r, 300))
        assert.ok(after - before >= 80, label + '：新字必须真画在单元格里（墨迹 ' + before + ' → ' + after + '）')
        console.log('PASS 表格单元格删后重打：' + label + '（墨迹 ' + before + ' → ' + after + '）')
      }
    }
  }

  // 没删过的单元格同样要能打字——病灶与删除无关，删除只是撞上它的场景。
  // 顺带覆盖回车：insert_paragraph 也曾用正文 XText 写单元格光标。
  await reload('balloons')
  await ok('find_navigate', { keyword: '壹仟万元' })
  await ok('collapse_selection', { to: 'end' })
  const plainTyped = await exec('insert_at_cursor', { text: '整' })
  assert.notEqual(plainTyped.success, false, '未删改的单元格也必须能打字：' + JSON.stringify(plainTyped))
  assert.equal((await cells())[1][1], '壹仟万元整')
  await ok('insert_paragraph')
  assert.equal((await cells())[1][1], '壹仟万元整\n', '回车必须在单元格内分段')
  console.log('PASS 未删改的单元格：直接键入与回车')

  // 同族：anchorBookmark 也曾用正文 XText 插书签（xModel.getText().insertTextContent），
  // 于是 find_text_locations 在单元格里的命中拿不到 anchorId——异常被 catch 吞掉，
  // AI 的 set_selection / replace_at_position 一律回「anchor not found」，表格够不着。
  // 末尾顺带钉住 clear_anchors：锚点从此会落在单元格里，而摘除与插入不同源——
  // removeTextContent 本引擎容得下别的 story 的书签，正文 XText 照样摘得掉。
  await reload('balloons')
  const located = await ok('find_text_locations', { keyword: NAME })
  assert.equal(located.count, 1, '夹具里「' + NAME + '」只在单元格出现一次：' + JSON.stringify(located.matches))
  const cellAnchor = located.matches[0].anchorId
  assert.ok(cellAnchor && cellAnchor.startsWith('__ai_anchor_'),
    '单元格里的命中必须拿得到锚点书签（病灶下是 null）：' + JSON.stringify(located.matches[0]))
  // 书签得真盖在单元格那几个字上：anchorRange 读回的文字要对得上
  assert.equal((await ok('set_selection', { anchor: cellAnchor })).text, NAME,
    '锚点读回的文字必须是单元格里的原文')
  // 经锚点改一格（挑没被修订盖过的那格，断言不受行内/页边语义干扰）
  const plainAnchor = (await ok('find_text_locations', { keyword: '壹仟万元' })).matches[0].anchorId
  assert.ok(plainAnchor && plainAnchor.startsWith('__ai_anchor_'), '未修订的单元格同样要拿得到锚点：' + plainAnchor)
  await ok('replace_at_position', { anchor: plainAnchor, newText: '贰仟万元' })
  assert.equal((await cells())[1][1], '贰仟万元', '经锚点的替换必须落在单元格里：' + JSON.stringify(await cells()))
  // 正文锚点这条老路不能被改坏
  const bodyAnchor = (await ok('find_text_locations', { keyword: '表后正文' })).matches[0].anchorId
  assert.ok(bodyAnchor && bodyAnchor.startsWith('__ai_anchor_'), '正文锚点仍须可用：' + bodyAnchor)
  const cleared = await ok('clear_anchors')
  assert.ok(cleared.cleared >= 3, 'clear_anchors 必须把单元格里的锚点一起摘干净：' + JSON.stringify(cleared))
  assert.equal((await exec('set_selection', { anchor: cellAnchor })).success, false, '摘干净后锚点不该还在')
  console.log('PASS 表格单元格锚点：find_text_locations / set_selection / replace_at_position / clear_anchors')

  // dev-board#627 的次生问题：表格单元格里按 Tab。
  // #627 之前覆盖层无条件 insert_at_cursor('\t')，在单元格里因 RuntimeException
  // 静默失败——看着像「Tab 没反应」，于是没人发现它本来就写错了；改用 vc.getText()
  // 之后同一条分支会真把制表符插进单元格（修订态下还多记一条修订）。Word/Writer
  // 的语义是：表格里 Tab 跳下一格、Shift+Tab 跳上一格，正文里才插制表符。
  // 必须走覆盖层的真实 keydown 路径——病灶就在那条分支上，直接调 worker 动作
  // 等于绕开被测对象。
  await reload('all')
  const cellNow = async () => ((await ok('get_ui_state')).selection || {}).cellName || ''
  const pressTab = async (shift) => {
    await page.$eval('[data-lo-ime]', e => e.focus())
    if (shift) await page.keyboard.down('Shift')
    await page.keyboard.press('Tab')
    if (shift) await page.keyboard.up('Shift')
  }
  // forward() 是即发即忘的（覆盖层不等回包），所以轮询等光标真的挪过去。
  const waitCell = async (want, label) => {
    let got = ''
    for (let i = 0; i < 30; i++) {
      got = await cellNow()
      if (got === want) return
      await new Promise(r => setTimeout(r, 200))
    }
    assert.fail(label + '：光标应落在 ' + want + '，实际 ' + JSON.stringify(got))
  }
  await ok('find_navigate', { keyword: '法定代表人' })  // A1
  await ok('collapse_selection', { to: 'end' })
  assert.equal(await cellNow(), 'A1', 'Tab 用例的起点必须是 A1')
  await pressTab(false)
  await waitCell('B1', '表格内 Tab 应跳到下一格')
  await pressTab(true)
  await waitCell('A1', '表格内 Shift+Tab 应跳回上一格')
  const tabbed = (await cells())[0]
  assert.ok(!tabbed[0].includes('\t') && !tabbed[1].includes('\t'),
    '表格内 Tab 不得把制表符写进单元格：' + JSON.stringify(tabbed))
  // 正文里 Tab 仍然插制表符（同一条覆盖层分支的另一半，不许一起改掉）。
  await ok('find_navigate', { keyword: '表后正文' })
  await ok('collapse_selection', { to: 'end' })
  assert.equal(await cellNow(), '', '正文对照的起点不应在表格里')
  await pressTab(false)
  let bodyText = ''
  for (let i = 0; i < 30 && !bodyText.includes('\t'); i++) {
    bodyText = ((await ok('get_document_text')).paragraphs.map(p => p.text).find(t => t.startsWith('表后正文')) || '')
    if (!bodyText.includes('\t')) await new Promise(r => setTimeout(r, 200))
  }
  assert.equal(bodyText, '表后正文\t', '正文里 Tab 仍应插制表符')
  console.log('PASS 表格内 Tab 跳格 / 正文 Tab 插制表符')

  // 对照：正文里的同一条链路本来就是好的，修复不得改变它。
  await reload('all')
  await ok('find_navigate', { keyword: '表后正文' })
  await ok('collapse_selection', { to: 'end' })
  await ok('insert_at_cursor', { text: '补充' })
  const paragraphs = (await ok('get_document_text')).paragraphs.map(p => p.text)
  assert.ok(paragraphs.includes('表后正文补充'), '正文插入不受影响：' + JSON.stringify(paragraphs))
  console.log('PASS 正文对照组未受影响')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
