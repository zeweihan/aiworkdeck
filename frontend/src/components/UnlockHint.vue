<template>
  <view class="unlock-hint">
    <text class="unlock-hint-text">{{ text }}</text>
    <text class="unlock-hint-link" @tap.stop="onLearnMore">{{ linkText }}</text>
  </view>
</template>

<script>
// 「解锁」引导：功能触达免费额度上限时的统一行内提示。
// 只提示与外链，不做拦截——是否拦、拦在哪由调用方自己判断（配合 useEntitlement）。
// 语气克制：用户没做错事，只是碰到了额度边界。
import { openExternalUrl } from '@/utils/externalLink.js'

// 官网账户页：解锁 SKU 的购买入口都挂在这里
const DEFAULT_LINK_URL = 'https://www.aiworkdeck.com/zh/account'

export default {
  name: 'UnlockHint',
  props: {
    // 提示正文，例如「免费版最多回溯 20 条剪贴记录」
    text: {
      type: String,
      required: true,
    },
    linkText: {
      type: String,
      default: '了解详情',
    },
    // 外链地址；走系统浏览器（桌面端 window.open 会被主进程吞掉）
    linkUrl: {
      type: String,
      default: DEFAULT_LINK_URL,
    },
  },
  methods: {
    onLearnMore() {
      openExternalUrl(this.linkUrl)
    },
  },
}
</script>

<style scoped>
/* 浅色体系：与顶栏「试用版」chip 同一族暖色，避免做成刺眼的警告条 */
.unlock-hint {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 10px;
  background: #fdf7ec;
  border: 1px solid #ecdfc3;
  border-radius: 4px;
}

.unlock-hint-text {
  font-size: 12px;
  line-height: 18px;
  color: #8a6d2f;
}

.unlock-hint-link {
  font-size: 12px;
  line-height: 18px;
  font-weight: 500;
  color: #1a5336;
  cursor: pointer;
}

.unlock-hint-link:hover {
  color: #2d7a52;
  text-decoration: underline;
}
</style>
