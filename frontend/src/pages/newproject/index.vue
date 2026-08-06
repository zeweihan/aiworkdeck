<template>
  <view class="page-new-project">
    <view class="workbench-container">
      <!-- 左侧个人信息卡片 (复用样式) -->
      <view class="user-sidebar">
        <!-- Logo Area -->
        <view class="sidebar-logo-area">
            <image src="/static/logo_full_v2.png" class="sidebar-logo" mode="heightFix" />
        </view>

        <view class="user-card">
          <view class="card-gold-accent"></view>
          <view class="user-profile-main">
            <view class="user-avatar-wrapper">
              <image
                v-if="userAvatarUrl"
                class="user-avatar"
                :src="userAvatarUrl"
                mode="aspectFill"
              />
              <view v-else class="user-avatar-placeholder">
                <text class="avatar-text">{{ userDisplayName?.charAt(0) || 'U' }}</text>
              </view>
            </view>
            <text class="user-name">{{ userDisplayName }}</text>
            <text class="user-handle">@{{ username || 'user' }}</text>
            <view class="user-role-tag">
              <text class="role-text">标准用户</text>
            </view>
          </view>

          <view class="user-actions">
            <view class="action-item" @tap="goToUserProfile">
              <text class="action-text">返回个人中心</text>
              <text class="action-arrow">›</text>
            </view>
          </view>
        </view>
      </view>

      <!-- 右侧主内容区 -->
      <view class="main-content">
        <view class="content-header">
           <text class="content-title">新建或打开项目</text>
           <text class="content-subtitle">项目就是您电脑上的一个文件夹：文件保存在原位，随时可在访达（Finder）中查看和管理。</text>
        </view>

        <view class="card project-form-card">
          <template v-if="isDesktop">
            <view class="ide-action" :class="{ 'is-busy': busy }" @tap="onOpenFolder">
              <view class="ide-action-text">
                <text class="ide-action-title">打开文件夹…</text>
                <text class="ide-action-desc">把电脑上已有的文件夹作为项目打开，里面的文件自动进入文件树</text>
              </view>
              <text class="ide-action-arrow">›</text>
            </view>
            <view class="ide-action" :class="{ 'is-busy': busy }" @tap="onCreateFolder">
              <view class="ide-action-text">
                <text class="ide-action-title">新建项目文件夹…</text>
                <text class="ide-action-desc">选择存放位置并新建一个文件夹，从空白开始工作</text>
              </view>
              <text class="ide-action-arrow">›</text>
            </view>
            <view class="ide-action" :class="{ 'is-busy': busy }" @tap="onOpenFile">
              <view class="ide-action-text">
                <text class="ide-action-title">打开文件…</text>
                <text class="ide-action-desc">打开单个文件，自动以它所在的文件夹作为项目</text>
              </view>
              <text class="ide-action-arrow">›</text>
            </view>
            <view v-if="busy" class="ide-busy-hint">
              <text>{{ busyText }}</text>
            </view>
          </template>

          <template v-else>
            <!-- 非桌面环境（浏览器）没有系统文件夹对话框，降级为托管空白项目 -->
            <view class="form-grid">
              <view class="form-row">
                <view class="form-label">
                  <text>项目名称</text>
                  <text class="required-mark">*</text>
                </view>
                <view class="form-field">
                  <input
                    class="input"
                    type="text"
                    placeholder="请输入项目名称"
                    :value="blankName"
                    @input="e => { blankName = e.detail && e.detail.value }"
                  />
                </view>
              </view>
            </view>
            <view class="form-actions">
               <button class="btn btn-cancel" @tap="goToUserProfile">取消</button>
               <button class="btn btn-create" :loading="busy" :disabled="!blankName || busy" @tap="onCreateBlank">
                  {{ busy ? '创建中...' : '创建项目' }}
               </button>
            </view>
            <view class="ide-web-hint">
              <text>提示：在桌面版中，新建项目可直接打开本地文件夹，与 IDE 体验一致。</text>
            </view>
          </template>
        </view>
      </view>
    </view>

    <!-- 新建项目文件夹：命名弹窗 -->
    <view v-if="namingVisible" class="naming-mask" @tap.self="namingVisible = false">
      <view class="naming-dialog">
        <text class="naming-title">新建项目文件夹</text>
        <text class="naming-location">位置：{{ namingParentDir }}</text>
        <input
          class="input naming-input"
          type="text"
          placeholder="文件夹名称"
          :value="namingName"
          :focus="namingVisible"
          @input="e => { namingName = e.detail && e.detail.value }"
          @confirm="confirmCreateFolder"
        />
        <view class="naming-actions">
          <button class="btn btn-cancel" @tap="namingVisible = false">取消</button>
          <button class="btn btn-create" :disabled="!namingNameValid || busy" @tap="confirmCreateFolder">创建</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { createProject } from '@/services/api.js'
