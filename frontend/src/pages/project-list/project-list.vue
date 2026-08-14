<template>
  <view class="page-project-list">
    <view class="project-list-container">
      <view class="main-content">
        <view class="content-header">
          <text class="header-title">{{ $t('projects.myProjects') }}</text>
          <!-- header-actions 本身不受角色/项目数门控：CLIENT 或零项目新用户否则在本页
               找不到任何通往个人中心的入口（登出/设置/解除授权全部不可达）。
               门控只收窄到「新建项目/取案卷」这两个写操作按钮上。 -->
          <view class="header-actions">
            <template v-if="!isClientUser && projects.length > 0">
              <button class="btn-secondary-small" @tap="openCloudAccept">{{ $t('projects.pullFromTeamLibrary') }}</button>
              <button class="btn-primary-small" @tap="goToNewProject">{{ $t('projects.newProjectBtn') }}</button>
            </template>
            <button class="btn-secondary-small" @tap="goToUserProfile">{{ $t('projects.personalCenter') }}</button>
          </view>
        </view>

        <view class="panel-projects">
          <!-- 只留「全部项目」一张卡：原先的「进行中」「已完成」是写死的字面量 0，
               Project 实体根本没有状态字段，搬迁时按 spec §4.3 删掉，不把假数字带过来 -->
          <view class="projects-stats-row">
            <view class="stat-card">
              <text class="stat-value">{{ projects.length }}</text>
              <text class="stat-label">{{ $t('projects.allProjects') }}</text>
            </view>
          </view>

          <view v-if="projectsLoading" class="loading-state">
            <text class="loading-text">{{ $t('projects.loading') }}</text>
          </view>

          <view v-else-if="projects.length === 0">
            <!-- CLIENT 没有建项目/取案卷的入口，空态只作说明 -->
            <template v-if="isClientUser">
              <view class="empty-state-dashed client-empty">
                <view class="dashed-content">
                  <text class="dashed-text">{{ $t('projects.clientEmptyHint') }}</text>
                </view>
              </view>
            </template>
            <template v-else>
              <view class="empty-state-dashed" @tap="goToNewProject">
                <view class="dashed-content">
                  <text class="dashed-icon">{{ $t('projects.plusSign') }}</text>
                  <text class="dashed-text">{{ $t('projects.newProject') }}</text>
                </view>
              </view>
              <!-- 协作的唯一入口。CollabDialog 的邀请话术写死指向这里，别删 -->
              <view class="cloud-accept-entry" @tap="openCloudAccept">
                <text class="cloud-accept-entry-text">{{ $t('projects.pullFromTeamLibrary') }}</text>
              </view>
            </template>
          </view>

          <view v-else class="project-grid">
            <view
              v-for="project in projects"
              :key="project.id"
              class="project-item-card"
              :class="getProjectCardClass(project.projectType)"
              @tap="goToProject(project.id)"
            >
              <view class="card-deco-header"></view>

              <view class="card-top-row">
                <view class="project-type-badge-new">
                  <text class="badge-text-new">{{ getProjectTypeLabel(project.projectType) }}</text>
                </view>
                <view v-if="!isClientUser" class="card-actions">
                  <view class="action-btn-icon danger" @tap.stop="handleDeleteProject(project.id)" :title="$t('projects.delete')"><svg class="act-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                </view>
              </view>

              <view class="card-main-content">
                <view class="project-title-area">
                  <view v-if="renamingProjectId === project.id" class="rename-box" @tap.stop>
                    <input
                      class="rename-input"
                      v-model="renameValue"
                      :focus="true"
                      @confirm="confirmRename"
                      @blur="cancelRename"
                    />
                  </view>
                  <view v-else class="title-row-flex">
                    <text class="project-title-new" @tap.stop="startRename(project)">{{ project.name }}</text>
                    <view class="project-role-badge" :class="getRoleClass(project.myRole)">
                      <text class="role-text">{{ getRoleLabel(project.myRole) }}</text>
                    </view>
                  </view>
                </view>

                <view class="company-info-area" v-if="project.projectType !== 'BLANK'">
                  <view class="info-row-new" v-if="shouldShowListedCompany(project.projectType)">
                    <text class="info-label-new">{{ $t('projects.listedCompany') }}</text>
                    <text class="info-val-new highlight">{{ project.listedCompanyName || '-' }}</text>
                  </view>
                  <view class="info-row-new" v-if="shouldShowTargetCompany(project.projectType)">
                    <text class="info-label-new">{{ $t('projects.targetCompany') }}</text>
                    <text class="info-val-new">{{ project.targetCompanyName || '-' }}</text>
                  </view>
                </view>
                <view v-else class="blank-placeholder">
                  <text class="placeholder-text">{{ $t('projects.blankWorkspace') }}</text>
                </view>
              </view>

              <view class="card-footer-new">
                <view class="members-area-new">
                  <view class="manager-avatar-wrapper" v-if="project.managerId" :title="$t('projects.managerLabel', { name: project.managerName || $t('projects.unknown') })">
                    <image v-if="project.managerAvatarUrl" :src="project.managerAvatarUrl" class="manager-avatar-img" />
                    <view v-else class="manager-avatar-placeholder">{{ project.managerName?.charAt(0) || 'M' }}</view>
                    <view class="manager-badge-icon"><svg class="badge-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.crown" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                  </view>
                  <view class="members-divider" v-if="project.managerId && getInternalMembers(project).length > 0"></view>

                  <view class="members-split-container">
                    <view class="members-group">
                      <view v-for="member in getInternalMembers(project)" :key="member.id" class="member-avatar-new" :title="member.displayName">
                        <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                        <view v-else class="avatar-placeholder-new">{{ member.displayName?.charAt(0) || 'U' }}</view>
                        <view v-if="canManageMembers(project) && member.userId !== userInfo.id" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
                      </view>
                      <view v-if="canManageMembers(project)" class="add-member-btn-new" @tap.stop="openInviteModal(project.id)">+</view>
                    </view>

                    <view class="members-vertical-divider" v-if="getClientMembers(project).length > 0"></view>

                    <view class="members-group clients-group" v-if="getClientMembers(project).length > 0">
                      <text class="client-group-label">{{ $t('projects.clientLabel') }}</text>
                      <view v-for="member in getClientMembers(project)" :key="member.id" class="member-avatar-new client-avatar" :title="$t('projects.clientMemberTitle', { name: member.displayName })">
                        <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                        <view v-else class="avatar-placeholder-new client-placeholder">{{ member.displayName?.charAt(0) || $t('projects.clientInitial') }}</view>
                        <view v-if="canManageMembers(project)" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
                      </view>
                    </view>
                  </view>
                </view>
                <view class="footer-meta">
                  <text class="time-text-new">{{ formatTime(project.createdAt) }}</text>
                  <view class="enter-btn-arrow">
                    <text class="arrow-char">→</text>
                  </view>
                </view>
              </view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <InviteMemberDialog
      v-model:visible="showInviteModal"
      :project-id="currentInviteProjectId"
      @success="loadProjects"
      @close="closeInviteModal"
    />

    <CloudAcceptDialog
      v-model:visible="showCloudAccept"
      @accepted="onCloudAccepted"
    />
  </view>
