// 应用语言（zh-CN / en-US）在主进程侧的持久化与订阅。
// 权威源是渲染层的语言设置（uni storage + 后端 system_setting），这里只是主进程的
// 本地镜像：菜单/系统通知/原生对话框在渲染层就绪之前就要用到语言，所以落一份
// userData/app-language.json；渲染层启动或用户切换语言时经 'checkba:set-app-language'
// 同步过来并触发订阅方（如应用菜单重建）。
// 首启猜测只看 app.getLocale()（zh* → zh-CN，其余 en-US）——与渲染层
// utils/appLanguage.js 的全新安装分支同一条规则，两侧猜测结果一致；
// 存量安装的保护（有使用痕迹默认 zh-CN）由渲染层判定后立刻同步覆盖到这里。

const { app } = require('electron')
const fs = require('fs')
const path = require('path')

const SUPPORTED = ['zh-CN', 'en-US']

let current = null
const listeners = []

function storeFile() {
  return path.join(app.getPath('userData'), 'app-language.json')
}

function guessFromSystem() {
  let loc = ''
  try { loc = String(app.getLocale() || '') } catch (e) { /* before ready 会抛，按空处理 */ }
  return loc.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
}

function getAppLanguage() {
  if (current) return current
  try {
    const parsed = JSON.parse(fs.readFileSync(storeFile(), 'utf8'))
    if (SUPPORTED.includes(parsed && parsed.language)) current = parsed.language
  } catch (e) { /* 首启无文件 */ }
  if (!current) current = guessFromSystem()
  return current
}

function setAppLanguage(lang) {
  if (!SUPPORTED.includes(lang)) return getAppLanguage()
  const changed = lang !== current
  current = lang
  try {
    fs.writeFileSync(storeFile(), JSON.stringify({ language: lang }), 'utf8')
  } catch (e) { console.warn('[app-language] persist failed:', e && e.message) }
  if (changed) {
    for (const fn of listeners) {
      try { fn(lang) } catch (e) { /* ignore */ }
    }
  }
  return current
}

function onAppLanguageChange(fn) {
  if (typeof fn === 'function') listeners.push(fn)
}

// 主进程文案取值：t({ zh: '…', en: '…' })
function t(pair) {
  return getAppLanguage() === 'en-US' ? pair.en : pair.zh
}

module.exports = { getAppLanguage, setAppLanguage, onAppLanguageChange, t, SUPPORTED }
