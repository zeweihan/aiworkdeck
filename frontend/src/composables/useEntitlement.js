// 功能权益（entitlement）前端出口。
//
// 单一数据源是后端 GET /api/entitlements（本地票据 ∪ 账户同步结果，合并逻辑在后端
// EntitlementService）。前端这层只做两件事：模块级缓存（整个应用只拉一次，多个组件
// 共享同一份 ref 与同一个在途请求），以及一个稳定的 isEnabled(feature) 出口。
//
// 用法：
//   import { useEntitlement, FEATURES } from '@/composables/useEntitlement.js'
//   const { isEnabled, refresh } = useEntitlement()
//   if (!isEnabled(FEATURES.CLIPBOARD_UNLIMITED)) { ...走免费额度... }
// 也可以传功能名拿到该功能的响应式布尔：
//   const { enabled } = useEntitlement(FEATURES.STAGE_UNLIMITED)
//
// 账户连接状态变化后（设置页连接/断开）必须调 refresh(true) 让缓存失效。
import { computed, ref } from 'vue'
import { getEntitlements } from '@/services/api.js'

// 功能目录：与后端 FeatureCatalog、官网 entitlements.feature 命名一一对应。
// 新增 SKU 时三处同步改（桌面后端 / 官网 / 这里），否则会出现只有一边认得的权益名。
export const FEATURES = {
  APP_UNLOCKED: 'app.unlocked',
  CLIPBOARD_UNLIMITED: 'clipboard.unlimited',
  STAGE_UNLIMITED: 'stage.unlimited',
}

// 模块级单例状态（跨组件共享，页面栈多实例也只有一份）
const features = ref([])
const loaded = ref(false)
let inflight = null

// 后端可能返回字符串数组，也可能返回 { feature, purchasedAt, ... } 对象数组
// （官网侧 entitlements 就是对象形态），两种都归一成字符串数组。
function normalize(list) {
  if (!Array.isArray(list)) return []
  return list
    .map((it) => (typeof it === 'string' ? it : it && it.feature))
    .filter(Boolean)
}

/**
 * 拉取权益清单。默认命中缓存直接返回；force=true 强制重取。
 * @param {boolean} force 是否绕过缓存
 * @returns {Promise<string[]>}
 */
export function refreshEntitlements(force = false) {
  if (!force) {
    if (inflight) return inflight
    if (loaded.value) return Promise.resolve(features.value)
  }
  const p = (async () => {
    try {
      // force 时让后端先同步一次官网：用户刚连接账户、或刚在官网买完回来，
      // 只重读后端本地缓存是拿不到新权益的
      const res = await getEntitlements(force)
      features.value = normalize(res && res.features)
      // loaded 只在成功时置位。失败也置位会把「后端还没起来」那一次的空结果
      // 变成整个进程生命周期的「没有任何付费功能」，只有显式 refresh(true) 能救
      loaded.value = true
    } catch (e) {
      // 未连接账户、旧后端没有该端点、离线：一律视为无附加权益。
      // 这里不弹提示——权益缺失的引导由 UnlockHint 在具体功能处给，
      // 在这里报错会变成打开任意页面都弹窗。
      features.value = []
    } finally {
      if (inflight === p) inflight = null
    }
    return features.value
  })()
  inflight = p
  return p
}

/**
 * 某项功能是否已解锁。读的是响应式 features，可直接用在 computed / 模板里。
 * @param {string} feature FEATURES 中的功能名
 * @returns {boolean}
 */
export function isEnabled(feature) {
  return !!feature && features.value.includes(feature)
}

/**
 * @param {string} [feature] 可选：传入后额外返回该功能的响应式布尔 enabled
 */
export function useEntitlement(feature) {
  // 惰性首拉：第一个用到权益的组件触发，后续组件命中缓存
  if (!loaded.value && !inflight) refreshEntitlements()
  return {
    features,
    loaded,
    isEnabled,
    refresh: refreshEntitlements,
    enabled: computed(() => isEnabled(feature)),
  }
}
