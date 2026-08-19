// 头像占位符首字母：ASCII 字母统一转大写；中文等非 ASCII 文本取第一个字符原样
// （中文没有大小写概念，转 toUpperCase 是空操作，写出来只是为了显式说明两种口径）。
export function getInitial(name) {
  if (!name) return ''
  const first = String(name).charAt(0)
  return /[a-zA-Z]/.test(first) ? first.toUpperCase() : first
}
