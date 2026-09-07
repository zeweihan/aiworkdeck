// 实测清单 A7/B7/B13/C11：真引擎落字→导出 OOXML，不以 UNO setter 回声代替落盘结果。
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

preflight()
const server = await startServer({ patchServed(url, content) {
  if (!url.endsWith('/office_thread.js')) return content
  // 只在内存注入失败，验证异常链路；不改源码、构建产物或正常建表路径。
  return Buffer.from(content.toString()
    .replace('function insertStyledTable(rows, headerRows) {', "function insertStyledTable(rows, headerRows) { if (rows[0][0] === '__TEST_TABLE_FAIL__') throw new Error('模拟建表失败');")
    .replace("dispatchUno('.uno:RemoveBullets');", "if (!p.debugNoop) dispatchUno('.uno:RemoveBullets');"))
} })
const browser = await launchBrowser(await loadPuppeteer())
const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
async function blankDocx(mode = 15) {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/_rels/document.xml.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="${W}"><w:body><w:p/></w:body></w:document>`)
  zip.file('word/settings.xml', `<w:settings xmlns:w="${W}"><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="${mode}"/></w:compat></w:settings>`)
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}
try {
  const page = await openEditor(browser)
  const exec = (action, params = {}) => page.evaluate((action, params) => window.__loExecutor.executeCommand(action, params), action, params)
  const fresh = async (mode = 15) => assert.equal((await exec('load_document', { name: 'new.docx', bytes: await blankDocx(mode) })).success, true)
  const exported = async () => {
    const bytes = await page.evaluate(async () => {
      const out = await window.__loExecutor.executeCommand('export_document', { name: 'new.docx' })
      if (!out.success) throw new Error(out.error || out.message)
      return Array.from(out.bytes)
    })
    const buffer = Buffer.from(bytes.map(b => b & 255))
    const zip = await JSZip.loadAsync(buffer)
    const document = await zip.file('word/document.xml').async('string')
    const text = await page.evaluate(xml => [...new DOMParser().parseFromString(xml, 'application/xml')
      .getElementsByTagNameNS('http://schemas.openxmlformats.org/wordprocessingml/2006/main', 't')]
      .map(node => node.textContent).join(''), document)
    return { document, text, settings: await zip.file('word/settings.xml').async('string') }
  }
  const grid = xml => {
    assert.match(xml, /<w:tbl[ >]/, '必须落成真实表格')
    // LOWA 以单元格边框写网格：每格左边+底边、首行顶边、末列右边覆盖所有有效边。
    const rows = [...xml.matchAll(/<w:tr[ >][\s\S]*?<\/w:tr>/g)]
    rows.forEach(([row], rowIndex) => {
      const cells = [...row.matchAll(/<w:tc[ >][\s\S]*?<\/w:tc>/g)]
      cells.forEach(([cell], colIndex) => {
        const borders = cell.match(/<w:tcBorders>[\s\S]*?<\/w:tcBorders>/)?.[0] || ''
        const sides = ['left', 'bottom', ...(rowIndex === 0 ? ['top'] : []), ...(colIndex === cells.length - 1 ? ['right'] : [])]
        for (const side of sides) {
          const names = side === 'left' ? '(?:left|start)' : side === 'right' ? '(?:right|end)' : side
          const tag = borders.match(new RegExp(`<w:${names}\\b[^>]*>`))?.[0] || ''
          assert.match(tag, /w:val="single"/, `${rowIndex},${colIndex} ${side} 必须有实线：${borders}`)
          assert.ok(Number(tag.match(/w:sz="(\d+)"/)?.[1]) > 0, side + ' 线宽必须非零')
        }
      })
    })
  }

  await fresh()
  const rows = [['编号', '证据', '来源', '日期', '用途', '页码', '备注'], ...Array.from({ length: 16 }, (_, i) => [`${i + 1}`, '证据' + i, '案卷', '2026-09-07', '证明事实', '1', '待核'])]
  const markdown = '# 证据清单\n\n**核查结果**\n\n' + [rows[0], Array(7).fill('---'), ...rows.slice(1)].map(row => '| ' + row.join(' | ') + ' |').join('\n')
  const insertStarted = Date.now()
  const inserted = await exec('stream_insert', { text: markdown, complete: true })
  assert.equal(inserted.success, true, JSON.stringify(inserted))
  console.log('17×7 完整插入耗时 ms:', Date.now() - insertStarted)
  const table = await exec('table_read', { tableIndex: 0 })
  assert.equal(table.rows, 17)
  assert.equal(table.cols, 7)
  let out = await exported()
  grid(out.document)
  assert.equal((out.document.match(/<w:tr[ >]/g) || []).length, 17)
  assert.equal((out.document.match(/<w:tc[ >]/g) || []).length, 119)
  assert.match(out.document, /<w:b(?:\s[^>]*)?\/>/, '粗体必须保留')
  assert.ok(out.text.includes('证据清单') && !out.text.includes('# 证据清单'), '标题文本必须去掉Markdown标记')
  assert.ok(!out.text.includes('| 编号 |'), 'Markdown 管道文本不应留在正文')
  const mode = out.settings.match(/<w:compatSetting\b[^>]*w:name="compatibilityMode"[^>]*>/)?.[0] || ''
  assert.match(mode, /w:val="15"/, '新建文档经过LOWA导出仍保持现代兼容级别')
  console.log('PASS 17×7 Markdown 尾表、标题、粗体、网格与兼容级别15的真实导出')

  await fresh(12)
  assert.equal((await exec('insert_at_cursor', { text: '旧兼容文档仍可编辑保存' })).success, true)
  out = await exported()
  assert.match(out.settings.match(/<w:compatSetting\b[^>]*w:name="compatibilityMode"[^>]*>/)?.[0] || '', /w:val="12"/,
    '用户旧文档保留原兼容级别，不擅自升级')
  console.log('PASS 旧文档兼容级别12在编辑导出后保持不变')

  await fresh()
  assert.equal((await exec('stream_insert', { text: 'Agent 尚未写完' })).success, true)
  assert.equal((await exec('stream_insert', { text: '不要串进来', complete: true })).success, false)
  assert.equal((await exec('stream_flush')).success, true)
  out = await exported()
  assert.ok(out.text.includes('Agent 尚未写完'))
  assert.ok(!out.text.includes('不要串进来'))
  assert.equal((await exec('stream_insert', { text: '后续完整插入', complete: true })).success, true)
  console.log('PASS 完整插入拒绝串流且不清掉 Agent 缓冲，收尾后可再次插入')

  await fresh()
  const failedTable = await exec('stream_insert', { text: '| __TEST_TABLE_FAIL__ |\n|---|\n| 正文 |', complete: true })
  assert.equal(failedTable.success, false, '建表失败必须向上报告')
  assert.equal((await exec('stream_insert', { text: '失败后可继续插入', complete: true })).success, true,
    '完整插入失败后必须清理STREAM，不能永久锁住后续插入')
  console.log('PASS 完整插入的尾表失败如实上报并清理状态')
  await exec('stream_insert', { text: '| __TEST_TABLE_FAIL__ |\n|---|\n| 正文 |' })
  assert.equal((await exec('stream_flush')).success, false)
  assert.equal((await exec('stream_insert', { text: 'Agent 收尾失败后也可插入', complete: true })).success, true)
  console.log('PASS Agent 流式收尾失败后同样清理状态')

  await fresh()
  assert.equal((await exec('insert_table', { rows, headerRow: true })).success, true)
  grid((await exported()).document)
  console.log('PASS doc_insert_table 默认真实网格边框可导出')

  await fresh()
  assert.equal((await exec('insert_at_cursor', { text: '图一：案件关系图' })).success, true)
  await exec('select_paragraph', { index: 0 })
  assert.equal((await exec('set_numbering', { preset: 'bullet' })).success, true)
  assert.equal((await exec('get_ui_state')).paragraph.listKind, 'bullet')
  assert.equal((await exec('set_numbering', { preset: 'none', debugNoop: true })).success, false, '移除命令未生效不能假报成功')
  assert.equal((await exec('set_numbering', { preset: 'none' })).success, true)
  assert.equal((await exec('set_paragraph_format', { alignment: 'center', headingLevel: 0 })).success, true)
  const format = await exec('get_formatting')
  assert.equal(format.paragraph.isNumbered, false)
  assert.equal(format.paragraph.alignment, 'center')
  assert.equal(format.paragraph.outlineLevel, 0)
  out = await exported()
  assert.ok(out.text.includes('图一：案件关系图'), '清列表不能损失正文')
  assert.match(out.document, /<w:jc w:val="center"/)
  console.log('PASS 真项目符号图注清除列表并居中，正文未改')
} finally {
  await browser.close()
  server.close()
}
