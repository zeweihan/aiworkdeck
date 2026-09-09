<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view v-if="visible" class="workdeck-dialog-mask" @tap="close">
    <view class="workdeck-dialog" @tap.stop>
      <view class="workdeck-dialog-header">
        <text class="workdeck-dialog-title">{{ $t('version.addPeopleTitle') }}</text>
        <view class="modal-close" @tap="close">×</view>
      </view>

      <!-- Tabs -->
      <view class="dialog-tabs">
        <view
          class="dialog-tab"
          :class="{ active: activeTab === 'MEMBER' }"
          @tap="activeTab = 'MEMBER'"
        >
          {{ $t('version.tabColleague') }}
        </view>
        <view
          class="dialog-tab"
          :class="{ active: activeTab === 'CLIENT' }"
          @tap="activeTab = 'CLIENT'"
        >
          {{ $t('version.tabClient') }}
        </view>
        <!-- Border bottom line -->
        <view class="tab-line" :style="{ left: activeTab === 'MEMBER' ? '0%' : '50%' }"></view>
      </view>

      <view class="workdeck-dialog-body">
        <!-- Internal Member Form -->
        <view v-if="activeTab === 'MEMBER'">
          <!-- 轨道还没判定出来（读 local-mode 与案件库状态）：先别把输入框摆出来，
               否则律师会在一个下一秒就要整块换掉的界面上开始打字。 -->
          <text v-if="trackLoading" class="role-hint">{{ $t('version.loadingGeneric') }}</text>

          <!-- 待放进案件库：桌面端单机装机的本机用户表里只有本机账号，同事一个都不在，
               在这里加谁都没有意义。先给「放进团队案件库」这一步。 -->
          <template v-else-if="needsLibrary">
            <text class="needs-library-lead">{{ $t('version.peopleNeedLinkFirst') }}</text>
            <view v-if="canShareToLibrary" class="needs-library-action">
              <text class="role-hint">{{ $t('version.addToOfficialLibraryNote') }}</text>
              <view
                class="workdeck-btn workdeck-btn-primary needs-library-btn"
                :class="{ disabled: sharing }"
                @tap="onShareToLibrary"
              >{{ sharing ? $t('version.processingEllipsis') : $t('version.addToOfficialLibraryTitle') }}</view>
            </view>
            <text v-else class="role-hint">{{ $t('version.noLibraryAvailableNote') }}</text>
            <text v-if="errorMessage" class="form-error">{{ errorMessage }}</text>
          </template>

          <template v-else>
            <view class="form-group">
              <text class="form-label">{{ $t('version.accountLabel') }}</text>
              <input
                class="workdeck-input"
                v-model="identifier"
                :placeholder="$t('version.colleagueIdentifierPlaceholder')"
                :focus="activeTab === 'MEMBER'"
              />

              <!-- 结果区：就地显示，一律不走 uni.showToast。
                   （toast 的层级问题已在 App.vue 修掉，但「查到谁了」这种要核对的信息
                   本来就该留在眼前，不该两秒后自己消失。） -->
              <view class="lookup-result">
                <text v-if="lookupBusy" class="lookup-hint">{{ $t('version.lookingUp') }}</text>

                <view v-else-if="candidate" class="member-candidate">
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
                  <text v-if="candidate.alreadyMember" class="lookup-hint">
                    {{ $t('version.alreadyInCaseFileAs', { role: roleLabel(candidate.currentRole) }) }}
                  </text>
                </view>

                <view v-else-if="notFoundMessage" class="lookup-notfound">
                  <text class="lookup-notfound-title">{{ $t('version.noSuchUser') }}</text>
                  <text class="lookup-hint">{{ notFoundMessage }}</text>
                  <text v-if="!inviteOpen" class="copy-link" @tap="inviteOpen = true">{{ $t('version.goInvite') }}</text>

                  <view v-else class="invite-block">
                    <text class="form-label">{{ $t('version.inviteLinkLabel') }}</text>
                    <view class="invite-link-row">
                      <text class="invite-link-text" selectable>{{ inviteLink }}</text>
                      <text class="copy-link" @tap="copyInviteLink">{{ $t('version.copyLink') }}</text>
                    </view>
                    <text v-if="linkCopied" class="copied-inline">{{ $t('version.copiedInline') }}</text>
                    <text class="role-hint">{{ $t('version.inviteLinkNote') }}</text>

                    <!-- 云端轨额外给整段加入说明：同事装好之后还要从团队案件库把这份
                         案卷取到本机，那几步只有这段话讲得清。本机轨没有这一步。 -->
                    <template v-if="isCloudTrack">
                      <text class="form-label invite-text-label">{{ $t('version.inviteInstructionsLabel') }}</text>
                      <view class="invite-desc-box"><text class="invite-desc">{{ inviteText }}</text></view>
                      <text class="copy-link" @tap="copyInviteText">{{ $t('version.copyThisText') }}</text>
                      <text v-if="textCopied" class="copied-inline">{{ $t('version.copiedInline') }}</text>
                    </template>
                  </view>
                </view>

                <text v-else-if="!errorMessage" class="lookup-hint">{{ $t('version.addColleagueByContactNote') }}</text>
              </view>
            </view>

            <!-- 角色单选：只有真查到一个还不在案卷里的人时才有意义 -->
            <view v-if="canSubmit" class="form-group">
              <text class="form-label">{{ $t('version.memberPermissionLabel') }}</text>
              <view class="role-options">
                 <view
                   v-for="r in ASSIGNABLE_ROLES"
                   :key="r.value"
                   class="role-option"
                   :class="{ active: role === r.value }"
                   :title="r.hint"
                   @tap="role = r.value"
                 >
                   <view class="role-dot"></view>
                   <text>{{ r.label }}</text>
                 </view>
              </view>
              <text class="role-hint">{{ currentRoleHint }}</text>
            </view>

            <!-- 本机轨（自建多用户服务器）加的是本机这份案卷的参与人；案卷同时放进过
                 团队案件库时，库那边的名单是另一张表，不再加一次同事就取不到案卷。
                 云端轨不显示这句——那时加的就是库里那张表本身。 -->
            <text v-if="showDualTrackHint" class="role-hint">
              {{ $t('version.localMemberDualTrackHint') }}
            </text>

            <text v-if="addedName" class="added-inline">{{ $t('version.addedPerson', { name: addedName }) }}</text>
            <text v-if="errorMessage" class="form-error">{{ errorMessage }}</text>
          </template>
        </view>

        <!-- External Client Form -->
        <view v-else>
           <view class="invite-desc-box">
             <text class="invite-desc">{{ $t('version.clientInviteDesc') }}</text>
           </view>

           <view v-if="!clientInviteCode">
               <view class="form-group">
                 <text class="form-label">{{ $t('version.clientNameLabel') }}</text>
                 <input
                   class="workdeck-input"
                   v-model="clientName"
                   :placeholder="$t('version.clientNamePlaceholder')"
                 />
               </view>
           </view>

           <view v-else class="code-result-box">
               <text class="code-label">{{ $t('version.accessCodeLabel') }}</text>
               <view class="code-display-row">
                   <text class="code-text">{{ clientInviteCode }}</text>
                   <text class="copy-link" @tap="copyClientCode">{{ $t('version.copy') }}</text>
               </view>
               <text class="code-tip">{{ $t('version.accessCodeTip') }}</text>
           </view>
        </view>
      </view>

      <view class="workdeck-dialog-footer">
        <view class="workdeck-btn workdeck-btn-secondary" @tap="close">{{ $t('common.cancel') }}</view>

        <block v-if="activeTab === 'MEMBER'">
            <view
              v-if="!trackLoading && !needsLibrary"
              class="workdeck-btn workdeck-btn-primary"
              :class="{ disabled: loading || !canSubmit }"
              @tap="submitMemberInvite"
            >
                {{ loading ? $t('version.processingEllipsis') : $t('version.addAction') }}
            </view>
        </block>
        <block v-else>
            <view v-if="!clientInviteCode" class="workdeck-btn workdeck-btn-primary" @tap="generateClientCode" :class="{ disabled: loading }">
                {{ loading ? $t('version.generatingEllipsis') : $t('version.generateAccessCode') }}
            </view>
            <view v-else class="workdeck-btn workdeck-btn-primary" @tap="close">{{ $t('version.finish') }}</view>
        </block>
      </view>
    </view>
  </view>
