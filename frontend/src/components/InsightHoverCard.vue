<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="ihc-mask" @tap="close" @contextmenu.prevent="close">
    <view
      ref="card"
      class="ihc"
      :style="{ left: pos.left + 'px', top: pos.top + 'px' }"
      @tap.stop
    >
      <view class="ihc-head">
        <text class="ihc-badge" :class="'k-' + kind">{{ $t('insight.entityKind.' + kind) }}</text>
        <text class="ihc-name" :title="entity.name || ''">{{ entity.name }}</text>
        <text class="ihc-x" @tap.stop="close">×</text>
      </view>

      <view class="ihc-status">
        <view class="ihc-dot" :class="'st-' + (entity.retrievalStatus || 'PENDING')"></view>
        <text v-if="entity.retrievalSource" class="ihc-src">{{ entity.retrievalSource }}</text>
      </view>
      <text v-if="entity.retrievalNote" class="ihc-note" :class="'st-' + (entity.retrievalStatus || 'PENDING')">{{ entity.retrievalNote }}</text>

      <scroll-view class="ihc-body" scroll-y>
        <text v-if="err" class="ihc-err">{{ err }}</text>
        <text v-else-if="loading" class="ihc-hint">{{ $t('insight.loadingDetail') }}</text>
        <InsightEntityBody
          v-else
          :entity="entity"
          :detail="detail"
          compact
          @open-url="$emit('open-url', $event)"
          @open-doc-file="openDoc"
        />
      </scroll-view>

      <!-- DOC 的主动作是「打开文件」（详情标签对它没有意义，打开文件本身就落在右侧分屏）；
           项目里没有这份文件时一个按钮都不给。 -->
      <view v-if="showFoot" class="ihc-foot">
        <text v-if="kind === 'DOC'" class="ihc-open" @tap.stop="openDoc">{{ $t('insight.openDocFile') }}</text>
        <text v-else class="ihc-open" @tap.stop="openTab">{{ $t('insight.openInNewTab') }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// InsightHoverCard.vue — 正文里 Cmd/Ctrl 点中一个实体后贴着点击处弹出的浮窗（dev-board#541）。
//
// 骨架照 FileTree.vue 的右键菜单（全屏透明 mask + position:fixed 卡片 + 高 z-index）：
// 编辑器画布是 <webview>（桌面壳）/ 同源 <iframe>（Web 版），浮层必须挂在宿主根节点
// 才叠得上去，所以本组件由 project-overview.vue 在根级渲染，不挂在面板里。
//
// 内容渲染复用 InsightEntityBody（与新标签页同一份），字段整形复用 utils/insightDetail.js。
// 落点坐标由 utils/insightPopup.js 的纯函数算（靠边翻转、始终不出屏）。

import InsightEntityBody from '@/components/InsightEntityBody.vue'
import { getDocInsightEntity } from '@/services/api.js'
import { projectFile } from '@/utils/insightDetail.js'
import { hoverCardPosition } from '@/utils/insightPopup.js'

const CARD_W = 320
const CARD_H_FALLBACK = 240

function unwrap(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && 'data' in resp) return resp.data
  return resp
}

