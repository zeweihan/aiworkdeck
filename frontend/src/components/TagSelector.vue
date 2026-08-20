<template>
  <view class="tag-selector">
    <view class="input-wrapper" @click="showDropdown = true">
      <input 
        v-model="searchText"
        class="tag-input"
        :placeholder="$t('files.addTagPlaceholder')"
        @focus="showDropdown = true"
        confirm-type="done"
      />
      <text v-if="searchText" class="clear-icon" @click.stop="searchText = ''">
        <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" width="14" height="14">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </text>
    </view>
    
    <view v-if="showDropdown" class="dropdown-menu">
      <!-- Color Picker Mode -->
      <view v-if="isCreatingTag" class="color-picker-mode">
        <view class="picker-header">
          <text class="picker-title">{{ $t('files.createNewTag', { name: pendingTagName }) }}</text>
        </view>

        <text class="picker-subtitle">{{ $t('files.tagTypeLabel') }}</text>
        <view class="type-segment">
          <view
            v-for="opt in typeOptions"
            :key="opt.type"
            class="type-segment-option"
            :class="{ selected: selectedType === opt.type }"
            @click="selectType(opt.type)"
          >{{ $t(opt.labelKey) }}</view>
        </view>

        <text class="picker-subtitle">{{ $t('files.pickColor') }}</text>
        <scroll-view scroll-x class="color-scroll" :show-scrollbar="false">
            <view class="color-row">
              <view 
                v-for="color in presetColors" 
                :key="color"
                class="color-option-compact"
                :class="{ selected: selectedColor === color }"
                :style="{ backgroundColor: color }"
                @click="selectColor(color)"
              >
                <view v-if="selectedColor === color" class="check-indicator">
                   <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" width="12" height="12">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7" />
                   </svg>
                </view>
              </view>
            </view>
        </scroll-view>

        <view class="picker-actions-compact">
          <view class="cancel-btn-compact" @click="cancelCreate">{{ $t('common.cancel') }}</view>
          <view class="confirm-btn-compact" @click="confirmCreate">{{ $t('files.create') }}</view>
        </view>
      </view>
      
      <!-- Normal Search/Select Mode -->
      <template v-else>
        <view v-if="filteredTags.length > 0" class="tag-list">
          <template v-for="group in tagGroups" :key="group.type">
            <text v-if="group.tags.length > 0" class="tag-group-head">{{ $t(group.labelKey) }}</text>
            <view
              v-for="tag in group.tags"
              :key="tag.id"
              class="tag-option"
              @click="selectTag(tag)"
            >
              <view :style="{backgroundColor: tag.color}" class="color-dot"></view>
              <text>{{ tag.name }}</text>
            </view>
          </template>
        </view>
        <view v-else-if="searchText" class="no-tags">
          <text>{{ $t('files.noTagsFound') }}</text>
          <view class="create-option-card" @click="startCreate">
             <view class="create-icon">+</view>
             <text>{{ $t('files.createTagQuoted', { name: searchText }) }}</text>
          </view>
        </view>
        <view v-else class="no-tags">
          <text>{{ $t('files.searchOrCreateTag') }}</text>
        </view>
        
        <view class="dropdown-footer">
          <text class="manage-link" @click="openManager">{{ $t('files.manageAllTags') }}</text>
          <view class="close-btn-icon" @click.stop="showDropdown = false">
             <svg fill="none" viewBox="0 0 24 24" stroke="currentColor" width="16" height="16">
                 <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7" />
             </svg>
          </view>
        </view>
      </template>
    </view>
  </view>
</template>

<script>
import {
  TAG_TYPE_NORMAL,
  TAG_TYPE_PARTY,
  TAG_TYPE_ISSUE,
  TAG_TYPE_DEFAULT_COLORS,
  groupTagsByType
} from '@/utils/tagTypes.js'

