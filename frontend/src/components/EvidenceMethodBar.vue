<template>
  <view v-if="visible" class="evidence-bar" @mousedown.stop @tap.stop>
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
// 拖文件到编辑器建链成功后的 method 浮动小条（spec §4.1）。宿主持有状态；
// 本组件只负责展示与点 chip 回传，3s 无操作自动 close（props 每次变化重置计时）。
import { EVIDENCE_METHODS } from '@/utils/evidenceLocator.js'

const AUTO_CLOSE_MS = 3000

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
    return { methods: EVIDENCE_METHODS, _timer: null }
  },
  watch: {
    visible(v) { if (v) this.arm(); else this.disarm() },
    fileName() { this.arm() },
    method() { this.arm() },
    targetId() { this.arm() },
  },
  mounted() { if (this.visible) this.arm() },
  beforeUnmount() { this.disarm() },
  methods: {
    arm() {
      this.disarm()
      if (!this.visible) return
      this._timer = setTimeout(() => { this._timer = null; this.$emit('close') }, AUTO_CLOSE_MS)
    },
    disarm() {
      if (this._timer) { clearTimeout(this._timer); this._timer = null }
    },
    pick(m) {
      this.arm()
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
  background: #fff; border: 1px solid #DEE2E6;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.12);
  font-size: 12px; color: #1e293b;
}
.evidence-bar-linked { font-weight: 600; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
