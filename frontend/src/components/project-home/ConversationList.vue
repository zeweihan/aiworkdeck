<template>
  <view class="conversation-list">
    <view v-if="loading && !conversations.length" class="conv-hint">{{ $t('projects.conversationsLoadingHint') }}</view>

    <view v-else-if="!conversations.length" class="conv-guide">
      <text class="conv-guide-title">{{ $t('projects.noConversationsTitle') }}</text>
      <text class="conv-guide-desc">{{ $t('projects.noConversationsDesc') }}</text>
    </view>

    <template v-else>
      <view
        v-for="c in conversations"
        :key="c.conversationId"
        class="conv-card"
        @tap="$emit('open', c.conversationId)"
      >
        <view class="conv-card-head">
          <text class="conv-title">{{ c.title }}</text>
          <!-- 插件镜像会话来源角标（dev-board#298）：sourceChannel 非空才渲染 -->
          <text v-if="c.sourceChannel" class="conv-source-chip">{{ sourceLabel(c.sourceChannel) }}</text>
          <text v-if="statusLabel(c.runStatus)" class="conv-status" :class="dotClass(c.runStatus)">
            {{ statusLabel(c.runStatus) }}
          </text>
        </view>
        <text v-if="hasPreview(c)" class="conv-preview">{{ c.lastMessage }}</text>
        <text class="conv-meta">{{ metaOf(c) }}</text>
      </view>
      <view v-if="hasMore" class="conv-more" :class="{ 'conv-more-busy': loading }" @tap="onLoadMore">
        {{ loading ? $t('projects.conversationsLoadingHint') : $t('projects.loadMoreConversations') }}
      </view>
    </template>
  </view>
</template>

<script>
// 概览页 AI 对话历史「列表层」。全项目成员可见（分层决策：只放开标题/时间/
// 发起人/状态，正文层一行都不放开）。点击不内嵌 ChatInterface —— loadHistoryChat
// 是完整切换会话，会在用户还没进工作台时就抢占当前会话，所以只 emit 出去由父页面跳工作台。
//
// 硬约束：title / lastMessage 是服务端 cleanTitle/extractPreview/truncatePreview
// 的输出，本组件一律原样渲染。仓里已有两套并行漂移的清洗正则，不许出第三套。
// 两个已知展示坑的兜底：lastMessage 可能是空串（不留空行）；title 可能是字面量
// 「新对话」（清洗兜底与 LLM 生成失败同文案，无法区分，照常显示不特判）。
import { formatDateTime, runStatusLabel, runStatusDotClass, hasConversationPreview } from '@/utils/projectHomeFormat.js'
import { sourceChannelLabel } from '@/utils/conversationSource.js'

export default {
  name: 'ConversationList',
  props: {
    conversations: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    hasMore: { type: Boolean, default: false },
  },
  emits: ['open', 'load-more'],
  methods: {
    hasPreview(c) {
      return hasConversationPreview(c)
    },
    statusLabel(status) {
      return runStatusLabel(status)
    },
    dotClass(status) {
      return runStatusDotClass(status)
    },
    sourceLabel(sourceChannel) {
      return sourceChannelLabel(sourceChannel)
    },
    metaOf(c) {
      return [c.ownerName, formatDateTime(c.updatedAt)].filter(Boolean).join(' · ')
    },
    // 翻页游标要等响应回来才更新，连点两下会用同一份游标取回同一页拼进列表，
    // 于是同一个 conversationId 出现两次，v-for 的 :key 撞车、卡片重复渲染。
    // 请求在飞时不再派发，行本身改成加载中文案（不隐藏，免得内容跳位）。
    onLoadMore() {
      if (this.loading) return
      this.$emit('load-more')
    },
  },
}
</script>

<style scoped>
.conv-hint {
  font-size: 13px;
  color: var(--awd-text-2);
}

.conv-guide-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text);
}

.conv-guide-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: var(--awd-text-2);
}

.conv-card {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  cursor: pointer;
}

.conv-card:hover {
  border-color: var(--awd-mint);
}

.conv-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conv-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-status {
  flex: none;
  font-size: 11px;
  color: var(--awd-text-2);
}

/* 插件镜像会话来源角标（dev-board#298），与工作台历史抽屉的 .conv-source-chip 同形 */
.conv-source-chip {
  flex: none;
  font-size: 10px;
  line-height: 15px;
  padding: 0 5px;
  border-radius: 3px;
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
}

.conv-status.dot-running {
  color: var(--awd-accent-text);
}

.conv-status.dot-attention {
  color: var(--awd-warning-text);
}

.conv-status.dot-error {
  color: var(--awd-danger-text);
}

.conv-preview {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--awd-text-2);
}

.conv-meta {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--awd-text-3);
}

.conv-more {
  padding: 8px 0;
  text-align: center;
  font-size: 12px;
  color: var(--awd-accent-text);
  cursor: pointer;
}

.conv-more-busy {
  color: var(--awd-text-2);
  cursor: default;
}

/* 响应祖先 .project-home-pane 的实际渲染宽度，见 project-home-pane.scss 的注释 */
@container home-pane (max-width: 359px) {
  .conv-card {
    padding: 8px 10px;
  }

  .conv-title {
    font-size: 12px;
  }

  .conv-preview {
    font-size: 11px;
  }

  .conv-meta,
  .conv-status {
    font-size: 10px;
  }
}
</style>
