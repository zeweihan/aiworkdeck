<template>
  <view v-if="visible" class="evidence-bar" :class="{ 'is-error': isError }" @mousedown.stop @tap.stop>
    <svg class="evidence-bar-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path v-for="(d, i) in linkIcon" :key="i" :d="d" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
    <template v-if="isError">
      <text class="evidence-bar-linked">{{ $t('workbench.evidence.failedTitle', { name: fileName }) }}</text>
      <text v-if="errorText" class="evidence-bar-error-text">{{ errorText }}</text>
    </template>
    <template v-else>
      <text class="evidence-bar-linked">{{ $t('workbench.evidence.linked', { name: fileName }) }}</text>
      <text class="evidence-bar-sep">·</text>
      <text class="evidence-bar-label">{{ $t('workbench.evidence.methodLabel') }}</text>
      <view class="evidence-bar-chips">
        <view
          v-for="m in methods"
          :key="m"
          class="evidence-chip"
          :class="{ on: m === method }"
          @tap="pick(m)"
        >{{ $t('workbench.evidence.method.' + m) }}</view>
      </view>
    </template>
    <text class="evidence-bar-close" @tap="$emit('close')">x</text>
  </view>
</template>

<script>
// 拖文件到编辑器建链的回执小条（spec §4.1）。纯展示，状态在宿主
// （evidenceLinkActions.js），这里只回传点击。
// **不自动收起**：它既是回执，又是「按什么方式核查」的提问，自动消失两件事都
// 办不成（dev-board#138）。成功态：绿色描边 + method chips。
// **失败也走小条**（dev-board#139）：uni.showToast 在编辑器场景会被 webview
// 遮挡（#133 定性），建链失败若只弹 toast 就是「文字变成了链接但库里没记录、
// 用户毫无感知」——失败态红描边 + 原因，凡编辑器回执一律不用 toast。
import { EVIDENCE_METHODS } from '@/utils/evidenceLocator.js'
import { ICONS } from '@/config/icons.js'

export default {
  name: 'EvidenceMethodBar',
  emits: ['change', 'close'],
  props: {
    visible: { type: Boolean, default: false },
    fileName: { type: String, default: '' },
    method: { type: String, default: 'written_review' },
    targetId: { type: [Number, String], default: null },
    status: { type: String, default: 'success' },
    errorText: { type: String, default: '' },
  },
  computed: {
    isError() { return this.status === 'error' },
  },
  data() {
    return { methods: EVIDENCE_METHODS, linkIcon: ICONS.link }
  },
  methods: {
    pick(m) {
      if (m === this.method) return
      this.$emit('change', { targetId: this.targetId, method: m })
    },
  },
}
</script>

<style scoped>
/* 浅色、悬浮在编辑器画布底部左侧；限宽避免压到右侧并排的审阅面板 */
.evidence-bar {
  position: absolute; left: 16px; bottom: 16px; z-index: 30;
  max-width: calc(100% - 32px);
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px;
  padding: 8px 12px; border-radius: 10px;
  background: #fff; border: 1px solid #1A5336;
  box-shadow: 0 6px 20px rgba(26, 83, 54, 0.22);
  font-size: 12px; color: #1e293b;
}
.evidence-bar.is-error { border-color: #B42318; box-shadow: 0 6px 20px rgba(180, 35, 24, 0.18); }
.evidence-bar.is-error .evidence-bar-icon,
.evidence-bar.is-error .evidence-bar-linked { color: #B42318; }
.evidence-bar-error-text { color: #475569; max-width: 420px; }
.evidence-bar-icon { width: 15px; height: 15px; flex: none; color: #1A5336; }
.evidence-bar-linked { font-weight: 600; color: #1A5336; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.evidence-bar-sep { color: #94a3b8; }
.evidence-bar-label { color: #64748b; }
.evidence-bar-chips { display: flex; gap: 6px; flex-wrap: wrap; }
.evidence-chip {
  padding: 3px 10px; border-radius: 999px; border: 1px solid #DEE2E6; background: #F8F9FA; color: #475569; cursor: pointer;
  white-space: nowrap; user-select: none;
}
.evidence-chip:hover { border-color: #1A5336; color: #1A5336; }
.evidence-chip.on { background: #E6F9F0; border-color: #1A5336; color: #1A5336; font-weight: 600; }
.evidence-bar-close { margin-left: 4px; padding: 0 6px; color: #94a3b8; cursor: pointer; font-family: monospace; }
.evidence-bar-close:hover { color: #1e293b; }
</style>
