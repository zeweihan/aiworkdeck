// EditorToolbar 插入链接的地址整形（utils/linkUrl.js）：worker set_selection_hyperlink 校验 scheme 之后，
// 用户只敲 www.example.com 不能被拒；mailto 放行；file:/javascript: 在宿主就拦下（本地化文案）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { normalizeLinkUrl } from '../../src/utils/linkUrl.js'

test('无 scheme 自动补 https://；已有 http/https/mailto/checkba 原样', () => {
  assert.equal(normalizeLinkUrl(' www.example.com '), 'https://www.example.com')
  assert.equal(normalizeLinkUrl('example.com/a?b=1'), 'https://example.com/a?b=1')
  assert.equal(normalizeLinkUrl('http://x.cn'), 'http://x.cn')
  assert.equal(normalizeLinkUrl('HTTPS://x.cn'), 'HTTPS://x.cn')
  assert.equal(normalizeLinkUrl('mailto:a@b.cn'), 'mailto:a@b.cn')
  assert.equal(normalizeLinkUrl('checkba://filelink?k=EVID_X&projectId=1'), 'checkba://filelink?k=EVID_X&projectId=1')
})

test('裸邮箱视作 mailto:；空串与不放行的 scheme 回空', () => {
  assert.equal(normalizeLinkUrl('a@b.cn'), 'mailto:a@b.cn')
  assert.equal(normalizeLinkUrl(''), '')
  assert.equal(normalizeLinkUrl(null), '')
  assert.equal(normalizeLinkUrl('file:///etc/passwd'), '')
  assert.equal(normalizeLinkUrl('javascript:alert(1)'), '')
})

test('worker set_selection_hyperlink 的 scheme 白名单与宿主整形一致（含 mailto）', () => {
  const src = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
  const body = src.slice(src.indexOf('  set_selection_hyperlink(p) {'), src.indexOf('  set_hyperlink_at_anchor(p) {'))
  const m = body.match(/if \(!(\/\^\(.*?\)\/i)\.test\(url\)\)/)
  assert.ok(m, 'set_selection_hyperlink 里找不到 scheme 校验')
  const re = new Function('return ' + m[1])()
  for (const ok of ['https://a.cn', 'http://a.cn', 'mailto:a@b.cn', 'checkba://filelink?k=X', 'https://checkba-internal.local/open?u=checkba%3A%2F%2Ffilelink']) {
    assert.ok(re.test(ok), ok)
    assert.ok(normalizeLinkUrl(ok) === ok)
  }
  for (const bad of ['file:///x', 'javascript:1', 'ftp://a.cn']) {
    assert.ok(!re.test(bad), bad)
    assert.equal(normalizeLinkUrl(bad), '')
  }
})

test('EditorToolbar.doLink 经 normalizeLinkUrl 再下发，失败用 linkInvalid 文案', () => {
  const vue = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
  const fn = vue.match(/    doLink\(\) \{([\s\S]*?)\n    \},/)[1]
  assert.match(fn, /normalizeLinkUrl\(raw\)/)
  assert.match(fn, /editor\.toolbar\.linkInvalid/)
  for (const lang of ['zh-CN', 'en-US']) {
    const loc = readFileSync(new URL(`../../src/locales/${lang}/editor.js`, import.meta.url), 'utf8')
    assert.match(loc, /linkInvalid:/, lang)
  }
})
