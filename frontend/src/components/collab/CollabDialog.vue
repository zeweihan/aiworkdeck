<template>
  <view v-if="visible" class="awd-mask collab-mask" @tap.self="close">
    <view class="awd-dialog collab-dialog" @tap.stop>
      <view class="awd-header collab-header">
        <text class="awd-title">{{ $t('version.collabTitle') }}</text>
        <view class="collab-close" @tap="close">×</view>
      </view>

      <view class="collab-tabs">
        <view
          v-for="t in tabs"
          :key="t.key"
          class="collab-tab"
          :class="{ active: activeTab === t.key }"
          @tap="switchTab(t.key)"
        >{{ t.label }}</view>
      </view>

      <view class="awd-body collab-body">
        <!-- ==================== 这份案卷 ==================== -->
        <template v-if="activeTab === 'casefile'">
          <template v-if="!linked">
            <view class="collab-lead">
              {{ $t('version.collabLeadNotLinked', { name: projectName }) }}
            </view>
            <view v-if="!connections.length" class="collab-empty-action">
              <text class="collab-note">{{ $t('version.noLibraryConnectedNote') }}</text>
              <view class="awd-btn awd-btn-primary" @tap="switchTab('library')">{{ $t('version.goConnectLibrary') }}</view>
            </view>
            <template v-else>
              <view v-if="connections.length > 1" class="collab-field">
                <text class="collab-label">{{ $t('version.chooseLibraryLabel') }}</text>
                <view class="collab-picker">
                  <view
                    v-for="c in connections"
                    :key="c.id"
                    class="collab-picker-item"
                    :class="{ checked: shareConnectionId === c.id }"
                    @tap="shareConnectionId = c.id"
                  >
                    <view class="collab-radio-dot"></view>
                    <text class="collab-picker-text">{{ c.serverUrl }}</text>
                  </view>
                </view>
              </view>
              <view class="collab-actions">
                <view
                  class="awd-btn awd-btn-primary"
                  :class="{ 'awd-btn-disabled': busy }"
                  @tap="onShare"
                >{{ $t('version.addToTeamLibrary') }}</view>
              </view>
            </template>
          </template>

          <template v-else>
            <view class="collab-state-row">
              <view class="collab-dot" :class="stateClass"></view>
              <text class="collab-state-text">{{ stateText }}</text>
            </view>
            <view class="collab-meta">
              <text class="collab-meta-line">{{ $t('version.caseFileLabel', { name: projectName }) }}</text>
              <text class="collab-meta-line">{{ $t('version.libraryUrlLabel', { url: cloud.serverUrl }) }}</text>
            </view>
            <view class="collab-actions">
              <view
                class="awd-btn awd-btn-primary"
                :class="{ 'awd-btn-disabled': busy }"
                @tap="onUpload"
              >{{ $t('version.submitDraftAction') }}</view>
              <view
                class="awd-btn awd-btn-secondary"
                :class="{ 'awd-btn-disabled': busy }"
                @tap="onUpdate"
              >{{ $t('version.pullLatestAction') }}</view>
              <view
                class="awd-btn awd-btn-secondary"
                :class="{ 'awd-btn-disabled': busy }"
                @tap="onRefresh"
              >{{ $t('version.refreshStatus') }}</view>
            </view>
            <view class="collab-note">
              {{ $t('version.collabActionsNote') }}
            </view>
          </template>
        </template>

        <!-- ==================== 案件参与人 ==================== -->
        <template v-else-if="activeTab === 'people'">
          <template v-if="!linked">
            <view class="collab-lead">
              {{ $t('version.peopleNeedLinkFirst') }}
            </view>
            <view class="collab-actions">
              <view class="awd-btn awd-btn-secondary" @tap="switchTab('casefile')">{{ $t('version.goAddToLibrary') }}</view>
            </view>
          </template>
          <template v-else>
            <view v-if="membersLoading" class="collab-note">{{ $t('version.loadingGeneric') }}</view>
            <view v-else-if="!members.length" class="collab-note">{{ $t('version.onlyYouInCaseFile') }}</view>
            <view v-else class="collab-member-list">
              <view v-for="m in members" :key="m.username || m.id" class="collab-member-row">
                <text class="collab-member-name">{{ m.displayName || m.username }}</text>
                <text class="collab-member-role">{{ roleLabel(m.role) }}</text>
              </view>
            </view>

            <view class="collab-field">
              <text class="collab-label">{{ $t('version.addColleagueLabel') }}</text>
              <view class="collab-add-row">
                <input v-model="addUsername" class="awd-input" :placeholder="$t('version.colleagueUsernamePlaceholder')" />
                <view
                  class="awd-btn awd-btn-primary"
                  :class="{ 'awd-btn-disabled': memberBusy || !addUsername }"
                  @tap="onAddMember"
                >{{ $t('version.addAction') }}</view>
              </view>
              <view class="collab-role-picker">
                <view
                  v-for="r in ASSIGNABLE_ROLES"
                  :key="r.value"
                  class="collab-picker-item"
                  :class="{ checked: addRole === r.value }"
                  @tap="addRole = r.value"
                >
                  <view class="collab-radio-dot"></view>
                  <text class="collab-picker-text">{{ r.label }}</text>
                  <text class="collab-picker-hint">{{ r.hint }}</text>
                </view>
              </view>
            </view>

            <view class="collab-field">
              <text class="collab-label">{{ $t('version.inviteInstructionsLabel') }}</text>
              <view class="collab-invite-box"><text class="collab-invite-text">{{ inviteText }}</text></view>
              <view class="collab-actions">
                <view class="awd-btn awd-btn-secondary" @tap="copyInvite">{{ $t('version.copyThisText') }}</view>
              </view>
              <text class="collab-note">
                {{ $t('version.inviteFooterNote') }}
              </text>
            </view>
          </template>
        </template>

        <!-- ==================== 团队案件库 ==================== -->
        <template v-else>
          <view v-if="!connections.length" class="collab-lead">
            {{ $t('version.libraryIntro') }}
          </view>
          <view v-else class="collab-conn-list">
            <view v-for="c in connections" :key="c.id" class="collab-conn-row">
              <view class="collab-conn-info">
                <text class="collab-conn-url">{{ c.serverUrl }}</text>
                <text class="collab-conn-user">{{ c.displayName || c.username }}</text>
              </view>
              <view class="awd-btn awd-btn-danger" @tap="onDisconnect(c)">{{ $t('version.disconnectLibrary') }}</view>
            </view>
          </view>

          <view class="collab-field">
            <text class="collab-label">{{ $t('version.connectLibrary') }}</text>
            <input v-model="form.serverUrl" class="awd-input" :placeholder="$t('version.libraryUrlPlaceholder')" />
            <text v-if="serverUrlIsHttp" class="collab-warn">{{ $t('version.httpWarning') }}</text>
            <input v-model="form.username" class="awd-input" :placeholder="$t('version.libraryUsernamePlaceholder')" />
            <input v-model="form.password" class="awd-input" :placeholder="$t('version.passwordPlaceholder')" password />
            <view class="collab-actions">
              <view
                class="awd-btn awd-btn-primary"
                :class="{ 'awd-btn-disabled': connectBusy || !form.serverUrl || !form.username }"
                @tap="onConnect"
              >{{ connectBusy ? $t('version.connecting') : $t('version.connect') }}</view>
            </view>
          </view>
        </template>
      </view>

      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="close">{{ $t('common.close') }}</view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  listCloudConnections, cloudConnect, disconnectCloudConnection,
  shareProjectToCloud, uploadToCloud, updateFromCloud, checkCloud,
  getCloudMembers, addCloudMember,
} from '@/services/api.js'
import { roleLabel, ASSIGNABLE_ROLES } from '@/config/memberRoles.js'

