<template>
  <view v-if="items.length" class="esb" @tap.stop>
    <view class="esb-main">
      <view class="esb-dot"></view>
      <text v-if="items.length === 1" class="esb-text">{{ $t('evidence.staleOne', { count: targetCount(items[0]) }) }}</text>
      <text v-else class="esb-text">{{ $t('evidence.staleMany', { count: items.length }) }}</text>
      <text v-if="items.length > 1" class="esb-link" @tap="expanded = !expanded">{{ expanded ? $t('evidence.action.collapse') : $t('evidence.action.expand') }}</text>
      <view class="esb-acts">
        <text class="esb-btn ok" @tap="$emit('keep', items.map((i) => i.linkKey))">{{ $t('evidence.action.keep') }}</text>
        <text v-if="items.length === 1 && firstTarget(items[0])" class="esb-btn" @tap="$emit('locate', firstTarget(items[0]))">{{ $t('evidence.action.open') }}</text>
        <text class="esb-btn" @tap="$emit('ignore', items.map((i) => i.linkKey))">{{ $t('evidence.action.ignore') }}</text>
      </view>
    </view>
    <view v-if="expanded && items.length > 1" class="esb-list">
      <view v-for="i in items" :key="i.linkKey" class="esb-row">
        <text class="esb-row-text">{{ $t('evidence.stalePrefix') }}{{ (i.text || '').slice(0, 60) }}</text>
        <text class="esb-btn ok" @tap="$emit('keep', [i.linkKey])">{{ $t('evidence.action.keep') }}</text>
        <text v-if="firstTarget(i)" class="esb-btn" @tap="$emit('locate', firstTarget(i))">{{ $t('evidence.action.open') }}</text>
        <text class="esb-btn" @tap="$emit('ignore', [i.linkKey])">{{ $t('evidence.action.ignore') }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// EvidenceStaleBar.vue — 改字 stale 的非阻塞提示条（spec §4.4）。叠在编辑器画布
// 顶部，不抢焦点、不挡输入；合并规则在 utils/evidenceStaleQueue.js，这里只渲染。
// items: [{ linkKey, text, link }]，link 是缓存里的 LinkView（取 targets 数与首个 target）。
export default {
  name: 'EvidenceStaleBar',
  emits: ['keep', 'locate', 'ignore'],
  props: {
    items: { type: Array, default: () => [] },
  },
  data() {
    return { expanded: false }
  },
  methods: {
    targetCount(i) { return (i && i.link && Array.isArray(i.link.targets)) ? i.link.targets.length : 0 },
    firstTarget(i) {
      const tg = i && i.link && Array.isArray(i.link.targets) ? i.link.targets.find((t) => t && t.fileId && !(t.file && t.file.isDeleted)) : null
      return tg ? { fileId: tg.fileId, locator: tg.locator || null, linkKey: i.linkKey, targetId: tg.id } : null
    },
  },
}
</script>

<style scoped>
.esb { position: absolute; top: 0; left: 0; right: 0; z-index: 30; background: #FFF7E6; border-bottom: 1px solid #FFD591;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06); font-size: 12px; color: #8A5A00; }
.esb-main { display: flex; align-items: center; gap: 8px; padding: 6px 12px; flex-wrap: wrap; }
.esb-dot { width: 8px; height: 8px; border-radius: 50%; background: #F5B30E; flex-shrink: 0; }
.esb-text { flex: 1; min-width: 0; }
.esb-link { color: #1A5336; text-decoration: underline; }
.esb-acts { display: flex; gap: 6px; }
.esb-btn { padding: 2px 10px; border: 1px solid #DEE2E6; border-radius: 6px; background: #fff; color: #495057; }
.esb-btn.ok { border-color: #5BD197; color: #1A5336; }
.esb-list { border-top: 1px solid #FFE7BA; padding: 4px 12px 6px; }
.esb-row { display: flex; align-items: center; gap: 6px; padding: 3px 0; }
.esb-row-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #495057; }
</style>
