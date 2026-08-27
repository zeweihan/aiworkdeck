<template>
  <view class="page-variable-library">
    <!-- 顶部项目信息 -->
    <view class="header-card">
      <view class="title-row">
        <text class="project-name">{{ project.name || $t('panels.vlUnnamedProject') }}</text>
        <text class="project-type-tag">{{ project.projectTypeLabel || $t('panels.vlProjectTypePending') }}</text>
      </view>
      <view class="meta-row">
        <text class="meta-item">{{ $t('panels.vlListedCompanyLine', { name: project.listedCompanyName || '-' }) }}</text>
        <text class="meta-item">{{ $t('panels.vlTargetCompanyLine', { name: project.targetCompanyName || '-' }) }}</text>
      </view>
    </view>

    <!-- 工具栏 -->
    <view class="toolbar">
      <button class="btn" type="primary" size="mini" @tap="addVariable">
        {{ $t('panels.vlAddVariable') }}
      </button>
      <button class="btn" type="default" size="mini" @tap="goBack">
        {{ $t('panels.vlGoBack') }}
      </button>
    </view>

    <!-- 搜索 -->
    <view class="search-bar">
      <input
        class="search-input"
        v-model="searchKeyword"
        :placeholder="VARIABLE_LIBRARY_SEARCH_PLACEHOLDER"
        confirm-type="search"
      />
      <view v-if="searchKeyword" class="search-clear" @tap="searchKeyword = ''">×</view>
    </view>

    <!-- 变量列表 -->
    <scroll-view class="variable-list" scroll-y="true">
      <view v-if="loading" class="empty">
        {{ $t('panels.vlLoading') }}
      </view>
      <view v-else-if="variables.length === 0" class="empty">
        {{ $t('panels.vlEmptyVariables') }}
      </view>
      <view v-else>
        <view 
          v-for="(groupVars, groupName) in groupedVariables" 
          :key="groupName" 
          class="variable-group"
        >
          <view class="group-header">
            <text class="group-title">{{ groupName }}</text>
          </view>
          
          <view
            v-for="v in groupVars"
            :key="v.id || v._tempId"
            class="variable-card"
          >
            <view class="card-header">
              <input
                class="var-name-input"
                v-model="v.name"
                :placeholder="$t('panels.vlNamePlaceholder')"
                @input="markDirty(v)"
              />
              <text class="var-type-tag">
                {{ v.type === 'TEMPLATE' ? $t('panels.vlTypeComposite') : $t('panels.vlTypeText') }}
              </text>
            </view>
            <textarea
              class="var-value-input"
              v-model="v.value"
              :placeholder="$t('panels.vlValuePlaceholder')"
              auto-height
              @input="markDirty(v)"
            />
            <view class="card-footer">
              <button
                class="btn-small"
                type="primary"
                size="mini"
                :disabled="!v._dirty || saving"
                @tap="saveVariable(v)"
              >
                {{ $t('panels.vlSave') }}
              </button>
              <button
                class="btn-small"
                type="warn"
                size="mini"
                :disabled="!v.id || saving"
                @tap="deleteVariable(v)"
              >
                {{ $t('panels.vlDelete') }}
              </button>
            </view>
            <view class="card-meta" v-if="v.updatedAt">
              <text class="meta-text">{{ $t('panels.vlUpdatedAtLine', { time: formatTime(v.updatedAt) }) }}</text>
            </view>
          </view>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script>
import { getProject, getProjectVariables, saveProjectVariable, deleteProjectVariable } from '@/services/api.js'
import { VARIABLE_LIBRARY_SEARCH_PLACEHOLDER } from '@/config/variables.js'

