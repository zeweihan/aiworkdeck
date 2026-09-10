// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 可选组件下载管理的应用级单例（dev-board#581）。五个入口一律 import 这一个实例，
// 不再各自 new 控制器——状态挂在模块上，组件卸载、页面 reLaunch 都不影响在途任务
// （桌面端前端是 uni-app H5 单页，reLaunch 走前端路由，不重新加载模块）。
// 工厂与判据在 composables/useComponentDownloads.js（纯函数，有单测），这里只做接线。

import { reactive } from 'vue'
import { host } from '@/services/host.js'
import { optionalComponents, packInstall, packStatus, packInfo } from '@/services/api.js'
import { t } from '@/i18n'
import { createComponentDownloadManager } from '@/composables/useComponentDownloads.js'

function componentName(item) {
  return t('components.' + (item.localeKey || item.packId) + '.name')
}

export const componentDownloads = createComponentDownloadManager({
  state: reactive({}),
  optionalComponents,
  packInstall,
  packStatus,
  packInfo,
  modelDownload: (id) => host.model.download(id),
  onModelProgress: (cb) => host.model.onProgress(cb),
  ensureService: (name) => host.services.ensure(name),
  // 没有入口在前台盯着时的全局提示。uni-toast 挂在 document.body 上，
  // 用户停在哪个页面都看得到（层级已由 App.vue 统一抬到弹窗遮罩之上）。
  notify: (item, ok) => {
    const title = ok
      ? t('components.backgroundDone', { name: componentName(item) })
      : t('components.backgroundFailed', { name: componentName(item), msg: item.error || '' })
    uni.showToast({ title, icon: 'none', duration: 4000 })
  },
})
