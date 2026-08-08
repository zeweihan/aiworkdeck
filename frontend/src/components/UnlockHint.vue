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
import { accountPageUrl } from '@/utils/siteLinks.js'

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
    // 外链地址；走系统浏览器（桌面端 window.open 会被主进程吞掉）。
    // 留空 = 当前站点的账户页（解锁 SKU 的购买入口都挂在那里）。
    linkUrl: {
      type: String,
      default: '',
    },
  },
  methods: {
    onLearnMore() {
      // 站点链接在点击时才取：siteLinks 首帧可能还是兜底值，
      // 宿主页面在 onLoad 里已预热过，点击这一刻读到的是当前站点。
      openExternalUrl(this.linkUrl || accountPageUrl())
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
