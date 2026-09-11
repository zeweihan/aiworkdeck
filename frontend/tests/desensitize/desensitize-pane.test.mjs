// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 脱敏面板的「要涂黑的姓名/词语」入口（dev-board#531）。
//
// 背景：自动中文姓名脱敏已下线——[一-龥]{2,4} 会把「甲方」「北京市」这类普通词一并涂黑，
// 而中文姓名没有可用的客观校验位，收紧不了。产品口径是「法律文书里漏涂比误涂安全」，
// 姓名改由用户手填。既然姓名的唯一入口就是这个输入区，它必须在首屏、必须自带说明，
// 不能藏在折叠区或高级设置里——藏起来就等于把姓名脱敏整个下线了。
//
// 渲染这一层照 tests/evidence/previewLocateRender.test.mjs 的做法：用 vue 自带的
// compiler-sfc 真编译 <template>、真渲染成 HTML。只读源码的断言发现不了「键打错了」
// 「挂在 v-if 的另一支里」这类问题。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileTemplate } from 'vue/compiler-sfc'
import * as VueRuntime from 'vue'
import * as SsrRuntime from 'vue/server-renderer'
import { renderToString } from 'vue/server-renderer'

const SRC = readFileSync(new URL('../../src/components/DesensitizePane.vue', import.meta.url), 'utf8')

function buildSsrRender() {
  const { descriptor, errors } = parse(SRC, { filename: 'DesensitizePane.vue' })
  assert.equal(errors.length, 0, 'DesensitizePane.vue 解析失败')
  const compiled = compileTemplate({
    source: descriptor.template.content,
    filename: 'DesensitizePane.vue',
    id: 'desensitize-pane',
    ssr: true,
    ssrCssVars: [],
  })
  assert.equal(compiled.errors.length, 0, '模板编译报错：' + compiled.errors.join('; '))
  const body = compiled.code
    .replace(/^import \{([\s\S]*?)\} from "vue"$/m, 'const {$1} = __vue')
    .replace(/^import \{([\s\S]*?)\} from "vue\/server-renderer"$/m, 'const {$1} = __ssr')
    .replace(/\bas\b/g, ':')
    .replace(/^export function ssrRender/m, 'function ssrRender')
  // eslint-disable-next-line no-new-func
  return new Function('__vue', '__ssr', body + '\nreturn ssrRender')(VueRuntime, SsrRuntime)
}

const ssrRender = buildSsrRender()

const componentScript = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/m, '').replace('export default', 'return')
const component = new Function(componentScript)()
const termsOf = text => component.methods.payload.call({ customTerms: text, excludedTerms: '' }).customTerms

// $t 直接回键名：断言键名即断言「模板引用的 i18n 键」，打错字就对不上。
async function render(state = {}) {
  const app = VueRuntime.createSSRApp({
    ssrRender,
    data: () => ({
      ...component.data(),
      filePath: '',
      fileName: '',
      fileId: null,
      availableStrategies: [
        { value: 'PHONE', label: '手机号 (138****1234)' },
        { value: 'ID_CARD', label: '身份证号 (3301**********1234)' },
      ],
      selectedStrategies: ['PHONE', 'ID_CARD'],
      processing: false,
      customWordsText: '',
      ...state,
    }),
    computed: component.computed,
    methods: {
      $t: (k, p) => (p ? k + JSON.stringify(p) : k),
    },
  })
  // 根节点是 uni 的 <scroll-view>，模板编译成 resolveComponent + ssrRenderComponent；
  // 不注册的话整棵树渲染成空串（用例会以「什么都没有」的方式假红）。桩成「原样渲染插槽」。
  app.component('scroll-view', {
    setup(_props, { slots }) {
      return () => VueRuntime.h('div', { class: 'scroll-view' }, slots.default ? slots.default() : [])
    },
  })
  app.config.warnHandler = () => {}
  const realWarn = console.warn
  console.warn = () => {}
  try {
    return (await renderToString(app)).replace(/&quot;/g, '"')
  } finally {
    console.warn = realWarn
  }
}

const stripComments = (html) => html.replace(/<!--[\s\S]*?-->/g, '')

// ==================== 首屏可见 ====================

