<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
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
    
    <view v-if="showDropdown" class="dropdown-menu" :class="{ 'is-inline': inline }">
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
    },
    // dev-board#884：默认 false 保持其它调用点（普通搜索场景）不变——下拉仍是浮层。
    // 「管理标签」弹窗（FileTree.vue）这种场景里，下拉本来就是弹窗正文的一部分，
    // 不该悬浮：创建表单展开多高，弹窗就该跟着长多高，而不是把表单塞进一个
    // 170px 高的浮层窗口、外面还留一截空白（dev-board#884 复测意见）。
    inline: {
      type: Boolean,
      default: false
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
      // 东方清雅体系的分类色板（design/tokens/awd-palette.json v2.0.0）。
      // 前十档与 TagManager 同源：CIELCh 上 L*=45 / C*=26，色相角 24° 起每 36° 一格。
      // 后六档是浅一阶的第二梯队（L*=60 / C*=24，色相角 42° 起每 60° 一格），
      // 靠明度差而不是更密的色相分格拉开距离；末档是暖中性灰。
      // 十七档两两最小色差 CIEDE2000 = 10.33（旧板 4.99）。
      presetColors: [
        '#955B5A', '#8A6246', '#756B3F', '#58724A', '#387661',
        '#17767B', '#27728F', '#536B95', '#78628B', '#8F5B74',
        '#B88475', '#979267', '#679B83', '#529BAC', '#8290BA',
        '#B2839E', '#6B675C'
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
      // dev-board#884：inline 模式下下拉已经参与正常流，弹窗（.awd-dialog）会跟着
      // 表单自然长高，多数窗口高度下根本不需要滚；只有窗口矮到弹窗撞上
      // .awd-dialog 的 85vh 上限时，.awd-dialog-body 才会真的出现滚动条，这时
      // 用户不会自己想到要去滚一个看起来已经"到底"的弹窗——这里当兜底，展开就把
      // 操作按钮那一行滚到看得见的地方。整块 color-picker-mode 可能比可视区域
      // 本身还高，scrollIntoView 打在它身上做不到"整块可见"，只对准操作按钮才有意义。
      // 非 inline 场景（普通浮层下拉）里 .dropdown-menu 自身也可能顶到 280px 上限，
      // 同一份兜底同样适用。
      this.$nextTick(() => {
        const actions = this.$el && this.$el.querySelector('.picker-actions-compact');
        if (actions && typeof actions.scrollIntoView === 'function') {
          actions.scrollIntoView({ block: 'nearest' });
        }
      });
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
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 0 10px;
  background: var(--awd-surface);
  transition: all 0.2s;
}

.input-wrapper:hover {
  border-color: var(--awd-border-strong);
}

.input-wrapper:focus-within {
  border-color: var(--awd-accent);
  box-shadow: 0 0 0 2px rgba(46, 90, 80, 0.1);
}

.tag-input {
  flex: 1;
  height: 36px;
  font-size: 13px;
  border: none;
  background: transparent;
  color: var(--awd-text);
}

.clear-icon {
  color: var(--awd-text-3);
  padding: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.clear-icon:hover {
  background: var(--awd-surface-2);
  color: var(--awd-text-2);
}

.dropdown-menu {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
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

/* dev-board#884：inline 场景（FileTree.vue「管理标签」弹窗）——退掉整套浮层样式，
   改回正常流。宿主弹窗（.awd-dialog）自己已经有 max-height:85vh +
   .awd-dialog-body { overflow-y: auto }，超高时该滚的是宿主弹窗整体，不该由这里
   再单独截出一个 280px/无 max-height 但内部又滚不动的小窗口。 */
.dropdown-menu.is-inline {
  position: static;
  top: auto;
  left: auto;
  right: auto;
  margin-top: 8px;
  padding-top: 8px;
  border: none;
  border-top: 1px solid var(--awd-border-subtle);
  border-radius: 0;
  box-shadow: none;
  z-index: auto;
  overflow: visible;
  max-height: none;
  background: transparent;
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
  color: var(--awd-text-3);
}
/* Custom Scrollbar */
.tag-list::-webkit-scrollbar {
  width: 4px;
}
.tag-list::-webkit-scrollbar-thumb {
  background-color: var(--awd-surface-3);
  border-radius: 2px;
}

.tag-option {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  cursor: pointer;
  font-size: 13px;
  color: var(--awd-text);
  border-bottom: 1px solid var(--awd-border-subtle);
}

.tag-option:last-child {
  border-bottom: none;
}

.tag-option:hover {
  background-color: var(--awd-bg);
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
  color: var(--awd-text-2);
  font-size: 12px;
}

.dropdown-footer {
  border-top: 1px solid var(--awd-border);
  padding: 6px 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  background: var(--awd-surface);
  flex-shrink: 0; /* Ensure footer doesn't shrink */
}

.manage-link {
  color: var(--awd-accent-text);
  font-weight: 500;
  cursor: pointer;
}

.manage-link:hover {
  text-decoration: underline;
}

.close-btn-icon {
    color: var(--awd-text-3);
    cursor: pointer;
    padding: 4px;
    border-radius: 4px;
}

.close-btn-icon:hover {
    background: var(--awd-surface-2);
    color: var(--awd-text);
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
  color: var(--awd-text);
}

.picker-subtitle {
    font-size: 11px;
    color: var(--awd-text-2);
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
    color: var(--awd-text-2);
    background: var(--awd-surface-2);
    border-radius: 4px;
    cursor: pointer;
    transition: all 0.15s ease;
}

.type-segment-option:hover {
    background: var(--awd-surface-3);
}

.type-segment-option.selected {
    background: var(--awd-accent);
    color: var(--awd-text-on-accent);
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
  border-color: var(--awd-accent);
  box-shadow: 0 0 0 2px rgba(46, 90, 80, 0.2);
  transform: scale(1.1);
}

.check-indicator {
  color: var(--awd-text-on-accent);
  display: flex;
  align-items: center;
  justify-content: center;
}

.picker-actions-compact {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  align-items: center;
  border-top: 1px solid var(--awd-border-subtle);
  padding-top: 10px;
}

.cancel-btn-compact {
  font-size: 12px;
  color: var(--awd-text-2);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}

.cancel-btn-compact:hover {
  background: var(--awd-surface-2);
  color: var(--awd-text);
}

.confirm-btn-compact {
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  font-weight: 500;
  transition: background 0.15s ease;
}

.confirm-btn-compact:hover {
  background: var(--awd-accent-hover);
}

.create-option-card {
    margin-top: 8px;
    padding: 8px;
    background: var(--awd-bg);
    border: 1px dashed var(--awd-mint);
    border-radius: 4px;
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    color: var(--awd-text);
    justify-content: center;
}

.create-option-card:hover {
    background: var(--awd-accent-soft);
}

.create-icon {
    font-size: 16px;
    font-weight: bold;
    line-height: 1;
}
</style>
