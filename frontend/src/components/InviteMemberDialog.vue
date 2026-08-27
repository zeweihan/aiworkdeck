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
          <view class="form-group">
            <text class="form-label">{{ $t('version.accountLabel') }}</text>
            <input
              class="workdeck-input"
              v-model="memberForm.username"
              :placeholder="$t('version.colleagueUsernamePlaceholderShort')"
              :focus="activeTab === 'MEMBER'"
            />
          </view>
          <view class="form-group">
            <text class="form-label">{{ $t('version.memberPermissionLabel') }}</text>
            <view class="role-options">
               <view
                 v-for="r in ASSIGNABLE_ROLES"
                 :key="r.value"
                 class="role-option"
                 :class="{ active: memberForm.role === r.value }"
                 :title="r.hint"
                 @tap="memberForm.role = r.value"
               >
                 <view class="role-dot"></view>
                 <text>{{ r.label }}</text>
               </view>
            </view>
            <text class="role-hint">{{ currentRoleHint }}</text>
          </view>
          <!-- 这里走 addProjectMember（本机这份案卷的参与人表），协作抽屉的「案件参与人」
               走 addCloudMember（团队案件库那边的表）。两条轨是既有机制，但两处现在共用
               同一套角色标签，界面上再无区别信号——只在库里加、没在这里加（或反过来）都
               会让同事白等，所以必须把这句话写在动作旁边。 -->
          <text class="role-hint">
            {{ $t('version.localMemberDualTrackHint') }}
          </text>
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
            <view class="workdeck-btn workdeck-btn-primary" @tap="submitMemberInvite" :class="{ disabled: loading }">
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
import { addProjectMember, inviteClient } from '@/services/api.js'
import { ASSIGNABLE_ROLES } from '@/config/memberRoles.js'

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
    }
  },
  data() {
    return {
      activeTab: 'MEMBER', // 'MEMBER' | 'CLIENT'
      memberForm: {
        username: '',
        role: 'PARTICIPANT'
      },
      clientName: '',
      clientInviteCode: '',
      loading: false,
      ASSIGNABLE_ROLES
    }
  },
  computed: {
    currentRoleHint() {
      const r = ASSIGNABLE_ROLES.find((x) => x.value === this.memberForm.role)
      return r ? r.hint : ''
    }
  },
  watch: {
    visible(val) {
      if (val) {
        // Reset state on open
        this.activeTab = 'MEMBER'
        this.memberForm = { username: '', role: 'PARTICIPANT' }
        this.clientName = ''
        this.clientInviteCode = ''
        this.loading = false
      }
    }
  },
  methods: {
    close() {
      this.$emit('update:visible', false)
      this.$emit('close')
    },
    async submitMemberInvite() {
       if (!this.memberForm.username) {
         uni.showToast({ title: this.$t('version.usernameRequired'), icon: 'none' })
         return
       }
       this.loading = true
       try {
         await addProjectMember(this.projectId, this.memberForm.username, this.memberForm.role)
         uni.showToast({ title: this.$t('version.addedSuccess'), icon: 'success' })
         this.$emit('success')
         this.close()
       } catch (e) {
         uni.showToast({ title: e.message || this.$t('version.addMemberFailed'), icon: 'none' })
       } finally {
         this.loading = false
       }
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
}

.role-hint {
  display: block;
  font-size: 12px;
  color: var(--awd-text-3);
  margin-top: 8px;
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

.workdeck-btn-primary.disabled {
  background: var(--awd-info);
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
