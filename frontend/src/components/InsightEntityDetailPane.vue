<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <scroll-view class="iedp" scroll-y>
    <view class="iedp-inner">
      <view class="iedp-head">
        <text class="iedp-badge" :class="'k-' + kind">{{ $t('insight.entityKind.' + kind) }}</text>
        <text class="iedp-name">{{ entity.name }}</text>
      </view>
      <view class="iedp-status">
        <view class="iedp-dot" :class="'st-' + (entity.retrievalStatus || 'PENDING')"></view>
        <text v-if="entity.retrievalSource" class="iedp-src">{{ entity.retrievalSource }}</text>
      </view>
      <text v-if="entity.retrievalNote" class="iedp-note" :class="'st-' + (entity.retrievalStatus || 'PENDING')">{{ entity.retrievalNote }}</text>

      <text v-if="err" class="iedp-err">{{ err }}</text>
      <text v-else-if="loading" class="iedp-hint">{{ $t('insight.loadingDetail') }}</text>
      <InsightEntityBody
        v-else
        :entity="entity"
        :detail="detail"
        @open-url="$emit('open-url', $event)"
        @open-doc-file="$emit('open-doc-file', $event)"
      />

      <template v-if="mentions.length">
        <text class="iedp-sub">{{ $t('insight.mentionsTitle') }}</text>
        <text v-for="(m, mi) in mentions" :key="'mn' + mi" class="iedp-quote">{{ m.quote }}</text>
      </template>
    </view>
  </scroll-view>
</template>

<script>
// InsightEntityDetailPane.vue — 一个实体的完整详情，作为中栏标签页（dev-board#541）。
//
// 由浮窗上的「在新标签页打开」开出来（宿主 openInsightEntityTab，tabType 'insight-entity'，
// 强制落在右侧分屏）。正文渲染与浮窗共用 InsightEntityBody，这里只多出「文中出处」一段。
//
// 出处在这里**只列不定位**：定位要 LibreOffice executor，而标签页与哪份文档并列
// 是用户拖出来的，绑不住一个确定的编辑器实例——定位仍走「依据」窗格那一条路。

import InsightEntityBody from '@/components/InsightEntityBody.vue'
import { getDocInsightEntity } from '@/services/api.js'

function unwrap(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && 'data' in resp) return resp.data
  return resp
}

export default {
  name: 'InsightEntityDetailPane',
  components: { InsightEntityBody },
  emits: ['open-url', 'open-doc-file'],
  props: {
    // { entity, detail } —— 标签页建出来时宿主塞进去的那份（detail 可能已经拉好了）
    spec: { type: Object, default: null },
    projectId: { type: [Number, String], default: null },
  },
  data() {
    return {
      // spec.detail 是浮窗已经拉到的那份（有就先画上，免得开标签闪一下空白）
      detail: (this.spec && this.spec.detail) || null,
      // GET /entities/{id} 的完整 EntityView：出处（mentions）与最新的检索状态在这里，
      // 宿主传下来的 entity 是瘦身索引，没有这些。
      view: null,
      loading: false,
      err: '',
    }
  },
  computed: {
    entity() { return Object.assign({}, (this.spec && this.spec.entity) || {}, this.view || {}) },
    kind() {
      const k = this.entity.kind
      return k === 'LAW' || k === 'CASE' || k === 'DOC' ? k : 'COMPANY'
    },
    mentions() {
      const m = this.entity.mentions
      return Array.isArray(m) ? m.filter((x) => x && x.quote) : []
    },
  },
  mounted() { this.loadView() },
  methods: {
    async loadView() {
      const pid = Number(this.projectId) || null
      const id = this.entity.id
      if (!pid || !id) return
      this.loading = !this.detail
      try {
        const v = unwrap(await getDocInsightEntity(pid, id)) || {}
        this.view = v
        if (v.detail) this.detail = v.detail
      } catch (err) {
        if (!this.detail) this.err = (err && err.message) || this.$t('insight.detailFailed')
      } finally {
        this.loading = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.iedp { height: 100%; background: var(--awd-surface); }
.iedp-inner { display: flex; flex-direction: column; gap: 6px; padding: 14px 18px 24px; max-width: 720px; }

.iedp-head { display: flex; align-items: center; gap: 8px; }
.iedp-badge {
  flex: none; padding: 1px 7px; border-radius: 10px; font-size: 11px; font-weight: 600;
  color: var(--awd-accent-text); background: var(--awd-accent-soft); border: 1px solid var(--awd-mint);
}
.iedp-name { flex: 1; min-width: 0; font-size: 15px; font-weight: 600; color: var(--awd-text); }

.iedp-status { display: flex; align-items: center; gap: 6px; }
.iedp-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--awd-border-strong); }
.iedp-dot.st-OK { background: var(--awd-mint); }
.iedp-dot.st-NOT_FOUND { background: var(--awd-text-3); }
.iedp-dot.st-UNAVAILABLE, .iedp-dot.st-ERROR { background: var(--awd-danger); }
.iedp-src { font-size: 11px; color: var(--awd-text-3); }

.iedp-note { font-size: 12px; line-height: 1.6; color: var(--awd-danger-text); }
.iedp-note.st-NOT_FOUND { color: var(--awd-text-3); }
.iedp-err { font-size: 12px; color: var(--awd-danger-text); }
.iedp-hint { font-size: 12px; color: var(--awd-text-3); }

.iedp-sub { margin-top: 10px; font-size: 11px; font-weight: 700; color: var(--awd-text-3); }
.iedp-quote {
  padding: 6px 8px; border-radius: 6px; background: var(--awd-surface-2);
  font-size: 12px; color: var(--awd-text-2); line-height: 1.6;
}
</style>