export default {
  props: {
    availableTags: {
      type: Array,
      default: () => []
    },
    existingTagIds: {
      type: Array,
      default: () => []
    },
    projectId: {
      type: [String, Number],
      required: true
    }
  },
  data() {
    return {
      searchText: '',
      showDropdown: false,
      isCreatingTag: false,
      pendingTagName: '',
      selectedType: TAG_TYPE_NORMAL,
      selectedColor: TAG_TYPE_DEFAULT_COLORS[TAG_TYPE_NORMAL],
      presetColors: [
        '#EF4444', '#F97316', '#F59E0B', '#84CC16', '#10B981',
        '#5BD197', '#14B8A6', '#06B6D4', '#3B82F6', '#6366F1',
        '#8B5CF6', '#A855F7', '#EC4899', '#F43F5E', '#6B7280',
        '#B45309', '#9B1C31'
      ],
      // 新建标签弹层里的类型三段控件，顺序与分组顺序一致
      typeOptions: [
        { type: TAG_TYPE_NORMAL, labelKey: 'files.tagTypeNormal' },
        { type: TAG_TYPE_PARTY, labelKey: 'files.tagTypeParty' },
        { type: TAG_TYPE_ISSUE, labelKey: 'files.tagTypeIssue' }
      ]
    }
  },
  computed: {
    filteredTags() {
      const unassignedTags = this.availableTags.filter(t => !this.existingTagIds.includes(t.id));

      if (!this.searchText) return unassignedTags;

      const lower = this.searchText.toLowerCase();
      return unassignedTags.filter(t => t.name.toLowerCase().includes(lower));
    },
    // 可选标签按「当事人 / 争议焦点 / 标签」三组展示，空组不渲染组头（模板里判空）
    tagGroups() {
      const grouped = groupTagsByType(this.filteredTags);
      return [
        { type: TAG_TYPE_PARTY, labelKey: 'files.tagGroupParty', tags: grouped[TAG_TYPE_PARTY] },
        { type: TAG_TYPE_ISSUE, labelKey: 'files.tagGroupIssue', tags: grouped[TAG_TYPE_ISSUE] },
        { type: TAG_TYPE_NORMAL, labelKey: 'files.tagGroupNormal', tags: grouped[TAG_TYPE_NORMAL] }
      ];
    }
  },
  methods: {
    selectTag(tag) {
      this.$emit('select', tag);
      this.searchText = '';
      this.showDropdown = false;
    },
    startCreate() {
      this.pendingTagName = this.searchText;
      this.isCreatingTag = true;
    },
    selectColor(color) {
      this.selectedColor = color;
    },
    selectType(type) {
      this.selectedType = type;
      // 换型时把颜色带到该型的默认色，选完仍可在下面色板里手动改
      this.selectedColor = TAG_TYPE_DEFAULT_COLORS[type];
    },
    cancelCreate() {
      this.isCreatingTag = false;
      this.pendingTagName = '';
      this.selectedType = TAG_TYPE_NORMAL;
      this.selectedColor = TAG_TYPE_DEFAULT_COLORS[TAG_TYPE_NORMAL];
    },
    confirmCreate() {
      this.$emit('create', { name: this.pendingTagName, color: this.selectedColor, type: this.selectedType });
      this.searchText = '';
      this.showDropdown = false;
      this.isCreatingTag = false;
      this.pendingTagName = '';
      this.selectedType = TAG_TYPE_NORMAL;
      this.selectedColor = TAG_TYPE_DEFAULT_COLORS[TAG_TYPE_NORMAL];
    },
    openManager() {
      this.$emit('manage');
      this.showDropdown = false;
    }
  }
}
</script>

<style scoped>
.tag-selector {
  position: relative;
  width: 100%;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 0 10px;
  background: #fff;
  transition: all 0.2s;
}

.input-wrapper:hover {
  border-color: #d1d5db;
}

.input-wrapper:focus-within {
  border-color: #1A5336;
  box-shadow: 0 0 0 2px rgba(26, 83, 54, 0.1);
}

.tag-input {
  flex: 1;
  height: 36px;
  font-size: 13px;
  border: none;
  background: transparent;
  color: #374151;
}