</template>

<script>
import {
  addProjectMember, lookupProjectMember, inviteClient,
  getLocalIdentityStatus, getCloudStatus, getOfficialCloud, listCloudConnections,
  shareProjectToCloud, addCloudMember, lookupCloudMember,
} from '@/services/api.js'
import { ASSIGNABLE_ROLES, roleLabel } from '@/config/memberRoles.js'
import { getInitial } from '@/utils/textInitial.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { siteBaseUrl } from '@/utils/siteLinks.js'
import { shareProjectToLibrary } from '@/utils/cloudShare.js'
import { TRACK, resolveTrack, isWorthLooking, lookupIdentifier, inviteLinkFor } from '@/utils/memberLookup.js'

// local-mode 是一台机器的装机形态，一次进程内不会变。弹窗每次打开都问一遍后端
// 纯属浪费——而这个请求恰好挡在「输入框出不出得来」前面，慢一次就是一次白屏。
let localModeCache = null
async function readLocalMode() {
  if (localModeCache !== null) return localModeCache
  try {
    // 这个端点回裸 JSON（没有 code/data 包装），见 api.js 的注释
    const identity = await getLocalIdentityStatus()
    localModeCache = !!(identity && identity.localMode)
  } catch (e) {
    // 读不到按「不是 local-mode」处理：那条轨最差也只是查不到人（就地显示「没有这个
    // 用户」＋邀请链接）；按 local-mode 处理则会把界面锁死在「先放进案件库」上，
    // 自建服务器的用户连输入框都见不到。
    localModeCache = false
  }
  return localModeCache
}

