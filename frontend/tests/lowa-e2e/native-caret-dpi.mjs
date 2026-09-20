// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 分数 DPI 下原生光标几何仍然可取（dev-board#725）。
//
// readNativeCaretRect 靠「可见区尺寸」在窗口树里认出 Writer 的编辑子窗：期望尺寸
// 是从 twip 经实时 DPI 换算来的，与窗口自己的整数像素尺寸之间有舍入误差，而这个
// 误差随设备缩放变大。原先的 ±2 **物理**像素硬容差在 Windows 125% / 150% 与
// Retina 上认不出来，readNativeCaretRect 回 null，IME 覆盖层退回「最近一次点击」
// 摆位——系统候选窗与预览条于是到处跳。
//
// 每一档 DPR 都连读 10 次：偶然成功一次不算数。
//
// Run:  npm run test:lowa-native-caret
//       LOWA_E2E_DPR=1.25 npm run test:lowa-native-caret   (单档复跑)
import assert from 'node:assert/strict'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

// 显式给了一档就只跑那一档，否则把三档一起跑掉（1 = 基线对照，1.25/1.5 = Windows
// 125%/150%，2 = Retina）。
const RATIOS = process.env.LOWA_E2E_DPR ? [Number(process.env.LOWA_E2E_DPR)] : [1, 1.25, 1.5, 2]

preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
const failures = []
try {
  for (const dpr of RATIOS) {
    const page = await openEditor(browser, { viewport: { width: 1280, height: 900, deviceScaleFactor: dpr } })
    page.on('pageerror', (e) => console.error('PAGE ERROR:', e.message))
    const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
    const ok = async (a, p) => { const r = await exec(a, p); assert.equal(r?.success, true, a + ': ' + JSON.stringify(r)); return r }
    await ok('insert_at_cursor', { text: '本文为原生光标几何回归夹具。\n'.repeat(60) })
    let missing = 0, first = null
    for (const zoom of [100, 194]) {
      await ok('set_zoom', { value: zoom })
      await ok('goto', { type: 'end' })
      for (let i = 0; i < 10; i++) {
        const raw = await ok('get_cursor_rect')
        if (!raw.nativeCaret || !Number.isFinite(raw.nativeCaret.x) || !Number.isFinite(raw.nativeCaret.y)
          || !(raw.nativeCaret.viewport?.width > 0) || !(raw.nativeCaret.viewport?.height > 0)) missing++
        else if (!first) first = raw.nativeCaret
        // 光标动一下，下一次读走的是同一条窗口查找路径而不是纯缓存。
        await ok('move_cursor', { dir: i % 2 ? 'left' : 'right' })
      }
    }
    console.log(`dpr=${dpr}: missing=${missing}/20 sample=${JSON.stringify(first)}`)
    if (missing) failures.push(`dpr=${dpr} 有 ${missing}/20 次读不到原生光标几何（退回点击摆位 → 候选窗乱跳）`)
    await page.close()
  }
  assert.deepEqual(failures, [], failures.join('; '))
  console.log('PASS 原生光标几何在 ' + RATIOS.join(' / ') + ' 各档 DPR、两档缩放下 20/20 次都取得到')
} finally {
  await browser.close()
  await new Promise((r) => server.close(r))
}
