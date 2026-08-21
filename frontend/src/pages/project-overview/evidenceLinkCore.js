// EvidenceLink 拖放建链的纯函数层：不碰 api.js / uni / this，exec 与 api 都由调用方注入。
// 相对路径 import（不用 @/ 别名）是为了 node --test 能直接加载（tests/evidence/evidenceLinkActions.test.mjs）。
import { ulid } from '../../utils/ulid.js'
import { parseFileLinkUrl, buildFileLinkUrl } from '../../utils/evidenceLocator.js'

function isNotFound(e) {
  if (!e) return false
  if (e.status === 404 || e.statusCode === 404) return true
  return /not[ _-]?found|不存在/i.test(String(e.message || ''))
}

// 返回 { ok, reason?, message?, linkKey?, view?, targetId?, created?, recovered? }。
// - 无选区 → no_selection；bookmark/hyperlink 失败不调 api
// - 选区已带 filelink?k= → 复用 linkKey 只 addEvidenceTargets；若后端说该 link 不存在（此前建链时
//   书签+超链接已写入、入库却失败留下的死锚点），回退 createEvidenceLink 带既有 linkKey（书签已在，不再
//   bookmark_selection），recovered:true
export async function createEvidenceLinkForDrop({ exec, api, projectId, docFileId, file, internalBase }) {
  let cur = null
  try { cur = await exec('get_selection_hyperlink', {}) } catch (e) { cur = null }
  const selText = cur && cur.success ? String(cur.text || '').trim() : ''
  if (!selText) return { ok: false, reason: 'no_selection' }

  let linkKey = ''
  const parsed = parseFileLinkUrl(cur.url || '')
  if (parsed && parsed.linkKey) linkKey = parsed.linkKey

  let created = false
  if (!linkKey) {
    linkKey = 'EVID_' + ulid()
    const bm = await exec('bookmark_selection', { name: linkKey })
    if (!bm || !bm.success) return { ok: false, reason: 'bookmark_failed', message: (bm && (bm.error || bm.message)) || '' }
    const url = buildFileLinkUrl(internalBase, linkKey, projectId)
    const r = await exec('set_selection_hyperlink', { url })
    if (!r || !r.success) return { ok: false, reason: 'hyperlink_failed', message: (r && (r.error || r.message)) || '' }
    created = true
  }

  let ctx = null
  try { ctx = await exec('get_bookmark_context', { name: linkKey }) } catch (e) { ctx = null }
  const target = { fileId: Number(file.id), relation: 'supports', method: 'written_review' }
  const createBody = {
    docFileId, linkKey, anchorText: selText,
    sectionPath: (ctx && ctx.sectionPath) || '', sectionTitle: (ctx && ctx.sectionTitle) || '',
    createdByKind: 'human', targets: [target],
  }
  let view
  let recovered = false
  if (created) {
    view = await api.createEvidenceLink(projectId, createBody)
  } else {
    try {
      view = await api.addEvidenceTargets(projectId, linkKey, [target])
    } catch (e) {
      if (!isNotFound(e)) throw e
      view = await api.createEvidenceLink(projectId, createBody)
      recovered = true
    }
  }
  const targets = (view && Array.isArray(view.targets)) ? view.targets : []
  // 同一文件可能已挂过：取该 fileId 下 id 最大的那条（刚追加的）
  const mine = targets.filter((x) => Number(x.fileId) === Number(file.id))
  const tgt = mine.length ? mine.reduce((a, b) => (Number(b.id) > Number(a.id) ? b : a)) : null
  return { ok: true, linkKey, view, created, recovered, targetId: tgt ? tgt.id : null }
}

// 点击链接后的 target 挑选：t 命中 → 那条；单 target → 它；多条 → null（交给弹窗）。
export function pickEvidenceTarget(view, targetId) {
  const targets = (view && Array.isArray(view.targets)) ? view.targets : []
  if (targetId != null) {
    const hit = targets.find((x) => Number(x.id) === Number(targetId))
    if (hit) return hit
  }
  if (targets.length === 1) return targets[0]
  return null
}
