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

test('无团队态三条路都在同一屏上：创建 / 邀请码加入 / 收到的邀请（设计 §10.4 第 4 条）', () => {
  const noTeam = teamPanel.slice(teamPanel.indexOf(`v-else-if="!team"`), teamPanel.indexOf('<!-- 态 3'))
  assert.ok(noTeam.length > 0, '找不到无团队态')
  for (const key of ['team.createTitle', 'team.joinTitle', 'team.invitesTitle']) {
    assert.ok(noTeam.includes(key), `无团队态缺「${key}」这条路`)
  }
  assert.match(noTeam, /joinCodeInput/, '缺邀请码输入框')
  assert.match(noTeam, /onJoinTeam/, '缺「加入团队」动作')
  // 并排而不是竖着叠三张卡：三条路是平等的选项，叠起来第三条要滚一屏才看得到
  assert.match(noTeam, /class="join-paths"/)
  assert.match(teamPanel, /\.join-paths \{[^}]*display: flex/)
})

test('收到的邀请行显示被邀角色与过期时间（接受之前就该知道自己以什么身份进去）', () => {
  assert.match(teamPanel, /team\.inviteRoleAs/)
  assert.match(teamPanel, /expiryText\(inv\.expiresAt\)/)
  // 服务端没给到期时间就明说取不到，不许自己按「7 天」算一个日期
  assert.match(teamPanel, /team\.inviteExpiresUnknown/)
  assert.ok(!/7 \* 24 \* 3600|addDays|\+ 7\)/.test(teamPanel), '前端不许自己推算邀请有效期')
})

test('律所区常显：未入所给创建/并入，入所后给团队列表与退出（设计 §10.4 第 5 条）', () => {
  for (const key of ['team.firmTitle', 'team.createFirmTitle', 'team.joinFirmTitle',
    'team.firmTeamsTitle', 'team.firmOwnerOnly', 'team.removeFirmTeam', 'team.leaveFirm',
    'team.firmJoinCodeTitle']) {
    assert.ok(teamPanel.includes(key), `律所区缺「${key}」`)
  }
  for (const fn of ['onCreateFirm', 'onJoinFirm', 'onRenameFirm', 'onResetFirmCode',
    'onRemoveFirmTeam', 'onLeaveFirm']) {
    assert.ok(teamPanel.includes(fn), `缺律所动作 ${fn}`)
  }
  // 「是不是总部」读服务端下发的 isHead，不拿 headTeamId === team.id 自己推
  assert.match(teamPanel, /this\.firm && this\.firm\.isHead/)
  // 退出律所要用自己的 teamId，取不到就不发——猜一个 id 会把别人的团队踢出去
  assert.match(teamPanel, /const teamId = this\.team && this\.team\.id/)
})

test('看板范围切换只在入所后出现，并把 scope 传给 summary', () => {
  assert.match(teamPanel, /v-if="firm" class="team-days-row"/, '范围切换必须挂在 firm 上')
  assert.match(teamPanel, /team\.scopeTeam/)
  assert.match(teamPanel, /team\.scopeFirm/)
  assert.match(teamPanel, /getTeamSummary\(this\.range, this\.scope\)/)
  // 退出律所后停在「全所」会一直打必然被拒的请求
  assert.match(teamPanel, /if \(!this\.firm\) this\.scope = 'team'/)
})

test('团队邀请码在成员区顶部，仅管理者可见，且有复制与重置', () => {
  const members = teamPanel.slice(teamPanel.indexOf('team.membersTitle'), teamPanel.indexOf('team.projectsTitle'))
  assert.ok(members.includes('team.joinCodeTitle'), '邀请码不在成员区')
  assert.match(members, /v-if="canManage" class="code-row"/, '邀请码必须只对 OWNER\/ADMIN 显示')
  assert.match(members, /onCopyCode\(team\.joinCode\)/)
  assert.match(members, /onResetJoinCode/)
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
  assert.match(teamPanel, /getTeamSummary\(this\.range, this\.scope\)/)
})

test('KPI 第一块不写死「本周」（档位可切 7\/30\/90），比例走 caption', () => {
  assert.ok(teamPanel.includes('team.kpiActiveMembersCaption'),
    'kpiActiveMembersCaption 是个死键：活跃人数必须给出「几人里有几人」的分母')
  assert.match(teamPanel, /activeMembersCaption/)
  // 分母取不到时整行不显示，不拿活跃数顶成分母
  assert.match(teamPanel, /typeof k\.memberCount !== 'number'/)
})

