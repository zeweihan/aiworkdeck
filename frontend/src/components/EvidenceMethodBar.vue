<template>
  <view v-if="visible" class="evidence-bar" @mousedown.stop @tap.stop>
    <svg class="evidence-bar-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path v-for="(d, i) in linkIcon" :key="i" :d="d" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
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
    <text class="evidence-bar-close" @tap="$emit('close')">x</text>
  </view>
</template>

<script>
// 拖文件到编辑器建链成功后的 method 浮动小条（spec §4.1）。纯展示，状态在宿主
// （evidenceLinkActions.js），这里只回传点击。
// **不自动收起**：它既是「关联成功」的回执，又是「按什么方式核查」的提问，
// 自动消失两件事都办不成（dev-board#138）。所以视觉上按"成功态"做：绿色描边 +
// 链接图标，让用户在窗格底部一眼认出来，而不是当成一排普通 chip。
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
