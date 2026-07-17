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
           <text class="content-title">项目创建向导</text>
           <text class="content-subtitle">请选择项目类型并录入相关信息，系统将为您初始化项目环境。</text>
        </view>

        <view class="card project-form-card">
            <view class="form-grid">
            <view class="form-row">
              <view class="form-label">
                <text>项目类型</text>
                <text class="required-mark">*</text>
              </view>
              <view class="form-field relative-field">
                <!-- Custom Dropdown Trigger -->
                <view 
                  class="selector-display" 
                  :class="{ 'is-open': projectTypeDropdownOpen }"
                  @tap.stop="toggleProjectTypeDropdown"
                >
                  <text class="selector-text">{{ currentProjectType.label }}</text>
                  <text class="selector-arrow">▼</text>
                </view>

                <!-- Custom Dropdown Menu -->
                <view v-if="projectTypeDropdownOpen" class="custom-dropdown-menu">
                   <view 
                     v-for="(type, index) in projectTypes" 
                     :key="type.value" 
                     class="dropdown-item"
                     :class="{ 'item-selected': index === projectTypeIndex }"
                     @tap.stop="selectProjectType(index)"
                   >
                     <text>{{ type.label }}</text>
                     <text v-if="index === projectTypeIndex" class="check-mark">✓</text>
                   </view>
                </view>
              </view>
            </view>

            <!-- 动态表单字段 -->
            <view
              v-for="field in currentProjectType.formFields"
              :key="field.field"
              class="form-row"
            >
              <view class="form-label">
                <text>{{ field.label }}</text>
                <text v-if="field.required" class="required-mark">*</text>
              </view>
              <view class="form-field">
                <input
                  class="input"
                  type="text"
                  :placeholder="field.placeholder"
                  :value="formModel[field.field]"
                  @input="onInput(field.field, $event)"
                />
              </view>
            </view>
          </view>

          <view class="form-actions">
             <button class="btn btn-cancel" @tap="goToUserProfile">取消</button>
             <button class="btn btn-create" :loading="creating" :disabled="!canCreate" @tap="onCreateProject">
                {{ creating ? '创建中...' : '立即创建' }}
             </button>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { PROJECT_TYPES } from '@/config/projectTypes.js'
import { COMPANY_ROLES } from '@/config/projectTypes.js'
import { fetchCompanyBasicInfo, createProject } from '@/services/api.js'
import { getCurrentUser } from '@/utils/auth.js'

