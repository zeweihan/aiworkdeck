// 禁字断言专用：解析出「这个组件实际会显示的中文」。
//
// i18n 迁移后组件源码里只剩 $t('ns.key')，于是 !CODE.includes('读取失败') 这类
// 护栏永远为真——把 locale 里的文案改成禁用词也拦不住。正向断言可以直接读整份
// locale 文件（#359 的做法），但禁字断言不行：projects.js 里就有
// loadFailedRetry: '加载失败，请稍后重试'，整份拼进去会让「unavailable 不许是
// 错误文案」误红。所以这里精确到该组件引用的那些键。

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const nsCache = new Map()

function loadNs(ns) {
  if (!nsCache.has(ns)) {
    let dict = {}
    try {
      // locale 是纯字面量对象的 ES 模块；不引 vue-i18n，直接抠出 default 导出求值
      const src = readFileSync(resolve(HERE, '../../src/locales/zh-CN/' + ns + '.js'), 'utf8')
        .replace(/^\s*export\s+default\s*/m, 'return ')
      dict = new Function(src)() || {}
    } catch (e) { /* ns 不存在：按空字典处理 */ }
    nsCache.set(ns, dict)
  }
  return nsCache.get(ns)
}

const dig = (obj, path) => path.reduce((o, k) => (o == null ? o : o[k]), obj)

/** 组件源码里 $t 引用到的全部 zh 文案值，换行拼接。 */
export function localeValuesOf(src) {
  return [...src.matchAll(/\$t\(\s*['"]([\w.]+)['"]/g)]
    .map((m) => {
      const [ns, ...rest] = m[1].split('.')
      const v = dig(loadNs(ns), rest)
      return typeof v === 'string' ? v : ''
    })
    .join('\n')
}
