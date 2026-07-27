// 全站线性图标 path 集合。
//
// 产品红线：界面里不使用 emoji。图标一律 stroke 线性 SVG，24x24 viewBox，
// 用 currentColor 描边，尺寸与颜色交给使用处的 CSS 控制。用法：
//
//   <svg class="xx-icon" viewBox="0 0 24 24" fill="none">
//     <path v-for="(d, i) in ICONS.doc" :key="i" :d="d"
//           stroke="currentColor" stroke-width="1.7"
//           stroke-linecap="round" stroke-linejoin="round" />
//   </svg>
//
// 勾选/关闭一类的排版符号（✓ ✕ ★）不属于 emoji，可继续直接用。

export const ICONS = {
  // —— 文件与目录 ——
  doc: ['M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M14 3v5h5'],
  docText: ['M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M14 3v5h5', 'M9 13h6', 'M9 17h4'],
  docPdf: ['M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M14 3v5h5', 'M8.5 16.5h2a1.5 1.5 0 0 0 0-3h-2v5'],
  docSheet: ['M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M14 3v5h5', 'M8 12h8', 'M8 16h8', 'M12 12v8'],
  docSlides: ['M4 5h16v11H4z', 'M12 16v4', 'M9 20h6'],
  folder: ['M3 7a1 1 0 0 1 1-1h5l2 2h8a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7Z'],
  folderOpen: ['M3 7a1 1 0 0 1 1-1h5l2 2h8a1 1 0 0 1 1 1v1H3V7Z', 'M3 10h18l-2 8a1 1 0 0 1-1 .8H5.6A1 1 0 0 1 4.6 18L3 10Z'],
  trash: ['M4 7h16', 'M10 11v6', 'M14 11v6', 'M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13', 'M9 7V4h6v3'],

  // —— 能力与对象 ——
  // 三块拼装的积木：插件 / 扩展
  blocks: ['M4 4h7v7H4z', 'M4 13h7v7H4z', 'M13 13h7v7h-7z', 'M14.5 2.5h7v7h-7z'],
  // 带书签的文稿：Skill（提示词能力）
  skill: ['M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M14 3v5h5', 'M9 13h6', 'M9 17h4'],
  bolt: ['M13 2 4 14h7l-1 8 9-12h-7l1-8Z'],
  link: ['M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1.5 1.5', 'M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7L12.5 19.5'],
  crown: ['M4 18h16', 'M4 18 3 7l5 4 4-6 4 6 5-4-1 11'],
  star: ['M12 3.5l2.6 5.6 6 .8-4.4 4.2 1.1 6.1-5.3-2.9-5.3 2.9 1.1-6.1L3.4 9.9l6-.8L12 3.5Z'],
  compare: ['M4 4h7v16H4z', 'M13 4h7v16h-7z', 'M7 9h1', 'M16 9h1', 'M7 13h1', 'M16 13h1'],
  audio: ['M9 18V6l10-2v12', 'M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0Z', 'M19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0Z'],

  // 地球：网页标签页
  web: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M3.6 9h16.8', 'M3.6 15h16.8', 'M12 3a14 14 0 0 1 0 18', 'M12 3a14 14 0 0 0 0 18'],

  // —— 设备与状态 ——
  phone: ['M7 2h10a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z', 'M11 18.5h2'],
  desktop: ['M3 4h18v12H3z', 'M8 20h8', 'M12 16v4'],
  warning: ['M12 3.5 2.5 20h19L12 3.5Z', 'M12 10v4', 'M12 17.5h.01'],
  // 断开的信号：远端不可达
  offline: ['M2 2l20 20', 'M8.5 16.5a5 5 0 0 1 7 0', 'M5 12.9a10 10 0 0 1 4-2.6', 'M15 10.3a10 10 0 0 1 4 2.6', 'M2 8.8a15 15 0 0 1 4.7-3', 'M17.3 5.8A15 15 0 0 1 22 8.8', 'M12 20h.01'],
  search: ['M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z', 'M21 21l-4.35-4.35']
}

/** 按文件类型取图标 path（未知类型回落到通用文稿） */
export function fileGlyph(fileType) {
  const t = String(fileType || '').toLowerCase()
  if (['doc', 'docx'].includes(t)) return ICONS.docText
  if (t === 'pdf') return ICONS.docPdf
  if (['xls', 'xlsx', 'csv'].includes(t)) return ICONS.docSheet
  if (['ppt', 'pptx'].includes(t)) return ICONS.docSlides
  return ICONS.doc
}
