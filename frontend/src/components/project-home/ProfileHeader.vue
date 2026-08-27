<template>
  <view class="profile-header">
    <text class="profile-project-name">{{ projectName }}</text>

    <view v-if="showGuide" class="profile-guide">
      <text class="profile-guide-desc">{{ $t('projects.profileEmptyGuideDesc') }}</text>
      <view v-if="canEdit" class="profile-guide-btn" @tap="startEdit('client')">{{ $t('projects.startFilling') }}</view>
    </view>

    <view class="profile-fields">
      <view v-for="f in fields" :key="f.fieldKey" class="profile-field">
        <text class="profile-field-label">{{ f.label }}</text>

        <AwdSelect
          v-if="editingKey === f.fieldKey && f.fieldKey === 'matterType'"
          :range="matterTypes"
          :value="matterTypeIndex"
          @change="onPickMatterType"
          @cancel="cancelEdit"
        >
          <view class="profile-field-picker">{{ draft || $t('projects.selectMatterType') }}</view>
        </AwdSelect>

        <input
          v-else-if="editingKey === f.fieldKey"
          class="profile-field-input"
          :value="draft"
          :placeholder="placeholderOf(f.fieldKey)"
          :focus="true"
          @input="draft = $event.detail.value"
          @confirm="commitEdit"
          @blur="commitEdit"
        />

        <text
          v-else
          class="profile-field-value"
          :class="{ 'profile-field-empty': !f.fieldValue, 'profile-field-weak': isWeak(f) }"
          @tap="startEdit(f.fieldKey)"
        >{{ f.fieldValue || $t('projects.notFilled') }}</text>

        <text v-if="hintOf(f)" class="profile-field-hint">{{ hintOf(f) }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 项目档案头。fields 是 GET /api/projects/{id}/profile 的 data.fields 原样：
// 服务端保证恒 5 条、顺序固定、label 由服务端给，本组件不补齐、不排序、不写第二份文案表。
//
// 编辑走行内 input 不开弹窗：awd-* 弹窗/按钮样式在仓里没有集中定义（project-overview /
// ChatInterface / FileTree 各自带一份 scoped 副本），走行内就不用复制第四份。
//
// A 期不渲染「重新分析」按钮：AI 抽取链路在 Plan 2，先出按钮就是点了没反应的死按钮。
// 空态引导因此写成手填口径而不是「让 AI 读一遍项目里的文件」——
// Plan 2 上线 AI 抽取后，把空态引导改回 AI 文案，并把按钮随抽取链路一起放出来。
import { MATTER_TYPES } from '@/config/matterTypes.js'
import { isProfileEmpty, profileFieldHint } from '@/utils/projectHomeFormat.js'
import { t } from '@/i18n'
import AwdSelect from '@/components/AwdSelect.vue'

// 语言切换 = 整页 reload，模块顶层调 t() 取到的静态文案是安全的（同 CONVENTIONS.md）
const PLACEHOLDERS = {
  client: t('projects.placeholderClient'),
  matterType: t('projects.selectMatterType'),
  openedAt: t('projects.placeholderOpenedAt'),
  nextStep: t('projects.placeholderNextStep'),
  counterparty: t('projects.placeholderCounterparty'),
}

export default {
  name: 'ProfileHeader',
  components: { AwdSelect },
  props: {
    projectId: { type: Number, required: true },
    projectName: { type: String, default: '' },
    fields: { type: Array, default: () => [] },
    canEdit: { type: Boolean, default: false },
  },
  emits: ['save'],
  data() {
    return { editingKey: '', draft: '', matterTypes: MATTER_TYPES }
  },
  computed: {
    showGuide() {
      return isProfileEmpty(this.fields) && !this.editingKey
    },
    // 下拉要高亮当前项；draft 不在候选里（历史自由文本、AI 猜的值）时给 -1，
    // 那就是「一个都不高亮」，比强行落在第 0 项上诚实
    matterTypeIndex() {
      return this.matterTypes.indexOf(this.draft)
    },
  },
  watch: {
    // 换了项目就把半截的编辑态丢掉，避免把 A 项目的输入提交到 B 项目
    projectId() {
      this.cancelEdit()
    },
  },
  methods: {
    isWeak(f) {
      return f.source === 'default' || f.source === 'ai'
    },
    hintOf(f) {
      return profileFieldHint(f)
    },
    placeholderOf(fieldKey) {
      return PLACEHOLDERS[fieldKey] || ''
    },
    startEdit(fieldKey) {
      if (!this.canEdit) return
      const f = this.fields.find((x) => x.fieldKey === fieldKey)
      this.editingKey = fieldKey
      this.draft = (f && f.fieldValue) || ''
    },
    cancelEdit() {
      this.editingKey = ''
      this.draft = ''
    },
    commitEdit() {
      // confirm 与 blur 会先后各来一次，第一次已清空 editingKey，第二次直接返回
      if (!this.editingKey) return
      const fieldKey = this.editingKey
      const value = this.draft
      const before = this.fields.find((x) => x.fieldKey === fieldKey)
      // source==='default' 的值是服务端派生的，不算"原值"：照原样再提交一次
      // 正是把它锁成 source='user' 的正常操作
      const beforeValue = (before && before.source !== 'default' && before.fieldValue) || ''
      this.cancelEdit()
      if (value === beforeValue) return
      this.$emit('save', { fieldKey: fieldKey, value: value })
    },
    // AwdSelect 直接抛下标
    onPickMatterType(idx) {
      this.draft = this.matterTypes[Number(idx)] || ''
      this.commitEdit()
    },
    // commitEdit 是乐观退出：emit('save') 后立刻清空编辑态，界面退回显示旧值。
    // 真实请求可能失败（网络抖动、权限刚好被收回等），届时 fields 不会更新成新值，
    // 而 draft 已经清空——律师刚敲的可能是带日期的完整句子，丢了只能凭记忆重打，
    // 且不会发觉自己丢了什么。父级容器必须在保存失败的 catch 里调用它，否则用户输入会丢。
    restoreEdit(fieldKey, value) {
      this.editingKey = fieldKey
      this.draft = value
    },
  },
}
</script>

<style scoped>
.profile-header {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 18px 20px;
}

.profile-project-name {
  display: block;
  font-size: 20px;
  font-weight: 600;
  color: var(--awd-accent-text);
  line-height: 28px;
}

.profile-guide {
  margin-top: 12px;
  padding: 12px 14px;
  background: var(--awd-bg);
  border-left: 3px solid var(--awd-mint);
  border-radius: 4px;
}

.profile-guide-desc {
  display: block;
  font-size: 12px;
  line-height: 19px;
  color: var(--awd-text-2);
}

.profile-guide-btn {
  display: inline-block;
  margin-top: 10px;
  padding: 5px 14px;
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.profile-guide-btn:hover {
  background: var(--awd-accent-hover);
}

.profile-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  margin-top: 16px;
}

.profile-field {
  flex: 1 1 200px;
  min-width: 180px;
}

.profile-field-label {
  display: block;
  font-size: 11px;
  color: var(--awd-text-3);
  line-height: 16px;
}

.profile-field-value {
  display: block;
  margin-top: 2px;
  font-size: 14px;
  line-height: 22px;
  color: var(--awd-text);
  cursor: pointer;
  word-break: break-word;
}

.profile-field-value.profile-field-empty {
  color: var(--awd-text-3);
}

/* AI 猜的与建档时间派生的都弱化：律师不能把它们当成有人填过的事实 */
.profile-field-value.profile-field-weak {
  color: var(--awd-text-2);
}

.profile-field-input,
.profile-field-picker {
  margin-top: 2px;
  padding: 3px 6px;
  font-size: 14px;
  line-height: 22px;
  color: var(--awd-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-accent);
  border-radius: 3px;
  box-sizing: border-box;
  width: 100%;
}

.profile-field-hint {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--awd-text-3);
}

