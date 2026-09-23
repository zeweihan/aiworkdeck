// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 解锁页「切换地区时界面语言随动」的纯判定（dev-board#864）。零依赖，node 直接导入测试
 * （tests/unlock/site-language.test.mjs）；接线在 pages/unlock/unlock.vue。
 *
 * 规则：国际站默认英文、大陆站默认中文，**两个方向都随动**；用户亲手选过界面语言
 * （设置页、应用菜单、解锁页底部「中文 · English」都算，见 appLanguage.isLanguageManuallyChosen）
 * 就一律尊重，不再替他改。
 */

const SITE_LANGUAGE = { intl: 'en-US', cn: 'zh-CN' }

/** 站点的默认界面语言；未知站点回空串（不表态）。 */
export function languageForSite(siteId) {
  return SITE_LANGUAGE[siteId] || ''
}

/**
 * 切到 siteId 之后要切成的语言；不需要切时回空串。
 * @param {{ siteId: string, current: string, manual: boolean }} p
 */
export function siteLanguageToApply({ siteId, current, manual }) {
  if (manual) return ''
  const want = languageForSite(siteId)
  if (!want || want === current) return ''
  return want
}
