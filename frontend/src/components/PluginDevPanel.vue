<template>
  <view class="pdp">
    <!-- 面板标题由外壳的 sidebar-header 出，这里只画分组头（照 sidebar-shell.md 的统一口径） -->
    <view class="pdp-sec-head">
      <text class="pdp-sec-title">{{ $t('panels.pdSectionTitle') }}</text>
      <text class="pdp-sec-count">{{ items.length }}</text>
      <view class="pdp-sec-spacer"></view>
      <view class="pdp-sec-action" :title="$t('panels.pdNewButton')" @tap="openNewForm">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path v-for="(d, gi) in ICONS.plus" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </view>
    </view>

    <view v-if="showNewForm" class="pdp-new-form">
      <input
        class="pdp-input"
        v-model="newId"
        :placeholder="$t('panels.pdIdPlaceholder')"
        @confirm="submitNew"
      />
      <input
        class="pdp-input"
        v-model="newName"
        :placeholder="$t('panels.pdNamePlaceholder')"
        @confirm="submitNew"
      />
      <text v-if="newIdError" class="pdp-form-error">{{ newIdError }}</text>
      <view class="pdp-form-actions">
        <view class="pdp-btn pdp-btn-primary" :class="{ busy: creating }" @tap="submitNew">
          <text>{{ creating ? $t('panels.pdCreating') : $t('panels.pdCreate') }}</text>
        </view>
        <view class="pdp-btn" @tap="cancelNewForm">
          <text>{{ $t('common.cancel') }}</text>
        </view>
      </view>
    </view>

    <scroll-view scroll-y class="pdp-body">
      <view v-if="!loading && !items.length" class="pdp-empty">
        <text>{{ $t('panels.pdEmptyText') }}</text>
      </view>

      <view v-for="item in items" :key="item.id" class="pdp-row">
        <view class="pdp-row-top">
          <view class="pdp-row-glyph">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in ICONS.blocks" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>
          <view class="pdp-row-main">
            <text class="pdp-row-name">{{ item.name || item.id }}</text>
            <text class="pdp-row-meta">{{ rowMeta(item) }}</text>
            <text v-if="versionMismatch(item)" class="pdp-row-hint">
              {{ $t('panels.pdVersionMismatch', { installed: item.installedVersion, source: item.version }) }}
            </text>
          </view>
        </view>

        <view class="pdp-row-actions">
          <view
            v-if="item.installed && item.enabled"
            class="pdp-action"
            @tap.stop="openPlugin(item)"
          >
            <text>{{ $t('panels.pdOpen') }}</text>
          </view>
          <view
            class="pdp-action pdp-action-primary"
            :class="{ busy: busyId === item.id && busyAction === 'install' }"
            @tap.stop="install(item)"
          >
            <text>{{ busyId === item.id && busyAction === 'install' ? $t('panels.pdInstalling') : (item.installed ? $t('panels.pdReinstall') : $t('panels.pdInstall')) }}</text>
          </view>
          <view
            v-if="item.installed"
            class="pdp-action pdp-action-danger"
            :class="{ busy: busyId === item.id && busyAction === 'uninstall' }"
            @tap.stop="uninstall(item)"
          >
            <text>{{ busyId === item.id && busyAction === 'uninstall' ? $t('panels.pdUninstalling') : $t('panels.pdUninstall') }}</text>
          </view>
          <view class="pdp-action pdp-action-ai" @tap.stop="aiDevelop(item)">
            <text>{{ $t('panels.pdAiDevelop') }}</text>
          </view>
        </view>

        <view v-if="errors[item.id]" class="pdp-row-error">
          <text class="pdp-row-error-toggle" @tap.stop="toggleError(item.id)">
            {{ expandedErrors[item.id] ? $t('panels.pdErrorDetailHide') : $t('panels.pdErrorDetailShow') }}
          </text>
          <text v-if="expandedErrors[item.id]" class="pdp-row-error-text">{{ errors[item.id] }}</text>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script>
// 插件开发左栏面板：列出项目「插件开发/<id>/」目录下的插件项目，提供
// 新建/装到本机/卸载/让 AI 开发/打开运行五个动作。数据与安装链路见
// services/api.js 的 pluginDev* 四个封装，契约由后端另一条线实现（不改）。
import { pluginDevScaffold, pluginDevStatus, pluginDevInstall, pluginDevUninstall } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'

const ID_PATTERN = /^[a-z0-9][a-z0-9-]{1,49}$/

