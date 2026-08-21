// Web 插件 SDK evidence.* 三方法的宿主端纯函数（PluginPane.vue 调用）。
// 不碰 Vue / uni / 网络，只依赖传入的 executor，方便在 node --test 里直接跑。

/** Crockford base32 字母表（无 I L O U），与后端 Ulid.java 同一口径 */
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/**
 * 生成一条 linkKey：EVID_ + 26 位 ULID（10 位时间 + 16 位随机）。
 * 书签名只许 [A-Za-z0-9_]，ULID 天然满足。宿主端先有 key 才能先打书签再落库，
 * 所以不能等后端生成。
 */
export function newLinkKey(now = Date.now(), random = defaultRandom) {
  let t = now
  let time = ''
  for (let i = 0; i < 10; i++) {
    time = CROCKFORD[t % 32] + time
    t = Math.floor(t / 32)
  }
  const bytes = random(16)
  let rand = ''
  for (let i = 0; i < 16; i++) rand += CROCKFORD[bytes[i] % 32]
  return 'EVID_' + time + rand
}

function defaultRandom(n) {
  const out = new Uint8Array(n)
  const c = typeof globalThis !== 'undefined' && globalThis.crypto
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(out)
  else for (let i = 0; i < n; i++) out[i] = Math.floor(Math.random() * 256)
  return out
}

/**
 * 把插件给的 anchor 解析成可打书签的目标。
 * @param {(action: string, params: object) => Promise<any>} exec 编辑器 executor
 * @param {{selection?: boolean, quote?: string}} anchor
 * @returns {Promise<{mode:'selection', text:string} | {mode:'quote', anchorId:string, text:string} | {error:{code:string,message:string}}>}
 */
export async function resolveAnchor(exec, anchor) {
  if (anchor && anchor.selection === true) {
    const cur = await exec('get_selection_hyperlink', {})
    const text = cur && cur.success ? String(cur.text || '').trim() : ''
    if (!text) return { error: { code: 'no_selection', message: '编辑器当前没有选区' } }
    return { mode: 'selection', text }
  }
  if (anchor && typeof anchor.quote === 'string' && anchor.quote.trim()) {
    const quote = anchor.quote.trim()
    // office_thread.find_text_locations：入参 keyword，命中项带 anchorId（书签 id，喂给 set_selection）
    const r = await exec('find_text_locations', { keyword: quote })
    const matches = r && r.success && Array.isArray(r.matches) ? r.matches : []
    const n = matches.length
    if (n !== 1) {
      return { error: { code: 'anchor_ambiguous', message: n === 0 ? '引文未命中：' + quote : '引文命中 ' + n + ' 处，请加长引文' } }
    }
    if (!matches[0].anchorId) return { error: { code: 'anchor_ambiguous', message: '引文命中但无法定位' } }
    return { mode: 'quote', anchorId: String(matches[0].anchorId), text: String(matches[0].text || quote) }
  }
  return { error: { code: 'anchor_ambiguous', message: 'anchor 需为 { selection: true } 或 { quote }' } }
}

/**
 * 后端 LinkView -> SDK 契约的 link 形状（fileId 反查成项目内相对路径）。
 * @param {object} link 后端 LinkView
 * @param {Map<number|string, string>} pathById fileId -> path
 */
export function toPluginLink(link, pathById) {
  const pathOf = (id) => (id == null ? '' : (pathById.get(id) || pathById.get(String(id)) || ''))
  return {
    linkKey: link.linkKey,
    docPath: pathOf(link.docFileId),
    anchorText: link.anchorText || '',
    sectionPath: link.sectionPath || '',
    status: link.status || '',
    targets: (Array.isArray(link.targets) ? link.targets : []).map(t => ({
      targetId: t.id,
      path: pathOf(t.fileId),
      locator: t.locator == null ? null : t.locator,
      relation: t.relation || '',
      method: t.method || ''
    }))
  }
}

/**
 * 插件给的 targets（按 path）-> 后端 TargetInput（按 fileId）。
 * 任一 path 不存在返回 { error: not_found }；空数组也算 not_found（后端本来就拒绝零 target）。
 * @param {Array<{path:string, locator?:object, relation?:string, method?:string, note?:string}>} targets
 * @param {Map<string, number>} idByPath
 */
export function toTargetInputs(targets, idByPath) {
  const list = Array.isArray(targets) ? targets : []
  if (!list.length) return { error: { code: 'not_found', message: 'targets 为空，至少关联一个底稿' } }
  const out = []
  for (const t of list) {
    const path = t && t.path != null ? String(t.path) : ''
    const fileId = idByPath.get(path)
    if (!fileId) return { error: { code: 'not_found', message: '文件不存在：' + path } }
    out.push({
      fileId,
      locatorJson: t.locator == null ? null : JSON.stringify(t.locator),
      relation: t.relation == null ? null : String(t.relation),
      method: t.method == null ? null : String(t.method),
      note: t.note == null ? null : String(t.note)
    })
  }
  return { targets: out }
}