</template>

<script>
/**
 * 项目列表页（三级导航第一级）。
 *
 * 2026-08-08 从 pages/userprofile/userprofile.vue 的「我的项目」tab 整块搬出：
 * 项目寄居在个人中心的一个 tab 里，是前端把项目概念弱化掉的历史遗留（后端 Project
 * 一直是一等公民）。搬出来之后个人中心只管人，这里只管案卷。
 *
 * 点卡片主体进的是**项目概览页**（pages/project-home），不是工作台——工作台是第三级。
 *
 * 本页不做 host.browser.setViewsVisible(false)：非工作台页面不必自己隐藏 BrowserView，
 * 兜底在工作台的 onHide/onUnload（admin / plugin-market / variable-library 都没做）。
 */
import { getMyProjects, deleteProject, renameProject, getProjectMembers, removeProjectMember, getCurrentUser as getCurrentUserApi } from '@/services/api.js'
import { getProjectTypeLabel } from '@/config/projectTypes.js'
import { roleLabel, ROLE_LABELS } from '@/config/memberRoles.js'
import { getCurrentUser, getSessionId } from '@/utils/auth.js'
import { isDesktopHost } from '@/services/host.js'
import { ICONS } from '@/config/icons.js'
import InviteMemberDialog from '@/components/InviteMemberDialog.vue'
import CloudAcceptDialog from '@/components/CloudAcceptDialog.vue'

