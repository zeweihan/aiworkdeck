<template>
  <view class="tag-manager">
    <view class="header">
      <text class="title">{{ $t('files.tagManagerTitle') }}</text>
      <view class="close-btn" @click="$emit('close')">
        <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" width="20" height="20">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </view>
    </view>
    
    <view class="content">
      <view class="add-section">
        <input 
          v-model="newTagName" 
          class="new-tag-input" 
          :placeholder="$t('files.tagNamePlaceholder')"
          @confirm="handleAdd"
        />
        <view 
          class="color-picker"
          :style="{ backgroundColor: newTagColor }"
          @click="toggleColorPicker"
        ></view>
        <button class="add-btn" @click="handleAdd" :disabled="!newTagName">{{ $t('files.add') }}</button>
      </view>
      
      <view v-if="showColorPicker" class="color-options">
        <view 
          v-for="color in presetColors" 
          :key="color"
          class="color-option"
          :style="{ backgroundColor: color }"
          @click="selectColor(color)"
        >
          <view v-if="newTagColor === color" class="check-mark">
            <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" width="14" height="14">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7" />
            </svg>
          </view>
        </view>
      </view>
      
      <scroll-view scroll-y class="tag-list-scroll">
        <view class="tag-list">
          <view v-for="tag in tags" :key="tag.id" class="tag-row">
            <view class="tag-info">
              <view class="tag-dot" :style="{ backgroundColor: tag.color }"></view>
              <input 
                v-if="editingId === tag.id"
                v-model="editName"
                class="edit-input"
                focus
                @blur="saveEdit(tag)"
                @confirm="saveEdit(tag)"
              />
              <text v-else class="tag-name-text" @click="startEdit(tag)">{{ tag.name }}</text>
              <text v-if="tag.isSystem" class="system-badge">{{ $t('files.systemBadge') }}</text>
            </view>
            <view class="actions">
              <view class="action-btn edit" @click="startEdit(tag)">
                <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" width="16" height="16">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                </svg>
              </view>
              <view class="action-btn delete" @click="handleDelete(tag)">
                 <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" width="16" height="16">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </view>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script>
import api from '@/services/api.js'

export default {
  props: {
    projectId: {
      type: [String, Number],
      required: true
    }
  },
  data() {
    return {
      tags: [],
      newTagName: '',
      newTagColor: '#3B82F6',
      showColorPicker: false,
      presetColors: [
        '#EF4444', '#F97316', '#F59E0B', '#10B981', '#3B82F6', 
        '#6366F1', '#8B5CF6', '#EC4899', '#6B7280', '#000000'
      ],
      editingId: null,
      editName: ''
    }
  },
  mounted() {
    this.refreshTags();
  },
  methods: {
    async refreshTags() {
      try {
        const res = await api.getProjectTags(this.projectId || 0);
        // api.request logic usually returns data directly or res.data
        // Assuming consistent response format. If api.js wraps response in `data` prop...
        // frontend api calls usually return promise resolving to response body.
        this.tags = res.data || res || [];
      } catch (e) {
        console.error('Failed to load tags', e);
      }
    },
    toggleColorPicker() {
      this.showColorPicker = !this.showColorPicker;
    },
    selectColor(color) {
      this.newTagColor = color;
      this.showColorPicker = false;
    },
    async handleAdd() {
      if (!this.newTagName.trim()) return;
      try {
        await api.createTag(this.projectId, {
          name: this.newTagName.trim(),
          color: this.newTagColor
        });
        this.newTagName = '';
        this.refreshTags();
      } catch (e) {
        uni.showToast({ title: 'Failed to create tag', icon: 'none' });
      }
    },
    startEdit(tag) {
      this.editingId = tag.id;
      this.editName = tag.name;
    },
    async saveEdit(tag) {
      if (!this.editingId) return;
      if (this.editName.trim() && this.editName !== tag.name) {
        try {
          await api.updateTag(this.projectId, tag.id, {
            name: this.editName.trim(),
            color: tag.color // Keep color for now, or allow editing
          });
          await this.refreshTags();
        } catch (e) {
          uni.showToast({ title: 'Update failed', icon: 'none' });
        }
      }
      this.editingId = null;
    },
    async handleDelete(tag) {
      uni.showModal({
        title: this.$t('files.deleteTagTitle'),
        content: this.$t('files.deleteTagConfirm', { name: tag.name }),
        confirmText: this.$t('common.delete'),
        cancelText: this.$t('common.cancel'),
        confirmColor: '#E74C3C',
        success: async (res) => {
          if (res.confirm) {
            try {
              await api.deleteTag(this.projectId, tag.id);
              this.refreshTags();
            } catch (e) {
              uni.showToast({ title: this.$t('files.deleteFailed'), icon: 'none' });
            }
          }
        }
      });
    }
  }
}
</script>

<style scoped>
.tag-manager {
  background: #fff;
  width: 400px;
  max-width: 90vw;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  display: flex;
  flex-direction: column;
  max-height: 80vh;
}

.header {
  padding: 16px;
  border-bottom: 1px solid #eee;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title {
  font-weight: 600;
  font-size: 16px;
}

.close-btn {
  font-size: 20px;
  cursor: pointer;
  padding: 0 8px;
  color: #999;
}

.content {
  padding: 16px;
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}

.add-section {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}

.new-tag-input {
  flex: 1;
  height: 36px;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 14px;
}

.color-picker {
  width: 36px;
  height: 36px;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid #ddd;
}

.add-btn {
  height: 36px;
  line-height: 36px;
  background: #1A5336;
  color: #fff;
  font-size: 14px;
  border: none;
  border-radius: 4px;
  padding: 0 16px;
}

.add-btn:disabled {
  background: #ccc;
}

.color-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px;
  background: #f9fafb;
  border-radius: 4px;
  margin-bottom: 12px;
}

.color-option {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.check-mark {
  color: #fff;
  font-size: 12px;
}

.tag-list-scroll {
  flex: 1;
  overflow-y: auto;
  border: 1px solid #eee;
  border-radius: 4px;
}

.tag-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid #f5f5f5;
}

.tag-row:last-child {
  border-bottom: none;
}

.tag-info {
  display: flex;
  align-items: center;
  flex: 1;
}

.tag-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 10px;
}

.tag-name-text {
  font-size: 14px;
  color: #333;
  cursor: pointer;
}

.edit-input {
  font-size: 14px;
  border: 1px solid #3b82f6;
  border-radius: 2px;
  padding: 2px 4px;
}

.system-badge {
  font-size: 10px;
  background: #f3f4f6;
  color: #666;
  padding: 1px 4px;
  border-radius: 4px;
  margin-left: 8px;
}

.actions {
  display: flex;
  gap: 8px;
}

.action-btn {
  cursor: pointer;
  padding: 4px;
  font-size: 14px;
  opacity: 0.6;
}

.action-btn:hover {
  opacity: 1;
}

.action-btn.delete:hover {
  color: #ef4444;
}
</style>
