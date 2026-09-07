<!--
  「团队」分区（dev-board#496）。设置页「个人」组的一栏，三态：
    1. 未连接账户 —— 团队挂在官网账户上，先去「账户与用量」连接；
    2. 已连接但没有团队 —— 创建团队表单 + 我手机号收到的邀请；
    3. 有团队 —— KPI / 成员 / 项目 / 管理区 / 数据共享开关。

  三条界面口径（改这个文件前逐条对一遍）：
    - 「节约时间」必须带「估算」二字并把公式摆出来。公式取自服务端下发的
      savedMinutesFormula，不在前端写死系数——写死的话服务端调了系数，
      界面上的脚注就开始骗人。取不到公式时只说「口径取不到」，不编一个。
    - 项目默认以匿名短码出现。项目名只有在团队显式打开「共享项目名」时才会存在，
      这里绝不拿本机的项目名去「补全」看板上的短码——那等于绕过团队设置把名字露出来。
    - 数据共享开关的可用性读后端下发的 available，不靠「有没有桌面壳」猜。
-->
<template>
  <view class="team-pane">
    <!-- 分区头 + 隐私一句话。三态都在，不随状态消失 -->
    <view class="section-card">
      <view class="section-header">
        <text class="section-title">{{ $t('team.navTeam') }}</text>
        <text class="section-subtitle">{{ $t('team.subtitle') }}</text>
      </view>
      <view class="section-body">
        <text class="privacy-line">{{ $t('team.privacyLine') }}</text>
      </view>
    </view>

    <!-- 态 1：未连接账户 -->
    <view v-if="!connected" class="section-card">
      <view class="section-header">
        <text class="section-title">{{ $t('team.needAccountTitle') }}</text>
        <text class="section-subtitle">{{ $t('team.needAccountDesc') }}</text>
      </view>
      <view class="section-body">
        <view class="team-btn primary" @tap="onGoAccount">{{ $t('team.goAccount') }}</view>
      </view>
    </view>

    <!-- 取数失败：给重试，不把整块吞掉 -->
    <view v-else-if="loadError" class="section-card">
      <view class="section-body">
        <text class="empty-line">{{ $t('team.loadFailed') }}</text>
        <view class="team-btn" @tap="reload">{{ $t('team.retry') }}</view>
      </view>
    </view>

    <!-- 态 2：已连接、没有团队 -->
    <template v-else-if="!team">
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.createTitle') }}</text>
          <text class="section-subtitle">{{ $t('team.createDesc') }}</text>
        </view>
        <view class="section-body">
          <view class="team-row">
            <input
              v-model="newTeamName"
              class="team-input"
              :placeholder="$t('team.teamNamePlaceholder')"
            />
            <view class="team-btn primary" :class="{ 'is-busy': busy }" @tap="onCreateTeam">
              {{ $t('team.createButton') }}
            </view>
          </view>
        </view>
      </view>

      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.invitesTitle') }}</text>
        </view>
        <view class="section-body">
          <text v-if="!receivedInvites.length" class="empty-line">{{ $t('team.invitesEmpty') }}</text>
          <view v-for="inv in receivedInvites" :key="inv.id" class="team-list-row">
            <text class="team-list-name">{{ $t('team.inviteFrom', { team: inv.teamName || inv.teamId }) }}</text>
            <view class="team-btn small primary" @tap="onAcceptInvite(inv)">{{ $t('team.acceptInvite') }}</view>
          </view>
        </view>
      </view>
    </template>

    <!-- 态 3：有团队 -->
    <template v-else>
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ team.name || $t('team.kpiTitle') }}</text>
          <text class="section-subtitle">{{ $t('team.kpiTitle') }}</text>
          <view class="team-days-row">
            <text
              v-for="d in [7, 30, 90]"
              :key="d"
              class="team-days-btn"
              :class="{ active: range === d }"
              @tap="setRange(d)"
            >{{ $t('team.rangeDays', { n: d }) }}</text>
          </view>
        </view>
        <view class="section-body">
          <template v-if="hasSummary">
            <view class="stats-tiles">
              <view class="stat-tile">
                <text class="stat-value">{{ kpi.activeMembers || 0 }}</text>
                <text class="stat-caption">{{ $t('team.kpiActiveMembers') }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ kpi.projectsCreated || 0 }}</text>
                <text class="stat-caption">{{ $t('team.kpiProjectsCreated') }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ kpi.appStarts || 0 }}</text>
                <text class="stat-caption">{{ $t('team.kpiAppStarts') }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ hoursLabel(kpi.activeMinutes) }}</text>
                <text class="stat-caption">{{ $t('team.kpiActiveMinutes') }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ hoursLabel(kpi.savedMinutes) }}</text>
                <text class="stat-caption">{{ $t('team.kpiSavedMinutes') }}</text>
              </view>
            </view>
            <text class="team-footnote">{{ savedFormulaText }}</text>
          </template>
          <text v-else class="empty-line">{{ $t('team.emptyData') }}</text>
        </view>
      </view>

      <!-- 成员 -->
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.membersTitle') }}</text>
        </view>
        <view class="section-body">
          <text v-if="!members.length" class="empty-line">{{ $t('team.membersEmpty') }}</text>
          <view v-else class="team-table">
            <view class="team-table-head">
              <text class="col col-name">{{ $t('team.colMember') }}</text>
              <text class="col">{{ $t('team.colRole') }}</text>
              <text class="col">{{ $t('team.colActiveDays') }}</text>
              <text class="col">{{ $t('team.colMinutes') }}</text>
              <text class="col">{{ $t('team.colAiTurns') }}</text>
              <text class="col">{{ $t('team.colLastActive') }}</text>
              <text v-if="canManage" class="col col-actions"></text>
            </view>
            <view v-for="m in members" :key="m.accountId" class="team-table-row">
              <text class="col col-name">{{ m.displayName || m.accountId }}</text>
              <text class="col">{{ roleLabel(m.role) }}</text>
              <text class="col">{{ m.activeDays || 0 }}</text>
              <text class="col">{{ hoursLabel(m.activeMinutes) }}</text>
              <text class="col">{{ m.aiTurns || 0 }}</text>
              <text class="col">{{ m.lastActiveDate || '-' }}</text>
              <view v-if="canManage" class="col col-actions">
                <text class="link-action" @tap="onChangeRole(m)">{{ $t('team.changeRole') }}</text>
                <text class="link-action danger" @tap="onRemoveMember(m)">{{ $t('team.removeMember') }}</text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <!-- 项目 -->
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.projectsTitle') }}</text>
          <text class="section-subtitle">{{ $t('team.projectsNote') }}</text>
        </view>
        <view class="section-body">
          <text v-if="!projects.length" class="empty-line">{{ $t('team.projectsEmpty') }}</text>
          <view v-else class="team-table">
            <view class="team-table-head">
              <text class="col col-name">{{ $t('team.colProject') }}</text>
              <text class="col">{{ $t('team.colMinutes') }}</text>
              <text class="col">{{ $t('team.colProjectMembers') }}</text>
              <text class="col">{{ $t('team.colAiTurns') }}</text>
              <text v-if="canManage" class="col col-actions"></text>
            </view>
            <view v-for="p in projects" :key="p.projectKey" class="team-table-row">
              <text class="col col-name">{{ p.label || p.projectKey }}</text>
              <text class="col">{{ hoursLabel(p.minutes) }}</text>
              <text class="col">{{ p.members || 0 }}</text>
              <text class="col">{{ p.aiTurns || 0 }}</text>
              <view v-if="canManage" class="col col-actions">
                <text class="link-action" @tap="onSetAlias(p)">{{ $t('team.setAlias') }}</text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <!-- 管理区：仅 OWNER/ADMIN -->
      <view v-if="canManage" class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.manageTitle') }}</text>
        </view>
        <view class="section-body">
          <text class="sub-title">{{ $t('team.inviteTitle') }}</text>
          <view class="team-row">
            <input
              v-model="invitePhone"
              class="team-input"
              :placeholder="$t('team.invitePhonePlaceholder')"
            />
            <AwdSelect
              class="team-select"
              :range="inviteRoleLabels"
              :value="inviteRoleIndex"
              @change="onInviteRolePick"
            />
            <view class="team-btn primary" :class="{ 'is-busy': busy }" @tap="onInvite">
              {{ $t('team.inviteButton') }}
            </view>
          </view>

          <text class="sub-title">{{ $t('team.pendingInvitesTitle') }}</text>
          <text v-if="!pendingInvites.length" class="empty-line">{{ $t('team.pendingInvitesEmpty') }}</text>
          <view v-for="inv in pendingInvites" :key="inv.id" class="team-list-row">
            <text class="team-list-name">{{ inv.phone }}</text>
            <text class="team-list-count">{{ roleLabel(inv.role) }}</text>
            <text class="link-action danger" @tap="onRevokeInvite(inv)">{{ $t('team.revokeInvite') }}</text>
          </view>

          <text class="sub-title">{{ $t('team.settingsTitle') }}</text>
          <view class="team-row">
            <input v-model="renameDraft" class="team-input" :placeholder="$t('team.renameLabel')" />
            <view class="team-btn" :class="{ 'is-busy': busy }" @tap="onRename">{{ $t('team.saveName') }}</view>
          </view>
          <view class="switch-row">
            <view class="switch-info">
              <text class="switch-name">{{ $t('team.shareProjectNamesLabel') }}</text>
              <text class="switch-desc">{{ $t('team.shareProjectNamesDesc') }}</text>
            </view>
            <AwdSwitch
              :checked="!!team.shareProjectNames"
              :disabled="busy"
              @change="onToggleShareNames"
            />
          </view>
        </view>
      </view>

      <!-- 数据共享（本机开关） -->
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.sharingTitle') }}</text>
          <text class="section-subtitle">{{ $t('team.sharingDesc') }}</text>
        </view>
        <view class="section-body">
          <view class="switch-row">
            <view class="switch-info">
              <text class="switch-name">{{ sharingHint }}</text>
            </view>
            <AwdSwitch
              :checked="!!sharing.enabled"
              :disabled="busy || sharing.available === false"
              @change="onToggleSharing"
            />
          </view>
          <view class="team-row">
            <view class="team-btn" :class="{ 'is-busy': busy }" @tap="onUploadNow">
              {{ $t('team.uploadNow') }}
            </view>
            <text class="team-footnote">{{ lastUploadText }}</text>
          </view>
          <view v-if="canLeave" class="team-row">
            <text class="link-action danger" @tap="onLeaveTeam">{{ $t('team.leaveTeam') }}</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<script>
