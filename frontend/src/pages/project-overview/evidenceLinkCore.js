// EvidenceLink 拖放建链的纯函数层：不碰 api.js / uni / this，exec 与 api 都由调用方注入。
// 相对路径 import（不用 @/ 别名）是为了 node --test 能直接加载（tests/evidence/evidenceLinkActions.test.mjs）。
import { ulid } from '../../utils/ulid.js'
import { parseFileLinkUrl, buildFileLinkUrl } from '../../utils/evidenceLocator.js'

function isNotFound(e) {
  if (!e) return false
  if (e.status === 404 || e.statusCode === 404) return true
  return /not[ _-]?found|不存在/i.test(String(e.message || ''))
}

// 拖放建链：targets 固定为一条 {fileId, supports, written_review}，createdByKind=human。
// 返回值在 createEvidenceLinkForSelection 之上多一个 targetId（该 fileId 下刚追加的那条）。
export async function createEvidenceLinkForDrop({ exec, api, projectId, docFileId, file, internalBase }) {
  const target = { fileId: Number(file.id), relation: 'supports', method: 'written_review' }
  const res = await createEvidenceLinkForSelection({ exec, api, projectId, docFileId, internalBase, targets: [target], createdByKind: 'human' })
  if (!res.ok) return res
  const targets = (res.view && Array.isArray(res.view.targets)) ? res.view.targets : []
  // 同一文件可能已挂过：取该 fileId 下 id 最大的那条（刚追加的）
  const mine = targets.filter((x) => Number(x.fileId) === Number(file.id))
  const tgt = mine.length ? mine.reduce((a, b) => (Number(b.id) > Number(a.id) ? b : a)) : null
  return { ...res, targetId: tgt ? tgt.id : null }
}

// 在当前选区上建链的唯一流程（拖放与 Web 插件 SDK evidence.link 共用，别再维护第二份）。
// 返回 { ok, reason?, message?, linkKey?, view?, created?, recovered?, selText? }。
// - 无选区 → no_selection；bookmark/hyperlink 失败不调 api
// - 选区已带 filelink?k= → 复用 linkKey 只 addEvidenceTargets；若后端说该 link 不存在（此前建链时
//   书签+超链接已写入、入库却失败留下的死锚点），回退 createEvidenceLink 带既有 linkKey（书签已在，不再
//   bookmark_selection），recovered:true
// - 否则 EVID_<ulid> → bookmark_selection → set_selection_hyperlink（书签是锚点、超链接只是跳转用，
//   两者必须成对，否则文档里点不到、再拖一次也走不进复用分支）→ get_bookmark_context → createEvidenceLink
export async function createEvidenceLinkForSelection({ exec, api, projectId, docFileId, internalBase, targets, createdByKind = 'human' }) {
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
  const createBody = {
    docFileId, linkKey, anchorText: selText,
    sectionPath: (ctx && ctx.sectionPath) || '', sectionTitle: (ctx && ctx.sectionTitle) || '',
    createdByKind, targets,
  }
  let view
  let recovered = false
  if (created) {
    view = await api.createEvidenceLink(projectId, createBody)
  } else {
    try {
      view = await api.addEvidenceTargets(projectId, linkKey, targets)
    } catch (e) {
      if (!isNotFound(e)) throw e
      view = await api.createEvidenceLink(projectId, createBody)
      recovered = true
    }
  }
  return { ok: true, linkKey, view, created, recovered, selText }
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
