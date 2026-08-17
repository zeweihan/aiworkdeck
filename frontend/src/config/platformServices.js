/**
 * 平台服务在界面上的展示元数据（首启向导步骤 2 与系统管理「平台服务」分区共用）。
 *
 * **权威源是后端**：`GET /api/platform-services` 回的 `service` / `provider` /
 * `hasLocal` / `hasByokCredentials` 才是真相，本表只补「界面上叫什么、干什么用、
 * 自备 Key 时要填哪几个字段」。展示当前档位**一律读接口的 provider**，
 * 绝不自己按凭证是否为空去猜——那两件事在存量机器上经常对不上
 * （回填规则见 licensing-billing.md 地雷 25）。
 *
 * 后端加了一项而这里没加：`platformServiceMeta()` 回一个以 key 原样显示的兜底项，
 * 那一项照样出现在界面上。宁可丑，也不要静默少掉一整项服务。
 *
 * AI 不在这张表里：它走「凭证下发 + 本机直连所选供应商」那条通路，不经网关
 * （设计 §3 通路 A）。界面上单列一行说明，指向「AI 功能设置」。
 */

/**
 * 本地档是否真的能用。
 *
 * 后端的 `hasLocal` 说的是「这个服务在模型上有本地档这个位置」，
 * 不等于「本地引擎已经随包发出去了」。asr 的本地引擎（asr-service）在 P3 才落地，
 * 在那之前把本地档摆成可选，就是把律师推回他主动规避掉的合规风险里：
 * 他为保密才开这一档，录完两小时才发现转不了，只剩「放弃录音」或「传上云」两条路。
 *
 * P3 合入时把 asr 改成 true，并按设计 §6.2.1 补上「切换时就地探一次 + 下载模型」的探测。
 */
const LOCAL_TIER_READY = {
  asr: false,
}

export const PLATFORM_SERVICES = [
  { key: 'asr', nameKey: 'platform.svcAsrName', descKey: 'platform.svcAsrDesc' },
  { key: 'ocr', nameKey: 'platform.svcOcrName', descKey: 'platform.svcOcrDesc' },
  { key: 'search', nameKey: 'platform.svcSearchName', descKey: 'platform.svcSearchDesc' },
  { key: 'qichacha', nameKey: 'platform.svcQichachaName', descKey: 'platform.svcQichachaDesc' },
  { key: 'tushare', nameKey: 'platform.svcTushareName', descKey: 'platform.svcTushareDesc' },
  { key: 'pkulaw', nameKey: 'platform.svcPkulawName', descKey: 'platform.svcPkulawDesc' },
]

/** 后端出现了本表没有的服务时的兜底：以 key 原样显示，不吞掉它。 */
export function platformServiceMeta(key) {
  return PLATFORM_SERVICES.find((s) => s.key === key) || { key, nameKey: '', descKey: '' }
}

/** 该服务的本地档现在能不能真正用起来（`hasLocal` 为真只是「模型上有这个位置」）。 */
export function localTierReady(key) {
  return LOCAL_TIER_READY[key] === true
}

/** 后端服务清单的稳定排序：本表里的先按本表顺序，本表没有的排在后面。 */
export function sortPlatformServices(list) {
  const order = PLATFORM_SERVICES.map((s) => s.key)
  return [...(list || [])].sort((a, b) => {
    const ia = order.indexOf(a.service)
    const ib = order.indexOf(b.service)
    return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib)
  })
}
