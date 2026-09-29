// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 播放器组件的 SSR 真渲染装载器（tests/media/*.test.mjs 共用）。
//
// 比 tests/evidence/previewLocateRender.test.mjs 的 buildSsrRender 多走一步：不光编译
// <template>，连 <script> 也装进来——computed、props 默认值、子组件注册都是真的，
// 断言看到的就是组件自己算出来的 class / aria / 文案键。做法：
//   1. compiler-sfc 把模板编成 ssrRender（与 evidence 那份同一套 API）；
//   2. <script> 里的 import 改写成绝对地址（@/ → src/，./X.vue → 递归装载后的临时模块，
//      uni 相关的 services/auth/付费确认换成 stubs 里的桩）；
//   3. 两段拼成一个 ES 模块写进系统临时目录再 import。
// 不引任何新依赖；临时文件不落仓库。
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parse, compileTemplate } from 'vue/compiler-sfc'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = resolve(HERE, '../../src')
const OUT = mkdtempSync(join(tmpdir(), 'awd-media-ssr-'))
const VUE_URL = import.meta.resolve('vue')
const SSR_URL = import.meta.resolve('vue/server-renderer')

// 依赖 uni 运行时的模块换成桩：SSR 只看渲染，不发请求
const STUBS = {
  '@/services/api.js': `
    export const getFileDownloadUrl = (id) => '/api/files/' + id + '/download'
    export const getMeetingByFile = async () => ({ meeting: null })
    export const getMeetingRecording = async () => null
    export const registerMeetingFromFile = async () => ({})
    export const transcribeMeetingRecording = async () => null
  `,
  '@/utils/auth.js': `
    export const getAuthHeaders = () => ({})
    export const getSessionId = () => 'sid'
  `,
  '@/utils/paidTranscribeGate.js': `
    export const confirmPaidTranscription = async () => true
  `,
}

const cache = new Map()
let seq = 0

function writeModule(code) {
  const file = join(OUT, 'm' + (++seq) + '.mjs')
  writeFileSync(file, code)
  return pathToFileURL(file).href
}

function resolveSpec(spec, fromFile) {
  if (STUBS[spec]) {
    if (!cache.has(spec)) cache.set(spec, writeModule(STUBS[spec]))
    return cache.get(spec)
  }
  let abs
  if (spec.startsWith('@/')) abs = join(SRC, spec.slice(2))
  else if (spec.startsWith('.')) abs = resolve(dirname(fromFile), spec)
  else return spec
  if (abs.endsWith('.vue')) return compileSfcToUrl(abs)
  return pathToFileURL(abs).href
}

function compileSfcToUrl(file) {
  if (cache.has(file)) return cache.get(file)
  const source = readFileSync(file, 'utf8')
  const { descriptor, errors } = parse(source, { filename: file })
  if (errors.length) throw new Error('解析失败 ' + file + '：' + errors.join('; '))
  const tpl = compileTemplate({
    source: descriptor.template.content,
    filename: file,
    id: 'ssr-' + seq,
    ssr: true,
    ssrCssVars: [],
  })
  if (tpl.errors.length) throw new Error('模板编译报错 ' + file + '：' + tpl.errors.join('; '))
  const render = tpl.code
    .replace(/from "vue"/g, 'from ' + JSON.stringify(VUE_URL))
    .replace(/from "vue\/server-renderer"/g, 'from ' + JSON.stringify(SSR_URL))
  const script = descriptor.script.content
    .replace(/from\s+'([^']+)'/g, (m, spec) => 'from ' + JSON.stringify(resolveSpec(spec, file)))
    .replace(/^export default \{/m, 'const __component = {')
  const url = writeModule(script + '\n' + render + '\n__component.ssrRender = ssrRender\nexport default __component\n')
  cache.set(file, url)
  return url
}

/** 装载 src 下的一个 .vue，返回组件选项对象（带真的 script 与 ssrRender）。 */
export async function loadSfc(relFromSrc) {
  const url = compileSfcToUrl(join(SRC, relFromSrc))
  return (await import(url)).default
}

/** 源文件原文（给「模板里不许出现某某」这类断言用）。 */
export function readSfcTemplate(relFromSrc) {
  const { descriptor } = parse(readFileSync(join(SRC, relFromSrc), 'utf8'))
  return descriptor.template.content
}
