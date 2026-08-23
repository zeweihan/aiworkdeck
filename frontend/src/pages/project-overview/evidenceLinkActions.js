// project-overview.vue 的证据链接（EvidenceLink）动作组：拖文件到编辑器即建链、
// method 浮动小条、点击 filelink 链接解包定位。与 stagingArea.js 同款导出 `{ data(), methods }`，
// 经展开进组件，`this` 即 project-overview 页面实例。
//
// 契约见 .claude/agents/ai-doc-bridge.md「EvidenceLink 契约」；书签名 = linkKey = `EVID_<ULID>`，
// 超链接 URL 只是跳转用（`<base>?u=checkba://filelink?k=&projectId=[&t=]`）。

import {
  createEvidenceLink, addEvidenceTargets, updateEvidenceTarget, getEvidenceLink, getFileDetail,
} from '@/services/api.js'
import { parseFileLinkUrl, locatorSummary } from '@/utils/evidenceLocator.js'
import { createEvidenceLinkForDrop, pickEvidenceTarget } from './evidenceLinkCore.js'

export { createEvidenceLinkForDrop, pickEvidenceTarget }

// method 浮动小条无操作时的自动收起延时（ms）。此前这个常量漏定义，
// armEvidenceMethodBarTimer 的 setTimeout 直接撞 ReferenceError——建链已成功，
// 但抛出打断了紧随的 awd:evidence-changed 通知，小条也永不收起，用户「释放后没反应」
// （dev-board#135，真机复现）。
const METHOD_BAR_TTL_MS = 3000

// request() 已把 {code:0,data} 整体 resolve 出来，这里统一剥一层。
function unwrap(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && 'data' in resp) return resp.data
  return resp
}

export const evidenceLinkData = () => ({
  // method 浮动小条：连续拖放只保留最后一条（对象整体替换）
  evidenceMethodBar: { visible: false, side: 'left', fileName: '', method: 'written_review', targetId: null, linkKey: '' },
})

