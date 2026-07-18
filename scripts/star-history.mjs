#!/usr/bin/env node
// 生成自托管的 Star History 曲线图（.github/assets/star-history.svg）。
//
// 背景：GitHub 已将 stargazer 明细（含 starred_at 时间戳）限制为仓库管理员/
// 协作者可读，star-history.com 的公开图床对本仓库持续 500，访客也无权查看。
// 因此用仓库自身凭证定期生成静态 SVG，README 直接内嵌，任何访客可见。
//
// 用法：GITHUB_TOKEN=<token> node scripts/star-history.mjs
// （CI 里由 .github/workflows/star-history.yml 每周运行；本地可用
//   GITHUB_TOKEN=$(gh auth token) node scripts/star-history.mjs）

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO = process.env.STAR_REPO || 'zeweihan/aiworkdeck'
const TOKEN = process.env.STAR_TOKEN || process.env.GITHUB_TOKEN
if (!TOKEN) { console.error('缺少 GITHUB_TOKEN'); process.exit(2) }

const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '.github', 'assets', 'star-history.svg')

// ---- 拉取全部 stargazer 时间戳（分页） ----
const dates = []
for (let page = 1; page <= 400; page++) {
  const r = await fetch(`https://api.github.com/repos/${REPO}/stargazers?per_page=100&page=${page}`, {
    headers: {
      Accept: 'application/vnd.github.star+json',
      Authorization: `Bearer ${TOKEN}`,
      'X-GitHub-Api-Version': '2022-11-28',
    },
  })
  if (!r.ok) { console.error(`GitHub API ${r.status}: ${(await r.text()).slice(0, 200)}`); process.exit(1) }
  const batch = await r.json()
  if (!Array.isArray(batch) || batch.length === 0) break
  for (const s of batch) if (s.starred_at) dates.push(new Date(s.starred_at))
  if (batch.length < 100) break
}
dates.sort((a, b) => a - b)
if (dates.length === 0) { console.error('没有 star 数据'); process.exit(1) }

// ---- 累计序列 ----
const points = dates.map((d, i) => ({ t: d.getTime(), n: i + 1 }))
const now = Date.now()
points.push({ t: now, n: dates.length })

// ---- SVG 渲染（品牌墨绿，浅深色模式均可读） ----
const W = 800, H = 360
const M = { l: 56, r: 24, t: 40, b: 44 }
const iw = W - M.l - M.r, ih = H - M.t - M.b
const t0 = points[0].t, t1 = now
const nMax = Math.max(5, Math.ceil(dates.length * 1.08))
const X = (t) => M.l + ((t - t0) / Math.max(1, t1 - t0)) * iw
const Y = (n) => M.t + ih - (n / nMax) * ih

// 阶梯折线（star 数是阶梯量）
let d = `M ${X(points[0].t).toFixed(1)} ${Y(0).toFixed(1)}`
for (const p of points) d += ` L ${X(p.t).toFixed(1)} ${Y(p.n - (p.t === now ? 0 : 1)).toFixed(1)} L ${X(p.t).toFixed(1)} ${Y(p.n).toFixed(1)}`
const area = d + ` L ${X(now).toFixed(1)} ${Y(0).toFixed(1)} Z`

// 纵轴刻度（≤5 个整数刻度）
const step = Math.max(1, Math.ceil(nMax / 5))
let yTicks = ''
for (let n = 0; n <= nMax; n += step) {
  yTicks += `<line x1="${M.l}" y1="${Y(n)}" x2="${W - M.r}" y2="${Y(n)}" stroke="#9AA7B0" stroke-opacity="0.25" stroke-width="1"/>`
  yTicks += `<text x="${M.l - 8}" y="${Y(n) + 4}" text-anchor="end" font-size="11" fill="#7C8A93">${n}</text>`
}
// 横轴：首尾 + 中间共 4 个日期
const fmt = (t) => { const d2 = new Date(t); return `${d2.getUTCFullYear()}-${String(d2.getUTCMonth() + 1).padStart(2, '0')}` }
let xTicks = ''
for (let i = 0; i <= 3; i++) {
  const t = t0 + ((t1 - t0) * i) / 3
  xTicks += `<text x="${X(t)}" y="${H - M.b + 20}" text-anchor="middle" font-size="11" fill="#7C8A93">${fmt(t)}</text>`
}

const stamp = new Date(now).toISOString().slice(0, 10)
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="Star history of ${REPO}">
  <defs>
    <linearGradient id="fill" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#5BD197" stop-opacity="0.35"/>
      <stop offset="1" stop-color="#5BD197" stop-opacity="0.02"/>
    </linearGradient>
  </defs>
  <text x="${M.l}" y="24" font-size="15" font-weight="600" fill="#2D7A52" font-family="ui-sans-serif,system-ui,sans-serif">⭐ ${dates.length} stars · ${REPO}</text>
  <text x="${W - M.r}" y="24" text-anchor="end" font-size="11" fill="#7C8A93" font-family="ui-sans-serif,system-ui,sans-serif">updated ${stamp}</text>
  <g font-family="ui-sans-serif,system-ui,sans-serif">${yTicks}${xTicks}</g>
  <path d="${area}" fill="url(#fill)"/>
  <path d="${d}" fill="none" stroke="#2D7A52" stroke-width="2.5" stroke-linejoin="round"/>
  <circle cx="${X(now)}" cy="${Y(dates.length)}" r="4" fill="#1A5336"/>
</svg>
`
fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, svg)
console.log(`OK ${dates.length} stars → ${path.relative(process.cwd(), OUT)}`)
