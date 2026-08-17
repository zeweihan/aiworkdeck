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

import { reactive } from 'vue'
import { probeLocalAsr } from '@/services/api.js'

/**
 * 本地档是否真的能用——**admin 档位下拉与会议面板开关共用的唯一出口**。
 *
 * 后端的 `hasLocal` 说的是「这个服务在模型上有本地档这个位置」，
 * 不等于「本地引擎在这台机器上现在能跑」。摆一个能选中、真用起来才炸的档位，
 * 正是设计 §6.2.1 点名要避免的事：律师为保密才开这一档，
 * 录完两小时才发现转不了，只剩「放弃录音」或「传上云」两条路，后者与他的目的正好相反。
 *
 * 今天只有 `asr` 一项：语音合成随 D7 去掉了档位（云端 ElevenLabs 下线，只剩本机 Kokoro，
 * 没有可选的东西了）。asr 的模型 1.5GB 不进包，**必须探**（`GET /api/asr/local/probe`）。
 *   初值 false 是保守起点：没探过就不摆这一档，宁可少一个选项也不给一个会炸的选项。
 *
 * 用 `reactive` 而不是普通对象：两个界面都是在 computed 里读它的，
 * 探测是异步的，非响应式对象改了不会触发重算——那样探测结果只在下次进页面才生效。
 */
const LOCAL_TIER_READY = reactive({
  asr: false,
})

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

/**
 * 最近一次本机转写探测的原文（`{status, model, diarization, message, nextStep}`），
 * 未探过时为 null。界面用它渲染「下一步该做什么」——
 * `MODEL_MISSING` 要给下载入口，`SERVICE_DOWN` 要给重启指路，两者不能合并。
 */
const localAsrProbe = reactive({ result: null })

export function localAsrProbeResult() {
  return localAsrProbe.result
}

/**
 * 探一次本机转写并把结论写回上面那张唯一的就绪表。
 *
 * <b>写入口和读出口放在同一个文件里是有意的</b>：admin 的档位下拉与会议面板的开关
 * 共用 `localTierReady`，只要有一处自己缓存一份判断，另一处就能把用户切进一个用不了的档，
 * 而他会在录完之后才发现。探测失败一律按「没就绪」处理——宁可少一个选项，
 * 也不给一个真用起来才炸的选项。
 */
export async function refreshLocalAsrReadiness() {
  try {
    const r = await probeLocalAsr()
    localAsrProbe.result = r || null
    LOCAL_TIER_READY.asr = !!r && r.status === 'READY'
  } catch (e) {
    localAsrProbe.result = null
    LOCAL_TIER_READY.asr = false
  }
  return localAsrProbe.result
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
