// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 左栏收起状态的持久化（dev-board#727）。
//
// 病灶：`sidebarCollapsed` 此前只是纯内存布尔值，三条切换入口（顶栏图标、再点同一个
// rail 图标、Alt+Ctrl+B）都不落盘——用户收起左栏专心看正文，刷新/重开项目后左栏又
// 弹回来，等于每次都要重新收一遍。
//
// 持久化键 `awd_sidebar_collapsed` 是**全局的、不带 projectId**：收不收左栏是本机使用
// 习惯，跟着人走不跟着案卷走（同 `awd_panel_docks` / `checkba_project_list_view` 的先例）。
//
// 刻意做成零依赖的纯函数（同 flushDirtyEditors.js 的先例）：既能被 project-overview.vue
// 直接用，也能在 node:test 里真跑一遍——本目录其余模块大多 import 了 @/ 别名或直接用
// uni 全局，测不动。`storage` 参数是 uni 的 getStorageSync/setStorageSync 接口子集，
// 调用方传 `uni`，测试传一个假实现。

const SIDEBAR_COLLAPSED_STORAGE_KEY = 'awd_sidebar_collapsed'

/**
 * 读取左栏收起状态，默认展开（false）。
 * @param {{ getStorageSync: (key: string) => any }} storage
 * @returns {boolean}
 */
export function loadSidebarCollapsed(storage) {
  try {
    return storage.getStorageSync(SIDEBAR_COLLAPSED_STORAGE_KEY) === true
  } catch (e) {
    return false
  }
}

/**
 * 写入左栏收起状态。
 * @param {{ setStorageSync: (key: string, value: any) => void }} storage
 * @param {boolean} collapsed
 */
export function saveSidebarCollapsed(storage, collapsed) {
  try {
    storage.setStorageSync(SIDEBAR_COLLAPSED_STORAGE_KEY, Boolean(collapsed))
  } catch (e) {
    // 存储不可用（隐私模式/配额满）时静默——收起状态退化为纯内存，不影响本次使用
  }
}