export default {
  name: 'PluginDevPanel',
  props: {
    projectId: {
      type: [Number, String],
      required: true
    }
  },
  emits: ['refresh-plugins', 'refresh-files', 'open-plugin', 'ai-develop'],
  data() {
    return {
      items: [],
      loading: false,
      showNewForm: false,
      newId: '',
      newName: '',
      newIdError: '',
      creating: false,
      busyId: '',
      busyAction: '', // 'install' | 'uninstall'
      errors: {},           // id -> 安装失败时后端给的校验错误明细
      expandedErrors: {},   // id -> 错误详情是否展开
    }
  },
  computed: {
    ICONS() {
      return ICONS
    },
  },
  mounted() {
    this.loadStatus()
  },
  methods: {
    async loadStatus() {
      this.loading = true
      try {
        const res = await pluginDevStatus(this.projectId)
        this.items = (res && res.items) || []
      } catch (e) {
        console.error('加载插件开发列表失败:', e)
        uni.showToast({ title: e?.message || this.$t('panels.pdLoadFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    rowMeta(item) {
      const parts = []
      if (item.version) parts.push('v' + item.version)
      if (item.installed) {
        parts.push(item.enabled ? this.$t('panels.pdEnabledTag') : this.$t('panels.pdDisabledTag'))
      } else {
        parts.push(this.$t('panels.pdNotInstalledTag'))
      }
      return parts.join(' · ')
    },
    versionMismatch(item) {
      return item.installed && item.installedVersion && item.version && item.installedVersion !== item.version
    },
    openNewForm() {
      this.showNewForm = true
      this.newId = ''
      this.newName = ''
      this.newIdError = ''
    },
    cancelNewForm() {
      this.showNewForm = false
    },
    async submitNew() {
      if (this.creating) return
      const id = this.newId.trim()
      if (!ID_PATTERN.test(id)) {
        this.newIdError = this.$t('panels.pdIdInvalid')
        return
      }
      this.newIdError = ''
      this.creating = true
      try {
        await pluginDevScaffold(this.projectId, id, this.newName.trim() || id)
        uni.showToast({ title: this.$t('panels.pdCreateSuccess'), icon: 'none' })
        this.showNewForm = false
        await this.loadStatus()
        this.$emit('refresh-files')
      } catch (e) {
        console.error('创建插件项目失败:', e)
        this.newIdError = e?.message || this.$t('panels.pdCreateFailed')
      } finally {
        this.creating = false
      }
    },
    async install(item) {
      if (this.busyId) return
      this.busyId = item.id
      this.busyAction = 'install'
      try {
        await pluginDevInstall(this.projectId, item.folderId)
        this.errors[item.id] = ''
        uni.showToast({ title: this.$t('panels.pdInstallSuccess'), icon: 'none' })
        await this.loadStatus()
        this.$emit('refresh-plugins')
      } catch (e) {
        console.error('安装插件失败:', e)
        // 校验错误明细：不用 toast（可能是多行), 就地展开成可折叠的错误区
        this.errors[item.id] = e?.message || this.$t('panels.pdInstallFailed')
        this.expandedErrors[item.id] = true
      } finally {
        this.busyId = ''
        this.busyAction = ''
      }
    },
    async uninstall(item) {
      if (this.busyId) return
      const ok = await new Promise(resolve => {
        uni.showModal({
          title: this.$t('panels.pdUninstall'),
          content: this.$t('panels.pdUninstallConfirm', { name: item.name || item.id }),
          confirmText: this.$t('panels.pdUninstall'),
          cancelText: this.$t('common.cancel'),
          success: r => resolve(r.confirm),
          fail: () => resolve(false),
        })
      })
      if (!ok) return
      this.busyId = item.id
      this.busyAction = 'uninstall'
      try {
        await pluginDevUninstall(item.id)
        uni.showToast({ title: this.$t('panels.pdUninstallSuccess'), icon: 'none' })
        await this.loadStatus()
        this.$emit('refresh-plugins')
      } catch (e) {
        console.error('卸载插件失败:', e)
        uni.showToast({ title: e?.message || this.$t('panels.pdUninstallFailed'), icon: 'none' })
      } finally {
        this.busyId = ''
        this.busyAction = ''
      }
    },
    openPlugin(item) {
      this.$emit('open-plugin', 'plugin-' + item.id)
    },
    aiDevelop(item) {
      this.$emit('ai-develop', { id: item.id, name: item.name, folderId: item.folderId })
    },
    toggleError(id) {
      this.expandedErrors[id] = !this.expandedErrors[id]
    },
  },
}
</script>

<style lang="scss" scoped>
/* 密度令牌见 App.vue 的 --awd-panel-*（基准 = 插件广场，见 MarketSidebarPanel.vue） */
.pdp {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: transparent;
}

.pdp-sec-head {
  display: flex;
  align-items: center;
  gap: 4px;
  height: var(--awd-panel-sec-h);
  padding: 0 var(--awd-panel-pad-x) 0 12px;
  flex-shrink: 0;
}

.pdp-sec-title {
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}

.pdp-sec-count {
  font-size: 10px;
  color: var(--awd-panel-text-3);
  background: var(--awd-panel-hover);
  border-radius: 999px;
  padding: 0 6px;
  line-height: 14px;
  margin-left: 2px;
}

.pdp-sec-spacer {
  flex: 1;
}

.pdp-sec-action {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  color: var(--awd-panel-text-3);
  cursor: pointer;

  svg {
    width: 13px;
    height: 13px;
  }

  &:hover {
    background: var(--awd-panel-accent-wash);
    color: var(--awd-panel-accent);
  }
}

.pdp-new-form {
  flex-shrink: 0;
  padding: var(--awd-panel-gap) var(--awd-panel-pad-x);
  border-bottom: 1px solid var(--awd-panel-border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.pdp-input {
  height: var(--awd-panel-row-h);
  box-sizing: border-box;
  padding: 0 8px;
  font-size: var(--awd-panel-fs);
  color: var(--awd-panel-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-panel-border);
  border-radius: var(--awd-panel-radius);
  outline: none;

  &::placeholder {
    color: var(--awd-panel-text-4);
  }

  &:focus {
    border-color: var(--awd-panel-accent-2);
    box-shadow: 0 0 0 2px rgba(91, 209, 151, 0.15);
  }
}

.pdp-form-error {
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-danger-text);
}

.pdp-form-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

.pdp-btn {
  height: 24px;
  line-height: 22px;
  padding: 0 10px;
  border-radius: 4px;
  border: 1px solid var(--awd-panel-border);
  background: var(--awd-surface);
  cursor: pointer;

  text {
    font-size: 11px;
    font-weight: 600;
    color: var(--awd-panel-text-2);
  }

  &:hover {
    background: var(--awd-panel-hover);
  }

  &.pdp-btn-primary {
    background: var(--awd-panel-accent);
    border-color: var(--awd-panel-accent);

    text {
      color: var(--awd-text-on-accent);
    }

    &:hover {
      background: var(--awd-accent-hover);
    }

    &.busy {
      opacity: 0.6;
      pointer-events: none;
    }
  }
}

.pdp-body {
  flex: 1;
  min-height: 0;
}

.pdp-empty {
  padding: 8px 12px 10px;
  font-size: 11px;
  color: var(--awd-panel-text-4);
}

.pdp-row {
  padding: 8px var(--awd-panel-pad-x) 10px 12px;
  border-bottom: 1px solid var(--awd-panel-border);

  &:hover {
    background: var(--awd-panel-accent-wash);
  }
}

.pdp-row-top {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.pdp-row-glyph {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  margin-top: 1px;
  border-radius: 6px;
  background: var(--awd-accent-hover);
  color: var(--awd-text-on-accent);
  display: flex;
  align-items: center;
  justify-content: center;

  svg {
    width: 13px;
    height: 13px;
  }
}

.pdp-row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.pdp-row-name {
  font-size: var(--awd-panel-fs);
  font-weight: 600;
  color: var(--awd-panel-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pdp-row-meta {
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text-3);
}

.pdp-row-hint {
  font-size: 10px;
  color: var(--awd-warning-text);
}

.pdp-row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
  padding-left: 32px;
}

.pdp-action {
  height: 20px;
  line-height: 18px;
  padding: 0 8px;
  border-radius: 4px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-panel-border);
  cursor: pointer;

  text {
    font-size: 10px;
    font-weight: 600;
    color: var(--awd-panel-text-2);
  }

  &:hover {
    background: var(--awd-panel-hover);
  }

  &.busy {
    opacity: 0.6;
    pointer-events: none;
  }

  &.pdp-action-primary {
    background: var(--awd-panel-accent);
    border-color: var(--awd-panel-accent);

    text {
      color: var(--awd-text-on-accent);
    }

    &:hover {
      background: var(--awd-accent-hover);
    }
  }

  &.pdp-action-danger {
    border-color: var(--awd-danger);

    text {
      color: var(--awd-danger-text);
    }

    &:hover {
      background: var(--awd-bg);
    }
  }

  &.pdp-action-ai {
    border-color: var(--awd-panel-accent-2);

    text {
      color: var(--awd-panel-accent);
    }

    &:hover {
      background: var(--awd-panel-accent-wash);
    }
  }
}

.pdp-row-error {
  margin-top: 6px;
  padding-left: 32px;
}

.pdp-row-error-toggle {
  font-size: 10px;
  color: var(--awd-danger-text);
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.pdp-row-error-text {
  display: block;
  margin-top: 4px;
  padding: 6px 8px;
  font-size: 10px;
  line-height: 1.5;
  color: var(--awd-danger-text);
  background: var(--awd-bg);
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
