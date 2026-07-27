<template>
  <view class="page-userprofile">
    <view class="workbench-container">
      <!-- 左侧个人信息卡片 -->
      <view class="user-sidebar">
        <!-- Logo Area -->
        <view class="sidebar-logo-area">
            <image src="/static/logo_full_v2.png" class="sidebar-logo" mode="heightFix" />
            <!-- Text removed as requested -->
        </view>

        <view class="user-card">
          <view class="card-gold-accent"></view>
          <view class="user-profile-main">
            <view class="user-avatar-wrapper" @tap="triggerAvatarUpload">
              <image
                v-if="userInfo.avatarUrl"
                class="user-avatar"
                :src="userInfo.avatarUrl"
                mode="aspectFill"
              />
              <view v-else class="user-avatar-placeholder">
                <text class="avatar-text">{{ userInfo.displayName?.charAt(0) || 'U' }}</text>
              </view>
              <!-- Hidden File Input for Avatar Upload -->
              <!-- Note: uniapp h5 mode uses uni.chooseImage, so we don't strictly need an input tag if we use the API -->
            </view>
            <text class="user-name">{{ userInfo.displayName || '用户' }}</text>
            <text class="user-handle">@{{ userInfo.username || userInfo.id || 'unknown' }}</text>
            <view class="user-role-tag">
              <text class="role-text">标准用户</text>
            </view>
          </view>
          
          
          <!-- Navigation Menu (Moved from Top) -->
          <view class="nav-menu">
            <view
              v-for="tab in tabs"
              :key="tab.key"
              class="nav-item"
              :class="{ 'nav-item-active': activeTab === tab.key }"
              @tap="switchTab(tab.key)"
            >
               <!-- Emojis removed as requested -->
               <text class="nav-text">{{ tab.label }}</text>
            </view>
            
            <!-- Separator -->
            <!-- Logout removed from here -->
          </view>

          <!-- Bottom Actions removed as requested -->
        </view>
      </view>

      <!-- 右侧主内容区 -->
      <view class="main-content">
        <!-- 顶部 Header (Title + Action) -->
        <view class="content-header">
           <text class="header-title">{{ getActiveTabLabel() }}</text>
           <button v-if="activeTab === 'projects' && projects.length > 0" class="btn-primary-small" @tap="goToNewProject">+ 新建项目</button>
        </view>

        <!-- Tab 内容区 -->
        <view class="tab-panel-container">
          
          <!-- 我的项目 Tab -->
          <view v-if="activeTab === 'projects'" class="panel-projects">
            <!-- 概览统计行 (UI容器) -->
            <!-- 概览统计行 (3张卡片) -->
            <view class="projects-stats-row">
              <view class="stat-card">
                <text class="stat-value">{{ projects.length }}</text>
                <text class="stat-label">全部项目</text>
              </view>
              <view class="stat-card">
                <text class="stat-value">0</text>
                <text class="stat-label">进行中</text>
              </view>
              <view class="stat-card">
                <text class="stat-value">0</text>
                <text class="stat-label">已完成</text>
              </view>
            </view>

            <!-- 项目列表 -->
            <view v-if="projectsLoading" class="loading-state">
              <text class="loading-text">加载中...</text>
            </view>

            <view v-else-if="projects.length === 0" class="empty-state-dashed" @tap="goToNewProject">
               <view class="dashed-content">
                  <text class="dashed-icon">+</text>
                  <text class="dashed-text">新建项目</text>
               </view>
            </view>

            <view v-else class="project-grid">
              <view
                v-for="project in projects"
                :key="project.id"
                class="project-item-card"
                :class="getProjectCardClass(project.projectType)"
                @tap="goToProject(project.id)"
              >
                <!-- Card Decorative Header -->
                <view class="card-deco-header"></view>

                <!-- Top Row: Type Badge & Menu -->
                <view class="card-top-row">
                    <view class="project-type-badge-new">
                        <text class="badge-text-new">{{ getProjectTypeLabel(project.projectType) }}</text>
                    </view>
                    <!-- Role Badge moved to title area -->
                    <view class="card-actions">
                         <view class="action-btn-icon danger" @tap.stop="handleDeleteProject(project.id)" title="删除"><svg class="act-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                    </view>
                </view>

                <!-- Main Content -->
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

                    <!-- Company Info Area -->
                    <view class="company-info-area" v-if="project.projectType !== 'BLANK'">
                        <view class="info-row-new" v-if="shouldShowListedCompany(project.projectType)">
                            <text class="info-label-new">上市公司</text>
                            <text class="info-val-new highlight">{{ project.listedCompanyName || '-' }}</text>
                        </view>
                        <view class="info-row-new" v-if="shouldShowTargetCompany(project.projectType)">
                            <text class="info-label-new">标的公司</text>
                            <text class="info-val-new">{{ project.targetCompanyName || '-' }}</text>
                        </view>
                    </view>
                    <view v-else class="blank-placeholder">
                        <text class="placeholder-text">通用项目工作区</text>
                    </view>
                </view>
                  
                <!-- Footer: Members & Time -->
                <view class="card-footer-new">
                    <view class="members-area-new">
                        <!-- Manager Avatar (Distinct) -->
                        <view class="manager-avatar-wrapper" v-if="project.managerId" :title="'项目负责人: ' + (project.managerName || '未知')">
                             <image v-if="project.managerAvatarUrl" :src="project.managerAvatarUrl" class="manager-avatar-img" />
                             <view v-else class="manager-avatar-placeholder">{{ project.managerName?.charAt(0) || 'M' }}</view>
                             <view class="manager-badge-icon"><svg class="badge-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.crown" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                        </view>
                        <view class="members-divider" v-if="project.managerId && getInternalMembers(project).length > 0"></view>
                        
                        <!-- Split Members: Internal & Clients -->
                        <view class="members-split-container">
                            <!-- Internal Members -->
                            <view class="members-group">
                                <view v-for="member in getInternalMembers(project)" :key="member.id" class="member-avatar-new" :title="member.displayName">
                                    <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                                    <view v-else class="avatar-placeholder-new">{{ member.displayName?.charAt(0) || 'U' }}</view>
                                    
                                    <view v-if="isProjectAdmin(project) && member.userId !== userInfo.id" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
                                </view>
                                <!-- Add Button (Internal) -->
                                <view v-if="isProjectAdmin(project)" class="add-member-btn-new" @tap.stop="openInviteModal(project.id)">+</view>
                            </view>

                            <!-- Divider (Only if clients exist) -->
                            <view class="members-vertical-divider" v-if="getClientMembers(project).length > 0"></view>

                            <!-- Client Members -->
                            <view class="members-group clients-group" v-if="getClientMembers(project).length > 0">
                                <text class="client-group-label">客户</text>
                                <view v-for="member in getClientMembers(project)" :key="member.id" class="member-avatar-new client-avatar" :title="member.displayName + ' (客户)'">
                                    <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                                    <view v-else class="avatar-placeholder-new client-placeholder">{{ member.displayName?.charAt(0) || '客' }}</view>
                                    
                                    <view v-if="isProjectAdmin(project)" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
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

          <!-- 工作记录 Tab -->
          <view v-else-if="activeTab === 'work_log'" class="panel-work-log">
             <view class="log-filter-bar">
                 <input class="filter-input" v-model="activityFilter.date" placeholder="日期 (YYYY-MM-DD)" />
                 <input class="filter-input" v-model="activityFilter.project" placeholder="项目名称" />
                 <input class="filter-input" v-model="activityFilter.content" placeholder="工作内容关键词" />
                 <button class="btn-export" @tap="exportLogsToExcel">导出 Excel</button>
             </view>
             
             <view class="log-table-container">
                 <view class="log-table-header">
                     <text class="th th-project">项目</text>
                     <text class="th th-action">操作</text>
                     <text class="th th-object">对象</text>
                     <text class="th th-start">开始时间</text>
                     <text class="th th-end">结束时间</text>
                     <text class="th th-duration">累计时长</text>
                     <text class="th th-idle">连续无动作时间</text>
                 </view>
                 <view v-if="activityLoading" class="loading-row">加载中...</view>
                 <view v-else-if="getFilteredLogs().length === 0" class="empty-row">无记录</view>
                 <scroll-view v-else scroll-y class="log-table-body">
                     <view v-for="log in getFilteredLogs()" :key="log.id" class="log-table-row">
                         <text class="td td-project" :title="getLogProject(log)">{{ getLogProject(log) }}</text>
                         <text class="td td-action">{{ log.actionType }}</text>
                         <text class="td td-object" :title="getLogObject(log)">{{ getLogObject(log) }}</text>
                         <text class="td td-start">{{ getLogStartTime(log) }}</text>
                         <text class="td td-end">{{ getLogEndTime(log) }}</text>
                         <text class="td td-duration">{{ getLogDuration(log) }}</text>
                         <text class="td td-idle" :title="getLogIdleTime(log)">{{ getLogIdleTime(log) }}</text>
                     </view>
                 </scroll-view>
             </view>
          </view>

          <!-- 我的收藏 -->
          <view v-else-if="activeTab === 'favorites'" class="panel-favorites">
            <view v-if="favoritesLoading" class="loading">
              <text class="loading-text">加载中...</text>
            </view>
            <view v-else-if="favorites.length === 0" class="empty-state">
              <view class="empty-icon-circle">
                <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.star" :key="gi" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </view>
              <text class="empty-title">我的收藏</text>
              <text class="empty-desc">暂无收藏内容</text>
            </view>
            <view v-else class="favorites-list">
              <view v-for="fav in favorites" :key="fav.id" class="favorite-card">
                <view class="favorite-header">
                  <text class="favorite-title">{{ fav.title || (fav.sourceUrl ? fav.sourceUrl : '未命名摘录') }}</text>
                  <button class="btn-danger-outline small" @tap.stop="handleDeleteFavorite(fav.id)">删除</button>
                </view>
                <view v-if="fav.sourceUrl" class="favorite-url">
                  <text class="url-text">{{ fav.sourceUrl }}</text>
                </view>
                <view v-if="fav.imagePath" class="favorite-image">
                  <image class="fav-img" mode="widthFix" :src="getFavoriteImageUrl(fav.id)" />
                </view>
                <view class="favorite-content">
                  <text class="content-text">{{ fav.content }}</text>
                </view>
                <view class="favorite-footer">
                  <text class="time-text">{{ formatTime(fav.createdAt) }}</text>
                </view>
              </view>
            </view>
          </view>

          <!-- 我的代办 (UI 占位) -->
          <view v-else-if="activeTab === 'todos'" class="panel-placeholder">
            <view class="empty-state">
              <view class="empty-icon-circle">
                <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.docText" :key="gi" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </view>
              <text class="empty-title">我的代办</text>
              <text class="empty-desc">暂无待办事项</text>
            </view>
          </view>

          <!-- 设置 (UI 占位) -->
          <view v-else-if="activeTab === 'settings'" class="panel-settings">
            <view class="settings-form">
              <view class="form-group">
                <text class="group-title">基本信息</text>
                <view class="form-row">
                  <text class="form-label">头像</text>
                  <view class="avatar-preview">
                    <text class="avatar-char">{{ userInfo.displayName?.charAt(0) || 'U' }}</text>
                  </view>
                </view>
                <view class="form-row">
                  <text class="form-label">昵称</text>
                  <text class="form-value">{{ userInfo.displayName }}</text>
                </view>
              </view>
              
              <view class="form-group">
                <text class="group-title">账号安全</text>
                <view class="form-row">
                  <text class="form-label">修改密码</text>
                  <text class="link-text">点击修改</text>
                </view>
              </view>

              <view class="form-group">
                  <button class="btn-logout-settings" @tap="handleLogout">退出登录</button>
              </view>
            </view>
          </view>

        </view>
      </view>
    </view>
    
    <!-- Invite Member Dialog -->
    <InviteMemberDialog
        v-model:visible="showInviteModal"
        :project-id="currentInviteProjectId"
        @success="loadProjects"
        @close="closeInviteModal"
    />
  </view>
