// markdownPlain.js — AI 回复（Markdown）落入文档前的纯文本化。
//
// 「插入当前文档 / 替换选区」走 LOWA 的纯文本原语（insert_at_cursor /
// replace_selection），直接传原始 Markdown 会把 **、#、``` 等符号原样写进
// 法律文书。这里只做符号剥离，不做富文本转换（导出 Word 走后端 flexmark，
// 不经此函数）。
export function markdownToPlainText(md) {
  let s = String(md || '')
  // 代码块围栏（保留围栏内文本）
  s = s.replace(/^```[^\n]*$/gm, '')
  // 标题前缀
  s = s.replace(/^#{1,6}\s+/gm, '')
  // 粗体/斜体/删除线/行内代码
  s = s.replace(/\*\*([^*]+)\*\*/g, '$1')
  s = s.replace(/__([^_]+)__/g, '$1')
  s = s.replace(/\*([^*\n]+)\*/g, '$1')
  s = s.replace(/(^|[^\w])_([^_\n]+)_(?=[^\w]|$)/g, '$1$2')
  s = s.replace(/~~([^~]+)~~/g, '$1')
  s = s.replace(/`([^`]+)`/g, '$1')
  // 图片/链接：留可读文字
  s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
  s = s.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
  // 引用块前缀
  s = s.replace(/^\s*>\s?/gm, '')
  // 列表符号：- * + 统一成中文常用的「• 」，有序列表保留编号
  s = s.replace(/^(\s*)[-*+]\s+/gm, '$1• ')
  // 分隔线
  s = s.replace(/^\s*([-*_]\s?){3,}\s*$/gm, '')
  // 折叠 3+ 连续空行
  s = s.replace(/\n{3,}/g, '\n\n')
  return s.trim()
}
