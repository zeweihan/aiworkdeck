// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// DOCX 比对稿生成编排（#1120）：读两份原始字节 → 隐藏 LOWA 引擎生成修订稿并导出 →
// 还掉引擎 → 原子落盘为新的可编辑文档。
//
// 本文件是纯编排（依赖全部由调用方注入，node --test 直接跑，形制照 useDocumentMerge.js）：
// 字节下载 / 引擎实例 / 落盘 API / 文案 / 取消判定都从 project-overview 侧喂进来，
// 这里只锁顺序与不变式：
//   ① 引擎只在「读字节之后、落盘之前」借一段，finally 必还——且必须**还完才**允许
//     落盘/开件（结果编辑器不该再多背一个隐藏 WASM 实例）；
//   ② build_comparison_document 只有 success:true 才算成，export 出空字节按失败论；
//   ③ 每个异步边界之后都核对会话是否仍有效（取消/换组/切项目即作废，绝不落盘），
//     并重查两份源文档没有重新变脏——比对的是磁盘快照，不是编辑器里的新字。

// #1120 走新流程的判据：双方都是 .docx（fileType 或文件名后缀）。.doc 旧格式等
// 仍走原来的文本 diff 虚拟标签，不进引擎。
export function isDocxDoc(doc) {
  if (!doc) return false
  const type = String(doc.fileType || '').toLowerCase()
  if (type === 'docx') return true
  return type === '' ? /\.docx$/i.test(String(doc.name || '')) : false
}

export async function sha256Hex(bytes) {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

// deps: { source, target, name, authorName, t, onStage, isCancelled, fetchBytes,
//         recheckClean, acquireEngine, releaseEngine, saveFile }
// 返回 saveFile 落成的 ProjectFile；任何失败 throw（err.cancelled=true 表示用户作废）。
export async function runDocxComparison(deps) {
  const t = deps.t || ((k) => k)
  const cancelled = () => {
    const e = new Error('cancelled')
    e.cancelled = true
    throw e
  }
  const guard = () => { if (deps.isCancelled && deps.isCancelled()) cancelled() }

  const bytesOf = async (doc) => {
    const bytes = await deps.fetchBytes(doc)
    if (!bytes || !bytes.length) throw new Error(t('editor.compare.failEmpty', { name: (doc && doc.name) || '' }))
    return bytes
  }

  guard()
  if (deps.onStage) deps.onStage('reading')
  const baseBytes = await bytesOf(deps.source)
  guard()
  const revisedBytes = await bytesOf(deps.target)
  guard()
  if (deps.recheckClean) deps.recheckClean()
  const baseSha256 = await sha256Hex(baseBytes)
  const revisedSha256 = await sha256Hex(revisedBytes)

  guard()
  if (deps.onStage) deps.onStage('comparing')
  const handle = await deps.acquireEngine()
  if (!handle) throw new Error(t('editor.compare.failEngine'))
  let outBytes
  try {
    guard()
    const run = async (action, payload) => {
      let result
      try {
        result = await (typeof handle.run === 'function'
          ? handle.run(action, payload) : handle.executeCommand(action, payload))
      } catch (e) {
        if (e && e.code === 'EDITOR_RESULT_TIMEOUT') throw new Error(t('editor.compare.failTimeout'))
        throw e
      }
      if (result && result.code === 'EDITOR_RESULT_TIMEOUT') throw new Error(t('editor.compare.failTimeout'))
      return result
    }
    const built = await run('build_comparison_document', {
      baseBytes, revisedBytes, name: deps.name, authorName: deps.authorName || '',
    })
    if (!built || built.success !== true) throw new Error(t('editor.compare.failBuild'))
    guard()
    const exported = await run('export_document', { name: deps.name })
    const bytes = exported && (exported.bytes || exported.data)
    if (!exported || exported.success === false || !bytes || !(bytes.byteLength || bytes.length)) {
      throw new Error(t('editor.compare.failExport'))
    }
    // Relay normally preserves Uint8Array; accept ArrayBuffer/array without corrupting Blob data.
    outBytes = bytes instanceof Uint8Array ? bytes
      : bytes instanceof ArrayBuffer ? new Uint8Array(bytes)
        : Array.isArray(bytes) ? Uint8Array.from(bytes) : null
    if (!outBytes || !outBytes.byteLength) throw new Error(t('editor.compare.failExport'))
  } finally {
    try { deps.releaseEngine(handle) } catch (e) { console.warn('[DocxCompare] 归还隐藏引擎失败', e) }
  }
  // 引擎已还。落盘之前再核对一次会话与源文档状态。
  guard()
  if (deps.recheckClean) deps.recheckClean()

  if (deps.onStage) deps.onStage('saving')
  const created = await deps.saveFile({
    bytes: outBytes, name: deps.name,
    baseFileId: deps.source.id, revisedFileId: deps.target.id,
    baseSha256, revisedSha256,
  })
  if (!created || created.id == null) throw new Error(t('editor.compare.failSave'))
  return created
}
