// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

const NUMBER = '[零〇一二三四五六七八九十百千万两\\d]+'
const ARTICLE = `第${NUMBER}条(?:之${NUMBER})?(?:第${NUMBER}款)?(?:第${NUMBER}项)?`
const ARTICLE_ONLY = new RegExp(`^${ARTICLE}$`, 'u')
const ARTICLE_END = new RegExp(`${ARTICLE}$`, 'u')
const CASE_NUMBER = /^\((?:19|20)\d{2}\)(?:最高法|[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼兵军][\d]{0,4})(?:民初|民终|民申|民再|民特|民撤|刑初|刑终|刑申|刑再|行初|行终|行申|行再|执|执恢|执异|执复|执监|执保|财保|证保|破|破申|清申|强清)\d+号$/u
const COMPANY = /^[\p{Script=Han}A-Za-z\d·()]{2,60}(?:有限责任公司|股份有限公司|有限公司|股份公司|集团公司|总公司|合伙企业)(?:\((?:有限合伙|普通合伙|特殊普通合伙)\))?(?:[\p{Script=Han}A-Za-z\d]{0,20}分公司)?$/u
const COMPANY_ENDINGS = /有限责任公司|股份有限公司|有限公司|股份公司|集团公司|总公司|合伙企业/gu
const COMMON_LAWS = new Set([
  '宪法', '民法典', '刑法', '公司法', '民事诉讼法', '刑事诉讼法', '行政诉讼法',
  '劳动法', '劳动合同法', '合同法', '物权法', '担保法', '婚姻法', '继承法',
  '证券法', '保险法', '票据法', '企业破产法', '仲裁法', '行政处罚法', '行政许可法',
  '行政复议法', '国家赔偿法', '著作权法', '商标法', '专利法', '反垄断法',
  '反不正当竞争法', '消费者权益保护法', '个人信息保护法', '数据安全法', '网络安全法',
])
const LAW_TITLE = /^[\p{Script=Han}]{2,60}(?:法|法典|条例|规定|办法|细则|解释)(?:\([一二三四五六七八九十\d]+\))?$/u
const OFFICIAL_TITLE = /^(?:中华人民共和国|最高人民法院|最高人民检察院|国务院|[\p{Script=Han}]{2,10}(?:省|市|自治区))/u
const PROSE_PREFIX = /^(?:请|我|我们|根据|依据|按照|依照|适用|违反|遵守|查询|查找|本案|本合同|本协议|甲方|乙方|原告|被告|申请人|被申请人|该公司|这家|那家|由|与)/u

/** Suggest one lookup only for a whole, recognizable selection. Never calls a service. */
export function classifySelectionLookup(text) {
  if (typeof text !== 'string' || text.length > 200) return null
  if (/[\r\n\u2028\u2029]/u.test(text.trim())) return null
  const value = text.normalize('NFKC').replace(/\s+/gu, '')
  if (!value || PROSE_PREFIX.test(value)) return null
  if (CASE_NUMBER.test(value)) return 'CASE'
  if (ARTICLE_ONLY.test(value)) return 'LAW'

  const title = value.replace(ARTICLE_END, '')
  const quoted = /^《[^《》]+》$/u.test(title)
  const law = quoted ? title.slice(1, -1) : title
  const lawBase = law.replace(/(?:实施条例|实施细则)$/u, '')
  if (COMMON_LAWS.has(lawBase) || (LAW_TITLE.test(law) && (quoted || OFFICIAL_TITLE.test(law)))) {
    return 'LAW'
  }

  // Multiple company names and institution names do not express a single company lookup.
  if (!value.includes('与') && COMPANY.test(value) && (value.match(COMPANY_ENDINGS) || []).length === 1) return 'COMPANY'
  return null
}
