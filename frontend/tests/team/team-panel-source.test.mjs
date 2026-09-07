// 「团队」分区的源码级契约（dev-board#496）。
//
// 这些点没法用纯函数覆盖（都是「某个写法必须接在某处」），所以按 tests/project-home 下
// audit-*-source-assertions 的写法读源文件断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (p) => readFileSync(resolve(root, p), 'utf8')

const adminPane = read('src/components/admin/AdminPane.vue')
const teamPanel = read('src/components/admin/TeamPanel.vue')
const api = read('src/services/api.js')

// ==================== 分区注册 ====================

test('team 分区接在 activeNav 长链的链尾（动链头会拿到编译错）', () => {
  const branches = [...adminPane.matchAll(/activeNav === '([a-z_]+)'/g)].map((m) => m[1])
  assert.ok(branches.includes('team'), 'AdminPane 里没有 activeNav === \'team\' 分支')
  assert.equal(branches[branches.length - 1], 'team', 'team 分支必须是链尾，实际链尾是 ' + branches[branches.length - 1])
  assert.match(adminPane, /v-else-if="activeNav === 'team'"/, 'team 分支必须是 v-else-if，不能是新起一条 v-if')
  assert.match(adminPane, /activeNav === 'ai'/, '链头 ai 的 v-if 不许被动')
})

test('navItems 里有 team 一项，落在 personal 组（system 组对非管理员整组收起）', () => {
  assert.match(adminPane, /\{ key: 'team', label: this\.\$t\('team\.navTeam'\), group: 'personal' \}/)
})

test('TeamPanel 被引入并注册，且 go-account 绑到了账户分区', () => {
  assert.match(adminPane, /import TeamPanel from '@\/components\/admin\/TeamPanel\.vue'/)
  assert.match(adminPane, /\bTeamPanel,/)
  assert.match(adminPane, /<TeamPanel @go-account="onNavTap\(\{ key: 'account' \}\)" \/>/)
})

// ==================== 三态 ====================

test('TeamPanel 三态分支齐全：未连接账户 / 无团队 / 有团队', () => {
  assert.match(teamPanel, /v-if="!connected"/, '缺「未连接账户」态')
  assert.match(teamPanel, /v-else-if="!team"/, '缺「无团队」态')
  assert.match(teamPanel, /v-else-if="loadError"/, '取数失败要单独给重试，不能把整块吞掉')
  assert.match(teamPanel, /<template v-else>/, '缺「有团队」态')
})

test('有团队态的五个 KPI 磁贴都在，并复用 .stat-tile 形制', () => {
  for (const key of ['kpiActiveMembers', 'kpiProjectsCreated', 'kpiAppStarts',
    'kpiActiveMinutes', 'kpiSavedMinutes']) {
    assert.ok(teamPanel.includes(`team.${key}`), `缺 KPI 磁贴 ${key}`)
  }
  assert.match(teamPanel, /class="stat-tile"/)
  assert.match(teamPanel, /border-left: 3px solid var\(--awd-mint\)/)
})

test('7/30/90 天切换存在，并把 range 传给 summary', () => {
  assert.match(teamPanel, /v-for="d in \[7, 30, 90\]"/)
  assert.match(teamPanel, /getTeamSummary\(this\.range\)/)
})

// ==================== 隐私与口径红线 ====================

test('数据共享开关默认关（data 初值 false），且可用性读后端下发的 available', () => {
  assert.match(teamPanel, /sharing: \{ enabled: false, lastUploadAt: '', available: undefined \}/,
    '开关默认必须是关，lastUploadAt 空串表示从未上报')
  assert.match(teamPanel, /sharing\.available === false/,
    '可用性必须读后端下发的 available，不许靠「有没有桌面壳」猜')
})

test('分区顶部有隐私一句话', () => {
  assert.match(teamPanel, /\$t\('team\.privacyLine'\)/)
})

test('节约时间的公式取自服务端 savedMinutesFormula，前端不写死系数', () => {
  assert.match(teamPanel, /savedMinutesFormula/)
  assert.match(teamPanel, /team\.savedFormulaUnknown/, '公式取不到时必须明说取不到，不许编一个')
  // 常见的两个拍脑袋系数不许出现在前端计算里
  assert.ok(!/savedMinutes\s*=\s*/.test(teamPanel), '节约时间必须由服务端算，前端不许自己乘系数')
})

test('项目名不在前端「补全」：只显示 label 或短码', () => {
  assert.match(teamPanel, /p\.label \|\| p\.projectKey/)
})

test('别名弹窗在平台不支持 editable 时按取消处理，不把已有别名清成空串', () => {
  assert.match(teamPanel, /typeof res\.content !== 'string'/)
})

// ==================== api.js ====================

test('api.js 导出团队相关函数，且全部走本地后端 /api/account/team*', () => {
  const fns = ['getTeam', 'createTeam', 'updateTeam', 'createTeamInvite', 'revokeTeamInvite',
    'acceptTeamInvite', 'updateTeamMemberRole', 'removeTeamMember', 'getTeamSummary',
    'setTeamProjectAlias', 'getTeamUsageSharing', 'setTeamUsageSharing', 'uploadTeamUsageNow']
  for (const fn of fns) {
    assert.ok(new RegExp(`export function ${fn}\\(`).test(api), `api.js 缺 ${fn}`)
  }
  assert.ok(!/aiworkdeck\.com\/api\/account\/team/.test(api), '前端不许直连官网，一律经本地后端透传')
})

test('api.js 的团队接口不用 PATCH（uni.request 的 method 枚举里没有它）', () => {
  const teamBlock = api.slice(api.indexOf('// ===================== 团队'), api.indexOf('export function getTelemetrySettings'))
  assert.ok(teamBlock.length > 0, '找不到团队接口段')
  assert.ok(!/method: 'PATCH'/.test(teamBlock), '团队接口出现了 PATCH')
})

test('路径参数一律 encodeURIComponent（accountId / inviteId / projectKey 来自服务端数据）', () => {
  const teamBlock = api.slice(api.indexOf('// ===================== 团队'), api.indexOf('export function getTelemetrySettings'))
  const interpolations = [...teamBlock.matchAll(/\$\{([^}]+)\}/g)].map((m) => m[1].trim())
  for (const expr of interpolations) {
    if (expr === 'range') continue // 数字，后端还会再归一一次
    assert.match(expr, /^encodeURIComponent\(/, `路径参数未编码：${expr}`)
  }
})