import { getCurrentUser } from '@/utils/auth.js'
import { openFolderFlow, openFileFlow, createFolderFlow } from '@/utils/ideOpen.js'
import { host } from '@/services/host.js'

export default {
  data() {
    return {
      userDisplayName: '用户',
      username: '',
      userAvatarUrl: '',
      busy: false,
      busyText: '正在打开项目…',
      blankName: '',
      namingVisible: false,
      namingParentDir: '',
      namingName: '',
    }
  },
  onLoad(query) {
    const user = getCurrentUser()
    if (user) {
      this.userDisplayName = user.displayName || user.username || '用户'
      this.username = user.username
      this.userAvatarUrl = user.avatarUrl
    }
    // 应用菜单「新建项目文件夹…」跳入时自动拉起流程
    if (query && query.auto === 'create-folder') {
      setTimeout(() => { if (this.isDesktop) this.onCreateFolder() }, 300)
    }
  },
  computed: {
    isDesktop() {
      return !!(host.fs
        && host.fs.showOpenDialog)
    },
    namingNameValid() {
      const n = (this.namingName || '').trim()
      return !!n && !n.includes('/') && !n.includes('\\') && n !== '.' && n !== '..'
    },
  },
  methods: {
    goToUserProfile() {
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
    },

    // ---- IDE 化入口（桌面） ----

    async onOpenFolder() {
      if (this.busy) return
      await this.withBusy('正在打开文件夹…', () => openFolderFlow())
    },

    async onCreateFolder() {
      if (this.busy) return
      const res = await host.fs.showOpenDialog({
        title: '选择存放位置',
        buttonLabel: '选择此处',
        properties: ['openDirectory', 'createDirectory'],
      })
      if (!res || res.canceled || !res.filePaths || !res.filePaths.length) return
      this.namingParentDir = res.filePaths[0]
      this.namingName = ''
      this.namingVisible = true
    },

    async confirmCreateFolder() {
      if (!this.namingNameValid || this.busy) return
      const parentDir = this.namingParentDir
      const name = this.namingName.trim()
      this.namingVisible = false
      await this.withBusy('正在创建项目…', () => createFolderFlow(parentDir, name))
    },

    async onOpenFile() {
      if (this.busy) return
      await this.withBusy('正在打开文件…', () => openFileFlow())
    },

    async withBusy(busyText, flow) {
      this.busy = true
      this.busyText = busyText || '正在打开项目…'
      try {
        await flow()
      } catch (err) {
        uni.showToast({ title: (err && err.message) || '打开项目失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },

    // ---- 浏览器降级：托管空白项目 ----

    async onCreateBlank() {
      if (!this.blankName || this.busy) return
      this.busy = true
      try {
        const res = await createProject({ projectType: 'BLANK', name: this.blankName })
        const projectId = res && res.id
        uni.showToast({ title: '项目创建成功', icon: 'success' })
        setTimeout(() => {
          uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${projectId}` })
        }, 500)
      } catch (err) {
        uni.showToast({ title: (err && err.message) || '创建项目失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
/* 品牌配色变量 - AI Workdeck Palette */
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

.page-new-project {
  min-height: 100vh;
  /* Subtle Gradient Background */
  background: linear-gradient(135deg, #F8F9FA 0%, #E8F3ED 100%);
  padding: 40px 24px;
  box-sizing: border-box;
}

.workbench-container {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 24px;
}

/* 左侧边栏 - 复用 UserProfile 样式 */
.user-sidebar {
  width: 280px;
  flex-shrink: 0;
  display: flex;
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
    width: auto;
}

.user-card {
  background: $brand-white;
  border-radius: 16px;
  box-shadow: 0 4px 16px rgba(18, 52, 77, 0.05);
  overflow: hidden;
  position: relative;
  padding-bottom: 24px;
  border: 1px solid rgba(0,0,0,0.02);
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
  background: rgba(26, 83, 54, 0.08); /* Forest Light */
  padding: 4px 12px;
  border-radius: 4px;
  border: 1px solid rgba(26, 83, 54, 0.1);
}

.role-text {
  font-size: 12px;
  color: $brand-primary;
  font-weight: 500;
}

.user-actions {
  padding: 16px 0 0;
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
  color: $text-secondary;
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
    margin-bottom: 24px;
}

.content-title {
    display: block;
    font-size: 24px;
    font-weight: 600;
    color: $text-main;
    margin-bottom: 8px;
}

.content-subtitle {
    font-size: 14px;
    color: $text-secondary;
}

.project-form-card {
  background: $brand-white;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 20px rgba(18, 52, 77, 0.04);
}

/* IDE 化动作行 */
.ide-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px;
  border: 1px solid $border-color;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;

  & + .ide-action {
    margin-top: 12px;
  }

  &:hover {
    border-color: $brand-primary;
    background: rgba(91, 209, 151, 0.05);
    transform: translateY(-1px);
    box-shadow: 0 4px 12px rgba(26, 83, 54, 0.08);
  }

  &.is-busy {
    opacity: 0.5;
    pointer-events: none;
  }
}

.ide-action-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.ide-action-title {
  font-size: 16px;
  font-weight: 600;
  color: $text-main;
}

.ide-action-desc {
  font-size: 13px;
  color: $text-secondary;
}

.ide-action-arrow {
  font-size: 22px;
  color: $text-light;
  font-family: monospace;
  margin-left: 16px;
}

.ide-busy-hint {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: $text-secondary;
}

.ide-web-hint {
  margin-top: 16px;
  font-size: 12px;
  color: $text-light;
}

/* 浏览器降级表单 */
.form-grid {
  display: flex;
  flex-direction: column;
  gap: 24px;
  max-width: 600px;
}

.form-row {
  display: flex;
  flex-direction: column;
}

.form-label {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 500;
  color: $text-main;
}

.required-mark {
  color: $danger-color;
  margin-left: 4px;
  font-size: 16px;
}

.form-field {
  display: flex;
  flex-direction: column;
}

.input {
  height: 48px;
  background-color: #fff;
  border: 1px solid $border-color;
  border-radius: 8px;
  padding: 0 16px;
  font-size: 15px;
  color: $text-main;
  transition: all 0.2s;
  box-sizing: border-box;
  width: 100%;
}

.input:hover {
  border-color: #bbb;
}

.input:focus {
  border-color: $brand-primary;
  box-shadow: 0 0 0 3px rgba(26, 83, 54, 0.1);
  outline: none;
}

.form-actions {
  display: flex;
  gap: 16px;
  margin-top: 40px;
  padding-top: 24px;
  border-top: 1px solid #f5f5f5;
  max-width: 600px;
}

.btn {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
  padding: 0 24px;
}

.btn-cancel {
    background: #f5f5f5;
    color: $text-secondary;
    flex: 1;

    &:hover {
        background: #e0e0e0;
    }
}

.btn-create {
  background-color: $brand-primary;
  color: #fff;
  flex: 2;
  box-shadow: 0 4px 12px rgba(26, 83, 54, 0.2);

  &:hover {
    background-color: lighten($brand-primary, 5%);
    box-shadow: 0 6px 16px rgba(26, 83, 54, 0.3);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(1px);
  }
}

.btn-create[disabled] {
  background-color: #E0E0E0;
  color: #999;
  box-shadow: none;
  cursor: not-allowed;
  transform: none;
}

/* 新建文件夹命名弹窗 */
.naming-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.naming-dialog {
  width: 420px;
  max-width: calc(100vw - 48px);
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
}

.naming-title {
  font-size: 17px;
  font-weight: 600;
  color: $text-main;
  margin-bottom: 8px;
}

.naming-location {
  font-size: 12px;
  color: $text-secondary;
  margin-bottom: 16px;
  word-break: break-all;
}

.naming-input {
  margin-bottom: 20px;
}

.naming-actions {
  display: flex;
  gap: 12px;
}

@media screen and (max-width: 768px) {
  .workbench-container {
    flex-direction: column;
  }

  .user-sidebar {
    width: 100%;
  }

  .form-actions {
      max-width: 100%;
  }

  .form-grid {
      max-width: 100%;
  }
}
</style>