test('自定义词输入区在首屏 DOM 里：一个文件都没选、什么都没点，它就在', async () => {
  const html = stripComments(await render())
  assert.match(html, /panels\.deCustomWordsTitle/, '首屏没有「要涂黑的姓名/词语」标题')
  assert.match(html, /class="[^"]*custom-words-input[^"]*"/, '首屏没有自定义词输入框')
  assert.match(html, /panels\.deCustomPlaceholder/, '输入框没有占位提示')
})

test('说明文案跟着输入区一起在首屏：不解释「姓名不会自动识别」，用户根本不知道要填', async () => {
  const html = stripComments(await render())
  assert.match(html, /panels\.deCustomWordsHint/, '首屏没有说明文案')
})

test('自定义词区在「脱敏策略」勾选区之前——姓名是主路径，不是附属选项', async () => {
  const html = stripComments(await render())
  const words = html.indexOf('panels.deCustomWordsTitle')
  const strategies = html.indexOf('panels.deStrategiesTitle')
  assert.ok(words > -1 && strategies > -1, '两个分区都得在')
  assert.ok(words < strategies, `自定义词区排在策略区后面了（${words} > ${strategies}）`)
})

test('不许再有「中文姓名」勾选项——清单由后端 /options 给，前端不许自己补回来', () => {
  assert.ok(!SRC.includes('CHINESE_NAME'), 'DesensitizePane.vue 里不该出现 CHINESE_NAME')
})

// ==================== 解析规则 ====================

test('逐行词语保留英文公司名中的空格，不拆分合法短语', () => {
  assert.deepEqual(termsOf('张三\n Acme Technology Co., Ltd. \n'), ['张三', 'Acme Technology Co., Ltd.'])
  assert.deepEqual(termsOf('   '), [])
})

test('自定义词通过预览和生成共用的 payload 传给后端', () => {
  assert.deepEqual(termsOf('张三\n李四'), ['张三', '李四'])
  assert.match(SRC, /previewSensitiveFile\(this\.payload\(\)\)/)
  assert.match(SRC, /desensitizeFile\(\{ \.\.\.this\.payload\(\)/)
})

test('仅填词语时可以先预览；没有输入或未选文件时不能预览', () => {
  const m = SRC.match(/<button[^>]*:disabled="([^"]+)"[^>]*@tap="handlePreview"/)
  assert.ok(m)
  const disabled = new Function('s', `with (s) { return (${m[1]}) }`)
  assert.equal(disabled({ processing: false, fileId: 1, selectedStrategies: [], customTerms: '张三' }), false)
  assert.equal(disabled({ processing: false, fileId: 1, selectedStrategies: [], customTerms: '' }), true)
  assert.equal(disabled({ processing: false, fileId: null, selectedStrategies: [], customTerms: '张三' }), true)
})

// ==================== 文案 ====================

const loadPanels = async (lang) =>
  (await import(new URL(`../../src/locales/${lang}/panels.js`, import.meta.url))).default

test('新增文案键在 zh-CN / en-US 两侧都有', async () => {
  const [zh, en] = await Promise.all([loadPanels('zh-CN'), loadPanels('en-US')])
  for (const key of ['deCustomWordsTitle', 'deCustomWordsPlaceholder', 'deCustomWordsHint']) {
    assert.ok(zh[key], `zh-CN 缺 panels.${key}`)
    assert.ok(en[key], `en-US 缺 panels.${key}`)
  }
})

test('说明文案把「中文姓名不会自动识别」说清楚，两语言都说', async () => {
  const zh = await loadPanels('zh-CN')
  const en = await loadPanels('en-US')
  assert.ok(zh.deCustomWordsHint.includes('姓名'), zh.deCustomWordsHint)
  assert.ok(zh.deCustomWordsHint.includes('不会自动识别'),
    '中文说明必须直说「不会自动识别」：' + zh.deCustomWordsHint)
  assert.ok(/not.*automatic/i.test(en.deCustomWordsHint),
    '英文说明必须直说不自动识别：' + en.deCustomWordsHint)
})

test('新增文案不含 emoji（全站红线）', async () => {
  const emoji = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/u
  for (const lang of ['zh-CN', 'en-US']) {
    const panels = await loadPanels(lang)
    for (const key of ['deCustomWordsTitle', 'deCustomWordsPlaceholder', 'deCustomWordsHint']) {
      assert.ok(panels[key], `${lang} 缺 panels.${key}（键缺席时这条用例会空过，先挡住）`)
      assert.ok(!emoji.test(String(panels[key])), `${lang} panels.${key} 含 emoji`)
    }
  }
})