export default {
  name: 'InsightHoverCard',
  components: { InsightEntityBody },
  // open-tab：底部「在新标签页打开」，宿主据此在右侧分屏开一个 insight-entity 标签。
  //           带上已经拉到的 detail，新标签页就不必再打一次接口。
  // open-url：法宝外链交给宿主的浏览器面板（面板自己不 window.open）。
  // open-doc-file：DOC 实体命中的项目文件（宿主在右侧分屏打开那份文件本身）。
  emits: ['close', 'open-tab', 'open-url', 'open-doc-file'],
  props: {
    entity: { type: Object, required: true },
    // 宿主换算好的页面坐标（客体页 clientX/clientY + webview rect）
    x: { type: Number, default: 0 },
    y: { type: Number, default: 0 },
    projectId: { type: [Number, String], default: null },
    // 已经有详情时直接用（面板里展开过的那些），省一次往返
    initialDetail: { type: Object, default: null },
  },
  data() {
    return {
      detail: this.initialDetail || null,
      loading: false,
      err: '',
      cardH: CARD_H_FALLBACK,
    }
  },
  computed: {
    kind() {
      const k = this.entity && this.entity.kind
      return k === 'LAW' || k === 'CASE' || k === 'DOC' ? k : 'COMPANY'
    },
    doc() { return projectFile(this.detail) },
    /** DOC 没命中项目文件时底栏整条不出：没有一个走得通的动作可给。 */
    showFoot() { return this.kind !== 'DOC' || !!this.doc },
    pos() {
      return hoverCardPosition({
        x: this.x,
        y: this.y,
        width: CARD_W,
        height: this.cardH,
        viewportWidth: typeof window !== 'undefined' ? window.innerWidth : 0,
        viewportHeight: typeof window !== 'undefined' ? window.innerHeight : 0,
      })
    },
  },
  mounted() {
    this.loadDetail()
    this.measure()
    this._onKey = (e) => { if (e && e.key === 'Escape') this.close() }
    try { document.addEventListener('keydown', this._onKey, true) } catch (e) { /* 非 h5 端没有 document */ }
  },
  beforeUnmount() {
    try { document.removeEventListener('keydown', this._onKey, true) } catch (e) { /* ignore */ }
  },
  methods: {
    close() { this.$emit('close') },
    openTab() { this.$emit('open-tab', { entity: this.entity, detail: this.detail }) },
    openDoc() {
      const d = this.doc
      if (d) this.$emit('open-doc-file', { fileId: d.fileId, fileName: d.fileName })
    },
    /** 真实高度量出来再定位一次：翻转判据要用真高度，不然靠近底边时会露出屏幕外。 */
    measure() {
      this.$nextTick(() => {
        try {
          const ref = this.$refs.card
          const el = ref && ref.$el ? ref.$el : ref
          const h = el && el.getBoundingClientRect ? el.getBoundingClientRect().height : 0
          if (h > 0) this.cardH = h
        } catch (e) { /* 量不到就用兜底高度 */ }
      })
    },
    async loadDetail() {
      const pid = Number(this.projectId) || null
      const e = this.entity
      if (this.detail || !pid || !e || !e.id) { this.measure(); return }
      if (e.hasDetail === false) { this.measure(); return }
      this.loading = true
      try {
        const v = unwrap(await getDocInsightEntity(pid, e.id)) || {}
        this.detail = v.detail || null
      } catch (err) {
        this.err = (err && err.message) || this.$t('insight.detailFailed')
      } finally {
        this.loading = false
        this.measure()
      }
    },
  },
}
</script>

<style lang="scss" scoped>
// z-index 与 FileTree 右键菜单同档：编辑器画布是独立合成层，浮层必须压在最上面。
.ihc-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  z-index: 9999; background: transparent;
}
.ihc {
  position: fixed; width: 320px; max-height: 420px;
  display: flex; flex-direction: column;
  background: var(--awd-surface); border: 1px solid var(--awd-border); border-radius: 8px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.16);
  z-index: 10000; overflow: hidden;
}

.ihc-head {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 10px 6px; border-bottom: 1px solid var(--awd-border);
}
.ihc-badge {
  flex: none; padding: 1px 6px; border-radius: 10px; font-size: 10px; font-weight: 600;
  color: var(--awd-accent-text); background: var(--awd-accent-soft); border: 1px solid var(--awd-mint);
}
.ihc-name {
  flex: 1; min-width: 0; font-size: 12px; font-weight: 600; color: var(--awd-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ihc-x { flex: none; width: 18px; text-align: center; font-size: 14px; color: var(--awd-text-3); }

.ihc-status { display: flex; align-items: center; gap: 6px; padding: 5px 10px 0; }
.ihc-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--awd-border-strong); }
.ihc-dot.st-OK { background: var(--awd-mint); }
// NOT_FOUND 用中性灰而不是告警色：文档里写了一家不存在的公司，是文档的问题，不是我们的故障。
.ihc-dot.st-NOT_FOUND { background: var(--awd-text-3); }
.ihc-dot.st-UNAVAILABLE, .ihc-dot.st-ERROR { background: var(--awd-danger); }
.ihc-src { flex: 1; min-width: 0; font-size: 10px; color: var(--awd-text-3); }

.ihc-note {
  padding: 4px 10px 0; font-size: 11px; line-height: 1.5; color: var(--awd-danger-text);
}
.ihc-note.st-NOT_FOUND { color: var(--awd-text-3); }

.ihc-body { flex: 1; min-height: 0; max-height: 300px; padding: 6px 10px 8px; }
.ihc-hint { font-size: 11px; color: var(--awd-text-3); }
.ihc-err { font-size: 11px; color: var(--awd-danger-text); line-height: 1.5; }

.ihc-foot {
  display: flex; justify-content: flex-end;
  padding: 6px 10px; border-top: 1px solid var(--awd-border); background: var(--awd-bg);
}
.ihc-open {
  padding: 3px 10px; border-radius: 10px; font-size: 11px; font-weight: 600;
  color: var(--awd-accent-text); background: var(--awd-accent-soft); border: 1px solid var(--awd-mint);
}
</style>
