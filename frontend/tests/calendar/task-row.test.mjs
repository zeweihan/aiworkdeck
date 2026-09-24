// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// TaskRow.vue 真渲染（dev-board#896）：模板用 vue 自带 compiler-sfc 编译成 SSR 渲染函数，
// 组件 <script> 抽出来去 import、注入真实的 taskUtils / icons / eventColors，渲染成 HTML 断言
// 类型色条、文件芯片 +N、到期徽标 kind、e2e 锚点。口径同 tests/evidence/previewLocateRender.test.mjs。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileTemplate } from 'vue/compiler-sfc'
import * as VueRuntime from 'vue'
import * as SsrRuntime from 'vue/server-renderer'
import { renderToString } from 'vue/server-renderer'
import * as taskUtils from '../../src/components/calendar/taskUtils.js'
import { ICONS } from '../../src/config/icons.js'
import { colorForProject } from '../../src/components/calendar/eventColors.js'

const SFC = readFileSync(new URL('../../src/components/calendar/TaskRow.vue', import.meta.url), 'utf8')

function buildComponent() {
  const { descriptor, errors } = parse(SFC, { filename: 'TaskRow.vue' })
  assert.equal(errors.length, 0, 'TaskRow.vue 解析失败')
  const compiled = compileTemplate({
    source: descriptor.template.content,
    filename: 'TaskRow.vue',
    id: 'task-row',
    ssr: true,
    ssrCssVars: [],
  })
  assert.equal(compiled.errors.length, 0, '模板编译报错：' + compiled.errors.join('; '))
  const renderBody = compiled.code
    .replace(/^import \{([\s\S]*?)\} from "vue"$/m, 'const {$1} = __vue')
    .replace(/^import \{([\s\S]*?)\} from "vue\/server-renderer"$/m, 'const {$1} = __ssr')
    .replace(/\bas\b/g, ':')
    .replace(/^export function ssrRender/m, 'function ssrRender')
  // eslint-disable-next-line no-new-func
  const ssrRender = new Function('__vue', '__ssr', renderBody + '\nreturn ssrRender')(VueRuntime, SsrRuntime)

  const script = descriptor.script.content
    .replace(/^import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const deps = { ICONS, colorForProject, ...taskUtils }
  // eslint-disable-next-line no-new-func
  const options = new Function(...Object.keys(deps), script)(...Object.values(deps))
  return { ...options, ssrRender }
}

const TaskRow = buildComponent()

async function render(props) {
  const app = VueRuntime.createSSRApp({
    render: () => VueRuntime.h(TaskRow, props),
  })
  app.config.globalProperties.$t = (k, p) => (p ? k + JSON.stringify(p) : k)
  app.config.warnHandler = () => {}
  const realWarn = console.warn
  console.warn = () => {}
  try {
    const html = await renderToString(app)
    return html.replace(/&quot;/g, '"').replace(/<!--[\s\S]*?-->/g, '')
  } finally {
    console.warn = realWarn
  }
}

/** 所有 class 属性里含 token 的那些（动态 class 与静态 class 合并后顺序不定，按集合比） */
function classSets(html, token) {
  return [...html.matchAll(/class="([^"]*)"/g)]
    .map((m) => new Set(m[1].split(/\s+/)))
    .filter((set) => set.has(token))
}
function hasEl(html, ...tokens) {
  return classSets(html, tokens[0]).some((set) => tokens.every((t) => set.has(t)))
}

const dayKey = (offset) => taskUtils.addDaysKey(taskUtils.localDateKey(), offset)

test('根类名 task-row + data-task-id 锚点 + 开庭类型色条（朱砂红令牌）', async () => {
  const html = await render({ task: { id: 42, title: '一审开庭', type: 'HEARING', dueDate: dayKey(3), status: 'OPEN' } })
  assert.match(html, /<view class="[^"]*\btask-row\b[^"]*"[^>]*data-task-id="42"/)
  assert.ok(hasEl(html, 'task-row', 'is-normal'))
  assert.match(html, /class="tr-type-bar" style="background:var\(--awd-danger\);"/)
  assert.match(html, /class="tr-title">一审开庭</)
})

test('文件芯片最多两个，其余折成 +N；悬空文件显示「已删除」', async () => {
  const html = await render({
    task: {
      id: 1, title: 't', dueDate: dayKey(10), status: 'OPEN',
      files: [
        { fileId: 1, fileName: '起诉状.docx' },
        { fileId: 2, fileName: null },
        { fileId: 3, fileName: '证据清单.xlsx' },
        { fileId: 4, fileName: '答辩状.docx' },
      ],
    },
  })
  assert.equal(classSets(html, 'tr-chip-file').length, 2)
  assert.match(html, /起诉状\.docx/)
  assert.ok(hasEl(html, 'tr-chip-file', 'is-missing'))
  assert.match(html, />calendar\.fileMissing</)
  assert.match(html, /class="tr-chip tr-chip-more"[^>]*>\+2</)
  assert.ok(!html.includes('答辩状'), '超出的文件不直接渲染')
})

test('showFiles=false 不渲染文件芯片；showProject 显示项目芯片', async () => {
  const task = { id: 1, title: 't', projectId: 3, projectName: '甲案', dueDate: dayKey(10), status: 'OPEN', files: [{ fileId: 1, fileName: 'a.docx' }] }
  const hidden = await render({ task, showFiles: false })
  assert.ok(!hidden.includes('tr-chip-file'))
  assert.ok(!hidden.includes('tr-chip-project'), 'showProject 默认 false')
  const shown = await render({ task, showProject: true })
  assert.ok(hasEl(shown, 'tr-chip-project'))
  assert.match(shown, />甲案</)
})

test('到期徽标 kind：逾期 / 今天（带时间）/ 之后；已完成统一 is-done', async () => {
  const overdue = await render({ task: { id: 1, title: 't', dueDate: dayKey(-2), status: 'OPEN' } })
  assert.ok(hasEl(overdue, 'tr-due', 'is-overdue'))
  assert.match(overdue, />calendar\.dueOverdueDays\{"count":2\}</)
  const today = await render({ task: { id: 1, title: 't', dueDate: dayKey(0), dueTime: '09:30', status: 'OPEN' } })
  assert.ok(hasEl(today, 'tr-due', 'is-today'))
  assert.match(today, />calendar\.dueTodayShort<text class="tr-due-time">09:30<\/text>/)
  const later = await render({ task: { id: 1, title: 't', dueDate: dayKey(30), status: 'OPEN' } })
  assert.ok(hasEl(later, 'tr-due', 'is-later'))
  const done = await render({ task: { id: 1, title: 't', dueDate: dayKey(-2), status: 'DONE' } })
  assert.ok(hasEl(done, 'tr-due', 'is-later'))
  assert.ok(!hasEl(done, 'tr-due', 'is-overdue'), '已完成不再显示逾期红')
  assert.ok(hasEl(done, 'task-row', 'is-done'))
  assert.ok(hasEl(done, 'tr-check', 'is-checked'))
})

test('重要小旗、提醒铃、负责人首字、compact 密度', async () => {
  const html = await render({
    task: { id: 1, title: 't', priority: 'HIGH', remindBefore: 1440, assigneeName: '韩泽伟', dueDate: dayKey(5), status: 'OPEN' },
    density: 'compact',
  })
  assert.ok(hasEl(html, 'task-row', 'is-compact', 'is-high'))
  assert.match(html, /class="tr-flag"/)
  assert.match(html, /class="tr-bell"/)
  assert.match(html, /class="tr-avatar">韩</)
  const plain = await render({ task: { id: 1, title: 't', dueDate: dayKey(5), status: 'OPEN', remindBefore: null } })
  assert.ok(!plain.includes('tr-bell'))
  assert.ok(!plain.includes('tr-flag'))
})

test('emits 契约', () => {
  assert.deepEqual(TaskRow.emits, ['toggle', 'open', 'open-file', 'open-project', 'delete'])
})

test('已完成的行：徽标是灰色日期（M月D日 + 时间），不再说逾期 / 今天', async () => {
  const [y, m, d] = dayKey(-3).split('-').map(Number)
  const expectKey = y === new Date().getFullYear()
    ? 'calendar.dueMonthDay' + JSON.stringify({ month: m, day: d })
    : 'calendar.dueYearMonthDay' + JSON.stringify({ year: y, month: m, day: d })
  const html = await render({ task: { id: 1, title: 't', dueDate: dayKey(-3), dueTime: '14:00', status: 'DONE' } })
  assert.ok(hasEl(html, 'tr-due', 'is-later'))
  assert.ok(html.includes('>' + expectKey + '<text class="tr-due-time">14:00</text>'))
  assert.ok(!html.includes('dueOverdueDays'))
  const today = await render({ task: { id: 1, title: 't', dueDate: dayKey(0), status: 'DONE' } })
  assert.ok(!today.includes('dueTodayShort'))
  assert.ok(hasEl(today, 'tr-due', 'is-later'))
})

test('唯一文件与标题同名（trim 后）时不显示文件芯片；多个文件照常全显', async () => {
  const single = await render({
    task: { id: 1, title: ' 起诉状.docx ', dueDate: dayKey(5), status: 'OPEN', files: [{ fileId: 1, fileName: '起诉状.docx' }] },
  })
  assert.equal(classSets(single, 'tr-chip-file').length, 0)
  assert.ok(!single.includes('tr-meta'), '没有别的芯片时整行第二行也不渲染')
  const other = await render({
    task: { id: 1, title: '提交起诉状', dueDate: dayKey(5), status: 'OPEN', files: [{ fileId: 1, fileName: '起诉状.docx' }] },
  })
  assert.equal(classSets(other, 'tr-chip-file').length, 1)
  const multi = await render({
    task: { id: 1, title: '起诉状.docx', dueDate: dayKey(5), status: 'OPEN', files: [{ fileId: 1, fileName: '起诉状.docx' }, { fileId: 2, fileName: '证据.pdf' }] },
  })
  assert.equal(classSets(multi, 'tr-chip-file').length, 2)
})
