// 外观主题（浅色 / 深色 / 跟随系统）。权威源是这里的 uni storage 值；
// documentElement 上的 data-theme 与桌面主进程的 nativeTheme 都是镜像。
//
// 三态与两态之分：存储里存 mode（含 'system'），页面上只挂解析后的
// 'light' | 'dark'——组件的 CSS 选择器不需要认识第三种状态。
//
// 「跟随系统」在桌面端有一个反直觉之处：Electron 一旦把 nativeTheme.themeSource
// 设成非 'system'，**所有渲染进程的 prefers-color-scheme 都会被钉死**成那个值，
// 于是 matchMedia 永远读不到真实系统设置。所以 system 态必须让主进程把
// themeSource 也设回 'system'，matchMedia 才恢复真话（见 desktop/main/main.js
// 的 applyNativeTheme 与 dev-board#218 的沿革）。
//
// 本模块刻意零依赖（不引 host.js / api.js），与 appLanguage.js 同一条理由：
// 启动最早期就要用，不能卷进循环引用。

export const APP_THEME_KEY = 'awd_theme'
export const APP_THEME_EVENT = 'awd-theme-changed'
export const THEME_MODES = ['light', 'dark', 'system']

// 默认浅色而不是跟随系统：深色是本版新加的，先让它是「选进去」的而不是
// 「升级后被切过去」的；等深色跑顺了再考虑把默认改成 system。
const DEFAULT_MODE = 'light'

let cachedMode = ''
let systemDark = false
let mediaQuery = null

function readStorage(key) {
  try {
    const v = uni.getStorageSync(key)
    return typeof v === 'string' ? v.trim() : ''
  } catch (e) {
    return ''
  }
}

/** 当前 mode（'light' | 'dark' | 'system'） */
export function getThemeMode() {
  if (cachedMode) return cachedMode
  const stored = readStorage(APP_THEME_KEY)
  cachedMode = THEME_MODES.includes(stored) ? stored : DEFAULT_MODE
  return cachedMode
}

/** mode 解析成实际生效的 'light' | 'dark' */
export function getResolvedTheme() {
  const mode = getThemeMode()
  if (mode !== 'system') return mode
  return systemDark ? 'dark' : 'light'
}

function paint() {
  const resolved = getResolvedTheme()
  try {
    document.documentElement.setAttribute('data-theme', resolved)
  } catch (e) { /* 非浏览器环境（小程序端）静默 */ }
  return resolved
}

/** 桌面主进程镜像：让原生标题栏/交通灯/右键菜单跟着一起变（dev-board#218） */
function mirrorToDesktop(mode) {
  try {
    const d = typeof window !== 'undefined' && window.checkbaDesktop
    if (d && d.theme && d.theme.set) {
      // 主进程回报「系统当前是不是深色」——system 态下这是唯一可靠的来源，
      // 因为 themeSource 刚被改过，本进程的 matchMedia 可能还没同步过来。
      Promise.resolve(d.theme.set(mode)).then((r) => {
        if (r && typeof r.systemDark === 'boolean' && r.systemDark !== systemDark) {
          systemDark = r.systemDark
          if (getThemeMode() === 'system') {
            paint()
            try { uni.$emit(APP_THEME_EVENT, getResolvedTheme()) } catch (e) { /* ignore */ }
          }
        }
      }).catch(() => {})
    }
  } catch (e) { /* ignore */ }
}

/**
 * 启动时调用一次：读存储、挂 data-theme、订阅系统外观变化。
 * 必须早于首屏渲染，否则会先白一下再变深。
 */
export function initAppTheme() {
  const mode = getThemeMode()
  try {
    if (typeof window !== 'undefined' && window.matchMedia) {
      mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
      systemDark = !!mediaQuery.matches
      const onChange = (e) => {
        systemDark = !!e.matches
        if (getThemeMode() === 'system') {
          paint()
          try { uni.$emit(APP_THEME_EVENT, getResolvedTheme()) } catch (err) { /* ignore */ }
        }
      }
      if (mediaQuery.addEventListener) mediaQuery.addEventListener('change', onChange)
      else if (mediaQuery.addListener) mediaQuery.addListener(onChange)
    }
  } catch (e) { /* ignore */ }
  paint()
  mirrorToDesktop(mode)
  return getResolvedTheme()
}

/** 切换主题并广播。桌面原生层的镜像在这里一并写透。 */
export function setThemeMode(mode) {
  if (!THEME_MODES.includes(mode)) return getThemeMode()
  const changed = mode !== getThemeMode()
  cachedMode = mode
  try { uni.setStorageSync(APP_THEME_KEY, mode) } catch (e) { console.warn('[appTheme] persist failed:', e) }
  const resolved = paint()
  mirrorToDesktop(mode)
  if (changed) {
    try { uni.$emit(APP_THEME_EVENT, resolved) } catch (e) { /* ignore */ }
  }
  return cachedMode
}
