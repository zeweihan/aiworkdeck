// 标签页文件类型色的映射契约（dev-board#504）。
// 跑法：cd frontend && npm run test:tab-visibility
import test from 'node:test'
import assert from 'node:assert/strict'
import { fileKindKey, fileKindClass } from '../../src/pages/project-overview/fileKind.js'

test('六类文件各自落到自己的 kind', () => {
  const cases = {
    word: ['doc', 'docx'],
    ppt: ['ppt', 'pptx'],
    excel: ['xls', 'xlsx', 'csv'],
    pdf: ['pdf'],
    md: ['md', 'markdown'],
    image: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'svg', 'webp']
  }
  for (const [kind, exts] of Object.entries(cases)) {
    for (const ext of exts) {
      assert.equal(fileKindKey(ext), kind, `${ext} 应该是 ${kind}`)
    }
  }
})

test('大小写与空白不影响判定（后端/拖拽来的扩展名不保证规范）', () => {
  assert.equal(fileKindKey('DOCX'), 'word')
  assert.equal(fileKindKey(' Pdf '), 'pdf')
  assert.equal(fileKindKey('PNG'), 'image')
})

test('未收录的文件类型不着色，保持原来的品牌绿激活态', () => {
  for (const ext of ['txt', 'json', 'zip', 'drawio', 'dd', 'mp4', 'html', 'java']) {
    assert.equal(fileKindKey(ext), '', `${ext} 不该被着色`)
  }
})

test('非文件标签一律不着色', () => {
  const tabs = [
    { fileType: 'web', tabType: 'web' },
    { fileType: undefined, tabType: 'browser' },
    { fileType: 'market-detail', tabType: 'market-detail' },
    { fileType: undefined, tabType: 'admin-settings' },
    { fileType: 'plugin', tabType: undefined },
    { fileType: 'version-compare', tabType: 'version-compare' },
    { fileType: 'version-text-diff', tabType: 'version-text-diff' },
    { fileType: 'diff', tabType: 'diff' }
  ]
  for (const t of tabs) {
    assert.equal(fileKindKey(t.fileType, t.tabType), '',
      `标签 ${String(t.tabType)} / ${String(t.fileType)} 不该被着色`)
  }
})

test('非文件 tabType 压过扩展名：浏览器标签就算带了 docx 也不着色', () => {
  assert.equal(fileKindKey('docx', 'web'), '')
  assert.equal(fileKindKey('png', 'admin-settings'), '')
})

test('AI 工作计划那种虚拟 markdown 标签仍按 md 着色（它确实是一份 md 文档）', () => {
  assert.equal(fileKindKey('md', 'markdown'), 'md')
})

test('空值不炸也不着色', () => {
  assert.equal(fileKindKey(null), '')
  assert.equal(fileKindKey(undefined), '')
  assert.equal(fileKindKey(''), '')
  assert.equal(fileKindKey(0), '')
})

test('fileKindClass 给出模板用的 class，无对应类型时是空串', () => {
  assert.equal(fileKindClass('docx'), 'kind-word')
  assert.equal(fileKindClass('pptx'), 'kind-ppt')
  assert.equal(fileKindClass('xlsx'), 'kind-excel')
  assert.equal(fileKindClass('pdf'), 'kind-pdf')
  assert.equal(fileKindClass('md'), 'kind-md')
  assert.equal(fileKindClass('png'), 'kind-image')
  assert.equal(fileKindClass('txt'), '')
  assert.equal(fileKindClass(undefined, 'web'), '')
})

test('六个 kind 与样式表里的 .kind-* / 令牌名一一对上（改一头忘另一头就红）', async () => {
  const { readFile } = await import('node:fs/promises')
  const scss = await readFile(
    new URL('../../src/pages/project-overview/project-overview.scss', import.meta.url), 'utf8')
  const app = await readFile(new URL('../../src/App.vue', import.meta.url), 'utf8')
  for (const kind of ['word', 'ppt', 'excel', 'pdf', 'md', 'image']) {
    assert.ok(scss.includes(`&.kind-${kind} { --awd-tab-kind: var(--awd-file-${kind}); }`),
      `project-overview.scss 缺 .kind-${kind} 规则`)
    // 浅色 + 深色两套都要有
    const hits = app.match(new RegExp(`--awd-file-${kind}:`, 'g')) || []
    assert.equal(hits.length, 2, `App.vue 里 --awd-file-${kind} 应该浅色/深色各定义一次`)
  }
})