const LOOKUP_DEBOUNCE_MS = 500

export default {
  name: 'InviteMemberDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    projectId: {
      type: [Number, String],
      required: true
    },
    // 宿主已经拉过的云端状态（形状同 CollabDialog 的 cloud prop：{linked, ...}）。
    // 不传就自己调 getCloudStatus——项目列表页没有这个状态，弹窗不能因此瘫掉。
    cloud: {
      type: Object,
      default: null
    },
    // 下面两个只用于云端轨的「发给同事的加入说明」（同 CollabDialog.inviteText）
    projectName: {
      type: String,
      default: ''
    },
    inviterName: {
      type: String,
      default: ''
    }
  },
  emits: ['update:visible', 'close', 'success'],
  data() {
    return {
      activeTab: 'MEMBER', // 'MEMBER' | 'CLIENT'
      identifier: '',
      role: 'PARTICIPANT',
      clientName: '',
      clientInviteCode: '',
      loading: false,
      // ---- 轨道 ----
      track: '',
      trackLoading: false,
      linked: false,
      connections: [],
      officialAvailable: false,
      sharing: false,
      // ---- 边输入边查 ----
      // 递增序号：快速改输入时，早发出的请求可能后回来，旧回包不能覆盖新结果
      lookupSeq: 0,
      lookupBusy: false,
      candidate: null,
      avatarBroken: false,
      notFoundMessage: '',
      // ---- 就地反馈 ----
      errorMessage: '',
      addedName: '',
      inviteOpen: false,
      linkCopied: false,
      textCopied: false,
      ASSIGNABLE_ROLES
    }
  },
  computed: {
    isCloudTrack() {
      return this.track === TRACK.CLOUD
    },
    needsLibrary() {
      return this.track === TRACK.NEEDS_LIBRARY
    },
    // 本机既没有官方案件库也没有任何连接时，这份案卷放不进任何地方，按钮不给
    canShareToLibrary() {
      return this.officialAvailable || this.connections.length > 0
    },
    canSubmit() {
      return !!(this.candidate && !this.candidate.alreadyMember)
    },
    showDualTrackHint() {
      return this.track === TRACK.LOCAL && this.linked
    },
    currentRoleHint() {
      const r = ASSIGNABLE_ROLES.find((x) => x.value === this.role)
      return r ? r.hint : ''
    },
    inviteLink() {
      return inviteLinkFor(siteBaseUrl(), getAppLanguage())
    },
    // 与 CollabDialog.inviteText 同源同键：有/无邀请人分两个键，
    // 英文人名后要空格，单键拼 {inviter} 在两种语言里无法同时成立。
    inviteText() {
      const inviter = this.inviterName || ''
      return inviter
        ? this.$t('version.inviteTextOfficial', { inviter, project: this.projectName })
        : this.$t('version.inviteTextOfficialNoInviter', { project: this.projectName })
    }
  },
  watch: {
    visible(val) {
      if (val) {
        // Reset state on open
        this.activeTab = 'MEMBER'
        this.identifier = ''
        this.role = 'PARTICIPANT'
        this.clientName = ''
        this.clientInviteCode = ''
        this.loading = false
        this.sharing = false
        this.errorMessage = ''
        this.addedName = ''
        this.clearLookupResult()
        this.resolveTrackState()
      } else {
        this.cancelPendingLookup()
      }
    },
    identifier(val) {
      // 值一变先把上一次的结果清掉：留着旧人卡的话，律师改了号码却仍看着上一个人，
      // 点「加进来」加的是他没打算加的那个。
      this.clearLookupResult()
      this.cancelPendingLookup()
      if (!isWorthLooking(val)) return
      this._lookupTimer = setTimeout(() => this.runLookup(val), LOOKUP_DEBOUNCE_MS)
    }
  },
  beforeUnmount() {
    this.cancelPendingLookup()
  },
  methods: {
    roleLabel,
    // Options API 模板拿不到裸导入函数，包一层 method 才能在模板里当 getInitial(...) 调用
    getInitial,
    close() {
      this.$emit('update:visible', false)
      this.$emit('close')
    },
    cancelPendingLookup() {
      if (this._lookupTimer) {
        clearTimeout(this._lookupTimer)
        this._lookupTimer = null
      }
    },
    clearLookupResult() {
      // 序号也要推进：已经在飞的那个请求回来时要被当成过期结果丢掉
      this.lookupSeq += 1
      this.lookupBusy = false
      this.candidate = null
      this.avatarBroken = false
      this.notFoundMessage = ''
      this.errorMessage = ''
      this.addedName = ''
      this.inviteOpen = false
      this.linkCopied = false
      this.textCopied = false
    },
    /**
     * 判定这次该走哪条轨（三态见 utils/memberLookup.js 的 TRACK）。
     * forceFetch：刚「放进案件库」完，宿主传进来的 cloud prop 还是旧的，必须重拉。
     */
    async resolveTrackState({ forceFetch = false } = {}) {
      this.trackLoading = true
      try {
        const localMode = await readLocalMode()
        let linked = false
        if (this.cloud && !forceFetch) {
          linked = !!this.cloud.linked
        } else {
          try {
            const res = await getCloudStatus(this.projectId)
            linked = !!(res && res.data && res.data.linked)
          } catch (e) {
            linked = false
          }
        }
        this.linked = linked
        this.track = resolveTrack({ localMode, linked })
        if (this.track === TRACK.NEEDS_LIBRARY) await this.loadLibraryOptions()
      } finally {
        this.trackLoading = false
      }
    },
    // 「放进团队案件库」按钮要不要给：官方案件库可得，或本机已经连过库。
    // 两个请求各自失败各自退成「没有」，不互相拖累。
    async loadLibraryOptions() {
      try {
        const res = await listCloudConnections()
        this.connections = (res && res.data && res.data.connections) || []
      } catch (e) {
        this.connections = []
      }
      try {
        const res = await getOfficialCloud()
        this.officialAvailable = !!(res && res.data && res.data.available)
      } catch (e) {
        this.officialAvailable = false
      }
    },
    async onShareToLibrary() {
      if (this.sharing) return
      this.sharing = true
      this.errorMessage = ''
      try {
        const res = await shareProjectToLibrary({
          projectId: this.projectId,
          connections: this.connections,
          share: shareProjectToCloud,
        })
        if (!res.ok) {
          this.errorMessage = this.$t('version.tooManyLibraries')
          return
        }
        uni.showToast({ title: this.$t('version.sharedToLibrary'), icon: 'none' })
        this.$emit('success')
        await this.resolveTrackState({ forceFetch: true })
      } catch (e) {
        this.errorMessage = (e && e.message) || this.$t('version.shareToLibraryFailed')
      } finally {
        this.sharing = false
      }
    },
    async runLookup(rawValue) {
      const seq = ++this.lookupSeq
      this.lookupBusy = true
      try {
        const id = lookupIdentifier(rawValue)
        const res = this.isCloudTrack
          ? await lookupCloudMember(this.projectId, id)
          : await lookupProjectMember(this.projectId, id)
        if (seq !== this.lookupSeq) return
        const person = (res && res.data) || {}
        if (person.found) {
          this.candidate = person
          this.avatarBroken = false
        } else {
          // 「这个号还没人用过」是正常结果（后端 code=0），就地显示那句话
          this.notFoundMessage = person.message || this.$t('version.colleagueNotFound')
        }
      } catch (e) {
        if (seq !== this.lookupSeq) return
        // 含限频（「查找同事过于频繁」）：这一条是真的出了状况，红字说清楚
        this.errorMessage = (e && e.message) || this.$t('version.lookupFailed')
      } finally {
        if (seq === this.lookupSeq) this.lookupBusy = false
      }
    },
    async submitMemberInvite() {
       if (this.loading || !this.canSubmit) return
       this.loading = true
       this.errorMessage = ''
       try {
         const id = lookupIdentifier(this.identifier)
         if (this.isCloudTrack) await addCloudMember(this.projectId, id, this.role)
         else await addProjectMember(this.projectId, id, this.role)
         this.addedName = this.candidate.displayName || this.candidate.maskedContact || id
         uni.showToast({ title: this.$t('version.addedSuccess'), icon: 'success' })
         this.$emit('success')
         // 先让「已把某某加进来」这一行留在眼前一拍再关：立刻关掉的话，这次动作
         // 唯一的回执就只剩那个两秒后自己消失的 toast，正是这次要修的那种「没反应」。
         // loading 有意不在这里放开：放开的话这 900ms 里主按钮又变回可点，
         // 手快的人能把同一个人加两次。
         setTimeout(() => this.close(), 900)
       } catch (e) {
         this.errorMessage = (e && e.message) || this.$t('version.addMemberFailed')
         this.loading = false
       }
    },
    copyInviteLink() {
      uni.setClipboardData({
        data: this.inviteLink,
        success: () => { this.linkCopied = true },
      })
    },
    copyInviteText() {
      uni.setClipboardData({
        data: this.inviteText,
        success: () => { this.textCopied = true },
      })
    },
    async generateClientCode() {
        this.loading = true
        try {
            const res = await inviteClient(this.projectId, this.clientName)
            if (res.code === 0 && res.data && res.data.accessCode) {
                this.clientInviteCode = res.data.accessCode
            } else {
                throw new Error(this.$t('version.generateFailed'))
            }
        } catch (e) {
            uni.showToast({ title: e.message || this.$t('version.generateFailed'), icon: 'none' })
        } finally {
            this.loading = false
        }
    },
    copyClientCode() {
        if (!this.clientInviteCode) return
        uni.setClipboardData({
            data: this.clientInviteCode,
            success: () => {
                uni.showToast({ title: this.$t('common.copied'), icon: 'success' })
            }
        })
    }
  }
}
</script>