import AwdSwitch from '@/components/AwdSwitch.vue'
import AwdSelect from '@/components/AwdSelect.vue'
import {
  getAccountStatus,
  getTeam, createTeam, updateTeam,
  createTeamInvite, revokeTeamInvite, acceptTeamInvite,
  updateTeamMemberRole, removeTeamMember,
  getTeamSummary, setTeamProjectAlias,
  getTeamUsageSharing, setTeamUsageSharing, uploadTeamUsageNow,
} from '@/services/api.js'

const ROLE_KEYS = ['ADMIN', 'MEMBER']

export default {
  name: 'TeamPanel',
  components: { AwdSwitch, AwdSelect },
  emits: ['go-account'],
  data() {
    return {
      connected: false,
      loadError: false,
      busy: false,
      team: null,
      myRole: '',
      // 官网 /api/account/team 若下发 myAccountId 才给「退出团队」入口。
      // 契约 §6 只写了 {team, myRole, members[], pendingInvites[]}，没有这个字段——
      // 拿不到就不显示这个动作，**绝不自己编一个 'me' 之类的 id 去打 DELETE**：
      // 猜错的后果是把别人踢出团队。
      myAccountId: '',
      members: [],
      pendingInvites: [],
      receivedInvites: [],
      summary: null,
      range: 7,
      // available 初值刻意是 undefined 而不是 true/false：还没问过后端时既不该
      // 把开关点亮，也不该显示「不可用」的说明
      sharing: { enabled: false, lastUploadAt: '', available: undefined },
      newTeamName: '',
      invitePhone: '',
      inviteRoleIndex: 1,
      renameDraft: '',
    }
  },
  computed: {
    canManage() {
      return this.myRole === 'OWNER' || this.myRole === 'ADMIN'
    },
    // OWNER 不能退出团队（退了就没人管了），界面上直接不给这个动作
    canLeave() {
      // OWNER 不能退出（退了就没人管了）；没有 myAccountId 时也不给，见 data 里的注释
      return !!this.team && this.myRole !== 'OWNER' && !!this.myAccountId
    },
    hasSummary() {
      return !!(this.summary && this.summary.kpi)
    },
    kpi() {
      return (this.summary && this.summary.kpi) || {}
    },
    projects() {
      return (this.summary && this.summary.projects) || []
    },
    inviteRoleLabels() {
      return ROLE_KEYS.map((r) => this.roleLabel(r))
    },
    savedFormulaText() {
      const f = this.summary && this.summary.savedMinutesFormula
      if (!f || typeof f.perAgentEdit !== 'number' || typeof f.perAiTurn !== 'number') {
        return this.$t('team.savedFormulaUnknown')
      }
      return this.$t('team.savedFormula', { perAgentEdit: f.perAgentEdit, perAiTurn: f.perAiTurn })
    },
    lastUploadText() {
      const at = this.sharing.lastUploadAt
      if (!at) return this.$t('team.lastUploadNever')
      return this.$t('team.lastUploadAt', { time: String(at).replace('T', ' ').slice(0, 16) })
    },
    sharingHint() {
      if (this.sharing.available === false) return this.$t('team.sharingDesktopOnly')
      return this.$t('team.sharingTitle')
    },
  },
  mounted() {
    this.reload()
  },
  methods: {
    hoursLabel(minutes) {
      const n = Number(minutes || 0)
      return this.$t('team.hours', { hours: (n / 60).toFixed(1) })
    },
    roleLabel(role) {
      if (role === 'OWNER') return this.$t('team.roleOwner')
      if (role === 'ADMIN') return this.$t('team.roleAdmin')
      return this.$t('team.roleMember')
    },
    toast(title) {
      uni.showToast({ title, icon: 'none' })
    },
    async reload() {
      this.loadError = false
      try {
        const status = await getAccountStatus()
        this.connected = !!(status && status.connected)
      } catch (e) {
        this.connected = false
        return
      }
      if (!this.connected) return
      try {
        const data = await getTeam()
        this.team = (data && data.team) || null
        this.myRole = (data && data.myRole) || ''
        this.myAccountId = (data && data.myAccountId) || ''
        this.members = (data && data.members) || []
        this.pendingInvites = (data && data.pendingInvites) || []
        this.receivedInvites = (data && data.invites) || []
        this.renameDraft = this.team ? this.team.name || '' : ''
      } catch (e) {
        this.loadError = true
        return
      }
      await this.loadSharing()
      if (this.team) await this.loadSummary()
    },
    async loadSharing() {
      try {
        const s = await getTeamUsageSharing()
        this.sharing = {
          enabled: !!(s && s.enabled),
          lastUploadAt: (s && s.lastUploadAt) || '',
          available: s ? s.available : undefined,
        }
      } catch (e) {
        // 开关读不到不该让整个面板报错：其余部分照常可用
      }
    },
    async loadSummary() {
      try {
        const data = await getTeamSummary(this.range)
        this.summary = data || null
        // summary 的成员行带统计数字，比 /team 那份更完整；有就用它
        if (data && Array.isArray(data.members) && data.members.length) {
          this.members = data.members
        }
      } catch (e) {
        this.summary = null
      }
    },
    setRange(days) {
      if (this.range === days) return
      this.range = days
      this.loadSummary()
    },
    async run(fn) {
      if (this.busy) return
      this.busy = true
      try {
        await fn()
      } catch (e) {
        this.toast((e && e.message) || this.$t('team.loadFailed'))
      } finally {
        this.busy = false
      }
    },
    onGoAccount() {
      this.$emit('go-account')
    },
    onCreateTeam() {
      const name = (this.newTeamName || '').trim()
      if (!name) {
        this.toast(this.$t('team.createFailedEmpty'))
        return
      }
      this.run(async () => {
        await createTeam(name)
        this.newTeamName = ''
        await this.reload()
      })
    },
    onAcceptInvite(invite) {
      this.run(async () => {
        await acceptTeamInvite(invite.id)
        await this.reload()
      })
    },
    onInviteRolePick(index) {
      this.inviteRoleIndex = index
    },
    onInvite() {
      const phone = (this.invitePhone || '').trim()
      if (!phone) {
        this.toast(this.$t('team.invitePhoneEmpty'))
        return
      }
      this.run(async () => {
        await createTeamInvite(phone, ROLE_KEYS[this.inviteRoleIndex] || 'MEMBER')
        this.invitePhone = ''
        await this.reload()
      })
    },
    onRevokeInvite(invite) {
      this.run(async () => {
        await revokeTeamInvite(invite.id)
        await this.reload()
      })
    },
    onChangeRole(member) {
      const next = member.role === 'ADMIN' ? 'MEMBER' : 'ADMIN'
      this.run(async () => {
        await updateTeamMemberRole(member.accountId, next)
        await this.reload()
      })
    },
    onRemoveMember(member) {
      uni.showModal({
        title: this.$t('team.removeMember'),
        content: this.$t('team.confirmRemove'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await removeTeamMember(member.accountId)
            await this.reload()
          })
        },
      })
    },
    onLeaveTeam() {
      uni.showModal({
        title: this.$t('team.leaveTeam'),
        content: this.$t('team.confirmLeave'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await removeTeamMember(this.myAccountId)
            await this.reload()
          })
        },
      })
    },
    onRename() {
      const name = (this.renameDraft || '').trim()
      if (!name) {
        this.toast(this.$t('team.createFailedEmpty'))
        return
      }
      this.run(async () => {
        await updateTeam({ name })
        await this.reload()
      })
    },
    onToggleShareNames(next) {
      this.run(async () => {
        await updateTeam({ shareProjectNames: !!next })
        await this.reload()
      })
    },
    onToggleSharing(next) {
      this.run(async () => {
        // 切换失败不改本地状态：重新读一次让界面回到真相，而不是显示一个没生效的档位
        await setTeamUsageSharing(!!next)
        await this.loadSharing()
      })
    },
    onSetAlias(project) {
      uni.showModal({
        title: this.$t('team.setAlias'),
        editable: true,
        placeholderText: this.$t('team.aliasPlaceholder'),
        content: project.label || '',
        success: (res) => {
          // content 为 undefined 说明这个平台的 showModal 没有实现 editable——
          // 那时把它当成 '' 会静默清掉已有别名，只能按取消处理
          if (!res.confirm || typeof res.content !== 'string') return
          this.run(async () => {
            await setTeamProjectAlias(project.projectKey, res.content)
            await this.loadSummary()
          })
        },
      })
    },
    onUploadNow() {
      this.run(async () => {
        const result = await uploadTeamUsageNow()
        await this.loadSharing()
        if (result && result.skipped) {
          const reasons = {
            disabled: 'team.skipDisabled',
            not_local_mode: 'team.skipNotLocalMode',
            not_connected: 'team.skipNotConnected',
            no_team: 'team.skipNoTeam',
          }
          this.toast(this.$t(reasons[result.reason] || 'team.loadFailed'))
          return
        }
        const days = (result && result.uploaded) || 0
        this.toast(days ? this.$t('team.uploadDone', { days }) : this.$t('team.uploadNothing'))
      })
    },
  },
}
</script>

