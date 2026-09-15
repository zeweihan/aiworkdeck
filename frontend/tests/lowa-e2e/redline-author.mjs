#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 修订署名的真引擎回归（v0.44.1 真机报「用查找替换产生的修订作者是『未知作者』」）。
//
// 这一组锁三件事，缺一件那条报告就无法定性：
//   1) 宿主给空 authorName 时，引擎按**自己的**兜底署名（zh-CN 语言包里就是
//      「未知作者」）——手打与查找替换一视同仁。所以「替换路径漏设作者」不成立：
//      worker 的 execCommand 每条命令开头都 setRedlineAuthor，病灶在宿主给的名字。
//   2) 不带 bytes 的 load_document 是**只更新署名、不动文档**的既有契约——宿主
//      （LibreOfficeEditor.resolveRedlineAuthor）靠它在文档已经装好之后补发展示名，
//      不必重载文档。
//   3) __agent 标记仍在同一条命令内切到 AI WorkDeck，切回用户名也在同一条命令内。
//
// Run:  npm run test:lowa-redline-author        (from frontend/)
// 前置与 env 同 run.mjs（dist/zetaoffice + 引擎 + Chrome，见 _boot.mjs）。
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

// 引擎自己的空作者兜底串（SwModule::GetRedlineAuthor → STR_REDLINE_UNKNOWN_AUTHOR），
// 本引擎固定在 24.2.8-zhcn-r5。引擎换了这条会红——那正是提醒，用户看到的就是这四个字。
const ENGINE_FALLBACK = '未知作者'
const USER = '韩泽伟'

preflight()
const puppeteer = await loadPuppeteer()
const server = await startServer({})

let passed = 0, failed = 0
function check(label, cond, detail) {
  if (cond) { passed++; console.log('  PASS ' + label) }
  else { failed++; console.log('  FAIL ' + label + (detail ? '  [' + detail + ']' : '')) }
}

const browser = await launchBrowser(puppeteer)
try {
  const page = await openEditor(browser, { clipboard: false })
  const exec = (a, p) => page.evaluate((a2, p2) => window.__loExecutor.executeCommand(a2, p2 || {}), a, p)
  const rows = async () => ((await exec('debug_revisions')).redlines || []).map((r) => [r.type, r.author, r.text])
  const authorsOf = async () => [...new Set((await rows()).map((r) => r[1]))].sort()
  const body = async () => (await exec('get_document_text', { __agent: true })).paragraphs.map((x) => x.text).join('|')
  // 修订关掉再写正文，残留（含页边模式下移出正文流的删除记录）才真正消失
  const reset = async (text) => {
    await exec('set_track_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: text })
    await exec('goto', { type: 'end' })
    await exec('set_track_changes', { on: true })
  }

  console.log('== 1) 宿主给空 authorName：引擎按自己的兜底署名，手打与查找替换一视同仁 ==')
  await exec('load_document', { authorName: '' })
  await reset('甲方应于三十日内付款，甲方确认无误。')
  await exec('insert_at_cursor', { text: '手打' })
  check('手打的修订署引擎兜底作者', (await authorsOf()).join() === ENGINE_FALLBACK, JSON.stringify(await rows()))
  const fr1 = await exec('find_replace', { findText: '甲方', replaceText: '乙方', replaceAll: true })
  check('查找替换（原生 replaceAll 快路径）替了 2 处', fr1.success && fr1.replaced === 2, JSON.stringify(fr1))
  check('查找替换的修订也署同一个兜底作者——不是「替换路径漏设作者」',
    (await authorsOf()).join() === ENGINE_FALLBACK, JSON.stringify(await rows()))

  console.log('== 2) 不带 bytes 的 load_document 只更新署名，不动文档 ==')
  const before = await body()
  const countBefore = (await rows()).length
  const up = await exec('load_document', { authorName: USER })
  check('不带 bytes 的 load_document 回 empty:true（不换文档）', up.success === true && up.empty === true, JSON.stringify(up))
  check('正文一字未动', (await body()) === before, before + ' -> ' + (await body()))
  check('已有修订一条未增未减', (await rows()).length === countBefore, countBefore + ' -> ' + (await rows()).length)
  check('旧修订的作者不回填（历史条目不改）',
    (await rows()).every((r) => r[1] === ENGINE_FALLBACK), JSON.stringify(await rows()))

  console.log('== 3) 补发署名之后，新产生的修订署用户名 ==')
  const fr2 = await exec('find_replace', { findText: '三十日内付款', replaceText: '六十日内支付', replaceAll: true })
  check('查找替换（逐命中字符级路径）成功', fr2.success && fr2.replaced === 1, JSON.stringify(fr2))
  const newRows = (await rows()).filter((r) => r[1] === USER)
  check('新修订署用户名', newRows.length >= 2, JSON.stringify(await rows()))
  check('删除只标到改动的字（颗粒度没被署名改造动过）',
    newRows.filter((r) => r[0] === 'Delete').every((r) => (r[2] || '').length <= 2), JSON.stringify(newRows))

  console.log('== 4) __agent 仍在同一条命令内切作者，切回也一样 ==')
  await exec('insert_at_cursor', { text: 'AI改', __agent: true })
  check('AI 的修订署 AI WorkDeck',
    (await rows()).some((r) => r[1] === 'AI WorkDeck' && r[2] === 'AI改'), JSON.stringify(await rows()))
  await exec('find_replace', { findText: '确认无误', replaceText: '确认有效', replaceAll: true })
  check('紧随其后的用户替换回到用户名，不残留 AI WorkDeck',
    (await rows()).some((r) => r[1] === USER && r[2] === '有效'), JSON.stringify(await rows()))
} finally {
  await browser.close()
  server.close()
}
console.log('\n' + passed + ' passed, ' + failed + ' failed')
process.exit(failed ? 1 : 0)
