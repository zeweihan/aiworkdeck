// IDE 化「打开文件夹 / 打开文件」共用流程：系统对话框 → open-local API → reLaunch 进项目。
// newproject 页与应用菜单（App.vue 的 menu-action 处理器）共用，两处只差 busy UI。

import { openLocalProject } from '@/services/api.js'
import { host } from '@/services/host.js'

export function desktopFsApi() {
  return (host.fs && host.fs.showOpenDialog) ? host.fs : null
}

async function launchProject(payload) {
  const r = await openLocalProject(payload)
  const d = (r && r.data) || {}
  if (!d.projectId) {
    throw new Error('打开项目失败，请稍后重试')
  }
  if (d.truncated) {
    uni.showToast({ title: '文件夹内容过多，仅导入了前 3000 项', icon: 'none' })
  }
  const query = `id=${d.projectId}` + (d.openFileId ? `&openFileId=${d.openFileId}` : '')
  // reLaunch：避免页面栈里堆叠多个 project-overview 实例（全局监听多实例地雷）
  uni.reLaunch({ url: `/pages/project-overview/project-overview?${query}` })
}

/** 打开文件夹… 返回 true=已发起导航，false=用户取消。异常上抛由调用方 toast。 */
export async function openFolderFlow() {
  const fs = desktopFsApi()
  if (!fs) return false
  const res = await fs.showOpenDialog({
    title: '打开文件夹',
    buttonLabel: '打开',
    properties: ['openDirectory', 'createDirectory'],
  })
  if (!res || res.canceled || !res.filePaths || !res.filePaths.length) return false
  await launchProject({ localRoot: res.filePaths[0] })
  return true
}

/** 打开文件…（单文件过渡版：以所在文件夹入项目并打开该文件）。 */
export async function openFileFlow() {
  const fs = desktopFsApi()
  if (!fs) return false
  const res = await fs.showOpenDialog({
    title: '打开文件',
    buttonLabel: '打开',
    properties: ['openFile'],
  })
  if (!res || res.canceled || !res.filePaths || !res.filePaths.length) return false
  const filePath = res.filePaths[0]
  const idx = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'))
  if (idx <= 0) {
    throw new Error('无法识别该文件的所在文件夹')
  }
  await launchProject({
    localRoot: filePath.slice(0, idx),
    openFileName: filePath.slice(idx + 1),
  })
  return true
}

/** 把一个绝对路径的文件夹直接作为项目打开（拖放/Dock/Finder 打开方式入口）。 */
export async function openLocalRootPath(absPath) {
  if (!absPath) return false
  await launchProject({ localRoot: absPath })
  return true
}

/** 把一个绝对路径的文件打开（以所在文件夹入项目并打开该文件）。 */
export async function openLocalFilePath(absPath) {
  if (!absPath) return false
  const idx = Math.max(absPath.lastIndexOf('/'), absPath.lastIndexOf('\\'))
  if (idx <= 0) throw new Error('无法识别该文件的所在文件夹')
  await launchProject({ localRoot: absPath.slice(0, idx), openFileName: absPath.slice(idx + 1) })
  return true
}

/** 新建项目文件夹…（命名弹窗在 newproject 页里，菜单入口跳过去自动触发）。 */
export async function createFolderFlow(parentDir, name) {
  const sep = parentDir.includes('\\') && !parentDir.includes('/') ? '\\' : '/'
  const localRoot = parentDir.replace(/[\\/]+$/, '') + sep + name.trim()
  await launchProject({ localRoot, createFolder: true, name: name.trim() })
  return true
}