export default {
  data() {
    return {
      projectId: null,
      project: {},
      variables: [],
      loading: false,
      saving: false,
      searchKeyword: '',
      VARIABLE_LIBRARY_SEARCH_PLACEHOLDER,
    }
  },
  computed: {
    filteredVariables() {
      const kw = (this.searchKeyword || '').trim().toLowerCase()
      if (!kw) return this.variables
      return (this.variables || []).filter(v => {
        const name = String(v?.name || '').toLowerCase()
        const value = String(v?.value || '').toLowerCase()
        const group = String(v?.variableGroup || '').toLowerCase()
        return name.includes(kw) || value.includes(kw) || group.includes(kw)
      })
    },
    groupedVariables() {
      const groups = {};
      this.filteredVariables.forEach(v => {
        const group = v.variableGroup || this.$t('panels.vlOtherGroup');
        if (!groups[group]) {
          groups[group] = [];
        }
        groups[group].push(v);
      });
      return groups;
    }
  },
  async onLoad(query) {
    if (query && query.id) {
      this.projectId = Number(query.id) || query.id
      this.project.id = this.projectId
    }
    if (query && query.name) {
      this.project.name = query.name
    }
    if (query && query.projectTypeLabel) {
      this.project.projectTypeLabel = query.projectTypeLabel
    }
    if (query && query.listedCompanyName) {
      this.project.listedCompanyName = query.listedCompanyName
    }
    if (query && query.targetCompanyName) {
      this.project.targetCompanyName = query.targetCompanyName
    }

    // 尝试从后端补全项目详情
    if (this.projectId) {
      try {
        const projectData = await getProject(this.projectId)
        if (projectData) {
          this.project = { ...this.project, ...projectData }
        }
      } catch (e) {
        console.error('加载项目详情失败:', e)
      }
    }

    // 加载变量列表
    if (this.projectId) {
      await this.loadVariables()
    }
  },
  methods: {
    async loadVariables() {
      if (!this.projectId) return
      this.loading = true
      try {
        const res = await getProjectVariables(this.projectId)
        // 后端直接返回 List<ProjectVariable>，所以 res 可能是数组
        // 但也可能被包装在 { data: [...] } 中
        let list = []
        if (Array.isArray(res)) {
          list = res
        } else if (res && res.data && Array.isArray(res.data)) {
          list = res.data
        } else if (res && typeof res === 'object') {
          // 尝试从对象中提取数组
          list = Object.values(res).find(v => Array.isArray(v)) || []
        }
        // 增加本地状态字段
        this.variables = list.map(item => ({
          ...item,
          _dirty: false,
          _tempId: `var_${item.id || Date.now()}`
        }))
      } catch (e) {
        console.error('加载变量列表失败:', e)
        uni.showToast({
          title: this.$t('panels.vlLoadFailed'),
          icon: 'none'
        })
      } finally {
        this.loading = false
      }
    },
    addVariable() {
      if (!this.projectId) {
        uni.showToast({
          title: this.$t('panels.vlProjectMissing'),
          icon: 'none'
        })
        return
      }
      const tempId = `temp_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`
      this.variables.unshift({
        id: null,
        projectId: this.projectId,
        name: '',
        value: '',
        type: 'TEXT',
        _dirty: true,
        _tempId: tempId
      })
    },
    markDirty(v) {
      v._dirty = true
    },
    async saveVariable(v) {
      if (!v.name || !v.name.trim()) {
        uni.showToast({
          title: this.$t('panels.vlNameRequired'),
          icon: 'none'
        })
        return
      }
      this.saving = true
      try {
        const payload = {
          id: v.id || undefined,
          projectId: this.projectId,
          name: v.name.trim(),
          value: v.value || '',
          type: v.type || 'TEXT'
        }
        const saved = await saveProjectVariable(payload)
        // 更新本地数据
        // 后端可能直接返回 ProjectVariable 对象，也可能包装在 data 中
        const savedData = saved?.data || saved
        if (savedData) {
          v.id = savedData.id
          v.projectId = savedData.projectId
          v.type = savedData.type
          v.updatedAt = savedData.updatedAt
          v.name = savedData.name || v.name
          v.value = savedData.value || v.value
          v._dirty = false
        }

        uni.showToast({
          title: this.$t('panels.vlSaveSuccess'),
          icon: 'success'
        })
      } catch (e) {
        console.error('保存变量失败:', e)
        uni.showToast({
          title: this.$t('panels.vlSaveFailed', { msg: e.message || this.$t('panels.vlUnknownError') }),
          icon: 'none'
        })
      } finally {
        this.saving = false
      }
    },
    async deleteVariable(v) {
      if (!v.id) {
        // 本地新增但未保存的变量，直接从列表移除
        this.variables = this.variables.filter(item => item !== v)
        return
      }
      const res = await new Promise(resolve => {
        uni.showModal({
          title: this.$t('panels.vlConfirmDeleteTitle'),
          content: this.$t('panels.vlConfirmDeleteBody', { name: v.name }),
          success: resolve
        })
      })
      if (!res.confirm) return

      this.saving = true
      try {
        await deleteProjectVariable(v.id)
        this.variables = this.variables.filter(item => item.id !== v.id)
        uni.showToast({
          title: this.$t('panels.vlDeleted'),
          icon: 'none'
        })
      } catch (e) {
        console.error('删除变量失败:', e)
        uni.showToast({
          title: this.$t('panels.vlDeleteFailed'),
          icon: 'none'
        })
      } finally {
        this.saving = false
      }
    },
    goBack() {
      // 优先返回上一页
      uni.navigateBack({
        fail: () => {
          // 如果无法返回，则跳转到项目概览
          if (this.projectId) {
            uni.redirectTo({
              url: `/pages/project-overview/project-overview?id=${this.projectId}&name=${encodeURIComponent(this.project.name || '')}`
            })
          }
        }
      })
    },
    formatTime(ts) {
      if (!ts) return ''
      try {
        const date = new Date(ts)
        const y = date.getFullYear()
        const m = String(date.getMonth() + 1).padStart(2, '0')
        const d = String(date.getDate()).padStart(2, '0')
        const hh = String(date.getHours()).padStart(2, '0')
        const mm = String(date.getMinutes()).padStart(2, '0')
        return `${y}-${m}-${d} ${hh}:${mm}`
      } catch (e) {
        return ts
      }
    }
  }
}
</script>

