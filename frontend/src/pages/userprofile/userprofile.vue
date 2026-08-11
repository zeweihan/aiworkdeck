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
        </view>

        <!-- Tab 内容区 -->
        <view class="tab-panel-container">
          

          <!-- 工作记录 Tab -->
          <view v-if="activeTab === 'work_log'" class="panel-work-log">
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
              
              <!-- 账号安全（server 模式；认证器恒可用，短信取决于通道配置） -->
              <view v-if="!isDesktop" class="form-group">
                <text class="group-title">账号安全</text>

                <!-- 认证器（TOTP）：零成本、无国界，登录二次验证优先走它 -->
                <view class="form-row">
                  <text class="form-label">认证器</text>
                  <text class="form-value">{{ userInfo.totpEnabled ? '已绑定' : '未绑定' }}</text>
                  <text class="bind-link" @tap="toggleTotpPanel">{{ userInfo.totpEnabled ? '解绑' : '绑定' }}</text>
                </view>
                <view v-if="showTotpPanel" class="bind-phone-form">
                  <template v-if="!userInfo.totpEnabled">
                    <text class="bind-tip">用 Google Authenticator、1Password 等 App 扫码，或手工录入下方密钥</text>
                    <image v-if="totpQrDataUrl" class="totp-qr" :src="totpQrDataUrl" mode="widthFix" />
                    <view class="form-row">
                      <text class="form-label">密钥</text>
                      <text class="totp-secret">{{ totpSecret }}</text>
                    </view>
                    <view class="form-row">
                      <text class="form-label">验证码</text>
                      <input class="bind-input code" type="number" maxlength="6" v-model="totpCodeInput" placeholder="App 上的 6 位码" />
                    </view>
                    <view class="bind-actions">
                      <button class="btn-bind-confirm" @tap="confirmTotpBind">完成绑定</button>
                      <text class="bind-link" @tap="cancelTotpPanel">取消</text>
                    </view>
                  </template>
                  <template v-else>
                    <text class="bind-tip">解绑需要验证一次当前验证码</text>
                    <view class="form-row">
                      <text class="form-label">验证码</text>
                      <input class="bind-input code" type="number" maxlength="6" v-model="totpCodeInput" placeholder="App 上的 6 位码" />
                    </view>
                    <view class="bind-actions">
                      <button class="btn-bind-confirm" @tap="confirmTotpDisable">确认解绑</button>
                      <text class="bind-link" @tap="cancelTotpPanel">取消</text>
                    </view>
                  </template>
                </view>

                <view v-if="userInfo.smsAuthEnabled" class="form-row">
                  <text class="form-label">手机号</text>
                  <text class="form-value">{{ userInfo.phoneMasked || '未绑定' }}</text>
                  <text class="bind-link" @tap="showBindPhone = !showBindPhone">{{ userInfo.phoneMasked ? '更换' : '绑定' }}</text>
                </view>
                <view v-if="showBindPhone" class="bind-phone-form">
                  <view class="form-row">
                    <text class="form-label">新手机号</text>
                    <input class="bind-input" type="number" maxlength="11" v-model="bindPhoneInput" placeholder="请输入大陆手机号" />
                  </view>
                  <view class="form-row">
                    <text class="form-label">验证码</text>
                    <input class="bind-input code" type="number" maxlength="6" v-model="bindCodeInput" placeholder="6 位验证码" />
                    <button class="btn-send-code" :disabled="bindCountdown > 0" @tap="sendBindPhoneCode">
                      {{ bindCountdown > 0 ? bindCountdown + 's' : '获取验证码' }}
                    </button>
                  </view>
                  <view class="bind-actions">
                    <button class="btn-bind-confirm" @tap="confirmBindPhone">确认绑定</button>
                    <text class="bind-link" @tap="cancelBindPhone">取消</text>
                  </view>
                  <text class="bind-tip">绑定后，本账号在网页端与桌面端连接时的密码登录均需短信验证码</text>
                </view>

                <view v-if="userInfo.mailAuthEnabled" class="form-row">
                  <text class="form-label">邮箱</text>
                  <text class="form-value">{{ userInfo.emailMasked || '未绑定' }}</text>
                  <text class="bind-link" @tap="showBindEmail = !showBindEmail">{{ userInfo.emailMasked ? '更换' : '绑定' }}</text>
                </view>
                <view v-if="showBindEmail" class="bind-phone-form">
                  <view class="form-row">
                    <text class="form-label">新邮箱</text>
                    <input class="bind-input" v-model="bindEmailInput" placeholder="请输入邮箱地址" />
                  </view>
                  <view class="form-row">
                    <text class="form-label">验证码</text>
                    <input class="bind-input code" type="number" maxlength="6" v-model="bindEmailCodeInput" placeholder="6 位验证码" />
                    <button class="btn-send-code" :disabled="bindEmailCountdown > 0" @tap="sendBindEmailCode">
                      {{ bindEmailCountdown > 0 ? bindEmailCountdown + 's' : '获取验证码' }}
                    </button>
                  </view>
                  <view class="bind-actions">
                    <button class="btn-bind-confirm" @tap="confirmBindEmail">确认绑定</button>
                    <text class="bind-link" @tap="cancelBindEmail">取消</text>
                  </view>
                  <text class="bind-tip">绑定邮箱后，登录二次验证优先走邮件而不再发短信</text>
                </view>
              </view>

              <!-- 授权（桌面端）：当前模式 / 激活时间 / 解除授权 -->
              <view v-if="isDesktop && licenseInfo.unlocked" class="form-group">
                <text class="group-title">授权</text>
                <view class="form-row">
                  <text class="form-label">当前模式</text>
                  <!-- 读 edition 不读 mode：mode 只是授权票据，先用试用码解锁、
                       后连账户的用户 mode 永远停在 trial（后端已把两条状态组合成 edition） -->
                  <text class="form-value">{{ licenseInfo.edition === 'paid' ? '正式版' : '试用版' }}</text>
                </view>
                <view class="form-row">
                  <text class="form-label">激活时间</text>
                  <text class="form-value">{{ licenseInfo.activatedAt ? formatTime(licenseInfo.activatedAt) : '—' }}</text>
                </view>
                <button class="btn-logout-settings" @tap="handleDeactivate">解除授权</button>
              </view>

              <!-- 插件访问令牌（桌面端）：Office 插件等外部客户端连接本机后端的凭据 -->
              <view v-if="isDesktop" class="form-group">
                <text class="group-title">插件访问令牌</text>
                <text class="bind-tip">供 Microsoft Office 插件等外部客户端连接本机使用。令牌等同于你的访问身份，请妥善保管。</text>
                <view class="form-row">
                  <text class="form-label">备注名</text>
                  <input class="bind-input" v-model="tokenNameInput" maxlength="30" placeholder="例如：Word 插件（可留空）" />
                  <button class="btn-send-code" :disabled="tokenIssuing" @tap="handleIssueToken">生成令牌</button>
                </view>
                <view v-for="t in deviceTokens" :key="t.id" class="form-row">
                  <view class="token-info">
                    <text class="token-name">{{ t.name || '未命名令牌' }}</text>
                    <text class="token-meta">
                      创建于 {{ formatTime(t.createdAt) || '—' }} · 最近使用 {{ t.lastUsedAt ? formatTime(t.lastUsedAt) : '从未' }}
                    </text>
                  </view>
                  <text class="bind-link" @tap="handleRevokeToken(t)">撤销</text>
                </view>
                <text v-if="!deviceTokens.length" class="bind-tip">还没有生成过令牌</text>
              </view>

              <view v-if="!isDesktop" class="form-group">
                  <button class="btn-logout-settings" @tap="handleLogout">退出登录</button>
              </view>
            </view>
          </view>

        </view>
      </view>
    </view>
    
  </view>
