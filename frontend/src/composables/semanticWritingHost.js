// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
export function createSemanticWritingHost({ projectId, fileId, title, writable, execute, send, api }) {
  let session = '', generation = 0, disposed = false, active = null, applying = false, generating = false, saving = false
  const stale = () => new Error('正文或光标已变化，请重新生成建议。')
  const current = seq => !disposed && seq === generation
  const cancel = id => id && Promise.resolve(api.cancel(projectId, id)).catch(() => {})
  function invalidate(state = 'STALE') {
    generation++; generating = false
    const old = active; active = null
    cancel(old?.id)
    if (!disposed && session) send({ __lo: 'lo-relay', type: 'writing-config', config: { session, semantic: { state } } })
  }
  function check(seq) { if (!current(seq)) throw stale() }
  async function generate(data) {
    if (saving) throw new Error('项目设置正在保存，请稍后生成。')
    if (data.composing) throw new Error('请先完成当前输入，再生成建议。')
    if (!['local', 'sentence', 'paragraph', 'rewrite'].includes(data.mode)) throw new Error('请选择建议类型。')
    const sourceFileIds = [...new Set(data.sourceFileIds || [])]
    if (sourceFileIds.length > 4 || sourceFileIds.some(id => !Number.isSafeInteger(id) || id <= 0)) throw new Error('最多选择四份项目材料。')
    invalidate('LOADING')
    const seq = generation
    generating = true
    try {
      const snapshot = await execute('capture_writing_context', {})
      check(seq)
      if (!snapshot?.success || !snapshot.available || !snapshot.token || snapshot.revision == null) throw new Error(snapshot?.message || '当前位置不能生成建议，请检查编辑权限及修订视图。')
      const selection = snapshot.selectedText || ''
      if (data.mode === 'rewrite' ? !selection : !!selection) throw new Error(data.mode === 'rewrite' ? '请先选中要改写的文字。' : '当前有选区，请选择“改写选区”或取消选区。')
      if (selection.length > 2000) throw new Error('请将改写选区缩短至 2000 字以内。')
      const paragraphs = [], coveredParagraphs = new Set()
      let start = 0, body = '', truncated = false
      for (let page = 0; page < 100 && body.length < 16000; page++) {
        const text = await execute('get_document_text', { startParagraph: start, maxParagraphs: 200, __agent: true })
        check(seq)
        if (!text?.success || String(text.revision) !== String(snapshot.revision) || !Array.isArray(text.paragraphs)) throw stale()
        let length = body.length
        for (const paragraph of text.paragraphs) {
          length += (paragraphs.length ? 1 : 0) + String(paragraph.text || '').length
          if (length <= 16000 && Number.isInteger(paragraph.index)) coveredParagraphs.add(paragraph.index)
          paragraphs.push(paragraph.text || '')
        }
        body = paragraphs.join('\n')
        truncated = body.length > 16000 || !!text.truncated
        if (!text.truncated || body.length >= 16000) break
        if (!Number.isInteger(text.nextStartParagraph) || text.nextStartParagraph <= start) break
        start = text.nextStartParagraph
      }
      const notices = truncated ? ['本次正文参考范围最多 16000 字；长文请结合当前章节与所选材料核对。'] : []
      const scopeKnown = snapshot.scopeKnown !== false && coveredParagraphs.has(snapshot.paragraphIndex)
      if (!scopeKnown) notices.push('当前位置未纳入可确认的正文范围，局部条款字段不会自动套用；请核对章节与材料。')
      const context = { fileId, revision: String(snapshot.revision), documentText: body.slice(0, 16000),
        before: String(snapshot.before || '').slice(-2000), after: String(snapshot.after || '').slice(0, 2000),
        selection, title: title || '', section: scopeKnown ? snapshot.sectionTitle || '' : '',
        documentType: ['auto', 'litigation', 'contract', 'diligence'].includes(data.documentType) ? data.documentType : 'auto', sourceFileIds }
      const view = await api.suggest(projectId, { mode: data.mode, context })
      if (!current(seq)) { cancel(view?.id); throw stale() }
      active = { id: view.id, snapshot, selection, notices, view, mode: data.mode }
      return { view, notices }
    } finally { if (current(seq)) generating = false }
  }
  return {
    bindSession(value) { session = value },
    async perform(action, data = {}) {
      if (disposed || !writable) throw new Error('当前文档不可编辑。')
      if (action === 'semantic-settings') {
        const settings = await api.settings(projectId)
        return { ...settings, files: (settings.files || []).filter(file => String(file.id) !== String(fileId)) }
      }
      if (action === 'semantic-save-settings') {
        if (saving) throw new Error('项目设置正在保存，请稍候。')
        saving = true; invalidate()
        try { return await api.saveSettings(projectId, { stance: data.stance || '', cutoffDate: data.cutoffDate || '' }) }
        finally { saving = false }
      }
      if (action === 'semantic-generate') return generate(data)
      if (action === 'semantic-cancel') { invalidate('CANCELLED'); return { cancelled: true } }
      if (!active) throw stale()
      const seq = generation, job = active
      if (action === 'semantic-poll') {
        const view = await api.get(projectId, job.id); check(seq); job.view = view
        return { view, notices: job.notices }
      }
      if (action === 'semantic-accept') {
        if (data.composing) throw new Error('请先完成当前输入，再采用建议。')
        if (!Number.isInteger(data.index) || data.index < 0 || job.view.status !== 'READY' || !job.view.advice?.[data.index]) throw new Error('请选择有效的建议。')
        const review = await execute('get_review_context', {}); check(seq)
        if (String(review?.revision) !== String(job.snapshot.revision)) throw stale()
        const result = await api.accept(projectId, job.id, { index: data.index, revision: String(job.snapshot.revision) }); check(seq)
        if (String(result.revision) !== String(job.snapshot.revision) || result.selection !== job.selection || typeof result.text !== 'string' || !result.text.trim()) throw stale()
        applying = true
        try {
          const applied = await execute('accept_writing_suggestion', { token: job.snapshot.token, revision: job.snapshot.revision, text: result.text, expectedSelection: job.selection, __agent: job.mode !== 'local' })
          if (!applied?.success) throw new Error(applied?.message || '未能插入，正文或光标可能已变化，请重新生成。')
          active = null; generation++
          return { applied: true, message: '已采用，可用 Ctrl+Z / ⌘Z 撤销。' }
        } finally { applying = false }
      }
      throw new Error('不支持的语义写作操作。')
    },
    modified() { if (!disposed && !applying && (active || generating)) invalidate() },
    destroy() { if (disposed) return; disposed = true; invalidate() },
  }
}