.clear-icon {
  color: #9ca3af;
  padding: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.clear-icon:hover {
  background: #f3f4f6;
  color: #6b7280;
}

.dropdown-menu {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
  z-index: 9999;
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  /* Prevent it from being too tall and getting cut off */
  max-height: 280px; 
}

.tag-list {
  overflow-y: auto;
  flex: 1;
  /* Enforce max height for the scrolling part to keep footer visible */
  max-height: 180px;
}

.tag-group-head {
  display: block;
  padding: 6px 12px 2px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #9ca3af;
}
/* Custom Scrollbar */
.tag-list::-webkit-scrollbar {
  width: 4px;
}
.tag-list::-webkit-scrollbar-thumb {
  background-color: #e5e7eb;
  border-radius: 2px;
}

.tag-option {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  cursor: pointer;
  font-size: 13px;
  color: #374151;
  border-bottom: 1px solid #f9fafb;
}

.tag-option:last-child {
  border-bottom: none;
}

.tag-option:hover {
  background-color: #f9fafb;
}

.color-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 10px;
  flex-shrink: 0;
}

.no-tags {
  padding: 16px 12px;
  text-align: center;
  color: #6b7280;
  font-size: 12px;
}

.dropdown-footer {
  border-top: 1px solid #e5e7eb;
  padding: 6px 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  background: #f8fafc;
  flex-shrink: 0; /* Ensure footer doesn't shrink */
}

.manage-link {
  color: #1A5336;
  font-weight: 500;
  cursor: pointer;
}

.manage-link:hover {
  text-decoration: underline;
}

.close-btn-icon {
    color: #999;
    cursor: pointer;
    padding: 4px;
    border-radius: 4px;
}

.close-btn-icon:hover {
    background: #f3f4f6;
    color: #333;
}

/* Compact Color Picker Mode Styles */
.color-picker-mode {
  padding: 12px;
}

.picker-header {
  margin-bottom: 8px;
}

.picker-title {
  font-size: 13px;
  font-weight: 600;
  color: #333;
}

.picker-subtitle {
    font-size: 11px;
    color: #666;
    margin-bottom: 6px;
    display: block;
}

.type-segment {
    display: flex;
    gap: 6px;
    margin-bottom: 10px;
}

.type-segment-option {
    flex: 1;
    text-align: center;
    padding: 5px 0;
    font-size: 12px;
    color: #666;
    background: #f3f4f6;
    border-radius: 4px;
    cursor: pointer;
    transition: all 0.15s ease;
}

.type-segment-option:hover {
    background: #e5e7eb;
}

.type-segment-option.selected {
    background: #1A5336;
    color: #fff;
}

.color-scroll {
    width: 100%;
    white-space: nowrap;
    margin-bottom: 12px;
}

.color-row {
    display: flex;
    gap: 8px;
    padding: 4px 2px;
}

.color-option-compact {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid transparent;
  flex-shrink: 0;
  transition: all 0.2s;
}

.color-option-compact:hover {
  transform: translateY(-2px);
}

.color-option-compact.selected {
  border-color: #1A5336;
  box-shadow: 0 0 0 2px rgba(26, 83, 54, 0.2);
  transform: scale(1.1);
}

.check-indicator {
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.picker-actions-compact {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  align-items: center;
  border-top: 1px solid #f0f0f0;
  padding-top: 10px;
}

.cancel-btn-compact {
  font-size: 12px;
  color: #666;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}

.cancel-btn-compact:hover {
  background: #f3f4f6;
  color: #333;
}

.confirm-btn-compact {
  background: #1A5336;
  color: #fff;
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  font-weight: 500;
  transition: background 0.15s ease;
}

.confirm-btn-compact:hover {
  background: #2D7A52;
}

.create-option-card {
    margin-top: 8px;
    padding: 8px;
    background: #f0fdf4;
    border: 1px dashed #5BD197;
    border-radius: 4px;
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    color: #166534;
    justify-content: center;
}

.create-option-card:hover {
    background: #dcfce7;
}

.create-icon {
    font-size: 16px;
    font-weight: bold;
    line-height: 1;
}
</style>
