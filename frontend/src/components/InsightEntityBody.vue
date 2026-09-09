<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="ieb" :class="{ compact }">
    <!-- 企业：工商基本情况表（键值两列） -->
    <template v-if="kind === 'COMPANY'">
      <view v-for="r in rows" :key="r.label" class="ieb-kv">
        <text class="ieb-k">{{ r.label }}</text>
        <text class="ieb-v">{{ r.value }}</text>
      </view>
      <template v-if="shareholders.length">
        <text class="ieb-sub">{{ $t('insight.shareholders') }}</text>
        <view v-for="(s, si) in shareholders" :key="'sh' + si" class="ieb-kv">
          <text class="ieb-k">{{ s.name }}</text>
          <text class="ieb-v">{{ [s.percent, s.capital].filter(Boolean).join(' / ') }}</text>
        </view>
      </template>
    </template>

    <!-- 法规：条文原文 + 引用校验回填的权威原文 -->
    <template v-else-if="kind === 'LAW'">
      <text v-if="law.title" class="ieb-title">{{ law.title }}{{ law.article }}</text>
      <text v-if="law.timeliness" class="ieb-meta">{{ law.timeliness }}</text>
      <text v-if="law.content" class="ieb-para">{{ clip(law.content) }}</text>
      <template v-if="!compact && law.more.length">
        <text class="ieb-sub">{{ $t('insight.moreCandidates') }}</text>
        <text v-for="(m, mi) in law.more" :key="'lm' + mi" class="ieb-cand">{{ m }}</text>
      </template>
      <template v-if="auth">
        <text class="ieb-sub">{{ $t('insight.authoritative') }}</text>
        <text v-if="auth.title" class="ieb-title">{{ auth.title }}</text>
        <text v-if="auth.date" class="ieb-meta">{{ $t('insight.implementDate', { date: auth.date }) }}</text>
        <text v-if="auth.text" class="ieb-para">{{ clip(auth.text) }}</text>
        <text v-if="auth.url" class="ieb-link" @tap.stop="openUrl(auth.url)">{{ $t('insight.openInPkulaw') }}</text>
      </template>
    </template>

    <!-- 文档：项目文件树里命中的那份文件（dev-board#541） -->
    <template v-else-if="kind === 'DOC'">
      <template v-if="doc">
        <text class="ieb-title">{{ doc.fileName }}</text>
        <text v-if="doc.filePath" class="ieb-meta">{{ doc.filePath }}</text>
        <!-- 浮窗（compact）自己的底栏已有「打开文件」，这里不再重复一个入口 -->
        <text v-if="!compact" class="ieb-link" @tap.stop="openDoc">{{ $t('insight.openDocFile') }}</text>
      </template>
    </template>

    <!-- 案例：案号识别（先导步）+ 判决书 -->
    <template v-else>
      <template v-if="rec">
        <text class="ieb-sub">{{ $t('insight.recognition') }}</text>
        <text v-if="rec.title" class="ieb-title">{{ rec.title }}</text>
        <text v-if="recMeta" class="ieb-meta">{{ recMeta }}</text>
        <text v-if="rec.url" class="ieb-link" @tap.stop="openUrl(rec.url)">{{ $t('insight.openInPkulaw') }}</text>
      </template>
      <text v-if="caseRec.title" class="ieb-title">{{ caseRec.title }}</text>
      <text v-if="caseMeta" class="ieb-meta">{{ caseMeta }}</text>
      <view v-for="s in sections" :key="s.key" class="ieb-block">
        <text class="ieb-sub">{{ $t('insight.caseSection.' + s.key) }}</text>
        <text class="ieb-para">{{ clip(s.text) }}</text>
      </view>
      <template v-if="!compact && caseRec.more.length">
        <text class="ieb-sub">{{ $t('insight.moreCandidates') }}</text>
        <text v-for="(m, mi) in caseRec.more" :key="'cm' + mi" class="ieb-cand">{{ m }}</text>
      </template>
    </template>

    <!-- 认得的字段一个都没渲染出来时才亮原文兜底（不是每次都把 JSON 铺一遍） -->
    <text v-if="showRaw" class="ieb-raw">{{ raw }}</text>
    <!-- DOC 未命中在浮窗里由头部 retrievalNote 说明，compact 档不再重复一句 -->
    <text v-if="empty && !(compact && kind === 'DOC')" class="ieb-hint">{{ kind === 'DOC' ? $t('insight.docNotFound') : $t('insight.noDetail') }}</text>
  </view>
</template>

<script>
// InsightEntityBody.vue — 一个实体的检索详情正文（dev-board#541）。
//
// 浮窗（InsightHoverCard）与新标签页（InsightEntityDetailPane）共用这一份渲染，
// 免掉两套字段解析：字段整形全部来自 utils/insightDetail.js 的纯函数，
// 这里只管「怎么摆」。compact = 浮窗档：截断长正文、不列其余候选。
//
// 与 InsightPane 内联的那份详情块的关系：形制一致但**不复用**——那边嵌在
// 可展开行里、还带出处定位，把它抽出来会把面板的滚动/展开逻辑一起拖进来。