<style scoped>
/* Workdeck Dialog Styles + Specifics */
.workdeck-dialog-mask {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background-color: var(--awd-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(2px);
}

.workdeck-dialog {
  width: 618px; /* Golden Ratio */
  background: var(--awd-surface);
  border-radius: 12px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
}

.workdeck-dialog-header {
  padding: 24px 32px 0;
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.workdeck-dialog-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--awd-text);
}

.modal-close {
  font-size: 24px;
  color: var(--awd-text-3);
  cursor: pointer;
  line-height: 1;
  padding: 4px;
}

.modal-close:hover {
  color: var(--awd-text);
}

/* Tabs */
.dialog-tabs {
  display: flex;
  position: relative;
  border-bottom: 1px solid var(--awd-border);
  margin-top: 16px;
}

.dialog-tab {
  flex: 1;
  text-align: center;
  padding: 12px 0;
  font-size: 15px;
  color: var(--awd-text-2);
  cursor: pointer;
  font-weight: 500;
  transition: all 0.2s;
}

.dialog-tab:hover {
  color: var(--awd-accent-text);
  background: var(--awd-bg);
}

.dialog-tab.active {
  color: var(--awd-accent-text);
  font-weight: 600;
}

.tab-line {
  position: absolute;
  bottom: 0;
  height: 2px;
  background: var(--awd-accent);
  width: 50%;
  transition: left 0.3s ease;
}

