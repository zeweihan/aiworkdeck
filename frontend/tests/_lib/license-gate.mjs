// 解锁门的测试起点（2026-08「官方版必须账户登录」之后收进共享模块）。
//
// 这段逻辑此前在 desktop-e2e / feedback-e2e / meeting-e2e 里逐字抄了三份，都是
// 「后端没解锁就拿 README 那枚公开试用码激活，激活失败就硬退出」。发版默认值
// （security.license.trial-code.enabled=false）关掉试用码之后那条路走不通，
// 三套会一起死在 setup 上，而报错只有一句「试用码解锁失败」——看不出该怎么办，
// 也看不出这是环境问题还是产品回归。抄三份的东西改起来必漏，故收口到这里。
//
// 契约：调用方传自己的 api(ep, opts) —— 三套各自维护 BACKEND 与会话头，形状一致。

/** README 曾公开发布的通用试用码（Ed25519 离线验签）。官方版已不再受理，仅用于 fork / 旧后端。 */
export const PUBLIC_TRIAL_CODE = process.env.APP_E2E_TRIAL_CODE
  || 'AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK'

const SEED_RECIPE = `
后端已关闭试用码解锁（security.license.trial-code.enabled=false，这是发版默认值），
且当前处于未解锁状态。本套件没有任何办法把它解锁——唯一的路是账户凭据，要真实手机号与官网。

两种正确起点，二选一：

  1) 指向一个已解锁的后端（常驻桌面后端通常已连账户），设 *_BACKEND 环境变量；

  2) 冷启动的隔离后端：往它的 user.home 播一份**存量 trial 票据**，
     这是真实存在的过渡期状态，不是绕过闸：

       mkdir -p "$HOME_E2E/.aiworkdeck"
       cat > "$HOME_E2E/.aiworkdeck/license.json" <<'EOF'
       { "mode":"trial", "code":"AWD-T-SEEDED-FOR-E2E",
         "activatedAt":"2026-08-18T00:00:00Z", "lastVerifiedAt":"2026-08-18T00:00:00Z" }
       EOF
       chmod 600 "$HOME_E2E/.aiworkdeck/license.json"

     只要今天早于 application-desktop.yml 里的 legacy-grace-until，后端即为已解锁。

**不要改用 -Dsecurity.license.trial-code.enabled=true 来解锁。**
那会让发版默认值反而没有任何一套 e2e 覆盖到，等于把闸变成摆设。`.trim()

/**
 * 保证后端处于已解锁状态，供各 e2e 套件在 setup 阶段调用。
 *
 * - 已解锁 → 原样返回 status；
 * - 未解锁且试用码仍开着（fork / 本地构建 / 旧后端）→ 用公开试用码激活，行为与改造前一致；
 * - 未解锁且试用码已关（发版默认值）→ 抛出带处置办法的错误，绝不静默继续。
 *
 * @param {(ep: string, opts?: object) => Promise<any>} api 调用方自己的 REST helper
 * @returns {Promise<object>} 解锁后的 /api/license/status
 */
export async function ensureUnlocked(api) {
  const lic = await api('/api/license/status')
  if (lic && lic.unlocked) return lic

  if (lic && lic.trialCodeEnabled === false) {
    throw new Error(SEED_RECIPE)
  }

  const act = await api('/api/license/activate', { method: 'POST', body: { code: PUBLIC_TRIAL_CODE } })
  if (!act || act.unlocked !== true) {
    throw new Error('试用码解锁失败: ' + JSON.stringify(act).slice(0, 200))
  }
  return await api('/api/license/status')
}
