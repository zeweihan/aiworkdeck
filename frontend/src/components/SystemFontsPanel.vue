<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view v-if="visible" class="sfp-mask" @tap.self="cancel">
    <view class="sfp" role="dialog" aria-modal="true">
      <view class="sfp__head">
        <text class="sfp__title">{{ $t('editor.systemFonts.title') }}</text>
        <view class="sfp__x" :title="$t('editor.systemFonts.close')" @tap="cancel">×</view>
      </view>

      <view v-if="!supported" class="sfp__empty">{{ $t('editor.systemFonts.desktopOnly') }}</view>

      <template v-else>
        <text class="sfp__desc">{{ $t('editor.systemFonts.description') }}</text>
        <view class="sfp__tools">
          <input
            v-model="query"
            class="sfp__search"
            type="text"
            :placeholder="$t('editor.systemFonts.searchPlaceholder')"
          />
          <view class="sfp__btn sfp__btn--ghost" @tap="selectRecommended">{{ $t('editor.systemFonts.selectRecommended') }}</view>
          <view class="sfp__btn sfp__btn--ghost" @tap="clearAll">{{ $t('editor.systemFonts.clearAll') }}</view>
        </view>

        <view class="sfp__list">
          <view v-if="loading" class="sfp__empty">{{ $t('editor.systemFonts.loading') }}</view>
          <view v-else-if="error" class="sfp__empty sfp__empty--error">{{ $t('editor.systemFonts.loadFailed') }}</view>
          <view v-else-if="!filtered.length" class="sfp__empty">
            {{ fonts.length ? $t('editor.systemFonts.noMatch') : $t('editor.systemFonts.noFonts') }}
          </view>
          <template v-else>
            <view
              v-for="f in filtered"
              :key="f.id"
              class="sfp__row"
              :class="{ 'is-on': selected[f.id] }"
              @tap="toggle(f.id)"
            >
              <view class="sfp__check" :class="{ 'is-on': selected[f.id] }">{{ selected[f.id] ? '✓' : '' }}</view>
              <view class="sfp__main">
                <view class="sfp__name">
                  <text class="sfp__primary">{{ f.primary }}</text>
                  <text v-if="f.recommended" class="sfp__tag sfp__tag--rec">{{ $t('editor.systemFonts.recommendedTag') }}</text>
                  <text v-if="f.kind === 'user'" class="sfp__tag">{{ $t('editor.systemFonts.userInstalledTag') }}</text>
                </view>
                <text class="sfp__sub">{{ secondary(f) }}</text>
              </view>
              <text class="sfp__size">{{ mb(f.sizeBytes) }} MB</text>
            </view>
          </template>
        </view>

        <text class="sfp__hint">{{ $t('editor.systemFonts.reopenHint') }}</text>
      </template>

      <view class="sfp__foot">
        <text v-if="supported" class="sfp__sum" :class="{ 'is-warn': overBudget }">
          {{ $t('editor.systemFonts.summary', { count: selectedCount, size: mb(selectedBytes) }) }}
          <template v-if="overBudget">{{ $t('editor.systemFonts.overBudget', { size: mb(maxRecommendedBytes) }) }}</template>
        </text>
        <view class="sfp__spacer" />
        <view v-if="supported" class="sfp__btn sfp__btn--ghost" :class="{ 'is-disabled': loading }" @tap="refresh">{{ $t('editor.systemFonts.refresh') }}</view>
        <view class="sfp__btn sfp__btn--ghost" @tap="cancel">{{ $t('editor.systemFonts.cancel') }}</view>
        <view
          v-if="supported"
          class="sfp__btn sfp__btn--primary"
          :class="{ 'is-disabled': saving || loading || error }"
          @tap="save"
        >{{ saving ? $t('editor.systemFonts.saving') : $t('editor.systemFonts.save') }}</view>
      </view>
    </view>
  </view>
</template>

<script>
// 编辑器本机字体面板（仅桌面）：列出本机字体，勾选哪些在编辑器引擎启动时注入。
// 数据与持久化在主进程 desktop/main/system-fonts.js（~/.aiworkdeck/editor-fonts.json），
// 经 preload 的 host.systemFonts.{list,setEnabled}。Web 版没有这组能力，只显示说明。
// 每个文档是独立引擎实例、字体在引擎启动时注入，所以保存只影响之后新打开的文档。
import { host } from '@/services/host.js'