</template>

<script>
import { getCurrentUser as getCurrentUserApi, getMyFavorites, deleteFavorite, getFavoriteImageUrl, addProjectMember, getUserActivityHistory, inviteClient, uploadAvatar, getLicenseStatus, deactivateLicense, sendSmsCode, bindPhone, sendMailCode, bindEmail, totpSetup, totpActivate, totpDisable, issueLocalDeviceToken, listDeviceTokens, revokeDeviceToken } from '@/services/api.js'
import { host, isDesktopHost } from '@/services/host.js'
 import { getCurrentUser, isLoggedIn, getSessionId, clearSession, setSessionUser } from '@/utils/auth.js'
import { ICONS } from '@/config/icons.js'

export default {

  computed: {

    ICONS() { return ICONS },

    isDesktop() {
      return isDesktopHost()
    }

  },
  name: 'UserProfile',
  data() {
    return {
      activeTab: 'work_log',
      tabs: [
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
      favoritesLoading: false,
      favorites: [],
      

      // 授权状态（桌面端）：{ unlocked, mode, plan, activatedAt?, accountConnected, edition }
      licenseInfo: {},

      // 插件访问令牌（桌面端）：明文只在生成时返回一次，这里只留列表元信息
      deviceTokens: [],
      tokenNameInput: '',
      tokenIssuing: false,

      // 认证器（TOTP）绑定
      showTotpPanel: false,
      totpSecret: '',
      totpQrDataUrl: '',
      totpCodeInput: '',

      // 手机号绑定（登录短信验证，仅 server 模式且启用时显示）
      showBindPhone: false,
      bindPhoneInput: '',
      bindCodeInput: '',
      bindCountdown: 0,
      bindCountdownTimer: null,

      // 邮箱绑定（与手机号并列的二次验证方式；绑了之后优先走邮件，省短信费）
      showBindEmail: false,
      bindEmailInput: '',
      bindEmailCodeInput: '',
      bindEmailCountdown: 0,
      bindEmailCountdownTimer: null,

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
      if (host.browser && host.browser.setViewsVisible) {
        host.browser.setViewsVisible({ visible: false }).catch(() => {})
      }
    } catch (e) {
      // ignore
    }
    // 检查登录状态（桌面端 local-mode 免登录，跳过该检查）
    const isDesktopEnv = isDesktopHost()
    if (!isDesktopEnv) {
      const sessionId = getSessionId()
      const user = getCurrentUser()

      if (!sessionId || !user) {
        console.warn('未登录，跳转到登录页', { sessionId, user })
        uni.reLaunch({
          url: '/pages/login/login',
        })
        return
      }
    }

    // 延迟加载，确保页面完全加载后再请求数据
    this.$nextTick(() => {
      // 加载用户信息
      this.loadUserInfo()
      // 默认 tab 是「工作记录」，它和收藏一样是懒加载的（只在 switchTab 里触发），
      // 默认落它就必须在这里补一次，否则一进来是一张永远空白的表
      this.loadActivityLogs()
      // 桌面端：加载授权状态（设置面板「授权」卡片）与插件访问令牌列表
      if (isDesktopEnv) {
        this.loadLicenseInfo()
        this.loadDeviceTokens()
      }
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
    async loadLicenseInfo() {
      try {
        const status = await getLicenseStatus()
        this.licenseInfo = status || {}
      } catch (e) {
        // 旧后端没有该端点：静默忽略
        this.licenseInfo = {}
      }
    },
    async loadDeviceTokens() {
      try {
        const res = await listDeviceTokens()
        this.deviceTokens = (res && res.data && res.data.tokens) || []
      } catch (e) {
        // 旧后端没有该端点：当作没有令牌，不打扰用户
        this.deviceTokens = []
      }
    },
    async handleIssueToken() {
      if (this.tokenIssuing) return
      this.tokenIssuing = true
      try {
        const res = await issueLocalDeviceToken(this.tokenNameInput.trim())
        const token = res && res.data && res.data.token
        if (!token) throw new Error('令牌生成失败')
        this.tokenNameInput = ''
        await this.loadDeviceTokens()
        // 明文只在这一次拿得到，弹窗里直接给复制
        uni.showModal({
          title: '令牌已生成',
          content: token + '\n\n令牌仅显示这一次，关闭后无法再次查看。请立即复制并粘贴到插件设置里。',
          cancelText: '关闭',
          confirmText: '复制',
          success: (r) => {
            if (!r.confirm) return
            uni.setClipboardData({
              data: token,
              success: () => uni.showToast({ title: '已复制', icon: 'none' }),
            })
          },
        })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '令牌生成失败', icon: 'none' })
      } finally {
        this.tokenIssuing = false
      }
    },
    handleRevokeToken(token) {
      uni.showModal({
        title: '撤销令牌',
        content: '撤销后，正在使用这枚令牌的插件会立即失去访问权，需要重新生成。确定撤销吗？',
        cancelText: '取消',
        confirmText: '确认撤销',
        success: async (r) => {
          if (!r.confirm) return
          try {
            await revokeDeviceToken(token.id)
            await this.loadDeviceTokens()
            uni.showToast({ title: '已撤销', icon: 'none' })
          } catch (e) {
            uni.showToast({ title: (e && e.message) || '撤销失败', icon: 'none' })
          }
        },
      })
    },
    handleDeactivate() {
      uni.showModal({
        title: '解除授权',
        content: '解除后应用将回到解锁页，需要重新输入试用码或账户 Key 才能继续使用。确定解除吗？',
        cancelText: '取消',
        confirmText: '确认解除',
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deactivateLicense()
            uni.reLaunch({ url: '/pages/launch/launch' })
          } catch (e) {
            uni.showToast({ title: (e && e.message) || '解除授权失败', icon: 'none' })
          }
        }
      })
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
      }
      // 短信绑定状态（smsAuthEnabled/phoneMasked）只在 /api/auth/me 下发，
      // 缓存的登录响应里没有——有缓存也拉一次合并
      try {
        const res = await getCurrentUserApi()
        if (res.code === 0 && res.data) {
          this.userInfo = { ...this.userInfo, ...res.data }
          this.checkAdminTab()
        }
      } catch (error) {
        console.error('获取用户信息失败:', error)
      }
    },
    async toggleTotpPanel() {
      if (this.showTotpPanel) {
        this.cancelTotpPanel()
        return
      }
      this.totpCodeInput = ''
      this.showTotpPanel = true
      if (this.userInfo.totpEnabled) return
      try {
        const res = await totpSetup()
        this.totpSecret = (res.data && res.data.secret) || ''
        const uri = (res.data && res.data.provisioningUri) || ''
        // 二维码在前端渲染：otpauth URI 含密钥，不该经由图片服务多走一手
        const QRCode = (await import('qrcode')).default
        this.totpQrDataUrl = uri ? await QRCode.toDataURL(uri, { margin: 1, width: 180 }) : ''
      } catch (e) {
        this.showTotpPanel = false
        uni.showToast({ title: e.message || '获取绑定信息失败', icon: 'none' })
      }
    },
    async confirmTotpBind() {
      if (!this.totpCodeInput || this.totpCodeInput.length < 6) {
        uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
        return
      }
      try {
        await totpActivate(this.totpCodeInput)
        this.userInfo = { ...this.userInfo, totpEnabled: true }
        setSessionUser(this.userInfo)
        uni.showToast({ title: '认证器已绑定', icon: 'success' })
        this.cancelTotpPanel()
      } catch (e) {
        uni.showToast({ title: e.message || '绑定失败', icon: 'none' })
      }
    },
    async confirmTotpDisable() {
      if (!this.totpCodeInput || this.totpCodeInput.length < 6) {
        uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
        return
      }
      try {
        await totpDisable(this.totpCodeInput)
        this.userInfo = { ...this.userInfo, totpEnabled: false }
        setSessionUser(this.userInfo)
        uni.showToast({ title: '认证器已解绑', icon: 'success' })
        this.cancelTotpPanel()
      } catch (e) {
        uni.showToast({ title: e.message || '解绑失败', icon: 'none' })
      }
    },
    cancelTotpPanel() {
      this.showTotpPanel = false
      this.totpSecret = ''
      this.totpQrDataUrl = ''
      this.totpCodeInput = ''
    },
    async sendBindPhoneCode() {
      if (this.bindCountdown > 0) return
      if (!/^1[3-9]\d{9}$/.test(this.bindPhoneInput)) {
        uni.showToast({ title: '请输入正确的手机号', icon: 'none' })
        return
      }
      try {
        await sendSmsCode({ scene: 'bind', phone: this.bindPhoneInput })
        uni.showToast({ title: '验证码已发送', icon: 'none' })
        this.bindCountdown = 60
        if (this.bindCountdownTimer) clearInterval(this.bindCountdownTimer)
        this.bindCountdownTimer = setInterval(() => {
          if (this.bindCountdown > 0) {
            this.bindCountdown--
          } else {
            clearInterval(this.bindCountdownTimer)
            this.bindCountdownTimer = null
          }
        }, 1000)
      } catch (e) {
        uni.showToast({ title: e.message || '发送失败', icon: 'none' })
      }
    },
    async confirmBindPhone() {
      if (!this.bindCodeInput || this.bindCodeInput.length < 6) {
        uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
        return
      }
      try {
        const res = await bindPhone(this.bindPhoneInput, this.bindCodeInput)
        uni.showToast({ title: '绑定成功', icon: 'success' })
        const phoneMasked = (res.data && res.data.phoneMasked) || ''
        this.userInfo = { ...this.userInfo, phoneMasked }
        setSessionUser(this.userInfo)
        this.cancelBindPhone()
      } catch (e) {
        uni.showToast({ title: e.message || '绑定失败', icon: 'none' })
      }
    },
    cancelBindPhone() {
      this.showBindPhone = false
      this.bindPhoneInput = ''
      this.bindCodeInput = ''
      this.bindCountdown = 0
      if (this.bindCountdownTimer) {
        clearInterval(this.bindCountdownTimer)
        this.bindCountdownTimer = null
      }
    },
    async sendBindEmailCode() {
      if (this.bindEmailCountdown > 0) return
      // 只挡明显不是邮箱的输入；真正的规范化与判定在后端，前端不复刻一套正则
      if (!/^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test((this.bindEmailInput || '').trim())) {
        uni.showToast({ title: '请输入正确的邮箱地址', icon: 'none' })
        return
      }
      try {
        await sendMailCode({ scene: 'bind', email: this.bindEmailInput.trim() })
        uni.showToast({ title: '验证码已发送', icon: 'none' })
        this.bindEmailCountdown = 60
        if (this.bindEmailCountdownTimer) clearInterval(this.bindEmailCountdownTimer)
        this.bindEmailCountdownTimer = setInterval(() => {
          if (this.bindEmailCountdown > 0) {
            this.bindEmailCountdown--
          } else {
            clearInterval(this.bindEmailCountdownTimer)
            this.bindEmailCountdownTimer = null
          }
        }, 1000)
      } catch (e) {
        uni.showToast({ title: e.message || '发送失败', icon: 'none' })
      }
    },
    async confirmBindEmail() {
      if (!this.bindEmailCodeInput || this.bindEmailCodeInput.length < 6) {
        uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
        return
      }
      try {
        const res = await bindEmail(this.bindEmailInput.trim(), this.bindEmailCodeInput)
        uni.showToast({ title: '绑定成功', icon: 'success' })
        const emailMasked = (res.data && res.data.emailMasked) || ''
        this.userInfo = { ...this.userInfo, emailMasked }
        setSessionUser(this.userInfo)
        this.cancelBindEmail()
      } catch (e) {
        uni.showToast({ title: e.message || '绑定失败', icon: 'none' })
      }
    },
    cancelBindEmail() {
      this.showBindEmail = false
      this.bindEmailInput = ''
      this.bindEmailCodeInput = ''
      this.bindEmailCountdown = 0
      if (this.bindEmailCountdownTimer) {
        clearInterval(this.bindEmailCountdownTimer)
        this.bindEmailCountdownTimer = null
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
    goToAdmin() {
      uni.navigateTo({
        url: '/pages/admin/admin',
      })
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

/* 手机号绑定（登录短信验证） */
.bind-link {
    color: $brand-primary;
    font-size: 13px;
    margin-left: 12px;
    cursor: pointer;
}
.bind-phone-form {
    margin-top: 8px;
    padding-top: 8px;
    border-top: 1px dashed $border-color;
}
.bind-input {
    flex: 1;
    height: 36px;
    border: 1px solid $border-color;
    border-radius: 6px;
    padding: 0 10px;
    font-size: 13px;
    background: #fff;
}
.btn-send-code {
    height: 36px;
    line-height: 34px;
    margin-left: 8px;
    padding: 0 12px;
    border: 1px solid $border-color;
    border-radius: 6px;
    background: #fff;
    color: $text-main;
    font-size: 13px;
    cursor: pointer;

    &[disabled] {
        opacity: 0.5;
        cursor: default;
    }
}
.bind-actions {
    display: flex;
    align-items: center;
    margin-top: 10px;
}
.btn-bind-confirm {
    height: 36px;
    line-height: 36px;
    padding: 0 18px;
    border-radius: 6px;
    background: $brand-primary;
    color: #fff;
    font-size: 13px;
    cursor: pointer;
}
.bind-tip {
    display: block;
    margin-top: 8px;
    font-size: 12px;
    color: $text-secondary;
}
.token-info {
    flex: 1;
    display: flex;
    flex-direction: column;
}
.token-name {
    font-size: 14px;
    color: $text-main;
    font-weight: 500;
}
.token-meta {
    margin-top: 2px;
    font-size: 12px;
    color: $text-secondary;
}
.totp-qr {
    width: 180px;
    margin: 10px 0;
    background: #fff;
    border: 1px solid $border-color;
    border-radius: 6px;
}
.totp-secret {
    flex: 1;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
    letter-spacing: 1px;
    color: $text-main;
    word-break: break-all;
    user-select: text;
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



.empty-icon {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  color: #C9D4CE;
}
</style>
