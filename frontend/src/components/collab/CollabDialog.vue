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
            <!-- 一条连接都没有、但本站有官方案件库：直接给「放进去」，后端用本机的
                 AI WorkDeck 账户自动连上——律师不该被要求填一个他不知道的服务器地址。 -->
            <view v-if="!connections.length && official.available" class="collab-empty-action">
              <text class="collab-note">{{ $t('version.addToOfficialLibraryNote') }}</text>
              <view
                class="awd-btn awd-btn-primary"
                :class="{ 'awd-btn-disabled': busy }"
                @tap="onShare"
              >{{ $t('version.addToOfficialLibraryTitle') }}</view>
            </view>
            <view v-else-if="!connections.length" class="collab-empty-action">
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

            <!-- 加人分两步：先按手机号/邮箱查出是谁，看清头像姓名再确认加入。
                 号码打错一位就把陌生人加进案卷，而案卷里是客户材料——这一步是唯一的可核对环节。 -->
            <view class="collab-field">
              <text class="collab-label">{{ $t('version.addColleagueLabel') }}</text>
              <view class="collab-add-row">
                <input
                  v-model="addContact"
                  class="awd-input"
                  :placeholder="$t('version.colleagueContactPlaceholder')"
                  @confirm="onLookup"
                />
                <view
                  class="awd-btn awd-btn-secondary"
                  :class="{ 'awd-btn-disabled': lookupBusy || !addContact }"
                  @tap="onLookup"
                >{{ lookupBusy ? $t('version.lookingUp') : $t('version.lookupAction') }}</view>
              </view>
              <text v-if="lookupMessage" class="collab-note">{{ lookupMessage }}</text>
              <text v-else-if="!candidate" class="collab-note">{{ $t('version.addColleagueByContactNote') }}</text>

              <view v-if="candidate" class="member-candidate">
                <view class="member-candidate-head">
                  <image
                    v-if="candidate.avatarUrl && !avatarBroken"
                    :src="candidate.avatarUrl"
                    class="member-candidate-avatar"
                    @error="avatarBroken = true"
                  />
                  <view v-else class="member-candidate-avatar member-candidate-initial">
                    {{ getInitial(candidate.displayName) || 'U' }}
                  </view>
                  <view class="member-candidate-id">
                    <text class="member-candidate-name">{{ candidate.displayName || candidate.maskedContact }}</text>
                    <!-- 手机号注册的账号展示名就是脱敏号，重复渲染一遍看着像出了错 -->
                    <text
                      v-if="candidate.maskedContact && candidate.maskedContact !== candidate.displayName"
                      class="member-candidate-contact"
                    >{{ candidate.maskedContact }}</text>
                  </view>
                </view>

                <template v-if="candidate.alreadyMember">
                  <text class="collab-note">
                    {{ $t('version.alreadyInCaseFileAs', { role: roleLabel(candidate.currentRole) }) }}
                  </text>
                  <view class="collab-actions">
                    <view class="awd-btn awd-btn-secondary awd-btn-disabled">{{ $t('version.alreadyAMember') }}</view>
                    <view class="awd-btn awd-btn-secondary" @tap="clearCandidate">{{ $t('version.lookupAnother') }}</view>
                  </view>
                </template>
                <template v-else>
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
                  <view class="collab-actions">
                    <view
                      class="awd-btn awd-btn-primary"
                      :class="{ 'awd-btn-disabled': memberBusy }"
                      @tap="onAddMember"
                    >{{ $t('version.confirmAddMember') }}</view>
                    <view class="awd-btn awd-btn-secondary" @tap="clearCandidate">{{ $t('version.lookupAnother') }}</view>
                  </view>
                </template>
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
  getCloudMembers, addCloudMember, lookupCloudMember, getOfficialCloud,
} from '@/services/api.js'
import { roleLabel, ASSIGNABLE_ROLES } from '@/config/memberRoles.js'
import { getInitial } from '@/utils/textInitial.js'

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
      addContact: '',
      addRole: 'PARTICIPANT',
      memberBusy: false,
      // 查人结果：null=还没查/已清空；对象=查到的那个人（只带展示名、头像、打码联系方式）
      candidate: null,
      lookupMessage: '',
      lookupBusy: false,
      // 官网头像 404（对方没传过头像）时降级成首字母方块，不留一个碎图标
      avatarBroken: false,
      form: { serverUrl: '', username: '', password: '' },
      connectBusy: false,
      // 官方团队案件库：{available, connected, serverUrl}。available=false（国际站）时
      // 界面回到「先连一个自建案件库」那条老路。
      official: { available: false, connected: false, serverUrl: '' },
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
      // 官方案件库的第 2 步（填地址、用账号密码连库）对同事根本不存在——他用自己的
      // AI WorkDeck 账号登录桌面端就有这个库。照旧话术发过去会让他去找一个不需要填的地址。
      const isOfficial = !!(this.official.serverUrl && url && this.official.serverUrl === url)
      // 有/无邀请人分两个键：英文人名后要空格，单键拼 {inviter} 在两种语言里无法同时成立。
      if (isOfficial) {
        return inviter
          ? this.$t('version.inviteTextOfficial', { inviter, project: this.projectName })
          : this.$t('version.inviteTextOfficialNoInviter', { project: this.projectName })
      }
      return inviter
        ? this.$t('version.inviteText', { inviter, project: this.projectName, url })
        : this.$t('version.inviteTextNoInviter', { project: this.projectName, url })
    },
  },
  watch: {
    visible(v) {
      if (!v) return
      this.activeTab = this.initialTab || 'casefile'
      // 上次查的那个人不能跟着弹窗一起回来——律师会以为这是这次要加的人
      this.addContact = ''
      this.clearCandidate()
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
      // 官方案件库状态独立失败：读不到就当没有，界面退回自建案件库那条路，不拖累连接列表
      try {
        const res = await getOfficialCloud()
        this.official = (res && res.data) || { available: false, connected: false, serverUrl: '' }
      } catch (e) {
        this.official = { available: false, connected: false, serverUrl: '' }
      }
    },
    async onShare() {
      if (this.busy) return
      // 连接一条都没有：不传 connectionId，让后端连官方案件库再共享（零配置直连）。
      // 已经连了案件库时反过来必须由律师指名放进哪一个：拿列表第一条会在存量死连接
      // （服务器早已不在、令牌早已失效）排在前面时，拿着死令牌去连一个不存在的后端。
      const connectionId = this.connections.length ? this.shareConnectionId : null
      if (this.connections.length && !connectionId) {
        uni.showToast({ title: this.$t('version.noLibrarySelectedToast'), icon: 'none' })
        return
      }
      this.busy = true
      try {
        await shareProjectToCloud(this.projectId, connectionId)
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
    // Options API 模板拿不到裸导入函数，包一层 method 才能在模板里当 getInitial(...) 调用
    getInitial,
    clearCandidate() {
      this.candidate = null
      this.lookupMessage = ''
      this.avatarBroken = false
    },
    async onLookup() {
      if (this.lookupBusy || !this.addContact) return
      this.lookupBusy = true
      this.clearCandidate()
      try {
        const res = await lookupCloudMember(this.projectId, this.addContact)
        const person = (res && res.data) || {}
        if (person.found) {
          this.candidate = person
        } else {
          // 「这个号还没人用过」是正常结果，就地显示那句话，不弹成像故障的提示
          this.lookupMessage = person.message || this.$t('version.colleagueNotFound')
        }
      } catch (e) {
        this.lookupMessage = (e && e.message) || this.$t('version.lookupFailed')
      } finally {
        this.lookupBusy = false
      }
    },
    async onAddMember() {
      if (this.memberBusy || !this.candidate || this.candidate.alreadyMember) return
      this.memberBusy = true
      try {
        await addCloudMember(this.projectId, this.addContact, this.addRole)
        this.addContact = ''
        this.clearCandidate()
        uni.showToast({ title: this.$t('version.addedSuccess'), icon: 'none' })
        await this.loadMembers()
        this.$emit('changed')
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
  position: fixed; inset: 0; background: var(--awd-overlay);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.collab-dialog {
  width: 640px; max-width: 92vw; max-height: 80vh;
  display: flex; flex-direction: column; background: var(--awd-surface);
  border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.collab-header {
  padding: 20px 24px 0; display: flex; align-items: center; justify-content: space-between;
}
.awd-title { font-size: 18px; font-weight: 600; color: var(--awd-text); }
.collab-close { font-size: 22px; color: var(--awd-text-3); cursor: pointer; line-height: 1; padding: 4px; }
.collab-close:hover { color: var(--awd-text); }

.collab-tabs { display: flex; border-bottom: 1px solid var(--awd-border); margin-top: 14px; padding: 0 24px; }
.collab-tab {
  padding: 10px 16px; font-size: 14px; color: var(--awd-text-2); cursor: pointer;
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.collab-tab:hover { color: var(--awd-accent-text); }
.collab-tab.active { color: var(--awd-accent-text); font-weight: 600; border-bottom-color: var(--awd-accent); }

.collab-body { padding: 20px 24px; overflow-y: auto; flex: 1; min-height: 220px; }
.collab-lead { font-size: 14px; color: var(--awd-text-2); line-height: 1.7; margin-bottom: 16px; }
.collab-note { font-size: 12.5px; color: var(--awd-text-3); line-height: 1.7; margin-top: 10px; }

.collab-state-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.collab-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--awd-warning); flex-shrink: 0; }
.collab-dot-amber { background: var(--awd-warning); }
.collab-dot-blue { background: var(--awd-info); }
.collab-dot-green { background: var(--awd-accent); }
.collab-state-text { font-size: 15px; font-weight: 600; color: var(--awd-text); }
.collab-meta { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; }
.collab-meta-line { font-size: 13px; color: var(--awd-text-2); word-break: break-all; }

.collab-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-top: 12px; }
.collab-empty-action { display: flex; flex-direction: column; gap: 10px; align-items: flex-start; }

.collab-field { margin-top: 20px; display: flex; flex-direction: column; gap: 8px; }
.collab-label { font-size: 13px; font-weight: 500; color: var(--awd-text); }
.collab-add-row { display: flex; gap: 10px; align-items: center; }
.collab-add-row .awd-input { flex: 1; }

.collab-picker, .collab-role-picker { display: flex; flex-direction: column; gap: 6px; }
.collab-picker-item {
  display: flex; align-items: center; gap: 8px; cursor: pointer;
  padding: 8px 10px; border: 1px solid var(--awd-border); border-radius: 6px;
}
.collab-picker-item.checked { border-color: var(--awd-accent); background: var(--awd-accent-soft); }
.collab-radio-dot {
  width: 12px; height: 12px; border-radius: 50%; border: 1px solid var(--awd-border-strong);
  background: var(--awd-surface); box-sizing: border-box; flex-shrink: 0;
}
.collab-picker-item.checked .collab-radio-dot { border-color: var(--awd-accent); background: var(--awd-accent); }
.collab-picker-text { font-size: 13.5px; color: var(--awd-text); word-break: break-all; }
.collab-picker-hint { font-size: 12px; color: var(--awd-text-3); margin-left: 4px; }

.member-candidate {
  margin-top: 12px; padding: 14px; border: 1px solid var(--awd-border); border-radius: 8px;
  display: flex; flex-direction: column; gap: 10px; background: var(--awd-bg);
}
.member-candidate-head { display: flex; align-items: center; gap: 12px; }
.member-candidate-avatar {
  width: 40px; height: 40px; border-radius: 50%; flex-shrink: 0; overflow: hidden;
}
.member-candidate-initial {
  display: flex; align-items: center; justify-content: center;
  background: var(--awd-accent-soft); color: var(--awd-accent-text);
  font-size: 16px; font-weight: 600;
}
.member-candidate-id { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.member-candidate-name { font-size: 15px; font-weight: 600; color: var(--awd-text); }
.member-candidate-contact { font-size: 12.5px; color: var(--awd-text-3); }

.collab-member-list { display: flex; flex-direction: column; }
.collab-member-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 0; border-bottom: 1px solid var(--awd-border-subtle);
}
.collab-member-name { font-size: 14px; color: var(--awd-text); }
.collab-member-role { font-size: 12.5px; color: var(--awd-text-2); }

.collab-invite-box {
  background: var(--awd-bg); border: 1px solid var(--awd-border); border-radius: 8px; padding: 12px;
}
.collab-invite-text { font-size: 13px; color: var(--awd-text); line-height: 1.8; white-space: pre-wrap; }

.collab-conn-list { display: flex; flex-direction: column; gap: 10px; }
.collab-conn-row {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px; border: 1px solid var(--awd-border); border-radius: 8px;
}
.collab-conn-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.collab-conn-url { font-size: 13.5px; color: var(--awd-text); word-break: break-all; }
.collab-conn-user { font-size: 12px; color: var(--awd-text-3); }
.collab-warn { font-size: 12px; color: var(--awd-warning-text); }

.awd-input {
  width: 100%; height: 38px; padding: 0 12px; border: 1px solid var(--awd-border-strong);
  border-radius: 6px; font-size: 14px; color: var(--awd-text); box-sizing: border-box;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 12px;
  padding: 14px 24px; border-top: 1px solid var(--awd-border-subtle); background: var(--awd-bg);
}
.awd-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 13.5px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.awd-btn-primary { background: var(--awd-accent); color: var(--awd-text-on-accent); }
.awd-btn-primary:hover { background: var(--awd-accent-hover); }
.awd-btn-secondary { background: var(--awd-surface); color: var(--awd-text-2); border: 1px solid var(--awd-border-strong); }
.awd-btn-secondary:hover { background: var(--awd-surface-2); }
.awd-btn-danger { background: var(--awd-surface); color: var(--awd-danger-text); border: 1px solid var(--awd-danger); }
.awd-btn-disabled { opacity: .45; pointer-events: none; }
</style>