<style scoped>
.team-pane {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.section-card {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  overflow: hidden;
}

.section-header {
  padding: 14px 18px 10px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.section-title {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.section-subtitle {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--awd-text-2);
  line-height: 18px;
}

.section-body {
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.privacy-line {
  font-size: 12px;
  color: var(--awd-text-2);
  line-height: 18px;
}

/* KPI 磁贴：与 OverviewStatsBar 的 .stat-tile 同一形制 */
.stats-tiles {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.stat-tile {
  flex: 1 1 140px;
  min-width: 120px;
  padding: 10px 12px;
  background: var(--awd-bg);
  border-left: 3px solid var(--awd-mint);
  border-radius: 4px;
}

.stat-value {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-accent-text);
  line-height: 22px;
}

.stat-caption {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--awd-text-2);
  line-height: 16px;
}

.team-footnote {
  font-size: 11px;
  color: var(--awd-text-3);
  line-height: 16px;
}

.team-days-row {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}

.team-days-btn {
  padding: 3px 10px;
  font-size: 12px;
  color: var(--awd-text-2);
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  cursor: pointer;
}

.team-days-btn.active {
  color: var(--awd-accent-text);
  border-color: var(--awd-accent);
  background: var(--awd-accent-wash);
}

/* 表格：与 telemetry 分区的 .telemetry-list 同一密度 */
.team-table {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--awd-border-subtle);
  border-radius: 4px;
  overflow-x: auto;
}

.team-table-head,
.team-table-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  min-width: 560px;
}

