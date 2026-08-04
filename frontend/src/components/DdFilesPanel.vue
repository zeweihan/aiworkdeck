<template>
  <!-- Vue 3 多根节点：直接暴露内部元素，无需包裹层 -->
  
  <!-- Header -->
  <view class="dd-panel-header">
    <text class="dd-panel-title">尽调清单</text>
    <view class="dd-add-btn" v-if="canCreateRequest" @tap="createRequest" title="新建清单">
      <text class="dd-add-icon">＋</text>
    </view>
  </view>

  <!-- List -->
  <view class="dd-request-list">
    <view
      v-for="req in requests"
      :key="req.id"
      class="dd-request-item"
      :class="{ active: activeRequestId === req.id }"
      @tap="openRequest(req)"
      @mouseenter="hoveredId = req.id"
      @mouseleave="hoveredId = null"
    >
      <view class="dd-req-icon">
        <svg class="dd-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path v-for="(d, gi) in ICONS.listChecks" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </view>
      <view class="dd-req-info">
        <!-- Edit Mode -->
        <input
          v-if="editingId === req.id"
          class="dd-rename-input"
          v-model="editName"
          @blur="saveRename(req)"
          @confirm="saveRename(req)"
          @tap.stop
          :focus="true"
        />
        <!-- View Mode -->
        <template v-else>
           <text class="dd-req-name">{{ req.name }}</text>
           <text class="dd-req-status" :class="req.status.toLowerCase()">{{ getStatusText(req.status) }}</text>
        </template>
      </view>

      <!-- Action Icons (Hover) -->
      <view class="dd-item-actions" v-if="hoveredId === req.id && !editingId">
        <view
            class="dd-action-btn"
            @tap.stop="copyRequest(req)"
            title="复制"
        >
            <svg class="dd-action-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in ICONS.copyDoc" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
        </view>
        <view
            class="dd-action-btn"
            @tap.stop="startRename(req)"
            title="重命名"
        >
            <svg class="dd-action-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in ICONS.pencil" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
        </view>
        <view
            class="dd-action-btn"
            @tap.stop="confirmDelete(req)"
            title="删除"
        >
            <svg class="dd-action-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
        </view>
      </view>
    </view>

    <view v-if="requests.length === 0" class="dd-empty-state">
      <text>暂无尽调清单</text>
    </view>
  </view>

  <!-- Custom AI Workdeck Delete Confirm Dialog -->
  <view class="dd-dialog-mask" v-if="showDeleteDialog" @tap="showDeleteDialog = false">
    <view class="dd-dialog-content" @tap.stop>
      <view class="dd-dialog-header">
        <text class="dd-dialog-title">提示</text>
      </view>
      <view class="dd-dialog-body">
        <text class="dd-dialog-msg">删除将可能删除清单下所有文件，请再次确认，点击确认后才删除。</text>
      </view>
      <view class="dd-dialog-footer">
        <view class="dd-dialog-btn cancel" @tap="showDeleteDialog = false">取消</view>
        <view class="dd-dialog-btn confirm" @tap="handleDelete">确认</view>
      </view>
    </view>
  </view>
</template>

<script>
import api from '@/services/api'
import { ICONS } from '@/config/icons.js'

export default {
  name: 'DdFilesPanel',
  props: {
    projectId: {
      type: Number,
      required: true
    },
    currentUser: {
      type: Object,
      default: null
    }
  },
  emits: ['open-request'],
  data() {
    return {
      requests: [],
      hoveredId: null,
      editingId: null,
      editName: '',
      activeRequestId: null,
      showDeleteDialog: false,
      deletingRequest: null
    }
  },
  computed: {
    ICONS() { return ICONS },
    canCreateRequest() {
      if (!this.currentUser) return false
      return this.currentUser.role !== 'CLIENT'
    }
  },
  mounted() {
    this.fetchRequests()
  },
  methods: {
    async fetchRequests() {
      try {
        const res = await api.getDdRequests(this.projectId)
        this.requests = res
      } catch (e) {
        console.error('Failed to fetch DD requests', e)
      }
    },
    async createRequest() {
      try {
        await api.createDdRequest(this.projectId, {
          name: 'newddlist',
          content: ''
        })
        await this.fetchRequests()
      } catch (e) {
        console.error('Failed to create DD request', e)
        uni.showToast({ title: '创建失败', icon: 'none' })
      }
    },
    async openRequest(req) {
      if (this.editingId) return // Don't open if editing
      this.activeRequestId = req.id
      this.$emit('open-request', req)
    },
    async copyRequest(req) {
      try {
        await api.copyDdRequest(req.id)
        uni.showToast({ title: '已复制', icon: 'none' })
        await this.fetchRequests()
      } catch (e) {
        console.error('Failed to copy request', e)
        uni.showToast({ title: '复制失败', icon: 'none' })
      }
    },
    confirmDelete(req) {
      this.deletingRequest = req
      this.showDeleteDialog = true
    },
    async handleDelete() {
      if (!this.deletingRequest) return
      try {
        await api.deleteDdRequest(this.deletingRequest.id)
        uni.showToast({ title: '已删除', icon: 'none' })
        this.showDeleteDialog = false
        this.deletingRequest = null
        await this.fetchRequests()
      } catch (e) {
        console.error('Failed to delete request', e)
        uni.showToast({ title: '删除失败', icon: 'none' })
      }
    },
    startRename(req) {
        this.editingId = req.id
        this.editName = req.name
    },
    async saveRename(req) {
        if (!this.editingId) return
        if (this.editName && this.editName !== req.name) {
            try {
                await api.updateDdRequest(req.id, this.editName)
                req.name = this.editName
                uni.showToast({ title: '已更名', icon: 'none' })
            } catch (e) {
                console.error(e)
                uni.showToast({ title: '更名失败', icon: 'none' })
            }
        }
        this.editingId = null
    },
    getStatusText(status) {
      const map = {
        'DRAFT': '草稿',
        'PUBLISHED': '进行中',
        'COMPLETED': '已完成'
      }
      return map[status] || status
    }
  }
}
</script>