export const evidenceLinkMethods = {
  // 编辑器 drop 事件：{ file: {fileId|id, name, fileType, wpsFileId} }，side = 'left' | 'right'
  async onEvidenceDrop(payload, side) {
    const raw = payload && payload.file
    if (!raw) return
    const file = { ...raw, id: Number(raw.id != null ? raw.id : raw.fileId) }
    if (!file.id) return
    if (file.fileType === 'folder' || raw.isFolder) return
    const doc = side === 'right' ? this.activeFileRight : this.activeFileLeft
    const exec0 = doc ? this.getLibreExecutorMap()[side + ':' + doc.id] : null
    if (!doc || !exec0) {
      uni.showToast({ title: this.$t('workbench.openDocFirst'), icon: 'none' })
      return
    }
    if (Number(file.id) === Number(doc.id)) {
      uni.showToast({ title: this.$t('workbench.evidence.selfLink'), icon: 'none' })
      return
    }
    const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
    const exec = (action, params) => exec0.executeCommand(action, params)
    let res
    try {
      res = await createEvidenceLinkForDrop({
        exec, api: { createEvidenceLink: (p, b) => createEvidenceLink(p, b).then(unwrap), addEvidenceTargets: (p, k, t) => addEvidenceTargets(p, k, t).then(unwrap) },
        projectId: pid, docFileId: Number(doc.id), file, internalBase: this.WPS_INTERNAL_HTTP_LINK_BASE || '',
      })
    } catch (e) {
      uni.showToast({ title: (e && e.message) || this.$t('workbench.linkFailed'), icon: 'none' })
      return
    }
    if (!res.ok) {
      const key = res.reason === 'no_selection' ? 'workbench.evidence.selectFirst'
        : res.reason === 'bookmark_failed' ? 'workbench.evidence.bookmarkFailed' : 'workbench.setHyperlinkFailed'
      uni.showToast({ title: this.$t(key), icon: 'none' })
      return
    }
    this.showEvidenceMethodBar({ side, fileName: file.name || '', targetId: res.targetId, linkKey: res.linkKey })
    uni.$emit('awd:evidence-changed', { docFileId: Number(doc.id), linkKey: res.linkKey })
  },

  showEvidenceMethodBar({ side, fileName, targetId, linkKey }) {
    this.evidenceMethodBar = { visible: true, side, fileName, method: 'written_review', targetId, linkKey }
    this.armEvidenceMethodBarTimer()
  },
  armEvidenceMethodBarTimer() {
    clearTimeout(this._evidenceBarTimer)
    this._evidenceBarTimer = setTimeout(() => { this.evidenceMethodBar.visible = false }, METHOD_BAR_TTL_MS)
  },
  closeEvidenceMethodBar() {
    clearTimeout(this._evidenceBarTimer)
    this.evidenceMethodBar.visible = false
  },
  async onEvidenceMethodChange({ targetId, method }) {
    if (!targetId || !method) return
    this.evidenceMethodBar.method = method
    this.armEvidenceMethodBarTimer()
    const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
    try {
      await updateEvidenceTarget(pid, targetId, { method })
      const doc = this.evidenceMethodBar.side === 'right' ? this.activeFileRight : this.activeFileLeft
      uni.$emit('awd:evidence-changed', { docFileId: doc ? Number(doc.id) : null, linkKey: this.evidenceMethodBar.linkKey })
    } catch (e) {
      uni.showToast({ title: (e && e.message) || this.$t('workbench.linkFailed'), icon: 'none' })
    }
  },

  // 文档里点击 filelink 链接（两条入口 onLibreOpenUrl / __checkbaHandleInternalLink 都汇到这里）
  handleFileLinkClick(rawUrl) {
    const parsed = parseFileLinkUrl(rawUrl)
    if (!parsed || !this.projectId) return false
    const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
    const side = this.focusedPane === 'right' && this.splitMode ? 'right' : 'left'
    getEvidenceLink(pid, parsed.linkKey)
      .then((resp) => {
        const view = unwrap(resp)
        const targets = (view && Array.isArray(view.targets)) ? view.targets : []
        if (targets.length === 0) {
          uni.showToast({ title: this.$t('workbench.linkedFileMissing'), icon: 'none' })
          return
        }
        const hit = pickEvidenceTarget(view, parsed.targetId)
        if (hit) {
          this.openFileLinkTarget(hit, side)
          return
        }
        this.fileLinkPicker = { visible: true, side, targets, linkKey: parsed.linkKey }
      })
      .catch((e) => {
        uni.showToast({ title: (e && e.message) ? e.message : this.$t('workbench.openFailed'), icon: 'none' })
      })
    return true
  },
  closeFileLinkPicker() {
    this.fileLinkPicker.visible = false
    this.fileLinkPicker.targets = []
    this.fileLinkPicker.linkKey = ''
  },
  evidenceTargetSummary(target) {
    return locatorSummary(target && target.locator, (k, p) => this.$t('workbench.' + k, p))
  },
  evidenceMethodLabel(method) {
    return method ? this.$t('workbench.evidence.method.' + method) : ''
  },
  // target = TargetView {id, fileId, file, locator, ...}
  async openFileLinkTarget(target, sideOverride = null) {
    const fid = Number(target && target.fileId)
    if (!fid || !this.projectId) return
    const side = sideOverride || this.fileLinkPicker.side || 'left'
    this.closeFileLinkPicker()
    try {
      if (target.file && target.file.isDeleted) throw new Error(this.$t('workbench.fileMissing'))
      const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
      const file = await getFileDetail(pid, fid)
      if (!file) throw new Error(this.$t('workbench.fileMissing'))
      const old = this.focusedPane
      this.focusedPane = side === 'right' && this.splitMode ? 'right' : 'left'
      this.openFile(file, { locator: target.locator || null })
      this.focusedPane = old
    } catch (e) {
      uni.showToast({ title: e.message || this.$t('workbench.openFailed'), icon: 'none' })
    }
  },
  // 编辑器/预览消费完 pendingLocator 后回调清空，避免切回标签时重复跳转
  onLocatorConsumed(fileId) {
    const fid = Number(fileId)
    for (const list of [this.leftFiles, this.rightFiles]) {
      const tab = Array.isArray(list) ? list.find((f) => Number(f.id) === fid) : null
      if (tab && tab.pendingLocator) tab.pendingLocator = null
    }
  },
}