.workdeck-dialog-body {
  padding: 24px 32px;
  min-height: 200px;
}

.form-group {
  margin-bottom: 20px;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--awd-text);
  margin-bottom: 8px;
}

.workdeck-input {
  width: 100%;
  height: 44px;
  padding: 0 12px;
  border: 1px solid var(--awd-border-strong);
  border-radius: 6px;
  font-size: 14px;
  color: var(--awd-text);
  transition: all 0.2s;
  box-sizing: border-box;
}

.workdeck-input:focus {
  border-color: var(--awd-accent);
  outline: none;
  box-shadow: 0 0 0 3px rgba(26, 83, 54, 0.1);
}

/* ---- 查人结果区 ---- */
.lookup-result {
  margin-top: 10px;
  min-height: 20px;
}

.lookup-hint {
  display: block;
  font-size: 12px;
  color: var(--awd-text-2);
  line-height: 1.6;
}

.member-candidate {
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  padding: 12px;
  background: var(--awd-bg);
}

.member-candidate-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.member-candidate-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  flex-shrink: 0;
}

.member-candidate-initial {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
  font-size: 15px;
  font-weight: 600;
}

.member-candidate-id {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.member-candidate-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.member-candidate-contact {
  font-size: 12px;
  color: var(--awd-text-2);
}

.lookup-notfound {
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  padding: 12px;
  background: var(--awd-bg);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lookup-notfound-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.invite-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
  padding-top: 10px;
  border-top: 1px solid var(--awd-border);
}

.invite-link-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.invite-link-text {
  flex: 1;
  font-size: 13px;
  color: var(--awd-text);
  background: var(--awd-surface-2);
  padding: 6px 10px;
  border-radius: 6px;
  word-break: break-all;
  user-select: text;
}

.invite-text-label {
  margin-top: 8px;
}

.copied-inline {
  display: block;
  font-size: 12px;
  color: var(--awd-accent-text);
}

.added-inline {
  display: block;
  font-size: 13px;
  color: var(--awd-accent-text);
  margin-top: 8px;
}

.form-error {
  display: block;
  font-size: 13px;
  color: var(--awd-danger-text);
  margin-top: 8px;
  line-height: 1.6;
}

/* ---- 待放进案件库 ---- */
.needs-library-lead {
  display: block;
  font-size: 14px;
  color: var(--awd-text);
  line-height: 1.7;
  margin-bottom: 12px;
}

.needs-library-action {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: flex-start;
}

.needs-library-btn {
  align-self: flex-start;
}

/* Role Options */
.role-options {
  display: flex;
  gap: 16px;
}

.role-option {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 20px;
  border: 1px solid var(--awd-border);
  font-size: 13px;
  color: var(--awd-text-2);
  transition: all 0.2s;
}

.role-option:hover {
  border-color: var(--awd-border-strong);
  background: var(--awd-bg);
}

.role-option.active {
  border-color: var(--awd-accent);
  background: var(--awd-bg);
  color: var(--awd-accent-text);
}

.role-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 1px solid var(--awd-border-strong);
  background: var(--awd-surface);
  position: relative;
}