</template>

<script>
import { getMyProjects, deleteProject, renameProject, getCurrentUser as getCurrentUserApi, getMyFavorites, deleteFavorite, getFavoriteImageUrl, getProjectMembers, addProjectMember, removeProjectMember, getUserActivityHistory, inviteClient, uploadAvatar } from '@/services/api.js'
import { getProjectTypeLabel } from '@/config/projectTypes.js'
 import { getCurrentUser, isLoggedIn, getSessionId, clearSession, setSessionUser } from '@/utils/auth.js'
import InviteMemberDialog from '@/components/InviteMemberDialog.vue'
import { ICONS } from '@/config/icons.js'

export default {

  computed: {

    ICONS() { return ICONS }

  },
  name: 'UserProfile',
  components: {
    InviteMemberDialog
  },
  data() {
    return {
      activeTab: 'projects',
      tabs: [
        { key: 'projects', label: '我的项目' },
        { key: 'work_log', label: '工作记录' },
        { key: 'favorites', label: '我的收藏' },
        { key: 'todos', label: '我的代办' },
        { key: 'settings', label: '设置' },
      ],
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
      favoritesLoading: false,
      favorites: [],
      
      showInviteModal: false,
      currentInviteProjectId: null,

      // Activity Logs
      activityLogs: [],
      activityLoading: false,
      activityFilter: {
        date: '',
        project: '',
        content: ''
      }
    }
  },
  onLoad() {
    // Desktop：个人中心页必须隐藏 BrowserView（避免工作区网页残留覆盖）
    try {
      if (typeof window !== 'undefined' && window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.setViewsVisible) {
        window.checkbaDesktop.browser.setViewsVisible({ visible: false }).catch(() => {})
      }
    } catch (e) {
      // ignore
    }
    // 检查登录状态
    const sessionId = getSessionId()
    const user = getCurrentUser()
    
    if (!sessionId || !user) {
      console.warn('未登录，跳转到登录页', { sessionId, user })
      uni.reLaunch({
        url: '/pages/login/login',
      })
      return
    }

    // 延迟加载，确保页面完全加载后再请求数据
    this.$nextTick(() => {
      // 加载用户信息
      this.loadUserInfo()
      // 加载项目列表
      this.loadProjects()
    })
  },
  methods: {
    triggerAvatarUpload() {
        uni.chooseImage({
            count: 1,
            sizeType: ['compressed'],
            sourceType: ['album', 'camera'],
            success: async (res) => {
                const tempFilePath = res.tempFilePaths[0];
                try {
                    uni.showLoading({ title: '上传中...' });
                    const result = await uploadAvatar(tempFilePath);
                    
                    if (result.data && result.data.avatarUrl) {
                        this.userInfo.avatarUrl = result.data.avatarUrl;
                        // Update local storage/session
                        setSessionUser(this.userInfo);
                        uni.showToast({ title: '头像更新成功', icon: 'success' });
                    }
                } catch (e) {
                    console.error('Avatar upload failed', e);
                    uni.showToast({ title: '上传失败: ' + e.message, icon: 'none' });
                } finally {
                    uni.hideLoading();
                }
            }
        });
    },
    getActiveTabLabel() {
      const tab = this.tabs.find(t => t.key === this.activeTab)
      return tab ? tab.label : ''
    },
    handleLogout() {
      uni.showModal({
        title: '确认注销',
        content: '确定要退出登录吗？',
        cancelText: '取消',
        confirmText: '确认',
        success: (res) => {
          if (!res.confirm) return
          try {
            clearSession()
          } catch (e) {
            // ignore
          }
          uni.reLaunch({ url: '/pages/login/login' })
        }
      })
    },
    switchTab(key) {
      if (key === 'system_admin') {
          uni.navigateTo({
              url: '/pages/admin/admin'
          })
          return
      }
      this.activeTab = key
      if (key === 'favorites') {
        this.loadFavorites()
      } else if (key === 'work_log') {
        this.loadActivityLogs()
      }
    },
    async loadActivityLogs() {
        this.activityLoading = true
        try {
            const res = await getUserActivityHistory()
            this.activityLogs = res.data || []
        } catch (e) {
            console.error('Failed to load activity logs', e)
        } finally {
            this.activityLoading = false
        }
    },
    getFilteredLogs() {
        return this.activityLogs.filter(log => {
            const dateMatch = !this.activityFilter.date || this.formatTime(log.timestamp).includes(this.activityFilter.date)
            const projectMatch = !this.activityFilter.project || (log.targetName && log.targetName.includes(this.activityFilter.project))
            const contentMatch = !this.activityFilter.content || (log.metaInfo && log.metaInfo.includes(this.activityFilter.content))
            return dateMatch && projectMatch && contentMatch
        })
    },
    getLogProject(log) {
        if (log.metaInfo && log.metaInfo.includes('Project:')) {
            const match = log.metaInfo.match(/Project:\s*([^,;]+)/)
            if (match) return match[1]
        }
        if (log.actionType === 'WORK') return log.targetName
        return '-'
    },
    getLogObject(log) {
        if (log.actionType === 'OPEN_FILE' || log.actionType === 'CLOSE_FILE') return log.targetName
        if (log.actionType === 'WORK') return '-'
        return log.targetName || '-'
    },
    getLogStartTime(log) {
        if (log.duration && log.duration > 0) {
            const end = new Date(log.timestamp).getTime()
            const dur = Number(log.duration) || 0
            return this.formatDateTime(new Date(end - dur))
        }
        return this.formatDateTime(log.timestamp)
    },
    getLogEndTime(log) {
        return this.formatDateTime(log.timestamp)
    },
    getLogDuration(log) {
        if (log.duration && log.duration > 0) {
             // Round up to nearest 0.25 minutes (15 seconds)
             // duration is in ms
             const seconds = log.duration / 1000
             const roundedSeconds = Math.ceil(seconds / 15) * 15
             const minutes = roundedSeconds / 60
             return minutes.toFixed(2) + '分'
        }
        // Fallback for old logs or if duration is 0 (instant actions)
        if (log.metaInfo && log.metaInfo.includes('总时长:')) {
             const match = log.metaInfo.match(/总时长:\s*([\d.]+)分/)
             if (match) return match[1] + '分'
        }
        return '-'
    },
    getLogIdleTime(log) {
        if (!log.metaInfo) return '-'
        if (log.metaInfo.includes('IdleSegments:')) {
            return log.metaInfo.split('IdleSegments:')[1].trim()
        }
        if (log.metaInfo.includes('空闲:')) {
            const idx = log.metaInfo.indexOf('空闲:')
            if (idx >= 0) return log.metaInfo.substring(idx)
        }
        return '-'
    },
    exportLogsToExcel() {
        // Simple CSV export for now
        const logs = this.getFilteredLogs()
        let csvContent = "data:text/csv;charset=utf-8,\uFEFF"; // Add BOM
        csvContent += "项目,操作,对象,开始时间,结束时间,累计时长,连续无动作时间\n";
        
        logs.forEach(log => {
            const project = (this.getLogProject(log) || '').replace(/,/g, ' ')
            const action = log.actionType
            const object = (this.getLogObject(log) || '').replace(/,/g, ' ')
             const start = this.getLogStartTime(log)
             const end = this.getLogEndTime(log)
             const duration = (this.getLogDuration(log) || '').replace(/,/g, ' ')
             const idle = (this.getLogIdleTime(log) || '').replace(/,/g, ' ').replace(/\n/g, ' ')
             
             csvContent += `${project},${action},${object},${start},${end},${duration},${idle}\n`;
        });
        
        const encodedUri = encodeURI(csvContent);
        const link = document.createElement("a");
        link.setAttribute("href", encodedUri);
        link.setAttribute("download", `work_log_${new Date().toISOString().slice(0,10)}.csv`);
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    },
    async loadFavorites() {
      this.favoritesLoading = true
      try {
        const list = await getMyFavorites()
        this.favorites = Array.isArray(list) ? list : (list?.data || [])
      } catch (e) {
        console.error('加载收藏失败:', e)
        uni.showToast({ title: '加载收藏失败', icon: 'none' })
      } finally {
        this.favoritesLoading = false
      }
    },
    getFavoriteImageUrl(id) {
      return getFavoriteImageUrl(id)
    },
    async handleDeleteFavorite(id) {
      uni.showModal({
        title: '确认删除',
        content: '确定要删除该收藏吗？',
        cancelText: '取消',
        confirmText: '确认',
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deleteFavorite(id)
            await this.loadFavorites()
            uni.showToast({ title: '删除成功', icon: 'success' })
          } catch (e) {
            console.error('删除收藏失败:', e)
            uni.showToast({ title: '删除失败', icon: 'none' })
          }
        }
      })
    },
    async loadUserInfo() {
      const user = getCurrentUser()
      if (user) {
        this.userInfo = user
        this.checkAdminTab()
      } else {
        // 如果本地没有，尝试从服务器获取
        try {
          const res = await getCurrentUserApi()
          if (res.code === 0 && res.data) {
            this.userInfo = res.data
            this.checkAdminTab()
          }
        } catch (error) {
          console.error('获取用户信息失败:', error)
        }
      }
    },
    checkAdminTab() {
        // isAdmin 由 /api/auth/me 下发（桌面单机=全员管理员；云端=仅 admin 账号）；
        // username==='admin' 兜底兼容缓存的旧 userInfo（无 isAdmin 字段）
        if (this.userInfo && (this.userInfo.isAdmin === true || this.userInfo.username === 'admin')) {
            const hasAdminTab = this.tabs.find(t => t.key === 'system_admin')
            if (!hasAdminTab) {
                // Insert before 'settings' or at the end
                const settingsIndex = this.tabs.findIndex(t => t.key === 'settings')
                const adminTab = { key: 'system_admin', label: '系统设置' }
                
                if (settingsIndex >= 0) {
                    this.tabs.splice(settingsIndex, 0, adminTab)
                } else {
                    this.tabs.push(adminTab)
                }
            }
        }
    },
    async loadProjects() {
      this.projectsLoading = true
      try {
        const projects = await getMyProjects()
        // Fetch members for each project
        const projectsWithMembers = await Promise.all(projects.map(async (p) => {
            try {
                const res = await getProjectMembers(p.id)
                let members = res.data || []
                // Deduplicate members by userId to prevent display issues
                const seen = new Set()
                members = members.filter(m => {
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
        // 错误处理逻辑保持不变
        if (error.message && error.message.includes('登录')) {
          uni.reLaunch({
            url: '/pages/login/login',
          })
        } else {
          uni.showToast({
            title: error.message || '加载失败，请稍后重试',
            icon: 'none',
            duration: 2000,
          })
        }
      } finally {
        this.projectsLoading = false
      }
    },
    
    // Member Management
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
            title: '确认移除',
            content: '确定要移除该成员吗？',
            cancelText: '取消',
            confirmText: '确认',
            success: async (res) => {
                if (res.confirm) {
                    try {
                        await removeProjectMember(projectId, userId)
                        uni.showToast({ title: '移除成功', icon: 'success' })
                        this.loadProjects()
                    } catch (e) {
                         uni.showToast({ title: e.message || '移除失败', icon: 'none' })
                    }
                }
            }
        })
    },
    isProjectAdmin(project) {
        if (!this.userInfo || !project) return false
        if (project.userId === this.userInfo.id) return true
        const member = project.members?.find(m => m.userId === this.userInfo.id)
        return member && member.role === 'ADMIN'
    },
    getRoleLabel(role) {
        const map = {
            'OWNER': '负责人',
            'ADMIN': '管理员',
            'PARTICIPANT': '成员',
            'READ_ONLY': '只读',
            'CLIENT': '客户'
        }
        return map[role] || role || '成员'
    },
    getRoleClass(role) {
        if (role === 'OWNER') return 'role-owner'
        if (role === 'ADMIN') return 'role-admin'
        if (role === 'CLIENT') return 'role-client'
        return 'role-member'
    },
    getInternalMembers(project) {
        if (!project.members) return []
        // Internal: NOT Client AND NOT Manager (if displayed separately)
        return project.members.filter(m => {
            const isClient = ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role)
            const isManager = project.managerId && m.userId === project.managerId
            return !isClient && !isManager
        })
    },
    getClientMembers(project) {
        if (!project.members) return []
        // Client only
        return project.members.filter(m => ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role))
    },
    getProjectTypeLabel(projectType) {
      return getProjectTypeLabel(projectType) || projectType
    },
    // New helper for card styling
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
    formatDateTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        const hour = String(date.getHours()).padStart(2, '0')
        const minute = String(date.getMinutes()).padStart(2, '0')
        const second = String(date.getSeconds()).padStart(2, '0')
        return `${year}-${month}-${day} ${hour}:${minute}:${second}`
      } catch (e) {
        return timeStr
      }
    },
    goToProject(projectId) {
      uni.navigateTo({
        url: `/pages/project-overview/project-overview?id=${projectId}`,
      })
    },
    async handleDeleteProject(projectId) {
      uni.showModal({
        title: '确认删除',
        content: '确定要删除这个项目吗？删除后无法恢复。',
        cancelText: '取消',
        confirmText: '确认',
        success: async (res) => {
          if (res.confirm) {
            this.deletingProjectId = projectId
            try {
              await deleteProject(projectId)
              uni.showToast({
                title: '删除成功',
                icon: 'success',
                duration: 2000,
              })
              // 重新加载项目列表
              await this.loadProjects()
            } catch (error) {
              console.error('删除项目失败:', error)
              uni.showToast({
                title: error.message || '删除失败，请稍后重试',
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
      uni.navigateTo({
        url: '/pages/newproject/index',
      })
    },
    goToAdmin() {
      uni.navigateTo({
        url: '/pages/admin/admin',
      })
    },
    startRename(project) {
        this.renamingProjectId = project.id
        this.renameValue = project.name
    },
    async confirmRename() {
        if (!this.renameValue || !this.renameValue.trim()) {
            uni.showToast({ title: '项目名称不能为空', icon: 'none' })
            return
        }
        try {
            await renameProject(this.renamingProjectId, this.renameValue.trim())
            // Update local state
            const project = this.projects.find(p => p.id === this.renamingProjectId)
            if (project) {
                project.name = this.renameValue.trim()
            }
            this.renamingProjectId = null
            this.renameValue = ''
            uni.showToast({ title: '重命名成功', icon: 'success' })
        } catch (e) {
            console.error('重命名失败', e)
            uni.showToast({ title: '重命名失败', icon: 'none' })
        }
    },
    cancelRename() {
        this.renamingProjectId = null
        this.renameValue = ''
    },
  },
}
</script>

<style lang="scss" scoped>
/* 品牌配色变量 - Updated to AI Workdeck Palette */
$brand-primary: #1A5336; /* Forest Green */
$brand-accent: #5BD197;  /* Mint Green */
$brand-dark: #212629;    /* Dark BG */
$brand-bg: #F8F9FA;      /* Gray-Pale */
$brand-white: #FFFFFF;
$text-main: #2C3338;     /* Gray-Dark */
$text-secondary: #6C757D;/* Gray-Medium */
$text-light: #ADB5BD;
$border-color: #E9ECEF;  /* Gray-Light */
$danger-color: #E74C3C;

.page-userprofile {
  min-height: 100vh;
  /* Subtle Gradient Background */
  background: linear-gradient(135deg, #F8F9FA 0%, #E8F3ED 100%);
  padding: 40px 24px;
  box-sizing: border-box;
  color: $text-main;
}

.workbench-container {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 24px;
}

/* 左侧边栏 */
.user-sidebar {
  width: 280px;
  flex-shrink: 0;
  display: flex; /* Flex for Logo + Card */
  flex-direction: column;
}

.sidebar-logo-area {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 24px;
    padding-left: 8px;
}

.sidebar-logo {
    height: 36px;
    width: auto; /* Allow full logo width */
}

.sidebar-app-title {
    font-size: 20px;
    font-weight: 700;
    color: $brand-primary;
    letter-spacing: -0.5px;
}

.user-card {
  background: $brand-white;
  border-radius: 16px;
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.05);
  overflow: hidden;
  position: relative;
  padding-bottom: 24px;
}

.card-gold-accent {
  height: 4px;
  width: 100%;
  background: $brand-primary;
}

.user-profile-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 24px;
  border-bottom: 1px solid $border-color;
}

.user-avatar-wrapper {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  overflow: hidden;
  margin-bottom: 16px;
  background-color: #eef2f5;
  box-shadow: 0 2px 8px rgba(0,0,0,0.05);
}

.user-avatar {
  width: 100%;
  height: 100%;
}

.user-avatar-placeholder {
  width: 100%;
  height: 100%;
  background: $brand-dark;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-text {
  font-size: 32px;
  color: #fff;
  font-weight: 500;
}

.user-name {
  font-size: 20px;
  font-weight: 600;
  color: $text-main;
  margin-bottom: 4px;
}

.user-handle {
  font-size: 14px;
  color: $text-secondary;
  margin-bottom: 12px;
}

.user-role-tag {
  background: rgba(26, 83, 54, 0.08);
  padding: 4px 12px;
  border-radius: 4px;
  border: 1px solid rgba(26, 83, 54, 0.1);
}

.role-text {
  font-size: 12px;
  color: $brand-primary;
  font-weight: 500;
}

.nav-menu {
    padding: 12px 0;
}


.nav-item {
    display: flex;
    align-items: center;
    padding: 16px 32px; /* Increased padding */
    cursor: pointer;
    transition: all 0.2s;
    border-left: 4px solid transparent;
    color: $text-secondary;
    
    &:hover {
        background-color: rgba(0,0,0,0.02);
        color: $text-main;
    }
}

.nav-item-active {
    background-color: rgba(91, 209, 151, 0.08);
    color: $brand-primary;
    border-left-color: $brand-primary;
    font-weight: 600; /* Bolder */
}

/* Removed nav-icon style */

.nav-text {
    font-size: 16px; /* Larger font size */
    font-weight: 500;
}

.nav-separator {
    height: 1px;
    background-color: $border-color;
    margin: 12px 24px;
}

.warning-item .nav-text {
    color: $danger-color;
}

.warning-item:hover {
    background-color: rgba(231, 76, 60, 0.05);
}


.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 24px;
  cursor: pointer;
  transition: background 0.2s;
  
  &:hover {
    background-color: #F8F9FA;
  }
}

.action-text {
  font-size: 14px;
  color: $text-main;
}

.action-arrow {
  font-size: 18px;
  color: $text-light;
  font-family: monospace;
}

/* 右侧主内容区 */
.main-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.content-header {
    display: flex !important;
    flex-direction: row !important;
    justify-content: flex-start !important;
    gap: 20px;
    align-items: center !important;
    margin-bottom: 24px;
    border-bottom: 1px solid $border-color;
    padding-bottom: 16px;
}

.header-title {
    font-size: 20px;
    font-weight: 600;
    color: $text-main;
    flex: 0 0 auto;
}

.btn-primary-small {
    background: rgba(26, 83, 54, 0.08); /* Soft Green Background */
    color: $brand-primary;
    font-size: 13px;
    font-weight: 500;
    padding: 6px 12px;
    border-radius: 6px;
    border: 1px solid rgba(26, 83, 54, 0.1);
    cursor: pointer;
    line-height: 1.5;
    margin-left: 20px;
    transition: all 0.2s;
    
    &:hover {
        background: $brand-primary;
        color: white;
        border-color: $brand-primary;
    }
}

/* 概览统计 */
.projects-stats-row {
  display: flex;
  flex-direction: row;
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  flex: 1;
  background: $brand-white;
  border-radius: 12px;
  padding: 16px 20px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05); /* Softer shadow */
  display: flex;
  flex-direction: column;
  justify-content: center;
  border: 1px solid $border-color;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: $brand-primary;
  margin-bottom: 4px;
  font-family: 'Inter', sans-serif;
}

.stat-label {
  font-size: 13px;
  color: $text-secondary;
}

/* 项目列表 Grid */
.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 24px;
}

/* Card Base Style */
.project-item-card {
  background: $brand-white;
  border-radius: 16px;
  box-shadow: 0 4px 20px rgba(18, 52, 77, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: all 0.2s ease;
  border: 1px solid rgba(0,0,0,0.02);
  position: relative;
  min-height: 200px;

  &:hover {
    transform: translateY(-4px);
    box-shadow: 0 12px 30px rgba(18, 52, 77, 0.1);
  }
}

/* Card Variants */
.card-style-restructuring {
  border-top: 4px solid $brand-dark;
  
  .card-deco-header {
      background: linear-gradient(90deg, rgba($brand-dark, 0.05), transparent);
  }
  .project-type-badge-new {
      background: rgba($brand-dark, 0.1);
      color: $brand-dark;
  }
  .info-val-new.highlight {
      color: $brand-dark;
      font-weight: 600;
  }
}

.card-style-refinancing {
  border-top: 4px solid #2ecc71; /* Green for growth/money */

  .project-type-badge-new {
      background: rgba(46, 204, 113, 0.1);
      color: #27ae60;
  }
  .info-val-new.highlight {
      color: #27ae60;
      font-weight: 600;
  }
}

.card-style-blank {
  border-top: 4px solid #e0e0e0;
  
  .project-type-badge-new {
      background: #f5f5f5;
      color: #999;
  }
  .project-title-new {
      font-weight: 500;
      color: #666;
  }
}

/* Card Header */
.card-top-row {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    padding: 16px 20px 0;
}

.project-type-badge-new {
    padding: 4px 10px;
    border-radius: 6px;
    font-size: 12px;
    font-weight: 500;
    display: inline-block;
}

.card-actions {
    display: flex;
    gap: 8px;
}

.action-btn-icon {
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 4px;
    cursor: pointer;
    font-size: 14px;
    opacity: 0.6;
    transition: opacity 0.2s;
    
    &:hover {
        opacity: 1;
        background: #f0f0f0;
    }
    
    &.danger:hover {
        background: #fff5f5;
        color: red;
    }
}

/* Card Content */
.card-main-content {
    padding: 16px 20px;
    flex: 1;
    display: flex;
    flex-direction: column;
}

.project-title-area {
    margin-bottom: 16px;
    min-height: 48px; /* Ensure alignment */
}

.project-title-new {
    font-size: 18px;
    font-weight: 700;
    color: $text-main;
    line-height: 1.4;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    overflow: hidden;
    cursor: pointer;
    
    &:hover {
        color: $brand-primary;
    }
}

.company-info-area {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.info-row-new {
    display: flex;
    align-items: center;
    font-size: 13px;
}

.info-label-new {
    color: $text-light;
    width: 70px;
    flex-shrink: 0;
}

.info-val-new {
    color: $text-secondary;
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.blank-placeholder {
    height: 40px;
    display: flex;
    align-items: center;
    
    .placeholder-text {
        font-size: 13px;
        color: #ccc;
        font-style: italic;
    }
}

/* Footer */
.card-footer-new {
    padding: 12px 20px;
    border-top: 1px solid #f5f5f5;
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: #fafbfc;
}

.members-area-new {
    display: flex;
    align-items: center;
}

.members-list-new {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

.member-avatar-new {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    position: relative;
    cursor: pointer;
    
    &:hover {
        z-index: 10;
        transform: scale(1.1);
    }
}

.avatar-img-new {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    border: 1px solid #fff;
    box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.avatar-placeholder-new {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    background: #eef2f5;
    color: #666;
    font-size: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid #fff;
}

.member-remove-overlay {
    position: absolute;
    top: -2px;
    right: -2px;
    width: 12px;
    height: 12px;
    background: #ff4d4f;
    color: white;
    border-radius: 50%;
    font-size: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    line-height: 1;
    display: none;
}

.member-avatar-new:hover .member-remove-overlay {
    display: flex;
}

.add-member-btn-new {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    border: 1px dashed #ccc;
    color: #999;
    font-size: 16px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    
    &:hover {
        border-color: $brand-primary;
        color: $brand-primary;
    }
}

.footer-meta {
    display: flex;
    align-items: center;
    gap: 12px;
}

.time-text-new {
    font-size: 12px;
    color: #bbb;
}

.enter-btn-arrow {
    width: 24px;
    height: 24px;
    background: $brand-dark;
    color: #fff;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    opacity: 0; /* Hidden by default, show on hover */
    transform: translateX(-10px);
    transition: all 0.2s;
}

.project-item-card:hover .enter-btn-arrow {
    opacity: 1;
    transform: translateX(0);
}

.panel-favorites {
  width: 100%;
}

.favorites-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.favorite-card {
  background: $brand-white;
  border-radius: 14px;
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.05);
  border: 1px solid rgba(224, 224, 224, 0.7);
  padding: 16px;
}

.favorite-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.favorite-title {
  font-size: 14px;
  font-weight: 600;
  color: $text-main;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.favorite-url {
  margin-top: 6px;
}

.url-text {
  font-size: 12px;
  color: $text-light;
  word-break: break-all;
}

.favorite-image {
  margin-top: 10px;
}

.fav-img {
  width: 100%;
  border-radius: 10px;
  border: 1px solid rgba(224, 224, 224, 0.7);
}

.favorite-content {
  margin-top: 10px;
}

.content-text {
  font-size: 13px;
  color: $text-secondary;
  line-height: 1.6;
  white-space: pre-wrap;
}

.favorite-footer {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

.time-text {
  font-size: 12px;
  color: $text-light;
}

.btn-enter {
  margin: 0;
  padding: 0 20px;
  height: 32px;
  line-height: 32px;
  background: $brand-dark;
  color: #fff;
  font-size: 13px;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  
  &::after { border: none; }
  
  &:hover {
    background: lighten($brand-dark, 5%);
    box-shadow: 0 2px 8px rgba(18, 52, 77, 0.2);
  }
}

/* Dashed Empty State */
.empty-state-dashed {
    height: 180px;
    border: 2px dashed $border-color;
    border-radius: 12px;
    display: flex;
    justify-content: center;
    align-items: center;
    cursor: pointer;
    transition: all 0.2s;
    background: #fff;
}

.empty-state-dashed:hover {
    border-color: $brand-accent;
    background: rgba(91, 209, 151, 0.03);
}

.dashed-content {
    display: flex;
    flex-direction: column;
    align-items: center;
}

.dashed-icon {
    font-size: 32px;
    color: $text-light;
    font-weight: 300;
}

.dashed-text {
    margin-top: 8px;
    font-size: 15px;
    color: $text-secondary;
}

/* 占位 Tab */
.panel-placeholder {
  background: $brand-white;
  border-radius: 12px;
  min-height: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 12px rgba(18, 52, 77, 0.04);
}

/* 设置 Tab */
.panel-settings {
  background: $brand-white;
  border-radius: 12px;
  padding: 32px;
  box-shadow: 0 2px 12px rgba(18, 52, 77, 0.04);
}

.group-title {
  display: block;
  font-size: 16px;
  font-weight: 600;
  color: $text-main;
  margin-bottom: 24px;
  padding-left: 12px;
  border-left: 4px solid $brand-primary;
}

.form-group {
  margin-bottom: 40px;
}

.form-row {
  display: flex;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid #f0f0f0;
  
  &:last-child {
    border-bottom: none;
  }
}

.form-label {
  width: 100px;
  font-size: 14px;
  color: $text-secondary;
}

.form-value {
  font-size: 14px;
  color: $text-main;
  font-weight: 500;
}

.link-text {
  font-size: 14px;
  color: $brand-dark;
  cursor: pointer;
  text-decoration: underline;
}

.avatar-preview {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: $brand-dark;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 20px;
}

/* Settings Logout Button */
.btn-logout-settings {
    background: #fff;
    border: 1px solid $border-color;
    color: $text-secondary;
    height: 44px;
    line-height: 42px; /* Adjust for border */
    border-radius: 8px;
    font-size: 14px;
    width: 100%;
    margin-top: 12px;
    cursor: pointer;
    transition: all 0.2s;
    
    &:hover {
        border-color: $text-secondary;
        color: $text-main;
        background: #fafafa;
    }
}

/* Work Log Styles */
.panel-work-log {
  width: 100%;
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.04);
}

.log-filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.filter-input {
  flex: 1;
  height: 36px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 0 12px;
  font-size: 13px;
}

.btn-export {
  height: 36px;
  line-height: 36px;
  padding: 0 20px;
  background: $brand-dark;
  color: #fff;
  font-size: 13px;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  
  &:hover {
    background: lighten($brand-dark, 5%);
  }
}

.log-table-container {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
}

.log-table-header {
  display: flex;
  background: #f8f9fa;
  border-bottom: 1px solid #e2e8f0;
  padding: 12px 16px;
}

.th {
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.th-project { width: 120px; }
.th-action { width: 80px; }
.th-object { width: 150px; }
.th-start { width: 140px; }
.th-end { width: 140px; }
.th-duration { width: 80px; }
.th-idle { flex: 1; }

.log-table-body {
  max-height: 500px;
}

.log-table-row {
  display: flex;
  padding: 12px 16px;
  border-bottom: 1px solid #f1f5f9;
  font-size: 13px;
  color: #334155;
  
  &:last-child {
    border-bottom: none;
  }
  
  &:hover {
    background: #f8f9fa;
  }
}

.td {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.td-project { width: 120px; color: $brand-dark; font-weight: 500; }
.td-action { width: 80px; font-weight: 500; }
.td-object { width: 150px; color: #334155; }
.td-start { width: 140px; color: #64748b; font-size: 12px; }
.td-end { width: 140px; color: #64748b; font-size: 12px; }
.td-duration { width: 80px; color: #94a3b8; }
.td-idle { flex: 1; color: #64748b; }

.loading-row, .empty-row {
  padding: 40px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}

/* 响应式适配 */
@media screen and (max-width: 768px) {
  .workbench-container {
    flex-direction: column;
  }
  
  .user-sidebar {
    width: 100%;
  }
  
  .projects-stats-row {
    flex-wrap: wrap;
  }
  
  .stat-card {
    min-width: 45%;
  }
}

/* Members */
.project-members {
    margin-top: 12px;
    border-top: 1px dashed #f0f0f0;
    padding-top: 12px;
}
.member-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}
.member-avatar-wrapper {
    position: relative;
    width: 28px;
    height: 28px;
    border-radius: 50%;
}
.member-avatar-small {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    border: 1px solid #fff;
}
.member-avatar-placeholder-small {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    background: #eef2f5;
    color: #666;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid #fff;
}
.remove-member-btn {
    position: absolute;
    top: -4px;
    right: -4px;
    width: 14px;
    height: 14px;
    background: #ff4d4f;
    color: #fff;
    border-radius: 50%;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    line-height: 1;
    display: none; /* Show on hover */
}
.member-avatar-wrapper:hover .remove-member-btn {
    display: flex;
}
.add-member-btn {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    border: 1px dashed #ccc;
    color: #999;
    font-size: 18px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    &:hover {
        border-color: $brand-primary;
        color: $brand-primary;
    }
}

/* Modal */
.modal-mask {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0,0,0,0.5);
    z-index: 999;
    display: flex;
    align-items: center;
    justify-content: center;
}
.modal-content {
    background: #fff;
    width: 400px;
    border-radius: 8px;
    padding: 24px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.15);
}
.modal-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 24px;
}
.modal-title {
    font-size: 18px;
    font-weight: 600;
}
.modal-close {
    font-size: 24px;
    color: #999;
    cursor: pointer;
}
.form-item {
    margin-bottom: 16px;
}
.label {
    display: block;
    margin-bottom: 8px;
    color: #666;
}
.input {
    width: 100%;
    height: 36px;
    border: 1px solid #ddd;
    border-radius: 4px;
    padding: 0 8px;
    box-sizing: border-box;
}
.radio-group {
    display: flex;
    gap: 16px;
}
.radio-label {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 14px;
}
.modal-tabs {
  display: flex;
  border-bottom: 1px solid #eee;
  margin-bottom: 16px;
}
.tab-btn {
  flex: 1;
  text-align: center;
  padding: 10px 0;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}
.tab-btn.active {
  color: $brand-primary;
  border-bottom-color: $brand-primary;
  font-weight: 500;
}
.invite-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.invite-desc {
  font-size: 13px;
  color: #666;
  line-height: 1.5;
}
.code-display {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8f9fa;
  padding: 12px;
  border-radius: 6px;
  border: 1px dashed #ddd;
}
.code-text {
  font-size: 20px;
  font-weight: bold;
  color: $brand-dark;
  font-family: monospace;
}
.copy-btn {
  font-size: 13px;
  color: $brand-primary;
  cursor: pointer;
  padding: 4px 8px;
  &:hover { text-decoration: underline; }
}
.modal-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    margin-top: 24px;
}
.btn-cancel {
    background: #f5f5f5;
    color: #666;
    border: none;
    padding: 6px 16px;
    border-radius: 4px;
    font-size: 14px;
    cursor: pointer;
}
.btn-confirm {
    background: $brand-primary;
    color: #fff;
    border: none;
    padding: 6px 16px;
    border-radius: 4px;
    font-size: 14px;
    cursor: pointer;
}

/* Project Role Badge */
.project-role-badge {
    margin-left: 8px;
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: 500;
}
.role-owner {
    background: #e6f7ff;
    color: #1890ff;
    border: 1px solid #91d5ff;
}
.role-admin {
    background: #f6ffed;
    color: #52c41a;
    border: 1px solid #b7eb8f;
}
.role-client {
    background: #fff0f6;
    color: #eb2f96;
    border: 1px solid #ffadd2;
}
.role-member {
    background: #f0f5ff;
    color: #2f54eb;
    border: 1px solid #adc6ff;
}

/* Manager Avatar */
.manager-avatar-wrapper {
    position: relative;
    width: 26px;
    height: 26px;
    margin-right: 6px;
    cursor: default; /* Changed from help to default */
}
.manager-avatar-img {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    border: 1px solid #ffd700; /* Gold border for manager */
}
.manager-avatar-img {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    border: 1px solid #ffd700; /* Gold border for manager */
    display: block; /* Eliminate font-size gap */
}
.manager-avatar-placeholder {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    background: #fffbe6;
    color: #faad14;
    border: 1px solid #ffd700;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 10px;
    font-weight: bold;
}
.manager-badge-icon {
    position: absolute;
    bottom: -4px;
    right: -4px;
    font-size: 10px;
    line-height: 1;
}
.members-divider {
    width: 1px;
    height: 16px;
    background: #e8e8e8;
    margin: 0 10px; /* Increased margin for better spacing */
    flex-shrink: 0; /* Prevent shrinking */
}

/* New CSS for Layout Opt */
.title-row-flex {
    display: flex;
    align-items: center;
}

.members-split-container {
    display: flex;
    align-items: center;
}

.members-group {
    display: flex;
    align-items: center;
    gap: 6px;
}

.members-vertical-divider {
    width: 1px;
    height: 18px;
    background: #e8e8e8;
    margin: 0 12px;
}

.clients-group {
    background: #fafafa;
    border-radius: 20px;
    padding: 2px 8px 2px 6px;
    border: 1px dashed #e8e8e8;
}

.client-group-label {
    font-size: 10px;
    color: #999;
    margin-right: 4px;
    font-weight: 500;
}

.client-avatar {
    width: 22px;
    height: 22px;
}

.client-placeholder {
    background: #fff0f6;
    color: #eb2f96;
    border-color: #ffadd2;
}


.act-glyph {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
}

.badge-glyph {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.empty-icon {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  color: #C9D4CE;
}
</style>
