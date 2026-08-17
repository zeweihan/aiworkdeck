import { getSiteStatus } from '@/services/api.js'

/**
 * 官网链接的唯一出口（双主站）。
 *
 * 在此之前，`https://www.aiworkdeck.com` 硬编码散落在 7 个文件里；接了国际站之后，
 * 用国内站地址去引导一个国际站用户，等于把他送到一个没有他账户的地方。
 *
 * 取值走 `GET /api/site`（后端按 ~/.aiworkdeck/site.json 解析），带内存缓存 +
 * 兜底常量：这些链接全都出现在「点一下跳出去」的路径上，为了它去阻塞渲染不值得，
 * 所以首帧可能用兜底值、拿到真值后自动纠正。
 *
 * 设计文档：docs/superpowers/specs/2026-08-08-dual-site-architecture.md §2.7
 */

/** 后端不可达时的兜底。与 application.yml 的 ai.account.sites.cn 保持一致。 */
const FALLBACK = {
  current: 'cn',
  baseUrl: 'https://www.aiworkdeck.com',
  accountPageUrl: 'https://www.aiworkdeck.com/zh/account',
  displayName: 'AI WorkDeck',
}

let cached = null
let inflight = null

function normalize(status) {
  const sites = (status && status.sites) || []
  const current = sites.find((s) => s.id === (status && status.current)) || sites[0]
  if (!current) return { ...FALLBACK }
  return {
    current: current.id,
    baseUrl: current.baseUrl || FALLBACK.baseUrl,
    accountPageUrl: current.accountPageUrl || current.baseUrl || FALLBACK.accountPageUrl,
    displayName: current.displayName || FALLBACK.displayName,
  }
}

/** 异步取当前站点链接；失败回落兜底常量，绝不抛。 */
export function loadSiteLinks() {
  if (cached) return Promise.resolve(cached)
  if (inflight) return inflight
  inflight = getSiteStatus()
    .then((status) => {
      cached = normalize(status)
      return cached
    })
    .catch(() => ({ ...FALLBACK }))
    .finally(() => {
      inflight = null
    })
  return inflight
}

/**
 * 同步取，供模板与不便 await 的地方使用。
 * 未取到真值时返回兜底并**顺手触发一次异步加载**，下次调用就准了。
 */
export function siteLinks() {
  if (cached) return cached
  loadSiteLinks()
  return { ...FALLBACK }
}

/** 官网首页。 */
export function siteBaseUrl() {
  return siteLinks().baseUrl
}

/** 官网账户页（「前往官网充值 / 分配额度 / 生成 Key」都指这里）。 */
export function accountPageUrl() {
  return siteLinks().accountPageUrl
}

/** 切站后必须让下一次读取重新拉取。 */
export function resetSiteLinks() {
  cached = null
}