export default {
  name: 'CollabDialog',
  props: {
    visible: { type: Boolean, default: false },
    projectId: { type: [String, Number], required: true },
    projectName: { type: String, default: '' },
    // project-overview 已经拉过的云端状态（{linked, serverUrl, pendingUpload, remoteAhead, offline}），
    // 弹窗不自己再拉一遍，动作完成后 emit('changed') 让页面重拉、再流回来。
    cloud: { type: Object, default: null },
    // 有没有等着做选择的文件（三语境任一），来自页面的 /version/status。
    conflictPending: { type: Boolean, default: false },
    // 本机手头还有没收尾的活（/version/status 的 working），用来避免状态条报假绿灯。
    working: { type: Boolean, default: false },
    initialTab: { type: String, default: 'casefile' },
    inviterName: { type: String, default: '' },
  },
  // changed：云端状态可能变了，页面重新拉一次。reload-files：磁盘被改写，重载打开中的编辑器。
  // conflict：撞上了要逐份选择的情况，页面把人送到裁决现场。
  emits: ['update:visible', 'changed', 'reload-files', 'conflict'],
  data() {
    return {
      activeTab: 'casefile',
      connections: [],
      shareConnectionId: null,
      busy: false,
      members: [],
      membersLoading: false,
      addUsername: '',
      addRole: 'PARTICIPANT',
      memberBusy: false,
      form: { serverUrl: '', username: '', password: '' },
      connectBusy: false,
      ASSIGNABLE_ROLES,
    }
  },
  computed: {
    tabs() {
      return [
        { key: 'casefile', label: this.$t('version.tabCaseFile') },
        { key: 'people', label: this.$t('version.tabCaseMembers') },
        { key: 'library', label: this.$t('version.tabTeamLibrary') },
      ]
    },
    linked() {
      return !!(this.cloud && this.cloud.linked)
    },
    serverUrlIsHttp() {
      return /^http:\/\//i.test((this.form.serverUrl || '').trim())
    },
    // 优先级与页面上的状态 chip 同源同序（口径见 project-overview.collabStateText）：
    // 待选择 > 连不上 > 同事交了新稿 > 有改动还没交稿 > 一致。
    stateText() {
      if (this.conflictPending) return this.$t('version.pendingChoice')
      if (!this.cloud) return ''
      if (this.cloud.offline) return this.$t('version.libraryUnreachable')
      if (this.cloud.remoteAhead) return this.$t('version.colleagueSubmittedNew')
      if (this.cloud.pendingUpload || this.working) return this.$t('version.hasUnsubmittedChanges')
      return this.$t('version.inSyncWithTeam')
    },
    stateClass() {
      if (this.conflictPending) return 'collab-dot-amber'
      if (!this.cloud) return ''
      if (this.cloud.offline) return 'collab-dot-amber'
      if (this.cloud.remoteAhead || this.cloud.pendingUpload || this.working) return 'collab-dot-blue'
      return 'collab-dot-green'
    },
    // 这段话是发给一个此刻手上还没有这份案卷的人的，每一步必须指向他真能看见的入口：
    // 他打开软件停在项目列表页，那里唯一的协作入口就是「从团队案件库取一份案卷」，
    // 连案件库也要从这个弹窗里的「去连一个」进。别写「打开设置」——项目列表页的
    // 「设置」面板里没有团队案件库，照着点会找不着。
    inviteText() {
      const url = (this.cloud && this.cloud.serverUrl) || ''
      const inviter = this.inviterName || ''
      // 有/无邀请人分两个键：英文人名后要空格，单键拼 {inviter} 在两种语言里无法同时成立。
      return inviter
        ? this.$t('version.inviteText', { inviter, project: this.projectName, url })
        : this.$t('version.inviteTextNoInviter', { project: this.projectName, url })
    },
  },
  watch: {
    visible(v) {
      if (!v) return
      this.activeTab = this.initialTab || 'casefile'
      this.loadConnections()
      if (this.linked && this.activeTab === 'people') this.loadMembers()
    },
  },
  methods: {
    roleLabel,
    close() {
      this.$emit('update:visible', false)
    },
    switchTab(key) {
      this.activeTab = key
      if (key === 'people' && this.linked && !this.members.length) this.loadMembers()
    },
    async loadConnections() {
      try {
        const res = await listCloudConnections()
        this.connections = (res && res.data && res.data.connections) || []
        if (!this.connections.some((c) => c.id === this.shareConnectionId)) {
          this.shareConnectionId = this.connections.length ? this.connections[0].id : null
        }
      } catch (e) {
        this.connections = []
        this.shareConnectionId = null
      }
    },
    async onShare() {
      if (this.busy) return
      // 多个案件库时必须由律师指名放进哪一个：拿列表第一条会在存量死连接（服务器早已
      // 不在、令牌早已失效）排在前面时，拿着死令牌去连一个不存在的后端。
      if (!this.shareConnectionId) {
        uni.showToast({ title: this.$t('version.noLibrarySelectedToast'), icon: 'none' })
        return
      }
      this.busy = true
      try {
        await shareProjectToCloud(this.projectId, this.shareConnectionId)
        uni.showToast({ title: this.$t('version.sharedToLibrary'), icon: 'none' })
        this.$emit('changed')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.shareToLibraryFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async onUpload() {
      if (this.busy) return
      this.busy = true
      try {
        const res = await uploadToCloud(this.projectId)
        const d = (res && res.data) || {}
        if (d.status === 'UPLOADED') {
          uni.showToast({ title: this.$t('version.submitted'), icon: 'none' })
          // 前台交稿被拒后会自动整合，磁盘可能已被改写：受影响文件要走重载链，
          // 否则打开中的编辑器会把整合前的旧字节自动保存写回去。
          const ids = d.affectedFileIds || []
          if (ids.length) this.$emit('reload-files', ids)
        } else if (d.status === 'CONFLICT') {
          this.close()
          this.$emit('conflict')
        } else {
          uni.showToast({ title: d.message || this.$t('version.submitFailedNotice'), icon: 'none' })
        }
        this.$emit('changed')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.submitFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async onUpdate() {
      if (this.busy) return
      this.busy = true
      try {
        const res = await updateFromCloud(this.projectId)
        const d = (res && res.data) || {}
        if (d.status === 'UPDATED') {
          uni.showToast({ title: this.$t('version.pulledLatest'), icon: 'none' })
          this.$emit('reload-files', d.affectedFileIds || [])
        } else if (d.status === 'CONFLICT') {
          this.close()
          this.$emit('conflict')
        } else if (d.status === 'OFFLINE') {
          uni.showToast({ title: this.$t('version.libraryOffline'), icon: 'none' })
        } else {
          uni.showToast({ title: this.$t('version.alreadyLatest'), icon: 'none' })
        }
        this.$emit('changed')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.pullFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async onRefresh() {
      if (this.busy) return
      this.busy = true
      try {
        await checkCloud(this.projectId)
        this.$emit('changed')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.libraryOffline'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async loadMembers() {
      this.membersLoading = true
      try {
        const res = await getCloudMembers(this.projectId)
        this.members = (res && res.data && res.data.members) || []
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.loadMembersFailed'), icon: 'none' })
      } finally {
        this.membersLoading = false
      }
    },
    async onAddMember() {
      if (this.memberBusy || !this.addUsername) return
      this.memberBusy = true
      try {
        await addCloudMember(this.projectId, this.addUsername, this.addRole)
        this.addUsername = ''
        uni.showToast({ title: this.$t('version.addedSuccess'), icon: 'none' })
        await this.loadMembers()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.addMemberFailed'), icon: 'none' })
      } finally {
        this.memberBusy = false
      }
    },
    copyInvite() {
      uni.setClipboardData({
        data: this.inviteText,
        success: () => uni.showToast({ title: this.$t('version.copiedPasteToColleague'), icon: 'none' }),
      })
    },
    async onConnect() {
      if (this.connectBusy || !this.form.serverUrl || !this.form.username) return
      this.connectBusy = true
      try {
        await cloudConnect(
          this.form.serverUrl.trim(), this.form.username.trim(), this.form.password, '桌面端')
        this.form = { serverUrl: '', username: '', password: '' }
        await this.loadConnections()
        uni.showToast({ title: this.$t('version.connectedToLibrary'), icon: 'none' })
        this.$emit('changed')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.connectFailed'), icon: 'none' })
      } finally {
        this.connectBusy = false
      }
    },
    async onDisconnect(conn) {
      const ok = await new Promise((r) => uni.showModal({
        title: this.$t('version.disconnectLibrary'),
        content: this.$t('version.disconnectLibraryConfirmContent'),
        success: (res) => r(res.confirm),
      }))
      if (!ok) return
      try {
        await disconnectCloudConnection(conn.id)
        await this.loadConnections()
        this.$emit('changed')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.disconnectFailed'), icon: 'none' })
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.collab-dialog {
  width: 640px; max-width: 92vw; max-height: 80vh;
  display: flex; flex-direction: column; background: #fff;
  border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.collab-header {
  padding: 20px 24px 0; display: flex; align-items: center; justify-content: space-between;
}
.awd-title { font-size: 18px; font-weight: 600; color: #0f172a; }
.collab-close { font-size: 22px; color: #94a3b8; cursor: pointer; line-height: 1; padding: 4px; }
.collab-close:hover { color: #0f172a; }

.collab-tabs { display: flex; border-bottom: 1px solid #e2e8f0; margin-top: 14px; padding: 0 24px; }
.collab-tab {
  padding: 10px 16px; font-size: 14px; color: #64748b; cursor: pointer;
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.collab-tab:hover { color: #1A5336; }
.collab-tab.active { color: #1A5336; font-weight: 600; border-bottom-color: #1A5336; }

.collab-body { padding: 20px 24px; overflow-y: auto; flex: 1; min-height: 220px; }
.collab-lead { font-size: 14px; color: #475569; line-height: 1.7; margin-bottom: 16px; }
.collab-note { font-size: 12.5px; color: #94a3b8; line-height: 1.7; margin-top: 10px; }

.collab-state-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.collab-dot { width: 8px; height: 8px; border-radius: 50%; background: #C8A45D; flex-shrink: 0; }
.collab-dot-amber { background: #C8A45D; }
.collab-dot-blue { background: #3E7CB1; }
.collab-dot-green { background: #4C9A6A; }
.collab-state-text { font-size: 15px; font-weight: 600; color: #0f172a; }
.collab-meta { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; }
.collab-meta-line { font-size: 13px; color: #64748b; word-break: break-all; }

.collab-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-top: 12px; }
.collab-empty-action { display: flex; flex-direction: column; gap: 10px; align-items: flex-start; }

.collab-field { margin-top: 20px; display: flex; flex-direction: column; gap: 8px; }
.collab-label { font-size: 13px; font-weight: 500; color: #334155; }
.collab-add-row { display: flex; gap: 10px; align-items: center; }
.collab-add-row .awd-input { flex: 1; }

.collab-picker, .collab-role-picker { display: flex; flex-direction: column; gap: 6px; }
.collab-picker-item {
  display: flex; align-items: center; gap: 8px; cursor: pointer;
  padding: 8px 10px; border: 1px solid #e2e8f0; border-radius: 6px;
}
.collab-picker-item.checked { border-color: #1A5336; background: #F0FDF4; }
.collab-radio-dot {
  width: 12px; height: 12px; border-radius: 50%; border: 1px solid #cbd5e1;
  background: #fff; box-sizing: border-box; flex-shrink: 0;
}
.collab-picker-item.checked .collab-radio-dot { border-color: #1A5336; background: #1A5336; }
.collab-picker-text { font-size: 13.5px; color: #334155; word-break: break-all; }
.collab-picker-hint { font-size: 12px; color: #94a3b8; margin-left: 4px; }

.collab-member-list { display: flex; flex-direction: column; }
.collab-member-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 0; border-bottom: 1px solid #f1f5f9;
}
.collab-member-name { font-size: 14px; color: #0f172a; }
.collab-member-role { font-size: 12.5px; color: #64748b; }

.collab-invite-box {
  background: #f8f9fa; border: 1px solid #e9ecef; border-radius: 8px; padding: 12px;
}
.collab-invite-text { font-size: 13px; color: #334155; line-height: 1.8; white-space: pre-wrap; }

.collab-conn-list { display: flex; flex-direction: column; gap: 10px; }
.collab-conn-row {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px; border: 1px solid #e2e8f0; border-radius: 8px;
}
.collab-conn-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.collab-conn-url { font-size: 13.5px; color: #0f172a; word-break: break-all; }
.collab-conn-user { font-size: 12px; color: #94a3b8; }
.collab-warn { font-size: 12px; color: #C8A45D; }

.awd-input {
  width: 100%; height: 38px; padding: 0 12px; border: 1px solid #cbd5e1;
  border-radius: 6px; font-size: 14px; color: #0f172a; box-sizing: border-box;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 12px;
  padding: 14px 24px; border-top: 1px solid #f1f5f9; background: #f8f9fa;
}
.awd-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 13.5px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.awd-btn-primary { background: #1A5336; color: #fff; }
.awd-btn-primary:hover { background: #14422b; }
.awd-btn-secondary { background: #fff; color: #475569; border: 1px solid #cbd5e1; }
.awd-btn-secondary:hover { background: #f1f5f9; }
.awd-btn-danger { background: #fff; color: #b23; border: 1px solid #f0c4c4; }
.awd-btn-disabled { opacity: .45; pointer-events: none; }
</style>