test('五个 KPI 磁贴固定五列，不用任何会在某个宽度裂成 4+1 的弹性写法', () => {
  const tiles = teamPanel.slice(teamPanel.indexOf('.stats-tiles {'), teamPanel.indexOf('.stat-value {'))
  assert.match(tiles, /display: grid/)
  assert.match(tiles, /grid-template-columns: repeat\(5, minmax\(0, 1fr\)\)/)
  // 两种「聪明」写法都实测会在某个常见窗口宽度上裂成 4+1：
  // flex 弹性基准在 1440 下裂，auto-fit 把断点挪到 1280 下裂
  assert.ok(!/flex: 1 1 140px/.test(tiles), 'flex 弹性基准 140px 在 1440 宽下会把五块排成 4+1')
  assert.ok(!/auto-fit/.test(tiles), 'auto-fit 在容器 600px（约合 1280 窗口）上仍是 4+1')
  // minmax 下界必须是 0：auto 下界等于内容宽，长文案会把列撑开又变回换行
  assert.ok(!/minmax\(auto/.test(tiles))
})

test('.team-btn 不撑满整卡：section-body 是竖向 flex，块级按钮会被拉满', () => {
  const btn = teamPanel.slice(teamPanel.indexOf('.team-btn {'), teamPanel.indexOf('.team-btn.primary'))
  assert.match(btn, /display: inline-flex/)
  assert.match(btn, /align-self: flex-start/)
  assert.match(btn, /width: auto/)
})

test('数据共享开关在看板顶部，且开关行不再把标题原样念第二遍', () => {
  const hasTeam = teamPanel.slice(teamPanel.indexOf('<!-- 态 3'), teamPanel.indexOf('team.kpiTitle'))
  assert.ok(hasTeam.includes('team.sharingTitle'), '数据共享开关必须排在 KPI 之前')
  assert.ok(hasTeam.includes('team.uploadNow'), '「立即上报」跟着开关一起上来')
  assert.match(teamPanel, /return this\.\$t\('team\.sharingSwitchDesc'\)/,
    '开关行要给说明文案，不是把上面那行标题再抄一遍')
})

test('待接受邀请行里「撤销」与角色标签留够距离', () => {
  assert.match(teamPanel, /class="link-action danger team-list-action"/)
  assert.match(teamPanel, /\.team-list-action \{\s*margin-left: 16px;/)
})

test('项目已有别名时按钮说「改别名」', () => {
  assert.match(teamPanel, /p\.label \? \$t\('team\.renameAlias'\) : \$t\('team\.setAlias'\)/)
})

test('面板底部留出反馈浮窗的高度，最后一行仍可点', () => {
  assert.match(teamPanel, /\.team-pane \{[\s\S]*?padding-bottom: 72px/)
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
    'setTeamProjectAlias', 'getTeamUsageSharing', 'setTeamUsageSharing', 'uploadTeamUsageNow',
    'joinTeam', 'regenerateTeamJoinCode',
    'createFirm', 'joinFirm', 'updateFirm', 'regenerateFirmJoinCode', 'removeFirmTeam']
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


// ==================== 入口地图（设计 §10.4） ====================

test('设置导航「团队」常显：不挂 desktopOnly、不落 system 组（那一组对非管理员整组收起）', () => {
  const item = adminPane.match(/\{ key: 'team',[^}]*\}/)
  assert.ok(item, 'navItems 里没有 team 一项')
  assert.ok(!/desktopOnly/.test(item[0]), '「团队」不许挂 desktopOnly')
  assert.ok(/group: 'personal'/.test(item[0]), '「团队」必须在 personal 组')
  // visibleNavItems 的两条过滤规则之外没有第三条，personal 组恒可见
  assert.match(adminPane, /if \(n\.desktopOnly && !this\.isDesktop\) return false/)
  assert.match(adminPane, /if \(n\.group === 'system' && !this\.isAdminUser\) return false/)
})

test('「账户与用量」在已连接账户时给出团队一行 +「前往团队」，就地切到 team 分区', () => {
  assert.match(adminPane, /team\.accountRowLabel/)
  assert.match(adminPane, /\$t\('team\.goTeam'\)/)
  assert.match(adminPane, /@tap="onNavTap\(\{ key: 'team' \}\)"/,
    '「前往团队」要在设置页内切分区，不该跳到别的页面')
  // 问不到时整行不渲染，不拿「未加入」冒充一个事实
  assert.match(adminPane, /v-if="teamLine\.loaded"/)
  assert.match(adminPane, /teamLine: \{ loaded: false, teamName: '', firmName: '' \}/)
})