import {
  companyRows, companyShareholders, lawArticle, caseRecord, rawFallback,
  authoritative, caseRecognition, projectFile,
} from '@/utils/insightDetail.js'

// 浮窗里一段正文的字数上限：再长就该去新标签页看了。
const COMPACT_CHARS = 220
// 浮窗里最多列几行工商字段 / 几个股东。
const COMPACT_ROWS = 6
const COMPACT_SHAREHOLDERS = 3

export default {
  name: 'InsightEntityBody',
  // open-doc-file：DOC 实体命中的项目文件，一路上抛到宿主在右侧分屏打开。
  emits: ['open-url', 'open-doc-file'],
  props: {
    entity: { type: Object, default: null },
    detail: { type: Object, default: null },
    compact: { type: Boolean, default: false },
  },
  computed: {
    kind() {
      const k = this.entity && this.entity.kind
      return k === 'LAW' || k === 'CASE' || k === 'DOC' ? k : 'COMPANY'
    },
    rows() {
      const all = companyRows(this.detail)
      return this.compact ? all.slice(0, COMPACT_ROWS) : all
    },
    shareholders() {
      const all = companyShareholders(this.detail)
      return this.compact ? all.slice(0, COMPACT_SHAREHOLDERS) : all
    },
    law() { return lawArticle(this.detail) },
    doc() { return projectFile(this.detail) },
    auth() { return authoritative(this.detail) },
    caseRec() { return caseRecord(this.detail) },
    rec() { return caseRecognition(this.detail) },
    recMeta() {
      const r = this.rec
      return r ? [r.caseNumber, r.court].filter(Boolean).join(' · ') : ''
    },
    caseMeta() {
      const c = this.caseRec
      return [c.caseNumber, c.court, c.date, c.caseType].filter(Boolean).join(' · ')
    },
    sections() {
      const all = this.caseRec.sections
      return this.compact ? all.slice(0, 1) : all
    },
    showRaw() {
      if (!this.detail) return false
      if (this.compact) return false      // 浮窗不铺原始 JSON，看不懂也占满整张卡
      if (this.kind === 'COMPANY') return !companyRows(this.detail).length
      if (this.kind === 'LAW') return !this.law.title && !this.law.content && !this.auth
      if (this.kind === 'DOC') return !this.doc
      return !this.caseRec.title && !this.caseRec.sections.length && !this.rec
    },
    raw() { return rawFallback(this.detail) },
    empty() {
      if (this.detail) return false
      return true
    },
  },
  methods: {
    clip(text) {
      const s = text == null ? '' : String(text)
      if (!this.compact || s.length <= COMPACT_CHARS) return s
      return s.slice(0, COMPACT_CHARS) + '…'
    },
    openUrl(url) {
      const u = url == null ? '' : String(url)
      if (u) this.$emit('open-url', u)
    },
    openDoc() {
      const d = this.doc
      if (d) this.$emit('open-doc-file', { fileId: d.fileId, fileName: d.fileName })
    },
  },
}
</script>

<style lang="scss" scoped>
// 浅色外壳 + --awd- 令牌（配色红线：不引入深色 chrome）。
.ieb { display: flex; flex-direction: column; gap: 4px; }

.ieb-kv { display: flex; gap: 8px; align-items: flex-start; }
.ieb-k { flex: none; width: 78px; font-size: 11px; color: var(--awd-text-3); line-height: 1.6; }
.ieb-v { flex: 1; min-width: 0; font-size: 11px; color: var(--awd-text); line-height: 1.6; word-break: break-all; }

.ieb-sub { margin-top: 4px; font-size: 10px; font-weight: 700; color: var(--awd-text-3); }
.ieb-title { font-size: 12px; font-weight: 600; color: var(--awd-text); line-height: 1.5; }
.ieb-meta { font-size: 10px; color: var(--awd-text-3); line-height: 1.5; }
.ieb-para { font-size: 11px; color: var(--awd-text-2); line-height: 1.7; white-space: pre-wrap; }
.ieb-cand { font-size: 11px; color: var(--awd-text-2); line-height: 1.6; }
.ieb-block { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; }
.ieb-link { align-self: flex-start; font-size: 11px; color: var(--awd-accent-text); text-decoration: underline; }
.ieb-raw {
  margin-top: 4px; padding: 6px; border-radius: 6px; background: var(--awd-surface-2);
  font-size: 10px; color: var(--awd-text-3); line-height: 1.5; white-space: pre-wrap; word-break: break-all;
}
.ieb-hint { font-size: 11px; color: var(--awd-text-3); }

.ieb.compact .ieb-para { max-height: 132px; overflow: hidden; }
</style>