export default {
  data() {
    return {
      userDisplayName: '用户',
      username: '',
      userAvatarUrl: '', // Added to support avatar display
      projectTypes: PROJECT_TYPES,
      projectTypeIndex: 0,
      formModel: {
        projectType: PROJECT_TYPES[0]?.value || '',
        listedCompanyName: '',
        targetCompanyName: '',
        name: '', // For blank project
      },
      creating: false,
      projectTypeDropdownOpen: false
    }
  },
  onLoad() {
    const user = getCurrentUser()
    if (user) {
      this.userDisplayName = user.displayName || user.username || '用户'
      this.username = user.username
      this.userAvatarUrl = user.avatarUrl
    }
  },
  computed: {
    currentProjectType() {
      return this.projectTypes[this.projectTypeIndex] || this.projectTypes[0]
    },
    canCreate() {
      // 动态检查必填字段
      const requiredFields = this.currentProjectType.formFields.filter(f => f.required)
      return requiredFields.every(f => !!this.formModel[f.field])
    },
    isBlankProject() {
        return this.currentProjectType.value === 'BLANK'
    }
  },
  methods: {
    goToUserProfile() {
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
    },
    toggleProjectTypeDropdown() {
      this.projectTypeDropdownOpen = !this.projectTypeDropdownOpen
    },
    selectProjectType(index) {
      this.projectTypeIndex = index
      const type = this.projectTypes[index]
      this.formModel.projectType = type ? type.value : ''
      // 清空字段值
      this.formModel.listedCompanyName = ''
      this.formModel.targetCompanyName = ''
      this.formModel.name = ''
      this.projectTypeDropdownOpen = false
    },
    onInput(field, event) {
      const value = event.detail && event.detail.value
      this.formModel[field] = value
    },
    async onCreateProject() {
      if (!this.canCreate) {
        uni.showToast({
          title: '请先补全必填信息',
          icon: 'none',
        })
        return
      }

      this.creating = true
      let listedInfo = null
      let targetInfo = null

      try {
        // 非空白项目才尝试拉取公司信息
        if (!this.isBlankProject) {
            // 1. 尝试拉取上市公司信息（如果有）
            if (this.formModel.listedCompanyName) {
            try {
                listedInfo = await fetchCompanyBasicInfo({
                projectType: this.formModel.projectType,
                role: COMPANY_ROLES.LISTED,
                name: this.formModel.listedCompanyName
                })
            } catch (e) {
                console.warn('拉取上市公司信息失败，将使用空信息创建', e)
            }
            }

            // 2. 尝试拉取标的公司信息（如果有）
            if (this.formModel.targetCompanyName) {
            try {
                targetInfo = await fetchCompanyBasicInfo({
                projectType: this.formModel.projectType,
                role: COMPANY_ROLES.TARGET,
                name: this.formModel.targetCompanyName
                })
            } catch (e) {
                console.warn('拉取标的公司信息失败，将使用空信息创建', e)
            }
            }
        }

        // 3. 创建项目
        const payload = {
          projectType: this.formModel.projectType,
          listedCompanyName: this.formModel.listedCompanyName,
          targetCompanyName: this.formModel.targetCompanyName,
          name: this.formModel.name, // 只有空白项目会有这个值，或者如果后端支持自定义名称也可以传
          listedCompanyInfo: listedInfo,
          targetCompanyInfo: targetInfo,
        }
        
        const res = await createProject(payload)
        
        uni.showToast({
          title: '项目创建成功',
          icon: 'success',
        })

        // 4. 跳转到项目概览页
        const projectId = res && res.id
        
        setTimeout(() => {
           uni.navigateTo({
             url: `/pages/project-overview/project-overview?id=${projectId}`,
           })
        }, 500)

      } catch (err) {
        uni.showToast({
          title: (err && err.message) || '创建项目失败，请稍后重试',
          icon: 'none',
        })
      } finally {
        this.creating = false
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
  /* Add border to match userprofile */
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
  padding: 40px;
  box-shadow: 0 4px 20px rgba(18, 52, 77, 0.04);
}

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

.relative-field {
    position: relative;
    z-index: 100; /* Ensure dropdown is on top */
}

/* 统一输入框与下拉框样式 */
.selector-display, .input {
  height: 48px;
  background-color: #fff; 
  border: 1px solid $border-color;
  border-radius: 8px;
  padding: 0 16px;
  font-size: 15px;
  color: $text-main;
  transition: all 0.2s;
  box-sizing: border-box;
}

.selector-display {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}

.input {
  width: 100%;
}

.selector-display:hover, .input:hover {
  border-color: #bbb;
}

.selector-display:active, .input:focus {
  border-color: $brand-primary;
  box-shadow: 0 0 0 3px rgba(26, 83, 54, 0.1);
  outline: none;
}

.selector-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selector-arrow {
  font-size: 10px;
  color: $text-secondary;
  margin-left: 8px;
  transition: transform 0.2s;
}

.selector-display.is-open .selector-arrow {
    transform: rotate(180deg);
}

.custom-dropdown-menu {
    position: absolute;
    top: 100%;
    left: 0;
    width: 100%;
    margin-top: 8px;
    background: #fff;
    border: 1px solid $border-color;
    border-radius: 8px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.08); /* Premium Shadow */
    z-index: 1000;
    overflow: hidden;
    padding: 8px 0;
}

.dropdown-item {
    padding: 12px 16px;
    font-size: 14px;
    color: $text-main;
    display: flex;
    justify-content: space-between;
    align-items: center;
    cursor: pointer;
    transition: all 0.2s;
    
    &:hover {
        background-color: rgba(91, 209, 151, 0.08); /* Mint Light */
        color: $brand-primary;
    }
}

.item-selected {
    color: $brand-primary;
    font-weight: 500;
    background-color: rgba(91, 209, 151, 0.08);
}

.check-mark {
    font-size: 12px;
    color: $brand-primary;
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