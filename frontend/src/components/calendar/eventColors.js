// 按 projectId 稳定配色（哈希取模，同一项目在会话内/刷新后颜色不变）。
// 浅色专业风调色板：浅底 + 深色文字，避免大面积饱和色块（sidebar-shell 配色红线）。
const PALETTE = [
  { bg: '#E8F3ED', text: '#1A5336' },
  { bg: '#EAF1FB', text: '#2C5AA0' },
  { bg: '#FBF0E4', text: '#9A5B1E' },
  { bg: '#F3EAFB', text: '#6B3FA0' },
  { bg: '#FBEAF0', text: '#A02C5A' },
  { bg: '#EAFBF6', text: '#1E8A73' },
  { bg: '#F5F5EA', text: '#7A7A1E' },
  { bg: '#EAEEFB', text: '#3A4EA0' },
]

/** djb2 字符串哈希，避免大数乘法的浮点精度问题 */
function hashString(s) {
  let hash = 5381
  for (let i = 0; i < s.length; i++) {
    hash = ((hash << 5) + hash + s.charCodeAt(i)) | 0
  }
  return Math.abs(hash)
}

export function colorForProject(projectId) {
  const idx = hashString(String(projectId ?? '0')) % PALETTE.length
  return PALETTE[idx]
}