.role-option.active .role-dot {
  border-color: var(--awd-accent);
  background: var(--awd-accent);
}

.role-option.active .role-dot::after {
  content: '';
  position: absolute;
  top: 4px; left: 4px; right: 4px; bottom: 4px;
  background: var(--awd-surface);
  border-radius: 50%;
}

/* Client Invite */
.invite-desc-box {
  background: var(--awd-bg);
  padding: 12px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.invite-desc {
  font-size: 13px;
  color: var(--awd-text-2);
  line-height: 1.6;
  white-space: pre-wrap;
}

.role-hint {
  display: block;
  font-size: 12px;
  color: var(--awd-text-3);
  margin-top: 8px;
  line-height: 1.6;
}

.code-tip {
  display: block;
  font-size: 12px;
  color: var(--awd-text-3);
  line-height: 1.6;
  margin-top: 12px;
}

.code-result-box {
  text-align: center;
  padding: 20px 0;
}

.code-label {
  font-size: 14px;
  color: var(--awd-text-2);
  margin-bottom: 8px;
  display: block;
}

.code-display-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.code-text {
  font-family: monospace;
  font-size: 24px;
  color: var(--awd-text);
  letter-spacing: 2px;
  background: var(--awd-surface-2);
  padding: 4px 12px;
  border-radius: 6px;
}

.copy-link {
  color: var(--awd-accent-text);
  font-size: 14px;
  cursor: pointer;
  text-decoration: underline;
  align-self: flex-start;
}

.workdeck-dialog-footer {
  padding: 20px 32px 24px;
  background: var(--awd-bg);
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  border-top: 1px solid var(--awd-border-subtle);
}

.workdeck-btn {
  height: 40px;
  padding: 0 24px;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.workdeck-btn-primary {
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border: 1px solid transparent;
}

.workdeck-btn-primary:hover {
  background: var(--awd-accent-hover);
}

/* 没查到人时主按钮是常态禁用（改造前它几乎总是可点，所以没人在意这条）。
   原先禁用态填的是 --awd-info（亮蓝），比可用态的墨绿还抢眼，看着像"这才是要点的按钮"
   ——禁用必须看起来是灰的。 */
.workdeck-btn-primary.disabled,
.workdeck-btn-primary.disabled:hover {
  background: var(--awd-surface-3);
  color: var(--awd-text-3);
  cursor: not-allowed;
}

.workdeck-btn-secondary {
  background: var(--awd-surface);
  color: var(--awd-text-2);
  border: 1px solid var(--awd-border-strong);
}

.workdeck-btn-secondary:hover {
  background: var(--awd-surface-2);
  border-color: var(--awd-info);
  color: var(--awd-text);
}
</style>
