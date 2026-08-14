// vue-i18n 接线（EN 版 PR2）。命名空间 = locales/<lang>/<ns>.js 的文件名，
// import.meta.glob 自动聚合——新增命名空间只需加两份同名文件，无中央注册表
//（批量迁移的各分片因此互不冲突）。两语言键集合由 scripts/check-locale-parity.mjs 对拍。
//
// 语言切换 = 整页 reload（admin.vue 语言项触发），因此模块顶层调 t() 取到的
// 静态文案（config 的 label 数组等）也是安全的。语言判定唯一来源 utils/appLanguage.js。

import { createI18n } from 'vue-i18n'
import { getAppLanguage } from '@/utils/appLanguage.js'

const zhModules = import.meta.glob('../locales/zh-CN/*.js', { eager: true })
const enModules = import.meta.glob('../locales/en-US/*.js', { eager: true })

function toMessages(mods) {
  const out = {}
  for (const [p, m] of Object.entries(mods)) {
    const ns = p.split('/').pop().replace(/\.js$/, '')
    out[ns] = (m && m.default) || {}
  }
  return out
}

export const i18n = createI18n({
  legacy: true,
  locale: getAppLanguage(),
  fallbackLocale: 'zh-CN',
  silentTranslationWarn: true,
  silentFallbackWarn: true,
  messages: {
    'zh-CN': toMessages(zhModules),
    'en-US': toMessages(enModules),
  },
})

/** 非组件模块（utils/composables/config/services）用的翻译入口。 */
export function t(key, params) {
  return i18n.global.t(key, params)
}