.team-table-head {
  background: var(--awd-surface-2);
}

.team-table-row + .team-table-row,
.team-table-head + .team-table-row {
  border-top: 1px solid var(--awd-border-subtle);
}

.col {
  flex: 1 1 0;
  font-size: 12px;
  color: var(--awd-text-2);
}

.team-table-row .col {
  color: var(--awd-text);
}

.col-name {
  flex: 2 1 0;
  font-weight: 500;
}

.col-actions {
  flex: 1.4 1 0;
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.link-action {
  font-size: 12px;
  color: var(--awd-accent-text);
  cursor: pointer;
}

.link-action.danger {
  color: var(--awd-danger-text);
}

.team-list-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.team-list-name {
  flex: 1;
  font-size: 13px;
  color: var(--awd-text);
}

.team-list-count {
  font-size: 12px;
  color: var(--awd-text-2);
}

.team-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.team-input {
  flex: 1 1 200px;
  min-width: 160px;
  height: 32px;
  padding: 0 10px;
  font-size: 13px;
  color: var(--awd-text);
  background: var(--awd-bg);
  border: 1px solid var(--awd-border);
  border-radius: 4px;
}

.team-select {
  flex: 0 0 120px;
}

.team-btn {
  padding: 6px 14px;
  font-size: 13px;
  color: var(--awd-text);
  background: var(--awd-surface-2);
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  cursor: pointer;
}

.team-btn.primary {
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
  border-color: var(--awd-accent);
}

.team-btn.small {
  padding: 4px 10px;
  font-size: 12px;
}

.team-btn.is-busy {
  opacity: 0.6;
  cursor: not-allowed;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-border-subtle);
  border-radius: 4px;
}

.switch-info {
  flex: 1;
}

.switch-name {
  display: block;
  font-size: 13px;
  color: var(--awd-text);
}

.switch-desc {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--awd-text-2);
  line-height: 16px;
}

.sub-title {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-text-2);
}

.empty-line {
  font-size: 12px;
  color: var(--awd-text-3);
  line-height: 18px;
}
</style>