<style lang="scss" scoped>
/* Vue 3 多根节点 - 无需包裹层 */

/* Header - 直接作为 sidebar-left 的子元素 */
.dd-panel-header {
  height: 36px;
  padding: 0 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid $awd-chrome-line;
  background-color: transparent;
  box-sizing: border-box;
  flex-shrink: 0;

  .dd-panel-title {
    font-size: 11px;
    font-weight: 600;
    color: $awd-text-on-dark-3;
    transform: scale(0.95);
    transform-origin: left center;
  }

  .dd-add-btn {
    width: 22px;
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: $awd-text-on-dark-2;
    border-radius: 4px;
    border: 1px solid transparent;
    transition: all 0.2s;

    &:hover {
      background-color: $awd-chrome-hover;
      border-color: transparent;
      color: $awd-text-on-dark;
    }

    .dd-add-icon {
        font-size: 14px;
        line-height: 1;
    }
  }
}

/* List - 直接作为 sidebar-left 的子元素 */
.dd-request-list {
  flex: 1;
  padding: 8px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  box-sizing: border-box;
  background-color: transparent;
  overflow-y: auto;
  min-height: 0;
}

.dd-request-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  background-color: $awd-chrome-hover;
  border-radius: 6px;
  margin: 0 4px 8px 4px;
  cursor: pointer;
  border: 1px solid transparent;
  box-sizing: border-box;
  position: relative;
  width: auto;
  flex-shrink: 0;

  &:hover {
    background-color: $awd-chrome-active;
  }

  &.active {
    border-color: $awd-mint;
    background-color: rgba($awd-mint, 0.12);
  }

  &.active:hover {
    background-color: rgba($awd-mint, 0.12);
  }

  .dd-req-icon {
    width: 18px;
    height: 18px;
    margin-right: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $awd-text-on-dark-3;

    .dd-icon-svg {
      width: 100%;
      height: 100%;
    }
  }

  &:hover .dd-req-icon,
  &.active .dd-req-icon {
    color: $awd-mint;
  }

  .dd-req-info {
    display: flex;
    flex-direction: column;
    flex: 1;
    overflow: hidden;
    min-width: 0;

    .dd-req-name {
      font-size: 13px;
      color: $awd-text-on-dark;
      margin-bottom: 2px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      font-weight: 500;
    }

    .dd-req-status {
      font-size: 11px;
      color: $awd-text-on-dark-3;

      &.published { color: $awd-mint; }
      &.completed { color: $awd-text-on-dark-2; }
    }

    .dd-rename-input {
        font-size: 13px;
        border: 1px solid $awd-mint;
        background: $awd-chrome-hover;
        color: $awd-text-on-dark;
        border-radius: 4px;
        padding: 2px 4px;
        width: 100%;
    }
  }

  .dd-item-actions {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-left: auto;
    padding-left: 8px;
    flex-shrink: 0;
    z-index: 2;

    .dd-action-btn {
      width: 20px;
      height: 20px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      position: relative;
      color: $awd-text-on-dark-3;
      transition: color 0.2s;

      .dd-action-svg {
        width: 14px;
        height: 14px;
      }

      &:hover {
        color: $awd-mint;
      }
    }
  }
}

.dd-dialog-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(2px);
}

.dd-dialog-content {
  width: 320px;
  height: 198px;
  background-color: #ffffff;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  overflow: hidden;
  border: 1px solid #e0e0e0;
}

.dd-dialog-header {
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
  
  .dd-dialog-title {
    font-size: 15px;
    font-weight: 600;
    color: #333;
  }
}

.dd-dialog-body {
  flex: 1;
  padding: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  
  .dd-dialog-msg {
    font-size: 13px;
    color: #666;
    line-height: 1.6;
    text-align: center;
  }
}

.dd-dialog-footer {
  display: flex;
  padding: 12px 20px 20px;
  gap: 12px;
  justify-content: flex-end;

  .dd-dialog-btn {
    padding: 6px 20px;
    border-radius: 6px;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.2s;
    
    &.cancel {
      background-color: #f5f5f5;
      color: #666;
      &:hover {
        background-color: #eeeeee;
      }
    }
    
    &.confirm {
      background-color: #4a90e2;
      color: #ffffff;
      &:hover {
        background-color: #357abd;
      }
    }
  }
}

.dd-empty-state {
  padding: 20px;
  text-align: center;
  color: $awd-text-on-dark-3;
  font-size: 12px;
}
</style>
