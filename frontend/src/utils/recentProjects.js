// IDE 化：最近项目记录（启动直达 + 顶栏最近项目切换器共用）。
// 只存 id 与访问时间，名称一律从 getMyProjects 实时解析，避免改名后显示陈旧。

const LAST_KEY = 'checkba_last_project_id'
const RECENT_KEY = 'checkba_recent_projects'
const MAX_RECENT = 8

export function recordProjectVisit(projectId) {
  const id = Number(projectId)
  if (!id) return
  try {
    uni.setStorageSync(LAST_KEY, id)
    const list = getRecentProjectIds().filter((x) => x !== id)
    list.unshift(id)
    uni.setStorageSync(RECENT_KEY, list.slice(0, MAX_RECENT))
  } catch (e) {
    console.warn('记录最近项目失败:', e)
  }
}

export function getRecentProjectIds() {
  try {
    const raw = uni.getStorageSync(RECENT_KEY)
    return Array.isArray(raw) ? raw.map(Number).filter(Boolean) : []
  } catch (e) {
    return []
  }
}

export function getLastProjectId() {
  try {
    return Number(uni.getStorageSync(LAST_KEY) || 0)
  } catch (e) {
    return 0
  }
}

/** 用项目全量列表把最近 id 解析成 {id, name}（改名不陈旧），推给桌面壳「最近打开」子菜单。 */
export function syncRecentToMenu(projects) {
  if (!(typeof window !== 'undefined' && window.checkbaDesktop
      && window.checkbaDesktop.menu && window.checkbaDesktop.menu.setRecentProjects)) {
    return
  }
  const byId = new Map((projects || []).map((p) => [Number(p.id), p]))
  const list = getRecentProjectIds()
    .map((id) => byId.get(id))
    .filter(Boolean)
    .map((p) => ({ id: Number(p.id), name: p.name }))
  window.checkbaDesktop.menu.setRecentProjects(list)
}

/** 自取项目列表版（project-overview 等没有现成列表的调用方用）。静默失败。 */
export async function syncRecentToMenuFetching() {
  try {
    const { getMyProjects } = await import('@/services/api.js')
    const projects = await getMyProjects()
    syncRecentToMenu(Array.isArray(projects) ? projects : (projects && projects.data) || [])
  } catch (e) {
    // 菜单只是便利入口，同步失败不打扰
  }
}
