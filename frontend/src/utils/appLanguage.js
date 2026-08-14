// 应用语言（zh-CN / en-US）。权威源是这里的 uni storage 值；后端 system_setting
// 与桌面主进程（菜单/原生对话框）只是镜像，由 App.vue 监听 EVENT 统一写透。
//
// 首启猜测规则（只猜一次，之后以用户显式设置为准）：
// 1. 存量安装保护：storage 里已有使用痕迹（最近项目/会话）→ zh-CN。
//    既有用户全部来自中文版，绝不能因为系统 locale 是 en-* 就把他们翻成英文
//    （Electron 下系统 locale 报 en-* 的教训见 utils/toolDisplayNames.js）。
// 2. 全新安装：navigator.language 以 zh 开头 → zh-CN，其余 → en-US。
//    与桌面主进程 desktop/main/app-language.js 的 app.getLocale() 规则一致。
//
// 本模块刻意零依赖（host.js / api.js 都不引），否则 host.js 取语言会形成
// host → appLanguage → api → host 的循环引用。

export const APP_LANGUAGE_KEY = 'awd_app_language'
export const APP_LANGUAGE_EVENT = 'awd-language-changed'
export const SUPPORTED_LANGUAGES = ['zh-CN', 'en-US']

let cached = ''

function readStorage(key) {
  try {
    const v = uni.getStorageSync(key)
    return typeof v === 'string' ? v.trim() : ''
  } catch (e) {
    return ''
  }
}

function hasExistingFootprint() {
  // 与 utils/recentProjects.js（checkba_last_project_id）、utils/auth.js（checkba_session_id）
  // 的存储键对齐；任一存在即视为存量用户。
  return !!(readStorage('checkba_last_project_id') || readStorage('checkba_session_id'))
}

function guessLanguage() {
  if (hasExistingFootprint()) return 'zh-CN'
  let loc = ''
  try { loc = String((typeof navigator !== 'undefined' && navigator.language) || '') } catch (e) { /* ignore */ }
  return loc.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
}

export function getAppLanguage() {
  if (cached) return cached
  const stored = readStorage(APP_LANGUAGE_KEY)
  if (SUPPORTED_LANGUAGES.includes(stored)) {
    cached = stored
    return cached
  }
  cached = guessLanguage()
  try { uni.setStorageSync(APP_LANGUAGE_KEY, cached) } catch (e) { /* ignore */ }
  return cached
}

export function isEnglish() {
  return getAppLanguage() === 'en-US'
}

/**
 * 设置语言并广播。镜像写透（后端 system_setting、桌面主进程）由 App.vue
 * 的 APP_LANGUAGE_EVENT 监听器完成，调用方不需要关心。
 */
export function setAppLanguage(lang) {
  if (!SUPPORTED_LANGUAGES.includes(lang)) return getAppLanguage()
  const changed = lang !== getAppLanguage()
  cached = lang
  try { uni.setStorageSync(APP_LANGUAGE_KEY, lang) } catch (e) { console.warn('[appLanguage] persist failed:', e) }
  if (changed) {
    try { uni.$emit(APP_LANGUAGE_EVENT, lang) } catch (e) { /* ignore */ }
  }
  return cached
}

/** 双语文案就地取值：tr({ zh: '…', en: '…' })。i18n 框架接线前的轻量通道。 */
export function tr(pair) {
  return isEnglish() ? pair.en : pair.zh
}
