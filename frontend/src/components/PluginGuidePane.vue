<template>
  <!-- 纯工具 / skill 型插件（manifest 无 frontendEntry）的宿主渲染启动面板。
       它就是这类插件的「独立页面」：介绍能做什么、怎么用、一键把任务发进 AI 对话。
       Web 插件（有 frontendEntry）走 PluginPane 的 iframe，不到这里。
       面板标题由外壳的 .sidebar-header 统一出，这里不自画一份。 -->
  <view class="pg-pane">
    <!-- 简介 -->
    <view class="pg-intro">
      <text class="pg-intro-text">{{ introText }}</text>
    </view>

    <!-- 一键动作：manifest.guide.quickActions；点击把 prompt 发进 AI 对话 -->
    <template v-if="quickActions.length">
      <view class="pg-sec-head">
        <text class="pg-sec-title">{{ $t('panels.pgQuickActionsTitle') }}</text>
      </view>
      <view class="pg-actions">
        <view
          v-for="(a, i) in quickActions"
          :key="i"
          class="pg-action"
          @tap="run(a)"
        >
          <view class="pg-action-main">
            <text class="pg-action-label">{{ a.label }}</text>
            <text v-if="a.hint" class="pg-action-hint">{{ a.hint }}</text>
          </view>
          <text class="pg-action-caret">›</text>
        </view>
      </view>
      <text class="pg-actions-tip">{{ $t('panels.pgQuickActionsTip') }}</text>
    </template>

    <!-- 怎么用：manifest.guide.steps -->
    <template v-if="steps.length">
      <view class="pg-sec-head">
        <text class="pg-sec-title">{{ $t('panels.pgStepsTitle') }}</text>
      </view>
      <view class="pg-steps">
        <view v-for="(s, i) in steps" :key="i" class="pg-step">
          <text class="pg-step-num">{{ i + 1 }}</text>
          <text class="pg-step-text">{{ s }}</text>
        </view>
      </view>
    </template>

    <!-- 没有任何 quickActions / steps 的兜底：告诉用户在对话里直接说需求即可 -->
    <view v-if="!quickActions.length && !steps.length" class="pg-fallback">
      <text class="pg-fallback-text">{{ $t('panels.pgChatHint') }}</text>
    </view>

    <!-- 该插件为 AI 提供的能力（工具清单）：只读展示，帮用户理解它会做什么 -->
    <template v-if="tools.length">
      <view class="pg-sec-head">
        <text class="pg-sec-title">{{ $t('panels.pgToolsTitle') }}</text>
        <text class="pg-sec-count">{{ tools.length }}</text>
      </view>
      <view class="pg-tools">
        <view v-for="t in tools" :key="t.name" class="pg-tool">
          <text class="pg-tool-name">{{ t.name }}</text>
          <text v-if="t.description" class="pg-tool-desc">{{ t.description }}</text>
        </view>
      </view>
    </template>
  </view>
</template>

<script>
// 启动面板：契约见 .claude/agents/plugin-system.md「启动面板」节。
// plugin = dynamicPlugins 里的一项：{ label, description, guide{intro,steps,quickActions}, tools[] }。
// quickActions 的 label/prompt 由后端 PluginService.parseManifest 预先过滤（缺 label/prompt 的已丢弃）。
export default {
  name: 'PluginGuidePane',
  emits: ['kickoff'],
  props: {
    plugin: { type: Object, required: true },
  },
  computed: {
    guide() {
      return (this.plugin && this.plugin.guide) || null
    },
    introText() {
      const g = this.guide
      if (g && g.intro) return g.intro
      if (this.plugin && this.plugin.description) return this.plugin.description
      return this.$t('panels.pgDefaultIntro', { name: (this.plugin && this.plugin.label) || '' })
    },
    steps() {
      const g = this.guide
      return g && Array.isArray(g.steps) ? g.steps.filter(Boolean) : []
    },
    quickActions() {
      const g = this.guide
      if (!g || !Array.isArray(g.quickActions)) return []
      // 后端已过滤，这里再兜一层：label 与 prompt 都在才画按钮
      return g.quickActions.filter(a => a && a.label && a.prompt)
    },
    tools() {
      return (this.plugin && Array.isArray(this.plugin.tools)) ? this.plugin.tools : []
    },
  },
  methods: {
    run(action) {
      if (!action || !action.prompt) return
      this.$emit('kickoff', { prompt: action.prompt })
    },
  },
}
</script>

<style scoped>
/* 密度令牌见 App.vue 的 --awd-panel-*（基准 = 插件广场）。对齐 DesensitizePane/LitigationVisualPanel。 */
.pg-pane {
  height: 100%;
  background: #fff;
  box-sizing: border-box;
  overflow-y: auto;
}

.pg-intro {
  padding: var(--awd-panel-gap) var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
}
.pg-intro-text {
  font-size: var(--awd-panel-fs);
  line-height: 1.6;
  color: var(--awd-panel-text-2);
}

.pg-sec-head {
  display: flex;
  align-items: center;
  gap: 6px;
  height: var(--awd-panel-sec-h);
  padding: 0 var(--awd-panel-pad-x);
}
.pg-sec-title {
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}
.pg-sec-count {
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text-4);
}

/* 一键动作 */
.pg-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 var(--awd-panel-pad-x);
}
.pg-action {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--awd-panel-border);
  border-radius: var(--awd-panel-radius);
  cursor: pointer;
  transition: background 0.12s, border-color 0.12s;
}
.pg-action:hover {
  background: var(--awd-panel-accent-wash);
  border-color: var(--awd-panel-accent);
}
.pg-action-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pg-action-label {
  font-size: var(--awd-panel-fs);
  font-weight: 600;
  color: var(--awd-panel-text);
}
.pg-action-hint {
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text-3);
  line-height: 1.4;
}
.pg-action-caret {
  color: var(--awd-panel-accent);
  font-size: 16px;
  flex-shrink: 0;
}
.pg-actions-tip {
  display: block;
  padding: 6px var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text-4);
  line-height: 1.5;
}

/* 怎么用 */
.pg-steps {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
}
.pg-step {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.pg-step-num {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 50%;
  background: var(--awd-panel-accent);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  margin-top: 1px;
}
.pg-step-text {
  font-size: var(--awd-panel-fs);
  line-height: 1.5;
  color: var(--awd-panel-text-2);
}

/* 兜底提示 */
.pg-fallback {
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
}
.pg-fallback-text {
  font-size: var(--awd-panel-fs);
  line-height: 1.6;
  color: var(--awd-panel-text-3);
}

/* 工具清单 */
.pg-tools {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
}
.pg-tool {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pg-tool-name {
  font-size: var(--awd-panel-fs-meta);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--awd-panel-text-2);
}
.pg-tool-desc {
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text-4);
  line-height: 1.4;
}
</style>
