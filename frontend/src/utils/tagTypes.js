// tagTypes.js — 标签类型维度共享 helper（dev-board#63，见
// docs/superpowers/specs/2026-08-20-file-party-issue-tags-design.md）。
//
// 后端 Tag.type 是 "NORMAL"/"PARTY"/"ISSUE"，可空、无 DB 默认值：存量行全部为 null。
// 前端所有类型判断一律走 normalizeTagType()，不要自己写 tag.type === 'PARTY'，
// 否则漏判 null/undefined 会把存量标签算成「无类型」而不是「普通标签」。

export const TAG_TYPE_NORMAL = 'NORMAL'
export const TAG_TYPE_PARTY = 'PARTY'
export const TAG_TYPE_ISSUE = 'ISSUE'

// 展示顺序：当事人 / 争议焦点 / 普通标签——TagSelector、SearchPanel 的分组顺序都按此来
export const TAG_TYPE_ORDER = [TAG_TYPE_PARTY, TAG_TYPE_ISSUE, TAG_TYPE_NORMAL]

// null/undefined 视同 NORMAL（存量行口径，零迁移）
export function normalizeTagType(tag) {
  const type = tag && tag.type
  return type === TAG_TYPE_PARTY || type === TAG_TYPE_ISSUE ? type : TAG_TYPE_NORMAL
}

// 建标签时的默认色系（spec 定案；颜色之后仍可在标签管理里改，这里只管新建默认值）
export const TAG_TYPE_DEFAULT_COLORS = {
  [TAG_TYPE_PARTY]: '#B45309',
  [TAG_TYPE_ISSUE]: '#9B1C31',
  [TAG_TYPE_NORMAL]: '#3B82F6'
}

// 类型名走 locale 文件（EN 版红线），这里只维护「类型 → i18n key」的映射，
// 组件里用 $t(TAG_TYPE_I18N_KEYS[type]) 取当前语言下的显示名。
export const TAG_TYPE_I18N_KEYS = {
  [TAG_TYPE_PARTY]: 'files.tagTypeParty',
  [TAG_TYPE_ISSUE]: 'files.tagTypeIssue',
  [TAG_TYPE_NORMAL]: 'files.tagTypeNormal'
}

// 按类型分组，供 TagSelector/SearchPanel 的三段式分组渲染共用一份口径，
// 组内不排序（排序规则各消费方不同：TagSelector 是原顺序，SearchPanel 另有命中数排序）。
export function groupTagsByType(tags) {
  const groups = { [TAG_TYPE_PARTY]: [], [TAG_TYPE_ISSUE]: [], [TAG_TYPE_NORMAL]: [] }
  ;(tags || []).forEach(tag => {
    groups[normalizeTagType(tag)].push(tag)
  })
  return groups
}
