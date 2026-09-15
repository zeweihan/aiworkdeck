// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真机走查（v0.44.1）报的三条脱敏面板缺陷：
//   B6 点「浏览」毫无反应，连提示都没有 —— 面板必须要到工作台的同步回执，要不到就报错；
//   C1 统一社会信用代码、律师执业证号没有规则 —— 新规则默认勾选；
//   C2 校验失败的证件/卡号静默丢弃 —— 预览下方要显示「另有 N 处…未处理」；
//   C3 复敏映射另存为落在无关目录、文件名不带原文件名 —— 文件名带上原文件名。
//
// 取舍同 pane.test.mjs：既跑真方法（vm 里执行 <script>），也真编译模板渲染 HTML——
// 只读源码的断言发现不了「键打错了」「挂在 v-if 的另一支里」这类问题。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const sfc = readFileSync(new URL('../../src/components/DesensitizePane.vue', import.meta.url), 'utf8')
const script = sfc.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/m, '').replace('export default', 'const component =') + '\ncomponent'

function pane(api = {}) {
  const events = []
  const component = vm.runInNewContext(script, { ...api, URL, Blob, setTimeout })
  const state = { ...component.data(), projectId: 1, prepareFile: async () => false, $t: (k, p) => (p ? k + JSON.stringify(p) : k), $emit: (...args) => events.push(args) }
  for (const [key, fn] of Object.entries(component.methods)) state[key] = fn.bind(state)
  for (const [key, fn] of Object.entries(component.computed)) Object.defineProperty(state, key, { get: fn.bind(state) })
  return { state, component, events }
}

const loadPanels = async (lang) =>
  (await import(new URL(`../../src/locales/${lang}/panels.js`, import.meta.url))).default

// ==================== B6：「浏览」不许静默 ====================

test('B6 工作台回执了「选择器已打开」，面板不报错并接受选中的文件', async () => {
  const { state } = pane()
  state.$emit = (_name, onPicked, ack) => {
    ack()
    onPicked({ id: 7, name: 'contract.docx', filePath: 'contract.docx' })
  }
  state.triggerFileSelect()
  assert.equal(state.error, '', '正常路径不该留下错误')
  assert.equal(state.fileId, 7)
  assert.equal(state.fileName, 'contract.docx')
})

test('B6 没有回执就说出来：不能让用户对着毫无反应的「浏览」猜', () => {
  const { state, events } = pane()
  state.triggerFileSelect()
  assert.equal(events[0][0], 'request-file-select')
  assert.equal(typeof events[0][1], 'function', '第一个参数是选中回调')
  assert.equal(typeof events[0][2], 'function', '第二个参数是同步回执')
  assert.equal(state.error, 'panels.deBrowseUnavailable', '断了必须有可见提示')
})

test('B6 工作台侧的 handleDesensitizeSelectFile 会回执，并且没有项目时不回执', () => {
  const overview = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const source = overview.match(/handleDesensitizeSelectFile\(callback, ack\) \{[\s\S]*?\n {4}\},/)
  assert.ok(source, '工作台侧的处理函数签名变了（回执参数没了？）')
  const handler = new Function('return function ' + source[0].replace(/,$/, ''))()

  let acked = 0
  const host = { projectId: 3, showFilePicker: false, filePickerAllowFolder: true, desensitizeFileSelectCallback: null }
  handler.call(host, () => {}, () => { acked++ })
  assert.equal(acked, 1, '打开了选择器就必须回执')
  assert.equal(host.showFilePicker, true)
  assert.equal(host.filePickerAllowFolder, false, '脱敏只收单文件')
  assert.equal(typeof host.desensitizeFileSelectCallback, 'function')

  let ackedWithoutProject = 0
  const noProject = { projectId: null, showFilePicker: false }
  handler.call(noProject, () => {}, () => { ackedWithoutProject++ })
  assert.equal(ackedWithoutProject, 0, '开不出来就别回执，面板要据此报错')
  assert.equal(noProject.showFilePicker, false)
})

test('C1 拉到策略后默认勾上统一社会信用代码与律师执业证号', async () => {
  const options = [
    { value: 'COMPANY' }, { value: 'CHINESE_NAME' }, { value: 'PHONE' }, { value: 'ID_CARD' },
    { value: 'UNIFIED_SOCIAL_CREDIT' }, { value: 'LAWYER_LICENSE' }, { value: 'EMAIL' },
    { value: 'BANK_CARD' }, { value: 'IPV6' },
  ]
  const { state } = pane({ getSensitiveOptions: async () => options })
  await state.fetchOptions()
  assert.ok(state.selectedStrategies.includes('UNIFIED_SOCIAL_CREDIT'), '默认不勾等于没加规则')
  assert.ok(state.selectedStrategies.includes('LAWYER_LICENSE'))
  assert.ok(!state.selectedStrategies.includes('IPV6'), '默认勾选清单不该顺手扩大')
})

// ==================== C2：校验失败的候选要看得见 ====================

test('C2 预览与生成结果里的 suspects 汇总成一个数', () => {
  const { state } = pane()
  state.preview = { counts: { PHONE: 2 }, suspects: { ID_CARD: 1, UNIFIED_SOCIAL_CREDIT: 2 } }
  assert.equal(state.totalSuspects, 3)
  state.result = { counts: {}, suspects: {} }
  assert.equal(state.totalSuspects, 0, '结果优先于预览')
  state.result = null
  state.preview = { counts: {} }
  assert.equal(state.totalSuspects, 0, '后端没给这个字段时不能炸')
})