export default {
  name: 'SystemFontsPanel',
  emits: ['close', 'saved'],
  props: {
    visible: { type: Boolean, default: false },
  },
  data() {
    return {
      fonts: [],
      selected: {},
      query: '',
      loading: false,
      saving: false,
      error: false,
      maxRecommendedBytes: 200 * 1024 * 1024,
    }
  },
  computed: {
    supported() {
      const sf = host.systemFonts
      return !!(sf && typeof sf.list === 'function' && typeof sf.setEnabled === 'function')
    },
    filtered() {
      const q = this.query.trim().toLowerCase()
      if (!q) return this.fonts
      return this.fonts.filter((f) =>
        String(f.file || '').toLowerCase().includes(q) ||
        (f.families || []).some((n) => String(n).toLowerCase().includes(q)))
    },
    selectedCount() {
      return this.fonts.filter((f) => this.selected[f.id]).length
    },
    selectedBytes() {
      return this.fonts.reduce((s, f) => s + (this.selected[f.id] ? f.sizeBytes || 0 : 0), 0)
    },
    overBudget() {
      return this.selectedBytes > this.maxRecommendedBytes
    },
  },
  watch: {
    visible: {
      immediate: true,
      handler(v) {
        if (v) this.load(false)
        if (typeof window === 'undefined') return
        if (v) window.addEventListener('keydown', this.onKey)
        else window.removeEventListener('keydown', this.onKey)
      },
    },
  },
  beforeUnmount() {
    if (typeof window !== 'undefined') window.removeEventListener('keydown', this.onKey)
  },
  methods: {
    onKey(e) { if (e && e.key === 'Escape') this.cancel() },
    async load(refresh) {
      if (!this.supported || this.loading) return
      this.loading = true
      this.error = false
      try {
        const r = await host.systemFonts.list({ refresh: !!refresh })
        this.fonts = Array.isArray(r && r.fonts) ? r.fonts : []
        if (r && r.maxRecommendedBytes > 0) this.maxRecommendedBytes = r.maxRecommendedBytes
        // 刷新时保留用户本次已改的勾选；首次打开取持久化/默认集
        const prev = refresh ? this.selected : null
        const next = {}
        for (const f of this.fonts) next[f.id] = prev && f.id in prev ? !!prev[f.id] : !!f.enabled
        this.selected = next
      } catch (e) {
        console.warn('[system-fonts] list failed:', e)
        this.error = true
      } finally {
        this.loading = false
      }
    },
    refresh() { if (!this.loading) this.load(true) },
    toggle(id) { this.selected = { ...this.selected, [id]: !this.selected[id] } },
    selectRecommended() {
      const next = { ...this.selected }
      for (const f of this.fonts) if (f.recommended) next[f.id] = true
      this.selected = next
    },
    clearAll() {
      const next = {}
      for (const f of this.fonts) next[f.id] = false
      this.selected = next
    },
    secondary(f) {
      const rest = (f.families || []).filter((n) => n !== f.primary)
      return rest.length ? rest.join(' · ') + ' · ' + f.file : f.file
    },
    mb(bytes) {
      const v = (bytes || 0) / 1048576
      return v >= 10 ? String(Math.round(v)) : v.toFixed(1)
    },
    cancel() { if (!this.saving) this.$emit('close') },
    async save() {
      if (!this.supported || this.saving || this.loading || this.error) return
      this.saving = true
      try {
        const ids = this.fonts.filter((f) => this.selected[f.id]).map((f) => f.id)
        const r = await host.systemFonts.setEnabled(ids)
        this.$emit('saved', { count: r && r.count != null ? r.count : ids.length, enabledBytes: r && r.enabledBytes != null ? r.enabledBytes : this.selectedBytes })
        this.$emit('close')
      } catch (e) {
        console.warn('[system-fonts] save failed:', e)
        try { uni.showToast({ title: this.$t('editor.systemFonts.saveFailed'), icon: 'none' }) } catch (x) { /* ignore */ }
      } finally {
        this.saving = false
      }
    },
  },
}
</script>

<style>
/* 不 scoped：类名统一 sfp 前缀。z 950：高于工具栏弹层（900）、低于全局弹窗遮罩（1000+）。
   颜色一律取 --awd-* 令牌，深色主题自动接管。 */
