// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「对某一版能做的四件事」的唯一实现：退回到这一版 / 从这一版另起一稿 /
 * 标记重要版本 / 和上一版对比（dev-board#624）。
 *
 * 两个宿主共用：左栏版本面板的 VersionNodeDetail 弹窗，与中栏的「提交历史」标签页
 * （CommitHistoryTab）。逻辑复制一份必然慢慢分叉——尤其是「退回」那个二次确认里
 * 写着的后果说明，和 busy 重入守卫（漏了它能打出两个并发退回请求，修过一次）。
 *
 * 宿主契约：值随组件状态变的都传函数，不传快照。
 *   host = {
 *     projectId(): string|number,
 *     t(key, params?): string,
 *     isBusy(): boolean,
 *     setBusy(v: boolean): void,
 *     emit(name, payload): void,          // 'reload-files' / 'milestoned' / 'draft-created' / 'compare-file'
 *   }
 */
import { revertToVersion, markVersionMilestone, createDraft } from '@/services/api.js'

export function createVersionActions(host) {
  const t = (k, p) => host.t(k, p)
  const toast = (title) => uni.showToast({ title, icon: 'none' })

  return {
    /** 退回到某一版。二次确认里必须把后果说全——这一步改磁盘。 */
    confirmRevert(sha) {
      if (host.isBusy()) return
      uni.showModal({
        title: t('version.revertToVersion'),
        content: t('version.revertConfirmContent'),
        success: async (r) => {
          if (!r.confirm) return
          host.setBusy(true)
          try {
            const res = await revertToVersion(host.projectId(), sha)
            const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
            host.emit('reload-files', affectedFileIds)
          } catch (e) {
            toast((e && e.message) || t('version.revertFailed'))
          } finally {
            host.setBusy(false)
          }
        },
      })
    },

    /** 标记/改名重要版本。name 为空是调用方该拦下的输入错误，这里给一句可读提示。 */
    async markMilestone(sha, rawName) {
      if (host.isBusy()) return false
      const name = (rawName || '').trim()
      if (!name) {
        toast(t('version.milestoneNameRequired'))
        return false
      }
      host.setBusy(true)
      try {
        await markVersionMilestone(host.projectId(), sha, name)
        toast(t('version.markedMilestone'))
        host.emit('milestoned', sha)
        return true
      } catch (e) {
        toast((e && e.message) || t('version.markMilestoneFailed'))
        return false
      } finally {
        host.setBusy(false)
      }
    },

    /** 从某一版另起一稿：HEAD 会切到新分支，宿主拿到 affectedFileIds 后要重载编辑器。 */
    async createDraftFrom(sha, rawName) {
      if (host.isBusy()) return false
      const name = (rawName || '').trim()
      if (!name) {
        toast(t('version.draftNameRequired'))
        return false
      }
      host.setBusy(true)
      try {
        const res = await createDraft(host.projectId(), sha, name)
        const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
        toast(t('version.draftCreatedSwitching', { name }))
        host.emit('draft-created', affectedFileIds)
        return true
      } catch (e) {
        toast((e && e.message) || t('version.createDraftFailed'))
        return false
      } finally {
        host.setBusy(false)
      }
    },

    /**
     * 和上一版对比：只上抛 {path, sha}，由工作台的 onVersionCompareFile 决定
     * 开修订稿对比标签还是文本对比标签（oldRef 推成 sha^）。
     */
    compareWithPrevious(sha, path) {
      host.emit('compare-file', { path, sha })
    },
  }
}