test('C2 提示文案两侧语言都有，且写明"未处理"与下一步', async () => {
  const [zh, en] = await Promise.all([loadPanels('zh-CN'), loadPanels('en-US')])
  for (const key of ['deSuspectCount', 'deKitFileTag', 'deKitFallbackName', 'deBrowseUnavailable']) {
    assert.ok(zh[key], `zh-CN 缺 panels.${key}`)
    assert.ok(en[key], `en-US 缺 panels.${key}`)
  }
  assert.match(zh.deSuspectCount, /\{count\}/, '不带 count 就成了一句废话')
  assert.match(en.deSuspectCount, /\{count\}/)
  assert.match(zh.deSuspectCount, /未处理/)
  const emoji = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/u
  for (const key of ['deSuspectCount', 'deKitFileTag', 'deKitFallbackName', 'deBrowseUnavailable']) {
    assert.ok(!emoji.test(String(zh[key])) && !emoji.test(String(en[key])), `${key} 含 emoji`)
  }
})

// ==================== C3：复敏映射文件名 ====================

test('C3 映射文件名带原文件名：<原名>-复敏-<时间戳>.awd-recovery', () => {
  const { state } = pane()
  const name = state.kitFileName('2026年租赁合同.docx')
  assert.match(name, /^2026年租赁合同-panels\.deKitFileTag-\d+\.awd-recovery$/, name)
  assert.match(state.kitFileName(''), /^panels\.deKitFallbackName-/, '没有原文件名时要有兜底名')
  assert.ok(state.kitFileName('a.docx').endsWith('.awd-recovery'), '扩展名决定桌面端另存目录，不能改')
})

test('C3 文件名取的是原件名而不是生成出来的副本名', async () => {
  const { state } = pane({
    desensitizeFile: async () => ({ file: { id: 4, name: '[已脱敏]-9876.docx', filePath: 'x' }, recoveryKit: 'AWD-RECOVERY-1:enc', counts: {} }),
  })
  state.fileId = 1; state.fileName = '股权转让协议.docx'; state.filePath = '股权转让协议.docx'
  state.preview = {}; state.password = 'long-enough-password'
  state.downloadKit = () => {}
  await state.handleGenerate()
  assert.match(state.exportedKitName, /^股权转让协议-/, '带上原文件名才分得清哪个映射配哪份副本')
  assert.ok(!state.exportedKitName.includes('已脱敏'), '副本名是中性名，拿它当映射名等于白改')
})

test('C3 downloadKit 用的就是这个文件名', () => {
  const link = { href: '', download: '', click() {}, remove() {} }
  const doc = { createElement: () => link, body: { appendChild() {} } }
  const { state } = pane({ document: doc })
  state.exportedKit = 'AWD-RECOVERY-1:enc'
  state.exportedKitName = '租赁合同-复敏-123.awd-recovery'
  state.downloadKit()
  assert.equal(link.download, '租赁合同-复敏-123.awd-recovery')
})

// ==================== 模板：提示行真的渲染出来 ====================

const VueRuntime = await import('vue')
const SsrRuntime = await import('vue/server-renderer')
const { parse, compileTemplate } = await import('vue/compiler-sfc')
const { descriptor } = parse(sfc)
const compiled = compileTemplate({
  source: descriptor.template.content, filename: 'DesensitizePane.vue', id: 'desensitize-pane',
  ssr: true, ssrCssVars: [], compilerOptions: { isCustomElement: tag => ['view', 'text', 'scroll-view'].includes(tag) },
})
assert.equal(compiled.errors.length, 0, compiled.errors.join('; '))
const renderBody = compiled.code
  .replace(/^import \{([^}]+)\} from "(vue(?:\/server-renderer)?)"$/gm,
    (_, names, source) => `const {${names.replace(/\bas\b/g, ':')}} = ${source === 'vue' ? '__vue' : '__ssr'}`)
  .replace('export function ssrRender', 'function ssrRender')
const ssrRender = new Function('__vue', '__ssr', renderBody + '\nreturn ssrRender')(VueRuntime, SsrRuntime)

async function renderPane(overrides = {}) {
  const { component } = pane()
  const app = VueRuntime.createSSRApp(
    { ...component, ssrRender, data: () => ({ ...component.data(), ...overrides }) },
    { projectId: 1, prepareFile: async () => false })
  app.config.globalProperties.$t = (key, params) => (params ? key + JSON.stringify(params) : key)
  return (await SsrRuntime.renderToString(app)).replace(/&quot;/g, '"')
}

test('C2 有校验失败的候选时预览下方出现那一行，没有时不出现', async () => {
  const shown = await renderPane({ fileId: 1, preview: { text: 'x', counts: { PHONE: 1 }, suspects: { ID_CARD: 2 } } })
  assert.match(shown, /panels\.deSuspectCount\{"count":2\}/, '提示行没渲染出来')
  assert.match(shown, /class="[^"]*suspect-text/, '提示行要有自己的样式，不能混在一堆灰字里')
  const quiet = await renderPane({ fileId: 1, preview: { text: 'x', counts: { PHONE: 1 }, suspects: {} } })
  assert.doesNotMatch(quiet, /panels\.deSuspectCount/, '一处都没有时不许显示「另有 0 处」')
})