.sfp-mask {
  position: fixed;
  inset: 0;
  z-index: 950;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  box-sizing: border-box;
  background: var(--awd-overlay, rgba(0, 0, 0, 0.32));
  font-family: var(--awd-font-sans);
}
.sfp {
  display: flex;
  flex-direction: column;
  width: 620px;
  max-width: calc(100vw - 32px);
  height: 640px;
  max-height: calc(100vh - 32px);
  box-sizing: border-box;
  padding: 20px 22px 16px;
  border-radius: 14px;
  border: 1px solid var(--awd-border);
  background: var(--awd-surface);
  color: var(--awd-text);
  box-shadow: var(--awd-shadow-lg, 0 24px 64px rgba(35, 32, 26, 0.28));
}
.sfp__head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.sfp__title { flex: 1; font: 600 16px/1.35 var(--awd-font-sans); color: var(--awd-text); }
.sfp__x {
  width: 28px; height: 28px; border-radius: 6px; display: flex; align-items: center; justify-content: center;
  color: var(--awd-text-3); font-size: 18px; cursor: pointer;
}
.sfp__x:hover { background: var(--awd-surface-2); color: var(--awd-text); }
.sfp__desc { display: block; font-size: 13px; line-height: 1.6; color: var(--awd-text-2); margin-bottom: 10px; }
.sfp__tools { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.sfp__search {
  flex: 1; height: 32px; padding: 0 10px; box-sizing: border-box; font-size: 13px;
  border: 1px solid var(--awd-border); border-radius: 8px; background: var(--awd-bg); color: var(--awd-text);
}
.sfp__list {
  flex: 1; min-height: 0; overflow-y: auto;
  border: 1px solid var(--awd-border); border-radius: 10px; background: var(--awd-bg);
}
.sfp__row {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px; cursor: pointer;
  border-bottom: 1px solid var(--awd-border);
}
.sfp__row:last-child { border-bottom: none; }
.sfp__row:hover { background: var(--awd-surface-2); }
.sfp__row.is-on { background: var(--awd-accent-soft); }
.sfp__check {
  flex: none; width: 16px; height: 16px; border-radius: 4px; box-sizing: border-box;
  border: 1.5px solid var(--awd-border-strong); display: flex; align-items: center; justify-content: center;
  font-size: 11px; line-height: 1; color: var(--awd-surface);
}
.sfp__check.is-on { background: var(--awd-accent-text); border-color: var(--awd-accent-text); }
.sfp__main { flex: 1; min-width: 0; }
.sfp__name { display: flex; align-items: center; gap: 6px; min-width: 0; }
.sfp__primary { font-size: 13.5px; color: var(--awd-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sfp__sub { display: block; font-size: 12px; color: var(--awd-text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sfp__tag {
  flex: none; font-size: 11px; line-height: 16px; padding: 0 6px; border-radius: 8px;
  background: var(--awd-surface-3); color: var(--awd-text-2);
}
.sfp__tag--rec { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
.sfp__size { flex: none; font-size: 12px; color: var(--awd-text-3); font-variant-numeric: tabular-nums; }
.sfp__empty { padding: 28px 16px; text-align: center; font-size: 13px; color: var(--awd-text-3); }
.sfp__empty--error { color: var(--awd-danger-text); }
.sfp__hint { display: block; margin-top: 8px; font-size: 12px; line-height: 1.5; color: var(--awd-text-3); }
.sfp__foot { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.sfp__sum { font-size: 12.5px; color: var(--awd-text-2); }
.sfp__sum.is-warn { color: var(--awd-danger-text); }
.sfp__spacer { flex: 1; }
.sfp__btn {
  flex: none; height: 30px; padding: 0 14px; border-radius: 8px; display: flex; align-items: center;
  font-size: 13px; cursor: pointer; user-select: none; white-space: nowrap;
}
.sfp__btn--ghost { border: 1px solid var(--awd-border); color: var(--awd-text); background: var(--awd-surface); }
.sfp__btn--ghost:hover { background: var(--awd-surface-2); }
.sfp__btn--primary { background: var(--awd-accent-text); color: var(--awd-surface); border: 1px solid var(--awd-accent-text); }
.sfp__btn.is-disabled { opacity: 0.5; pointer-events: none; }
</style>
