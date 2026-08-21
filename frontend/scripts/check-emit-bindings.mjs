#!/usr/bin/env node
/**
 * emit/绑定契约静态检查（PR#147 剪贴板/收藏夹闪电 bug 后加装的护栏）。
 *
 * 规则：父组件模板里对子组件写了 @some-event="handler"，而子组件源码里
 * 找不到对应的 $emit('some-event') / emit('some-event') / defineEmits 声明，
 * 即判为"死绑定"（多半是子组件重构时把 emit 弄丢了，或事件名打错）。
 *
 * 只查"绑定 → emit"这个方向：emit 了没人听是合法的（可选事件）。
 * 原生 DOM/uni-app 事件（click/tap/input/...）会透传到子组件根元素，不检查。
 * 子组件里存在动态 emit（$emit(variable)）时跳过该组件，避免误报。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, resolve, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

// CHECK_EMITS_SRC 只给单测用（指向临时 fixture 目录），正常跑走默认的 src/
const SRC = process.env.CHECK_EMITS_SRC
  ? resolve(process.env.CHECK_EMITS_SRC)
  : resolve(dirname(fileURLToPath(import.meta.url)), '../src')

// 原生/透传事件白名单（组件上绑这些不要求子组件 emit）
const NATIVE_EVENTS = new Set([
  'click', 'dblclick', 'tap', 'longpress', 'contextmenu',
  'mousedown', 'mouseup', 'mousemove', 'mouseenter', 'mouseleave', 'mouseover', 'mouseout', 'wheel',
  'touchstart', 'touchmove', 'touchend', 'touchcancel',
  'keydown', 'keyup', 'keypress',
  'input', 'change', 'focus', 'blur', 'submit', 'reset', 'confirm', 'select',
  'scroll', 'scrolltolower', 'scrolltoupper',
  'load', 'error', 'loaded',
  'dragstart', 'drag', 'dragenter', 'dragover', 'dragleave', 'dragend',
  'animationstart', 'animationend', 'transitionend',
])

const toKebab = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (name.endsWith('.vue')) out.push(p)
  }
  return out
}

const vueFiles = walk(SRC)
const feedbackWidgetFile = resolve(SRC, 'components/FeedbackWidget.vue')

// 1) 每个 .vue 组件声明/使用的 emit 事件名（kebab 归一）
const emitsByFile = new Map()   // absPath -> Set<kebab-name> | null(动态 emit，跳过)
for (const f of vueFiles) {
  const code = readFileSync(f, 'utf8')
  // 动态 emit：$emit(expr) / emit(expr)，无法静态分析 → 该组件不检查
  if (/[$.\s]emit\(\s*[^'")\s]/.test(code)) { emitsByFile.set(f, null); continue }
  const names = new Set()
  for (const m of code.matchAll(/[$.\s]emit\(\s*['"]([\w:-]+)['"]/g)) names.add(toKebab(m[1]))
  for (const m of code.matchAll(/defineEmits\(\s*\[([^\]]*)\]/gs)) {
    for (const e of m[1].matchAll(/['"]([\w:-]+)['"]/g)) names.add(toKebab(e[1]))
  }
  for (const m of code.matchAll(/emits\s*:\s*\[([^\]]*)\]/gs)) {
    for (const e of m[1].matchAll(/['"]([\w:-]+)['"]/g)) names.add(toKebab(e[1]))
  }
  emitsByFile.set(f, names)
}

// 2) 逐文件解析 import 出的本地组件标签 → 源文件，再对模板里 <Tag ... @event 检查
let errors = 0
for (const f of vueFiles) {
  const code = readFileSync(f, 'utf8')
  const tagToFile = new Map()
  for (const m of code.matchAll(/import\s+(\w+)\s+from\s+['"]([^'"]+\.vue)['"]/g)) {
    const [, local, spec] = m
    const abs = spec.startsWith('@/') ? resolve(SRC, spec.slice(2)) : resolve(dirname(f), spec)
    tagToFile.set(local, abs)
  }
  if (tagToFile.size === 0) continue

  for (const [tag, target] of tagToFile) {
    const emits = emitsByFile.get(target)
    if (emits === undefined || emits === null) continue // 非本仓组件或动态 emit → 跳过
    // 标签可能写成 <PascalCase 或 <kebab-case>
    // 属性区先整段吃掉引号字面量，否则 :prop="a > b" 里的裸 '>' 会被当成标签闭合，
    // 把它后面的 @event 绑定整个漏检
    const tagPattern = new RegExp(`<(?:${tag}|${toKebab(tag)})\\b((?:"[^"]*"|'[^']*'|[^>])*)`, 'g')
    for (const m of code.matchAll(tagPattern)) {
      const attrs = m[1]
      for (const ev of attrs.matchAll(/@([\w-]+(?::[\w-]+)?)(?:\.[\w.]+)*=/g)) {
        const name = toKebab(ev[1])
        if (NATIVE_EVENTS.has(name)) continue
        if (name.startsWith('update:')) continue // v-model 族，另有机制
        if (!emits.has(name)) {
          const line = code.slice(0, m.index).split('\n').length
          console.error(`✗ ${f.replace(SRC, 'src')}:${line}  <${tag}> 绑定了 @${name}，但 ${basename(target)} 从不 emit '${name}'`)
          errors++
        }
      }
    }
  }
}

// 回归护栏：反馈浮窗不允许“点遮罩空白处关闭”，否则用户误点会中断正在写的反馈。
{
  const code = readFileSync(feedbackWidgetFile, 'utf8')
  const maskTag = code.match(/<div\b[^>]*class="awdfb-mask"[^>]*>/s)
  if (maskTag && /@(mousedown|click)\.self\s*=\s*["']closePanel["']/.test(maskTag[0])) {
    console.error('✗ FeedbackWidget: awdfb-mask 不应把空白点击绑定到 closePanel')
    errors++
  }
}

if (errors > 0) {
  console.error(`\n共 ${errors} 处静态检查失败。`)
  process.exit(1)
}
console.log(`✓ emit/绑定契约检查通过（扫描 ${vueFiles.length} 个 .vue）`)
