// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 按 projectId 稳定配色（哈希取模，同一项目在会话内/刷新后颜色不变）。
// 浅色专业风调色板：浅底 + 深色文字，避免大面积饱和色块（sidebar-shell 配色红线）。
//
// 东方清雅体系下的分类色板（design/tokens/awd-palette.json v2.0.0）。
// 构造法：不在 HSL 上等分色相——HSL 会把绿区挤在一起、把蓝区拉开，八个项目色里
// 总有两个看着一样。改为在 CIELCh 上固定明度与彩度、只等分感知色相角：
//   底色 L*=90 C*=14，文字 L*=40 C*=28，八个色相从 24° 起每 45° 一格。
// 实测（CIEDE2000，脚本见落实记录）：
//   底色两两最小色差 7.83（旧版 2.35，#EAF1FB 与 #EAEEFB 肉眼几乎同色）
//   文字两两最小色差 12.84（旧版 5.76）
//   同格文字压底色最小对比度 4.93:1（旧版 3.97:1，不过 AA）
const PALETTE = [
  { bg: '#FFDAD8', text: '#8A4D4D' },
  { bg: '#F6DECA', text: '#7A5734' },
  { bg: '#E2E5CA', text: '#5A6233' },
  { bg: '#CBEAD8', text: '#2F694D' },
  { bg: '#C1EAED', text: '#006A70' },
  { bg: '#CAE6FB', text: '#146588' },
  { bg: '#E2E0FB', text: '#5A5A88' },
  { bg: '#F8DAEC', text: '#804E6F' },
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