export default {
  name: 'ProjectList',
  components: {
    InviteMemberDialog,
    CloudAcceptDialog,
  },
  computed: {
    ICONS() {
      return ICONS
    },
    isDesktop() {
      return isDesktopHost()
    },
    // CLIENT 看得见别人分享给他的案卷（ProjectService.getUserProjects 把成员身份的项目
    // 也算进去），但建项目/取案卷/删除/重命名/邀请全部对他隐藏。
    // 角色在一次会话里不会变，computed 无响应式依赖只算一次正合适。
    isClientUser() {
      const u = getCurrentUser()
      return !!u && u.role === 'CLIENT'
    },
  },
  data() {
    return {
      userInfo: {
        id: null,
        username: '',
        displayName: '用户',
        avatarUrl: null,
      },
      projects: [],
      projectsLoading: false,
      deletingProjectId: null,
      renamingProjectId: null,
      renameValue: '',

      showInviteModal: false,
      currentInviteProjectId: null,
      showCloudAccept: false,
    }
  },
  onLoad() {
    if (!this.ensureLoggedIn()) return
    this.loadUserInfo()
  },
  onShow() {
    // 从概览页 navigateBack、从新建项目页回来都要看到最新结果（改名/删除都在这一页做）
    if (!this.ensureLoggedIn()) return
    this.loadProjects()
  },
  methods: {
    // 浏览器端未登录直接回登录页；桌面 local-mode 免登，跳过该检查（同 userprofile.vue:565-578）
    ensureLoggedIn() {
      if (isDesktopHost()) return true
      if (getSessionId() && getCurrentUser()) return true
      uni.reLaunch({ url: '/pages/login/login' })
      return false
    },
    async loadUserInfo() {
      const user = getCurrentUser()
      if (user) this.userInfo = user
      // isProjectAdmin 要靠 userInfo.id，缓存里可能没有，拉一次 /api/auth/me 补齐
      try {
        const res = await getCurrentUserApi()
        if (res && res.code === 0 && res.data) {
          this.userInfo = { ...this.userInfo, ...res.data }
        }
      } catch (e) {
        // 拿不到就用缓存那份，不拦路
        console.error('获取用户信息失败:', e)
      }
    },
    async loadProjects() {
      this.projectsLoading = true
      try {
        // getMyProjects 返回的是裸数组（ProjectController 直接返 List<ProjectCardDTO>，
        // 无信封）。这里写 res.data 会恒空——admin.vue 现在就踩着这个坑。
        const projects = await getMyProjects()
        // 每个项目一次成员查询（N+1）。这是既有行为，spec §9 第 11 条已记为前置修复，
        // 属 Plan 3；本次原样搬，不要顺手优化（改 ProjectCardDTO 会牵动
        // ProjectService 的 BeanUtils.copyProperties 那条静默失败链）。
        const projectsWithMembers = await Promise.all(projects.map(async (p) => {
          try {
            const res = await getProjectMembers(p.id)
            let members = res.data || []
            // getProjectMembers 返回 project_member 裸行，owner 可能另有一行，去重
            const seen = new Set()
            members = members.filter((m) => {
              if (seen.has(m.userId)) return false
              seen.add(m.userId)
              return true
            })
            return { ...p, members }
          } catch (e) {
            console.error(`Failed to load members for project ${p.id}`, e)
            return { ...p, members: [] }
          }
        }))
        this.projects = projectsWithMembers
      } catch (error) {
        console.error('加载项目列表失败:', error)
        // 桌面端免登：绝不跳 login（launch 分流已保证桌面不进登录页，这里若跳就是死胡同），
        // 只提示错误。浏览器端保留原「登录失效回登录页」兜底。
        if (!this.isDesktop && error.message && error.message.includes('登录')) {
          uni.reLaunch({ url: '/pages/login/login' })
        } else {
          uni.showToast({
            title: error.message || this.$t('projects.loadFailedRetry'),
            icon: 'none',
            duration: 2000,
          })
        }
      } finally {
        this.projectsLoading = false
      }
    },

    // ---- 成员 ----
    openInviteModal(projectId) {
      this.currentInviteProjectId = projectId
      this.showInviteModal = true
    },
    closeInviteModal() {
      this.showInviteModal = false
      this.currentInviteProjectId = null
    },
    async removeMember(projectId, userId) {
      uni.showModal({
        title: this.$t('projects.removeConfirmTitle'),
        content: this.$t('projects.removeConfirmContent'),
        cancelText: this.$t('projects.cancel'),
        confirmText: this.$t('projects.confirm'),
        success: async (res) => {
          if (res.confirm) {
            try {
              await removeProjectMember(projectId, userId)
              uni.showToast({ title: this.$t('projects.removeSuccess'), icon: 'success' })
              this.loadProjects()
            } catch (e) {
              uni.showToast({ title: e.message || this.$t('projects.removeFailed'), icon: 'none' })
            }
          }
        },
      })
    },
    isProjectAdmin(project) {
      if (!this.userInfo || !project) return false
      if (project.userId === this.userInfo.id) return true
      const member = project.members?.find((m) => m.userId === this.userInfo.id)
      return member && member.role === 'ADMIN'
    },
    canManageMembers(project) {
      return !this.isClientUser && this.isProjectAdmin(project)
    },
    // 角色文案唯一来源是 config/memberRoles.js（源页面自己硬编码了一份「管理员/成员」，
    // 与唯一来源里的「案件管理员/协作人」不一致，搬迁时收敛）
    getRoleLabel(role) {
      return roleLabel(role) || ROLE_LABELS.PARTICIPANT
    },
    getRoleClass(role) {
      if (role === 'OWNER') return 'role-owner'
      if (role === 'ADMIN') return 'role-admin'
      if (role === 'CLIENT') return 'role-client'
      return 'role-member'
    },
    getInternalMembers(project) {
      if (!project.members) return []
      return project.members.filter((m) => {
        const isClient = ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role)
        const isManager = project.managerId && m.userId === project.managerId
        return !isClient && !isManager
      })
    },
    getClientMembers(project) {
      if (!project.members) return []
      return project.members.filter((m) => ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role))
    },

    // ---- 卡片展示 ----
    // Project.projectType 是重大资产重组时代的遗留列，只用于旧数据回显；
    // 概览页的「事项类型」以 project_profile_field.matterType 为准，两者冲突时不提示
    getProjectTypeLabel(projectType) {
      return getProjectTypeLabel(projectType) || projectType
    },
    getProjectCardClass(type) {
      if (['MAJOR_ASSET_RESTRUCTURING', 'ACQUISITION'].includes(type)) {
        return 'card-style-restructuring'
      } else if (['PRIVATE_PLACEMENT', 'PUBLIC_PLACEMENT'].includes(type)) {
        return 'card-style-refinancing'
      } else if (type === 'BLANK') {
        return 'card-style-blank'
      }
      return 'card-style-default'
    },
    shouldShowListedCompany(type) {
      return type !== 'BLANK'
    },
    shouldShowTargetCompany(type) {
      return ['MAJOR_ASSET_RESTRUCTURING', 'ACQUISITION'].includes(type)
    },
    formatTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        return `${year}-${month}-${day}`
      } catch (e) {
        return timeStr
      }
    },

    // ---- 导航与写操作 ----
    // 列表页 → 概览页：两端都不是工作台，用 navigateTo（工作台参与的跳转才 reLaunch）
    goToProject(projectId) {
      uni.navigateTo({
        url: `/pages/project-home/project-home?id=${projectId}`,
      })
    },
    openCloudAccept() {
      this.showCloudAccept = true
    },
    onCloudAccepted(localProjectId) {
      this.loadProjects()
      if (localProjectId) this.goToProject(localProjectId)
    },
    async handleDeleteProject(projectId) {
      uni.showModal({
        title: this.$t('projects.deleteConfirmTitle'),
        content: this.$t('projects.deleteConfirmContent'),
        cancelText: this.$t('projects.cancel'),
        confirmText: this.$t('projects.confirm'),
        success: async (res) => {
          if (res.confirm) {
            this.deletingProjectId = projectId
            try {
              await deleteProject(projectId)
              uni.showToast({ title: this.$t('projects.deleteSuccess'), icon: 'success', duration: 2000 })
              await this.loadProjects()
            } catch (error) {
              console.error('删除项目失败:', error)
              uni.showToast({
                title: error.message || this.$t('projects.deleteFailedRetry'),
                icon: 'none',
                duration: 2000,
              })
            } finally {
              this.deletingProjectId = null
            }
          }
        },
      })
    },
    goToNewProject() {
      uni.navigateTo({ url: '/pages/newproject/index' })
    },
    // 本页与个人中心两端都不是工作台，用 navigateTo（工作台参与的跳转才 reLaunch）
    goToUserProfile() {
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
    },
    startRename(project) {
      if (this.isClientUser) return
      this.renamingProjectId = project.id
      this.renameValue = project.name
    },
    async confirmRename() {
      if (!this.renameValue || !this.renameValue.trim()) {
        uni.showToast({ title: this.$t('projects.projectNameEmpty'), icon: 'none' })
        return
      }
      try {
        await renameProject(this.renamingProjectId, this.renameValue.trim())
        const project = this.projects.find((p) => p.id === this.renamingProjectId)
        if (project) {
          project.name = this.renameValue.trim()
        }
        this.renamingProjectId = null
        this.renameValue = ''
        uni.showToast({ title: this.$t('projects.renameSuccess'), icon: 'success' })
      } catch (e) {
        console.error('重命名失败', e)
        uni.showToast({ title: this.$t('projects.renameFailed'), icon: 'none' })
      }
    },
    cancelRename() {
      this.renamingProjectId = null
      this.renameValue = ''
    },
  },
}
</script>

<style lang="scss" scoped src="./project-list.scss"></style>