<style lang="scss">
.page-variable-library {
  min-height: 100vh;
  padding: 24rpx;
  background-color: $uni-bg-color-grey;
  box-sizing: border-box;
}

.header-card {
  background-color: $uni-bg-color;
  border-radius: 16rpx;
  padding: 16rpx;
  box-shadow: 0 4rpx 12rpx rgba(0, 0, 0, 0.04);
  box-sizing: border-box;
  margin-bottom: 16rpx;
}

.title-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  column-gap: 12rpx;
  margin-bottom: 8rpx;
}

.project-name {
  font-size: 34rpx;
  font-weight: 500;
  color: $uni-color-title;
}

.project-type-tag {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  background-color: rgba($uni-color-primary, 0.08);
  color: $uni-color-primary;
}

.meta-row {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  column-gap: 24rpx;
  row-gap: 4rpx;
}

.meta-item {
  font-size: 24rpx;
  color: $uni-text-color-grey;
}

.toolbar {
  margin-top: 16rpx;
  margin-bottom: 12rpx;
  display: flex;
  flex-direction: row;
  column-gap: 12rpx;
}

.search-bar {
  position: relative;
  margin-bottom: 12rpx;
}

.search-input {
  width: 100%;
  height: 64rpx;
  border-radius: 16rpx;
  border: 1rpx solid rgba($uni-border-color, 0.85);
  padding: 0 70rpx 0 24rpx;
  font-size: 26rpx;
  background-color: $uni-bg-color;
  box-sizing: border-box;
}

.search-clear {
  position: absolute;
  right: 18rpx;
  top: 50%;
  transform: translateY(-50%);
  width: 44rpx;
  height: 44rpx;
  line-height: 44rpx;
  text-align: center;
  border-radius: 999rpx;
  background-color: rgba($uni-color-primary, 0.08);
  color: $uni-color-primary;
  font-size: 28rpx;
}

.btn {
  padding: 0 20rpx;
}

.variable-list {
  margin-top: 8rpx;
  max-height: calc(100vh - 260rpx);
}

.empty {
  padding: 40rpx 20rpx;
  text-align: center;
  color: $uni-text-color-grey;
  font-size: 24rpx;
}

.variable-card {
  background-color: var(--awd-surface);
  border-radius: 12rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: $uni-border-color;
  padding: 16rpx;
  box-sizing: border-box;
  margin-bottom: 12rpx;
}

.card-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  column-gap: 8rpx;
  margin-bottom: 8rpx;
}

.var-name-input {
  flex: 1;
  min-width: 0;
  border-width: 1rpx;
  border-style: solid;
  border-color: $uni-border-color;
  border-radius: 8rpx;
  padding: 8rpx 12rpx;
  font-size: 26rpx;
  box-sizing: border-box;
}

.var-type-tag {
  font-size: 22rpx;
  padding: 2rpx 8rpx;
  border-radius: 999rpx;
  background-color: rgba($uni-color-primary, 0.06);
  color: $uni-color-primary;
}

.var-value-input {
  width: 100%;
  min-height: 120rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: $uni-border-color;
  border-radius: 8rpx;
  padding: 8rpx 12rpx;
  font-size: 24rpx;
  box-sizing: border-box;
  margin-top: 4rpx;
}

.card-footer {
  margin-top: 10rpx;
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
  column-gap: 12rpx;
}

.btn-small {
  min-width: 140rpx;
}

.card-meta {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 22rpx;
  color: $uni-text-color-grey;
}

.variable-group {
  margin-bottom: 24rpx;
}

.group-header {
  padding: 8rpx 0;
  margin-bottom: 8rpx;
}

.group-title {
  font-size: 28rpx;
  font-weight: 500;
  color: var(--awd-text);
  padding-left: 12rpx;
  border-left: 4rpx solid $uni-color-primary;
}
</style>


