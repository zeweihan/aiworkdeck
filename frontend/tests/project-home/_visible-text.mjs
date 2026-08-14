// i18n 迁移后，组件源码里只剩 $t('ns.key')，中文文案实体在 src/locales/zh-CN/<ns>.js。
// 这些测试守的是「界面上有没有这句话」这条契约，不是「源码里有没有这个字符串」，
// 所以断言要看组件**实际引用的那些键**解析出来的中文，而不是整份 locale 文件——
// 整份拼进去会让禁字断言（"unavailable 不许是错误文案"之类）永远通不了红。

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const nsCache = new Map()

function loadNs(ns) {
  if (!nsCache.has(ns)) {
    const p = resolve(HERE, '../../src/locales/zh-CN/' + ns + '.js')
    let dict = {}
    try {
      // locale 是纯字面量对象的 ES 模块；这里不引 vue-i18n，直接抠出 default 导出求值
      const src = readFileSync(p, 'utf8').replace(/^\s*export\s+default\s*/m, 'return ')
      dict = new Function(src)() || {}
    } catch (e) { /* 该 ns 还没建：按空字典处理，断言自然会红 */ }
    nsCache.set(ns, dict)
  }
  return nsCache.get(ns)
}

const dig = (obj, path) => path.reduce((o, k) => (o == null ? o : o[k]), obj)

/** 组件引用到的全部 zh 文案值（不含源码），换行拼接。 */
export function localeValuesOf(src) {
  const keys = [...src.matchAll(/\$t\(\s*['"]([\w.]+)['"]/g)].map((m) => m[1])
  return keys
    .map((k) => {
      const [ns, ...rest] = k.split('.')
      const v = dig(loadNs(ns), rest)
      return typeof v === 'string' ? v : ''
    })
    .join('\n')
}

/**
 * 组件源码 + 它引用到的 zh 文案，拼成「这个组件会显示的文字」。
 * 断言 includes 某句中文时用它替代裸 SRC。
 */
export function visibleText(src) {
  return src + '\n' + localeValuesOf(src)
}

/**
 * 禁字断言专用：去注释的源码 + 引用到的 zh 文案。
 * 迁移后文案不在源码里了，禁字断言只查源码等于失效——「不许是错误文案」这类
 * 契约必须能看到 locale 里的实际值才拦得住。注释仍然排除（注释里要能写清楚
 * 为什么不做某件事，不该判红）。
 */
export function visibleCode(src, stripComments) {
  return stripComments(src) + '\n' + localeValuesOf(src)
}