/* 响应祖先 .project-home-pane 的实际渲染宽度（container-name: home-pane，
   定义在 project-home-pane.scss），不是靠 compact 布尔值。三档见该文件的注释。 */
@container home-pane (max-width: 359px) {
  .profile-header {
    padding: 10px 12px;
  }

  .profile-project-name {
    font-size: 15px;
    line-height: 22px;
  }

  .profile-guide {
    margin-top: 10px;
    padding: 10px 12px;
  }

  .profile-fields {
    gap: 8px 0;
    margin-top: 10px;
  }

  /* 单列：min-width 是横向溢出的直接原因，必须归零 */
  .profile-field {
    flex: 1 1 100%;
    min-width: 0;
  }

  .profile-field-label {
    font-size: 10px;
  }

  .profile-field-value,
  .profile-field-input,
  .profile-field-picker {
    font-size: 12px;
    line-height: 18px;
  }
}

@container home-pane (min-width: 360px) and (max-width: 559px) {
  .profile-header {
    padding: 14px 16px;
  }

  .profile-project-name {
    font-size: 17px;
    line-height: 24px;
  }

  .profile-fields {
    gap: 10px 12px;
    margin-top: 12px;
  }

  /* 两列：每格留一半 gap 的宽度 */
  .profile-field {
    flex: 1 1 calc(50% - 6px);
    min-width: 0;
  }

  .profile-field-value,
  .profile-field-input,
  .profile-field-picker {
    font-size: 13px;
    line-height: 20px;
  }
}
</style>
