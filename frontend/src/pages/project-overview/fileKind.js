// 标签页的文件类型色（dev-board#504）：fileType → kind key 的唯一出处。
//
// 零依赖纯函数（不 import Vue / uni / '@/' 别名），node --test 可直接导入；
// 单测在 frontend/tests/tab-visibility/file-kind.test.mjs（跟着
// `npm run test:tab-visibility` 一起跑）。
//
// 只给六类律师日常真会开的文件上色：Word 蓝 / PPT 橙 / Excel 绿 / PDF 红 /
// Markdown 灰 / 图片紫。其余扩展名与所有非文件标签（浏览器、设置、插件、
// 版本对比…）一律返回 ''，保持原来的品牌绿激活态，一个像素都不动。

// 非文件标签。它们的 fileType 要么缺席、要么与 tabType 同值（'version-compare'
// / 'diff' / 'web'…），本来就落不进下面的映射表；这里显式挡一道，是为了让
// 「非文件标签不着色」这条契约有个能被单测钉住的位置，而不是靠「扩展名恰好没撞上」。
const NON_FILE_TAB_TYPES = ['web', 'browser', 'market-detail', 'admin-settings']

// 分组口径与 config/icons.js 的 fileGlyph 保持一致（csv 跟着 Excel 走）。
const KIND_BY_EXT = {
  doc: 'word', docx: 'word',
  ppt: 'ppt', pptx: 'ppt',
  xls: 'excel', xlsx: 'excel', csv: 'excel',
  pdf: 'pdf',
  md: 'md', markdown: 'md',
  jpg: 'image', jpeg: 'image', png: 'image',
  gif: 'image', bmp: 'image', svg: 'image', webp: 'image'
}

/** fileType（+ tabType）→ kind key（'word' | 'ppt' | 'excel' | 'pdf' | 'md' | 'image'），无对应时 '' */
export function fileKindKey(fileType, tabType) {
  if (tabType && NON_FILE_TAB_TYPES.includes(tabType)) return ''
  const t = String(fileType == null ? '' : fileType).toLowerCase().trim()
  return KIND_BY_EXT[t] || ''
}

/** 模板用：'kind-word' 一类的 class；无对应类型时返回 ''（class 数组里等于不加） */
export function fileKindClass(fileType, tabType) {
  const kind = fileKindKey(fileType, tabType)
  return kind ? `kind-${kind}` : ''
}
