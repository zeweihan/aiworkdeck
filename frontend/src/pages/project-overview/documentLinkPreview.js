// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { parseFileLinkUrl } from '../../utils/evidenceLocator.js'

export const documentLinkPreviewMethods = {
  openDocumentLinkPreview(payload) {
    const p = typeof payload === 'string' ? { url: payload } : payload || {}
    const url = String(p.url || '')
    if (!url) return
    const sourceSide = p.fileId && Number(this.activeFileIdRight) === Number(p.fileId) && this.splitMode
      ? 'right' : p.fileId && Number(this.activeFileIdLeft) === Number(p.fileId) ? 'left' : this.focusedPane || 'left'
    const fileId = p.fileId || this[sourceSide === 'right' ? 'activeFileIdRight' : 'activeFileIdLeft'] || null
    if (String(this[sourceSide === 'right' ? 'activeFileIdRight' : 'activeFileIdLeft']) !== String(fileId)) return
    this.closeDocumentLinkPreview()
    const preview = {
      url, target: p.target || null, fileId, sourceSide, projectId: this.projectId, projectRecordId: this.project?.id, epoch: this._documentLinkPreviewEpoch,
      x: p.meta?.hostX, y: p.meta?.hostY, side: sourceSide === 'right' ? 'left' : 'right',
      targets: [], loading: false, opening: false, error: '',
    }
    this.closeInsightHoverCard()
    this.documentLinkPreview = preview
    // A synchronous watcher also catches A -> B -> A within the same turn.
    // Keep the source identity separate from whichever pane receives the result.
    if (this.$watch) this._documentLinkPreviewUnwatch = this.$watch(
      () => [this.projectId, this.project?.id, this[sourceSide === 'right' ? 'activeFileIdRight' : 'activeFileIdLeft'], sourceSide === 'right' && !this.splitMode],
      () => this.closeDocumentLinkPreview(), { flush: 'sync' },
    )
    if (parseFileLinkUrl(url)) this.handleFileLinkClick(url, this.documentLinkPreview)
  },
  isDocumentLinkPreviewCurrent(preview) {
    return !!preview && this.documentLinkPreview === preview && preview.epoch === this._documentLinkPreviewEpoch
      && String(preview.projectId) === String(this.projectId) && String(preview.projectRecordId) === String(this.project?.id)
      && (preview.sourceSide !== 'right' || this.splitMode)
      && String(preview.fileId) === String(this[preview.sourceSide === 'right' ? 'activeFileIdRight' : 'activeFileIdLeft'])
  },
  closeDocumentLinkPreview() {
    this._documentLinkPreviewUnwatch?.(); this._documentLinkPreviewUnwatch = null
    this._documentLinkPreviewEpoch = (this._documentLinkPreviewEpoch || 0) + 1
    this.documentLinkPreview = null
  },
  async openDocumentLinkAside(target) {
    const preview = this.documentLinkPreview
    if (!this.isDocumentLinkPreviewCurrent(preview) || preview.opening) return
    // A same-document reference stays in the read-only excerpt. Opening its
    // live editor at the target would destroy the reader's original position.
    if (target && Number(target.fileId) === Number(preview.fileId)) return
    if (target) {
      preview.opening = true
      try { await this.openFileLinkTarget(target, preview.side, { preview }) }
      finally { if (this.isDocumentLinkPreviewCurrent(preview)) preview.opening = false }
    } else if (/^https?:\/\//i.test(preview.url) && !parseFileLinkUrl(preview.url)) {
      this.splitMode = true
      this.closeDocumentLinkPreview()
      this.openBrowserTab(preview.url, preview.side)
    }
  },
}
