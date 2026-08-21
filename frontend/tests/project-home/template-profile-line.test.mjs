// 概览页「已学习模板」一行（dev-board#112）：`_模板/画像.json` → 四个数 → 一句话；
// 读不到/读坏了一律静默不渲染。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { summarizeTemplateProfile, templateProfileLine } from '../../src/utils/projectHomeFormat.js'

const PANE = readFileSync(new URL('../../src/components/project-home/ProjectHomePane.vue', import.meta.url), 'utf8')
const TREE = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')
const ZH_PROJECTS = readFileSync(new URL('../../src/locales/zh-CN/projects.js', import.meta.url), 'utf8')
const EN_PROJECTS = readFileSync(new URL('../../src/locales/en-US/projects.js', import.meta.url), 'utf8')
const ZH_FILES = readFileSync(new URL('../../src/locales/zh-CN/files.js', import.meta.url), 'utf8')
const EN_FILES = readFileSync(new URL('../../src/locales/en-US/files.js', import.meta.url), 'utf8')

const SAMPLE = {
  schemaVersion: 1,
  body: { font: { eastAsia: 'KaiTi_GB2312', ascii: 'Arial' }, size: { value: 12, unit: 'pt' } },
  headings: [{ level: 1 }, { level: 2 }, { level: 3 }],
  table: { samples: [{ borders: 'cell' }, { borders: 'table' }] },
}

test('样本画像：楷体 12pt / 三级编号 / 2 类表格', () => {
  assert.deepEqual(summarizeTemplateProfile(SAMPLE), { font: 'KaiTi_GB2312', size: 12, levels: 3, tables: 2 })
  assert.equal(templateProfileLine(summarizeTemplateProfile(SAMPLE)), '已学习模板：KaiTi_GB2312 12pt / 3 级编号 / 2 类表格')
})

test('size 为裸数字、samples 为计数也能读；小数字号保留一位', () => {
  const p = { body: { font: { eastAsia: '宋体' }, size: 10.5 }, headings: [], table: { samples: 3 } }
  assert.deepEqual(summarizeTemplateProfile(p), { font: '宋体', size: 10.5, levels: 0, tables: 3 })
})

test('正文字体或字号缺失 → null，一行不渲染（不许出现 undefined / NaNpt）', () => {
  assert.equal(summarizeTemplateProfile(null), null)
  assert.equal(summarizeTemplateProfile({}), null)
  assert.equal(summarizeTemplateProfile({ body: { size: 12 } }), null)
  assert.equal(summarizeTemplateProfile({ body: { font: { eastAsia: '楷体' }, size: 'abc' } }), null)
  assert.equal(templateProfileLine(null), '')
})

test('ProjectHomePane：读 _模板/画像.json 走既有 getProjectFiles + getFileText，失败静默', () => {
  assert.match(PANE, /getProjectFiles,\s*\n\s*getFileText,/, '经 api.js 既有接口取文件，不另开端点')
  assert.ok(PANE.includes("const TEMPLATE_FOLDER_NAME = '_模板'"))
  assert.ok(PANE.includes("const TEMPLATE_PROFILE_FILE = '画像.json'"))
  assert.ok(PANE.includes('v-if="templateLine"'), '没学过模板整行不渲染')
  const fn = PANE.slice(PANE.indexOf('async loadTemplateProfile()'), PANE.indexOf('async loadProjectCard()'))
  assert.ok(fn.length > 0, 'loadTemplateProfile 紧跟在 loadAll 之后')
  assert.ok(fn.includes('catch (e)'), '要有兜底')
  assert.ok(!/console\.warn|showToast/.test(fn), '读不到画像是新项目常态：不 warn、不 toast')
  assert.ok(fn.includes('gen !== this.loadGeneration'), '与其余 loadX 一样守请求代')
})

test('文案走 locale（zh/en 各一份），四个占位都在', () => {
  for (const src of [ZH_PROJECTS, EN_PROJECTS]) {
    const m = src.match(/learnedTemplateLine:\s*'([^']+)'/)
    assert.ok(m, '缺 learnedTemplateLine')
    for (const ph of ['{font}', '{size}', '{levels}', '{tables}']) assert.ok(m[1].includes(ph), '缺占位 ' + ph)
  }
  assert.ok(ZH_FILES.includes('templateFolderHint:') && EN_FILES.includes('templateFolderHint:'))
})

test('FileTree：只有根级 _模板 文件夹换模板图标 + 悬浮说明', () => {
  assert.ok(TREE.includes('isTemplateFolder(item) {'), '判定函数')
  assert.match(TREE, /item\.name === '_模板' && item\.parentId == null/, '必须同时满足同名 + 根级')
  const uses = TREE.match(/v-if="isTemplateFolder\(item\)"/g) || []
  assert.equal(uses.length, 2, '两处文件项模板（普通/回收站）都要换')
  assert.ok(TREE.includes(':title="$t(\'files.templateFolderHint\')"'), '悬浮说明走 locale')
  assert.equal((TREE.match(/v-else-if="item\.isFolder"/g) || []).length, 2, '普通文件夹图标退成 v-else-if')
})

test('禁 emoji', () => {
  for (const s of [PANE, ZH_PROJECTS, EN_PROJECTS, ZH_FILES, EN_FILES]) {
    assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(s))
  }
})
