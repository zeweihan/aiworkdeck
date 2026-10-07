<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="pfp" @tap.stop>
    <view class="pfp-head">
      <text class="pfp-title">{{ $t('editor.format.title') }}</text>
      <text class="pfp-scope">{{ hasSelection ? $t('editor.format.scopeSelection') : $t('editor.format.scopeParagraph') }}</text>
    </view>

    <!-- 一键预设：点了立即作用于当前段落/选中段落 -->
    <view class="pfp-presets">
      <text v-for="p in PRESETS" :key="p.key" class="pfp-chip" @tap.stop="usePreset(p.key)">{{ $t('editor.format.presets.' + p.key) }}</text>
    </view>

    <view class="pfp-grid">
      <text class="pfp-l">{{ $t('editor.format.alignment') }}</text>
      <view class="pfp-seg">
        <text v-for="a in ALIGNMENTS" :key="a" class="pfp-seg-b" :class="{ on: form.alignment === a }"
              @tap.stop="form.alignment = a">{{ $t('editor.format.align.' + a) }}</text>
      </view>

      <text class="pfp-l">{{ $t('editor.format.indentMode') }}</text>
      <view class="pfp-row">
        <view class="pfp-seg">
          <text v-for="m in INDENT_MODES" :key="m" class="pfp-seg-b" :class="{ on: form.indentMode === m }"
                @tap.stop="form.indentMode = m">{{ $t('editor.format.indent.' + m) }}</text>
        </view>
        <input v-if="form.indentMode !== 'none'" class="pfp-num" type="digit" v-model="form.indentChars" @click.stop />
        <text v-if="form.indentMode !== 'none'" class="pfp-u">{{ $t('editor.format.unitChars') }}</text>
      </view>

      <text class="pfp-l">{{ $t('editor.format.leftRight') }}</text>
      <view class="pfp-row">
        <input class="pfp-num" type="digit" v-model="form.leftChars" @click.stop />
        <text class="pfp-u">{{ $t('editor.format.unitChars') }}</text>
        <text class="pfp-dash">/</text>
        <input class="pfp-num" type="digit" v-model="form.rightChars" @click.stop />
        <text class="pfp-u">{{ $t('editor.format.unitChars') }}</text>
      </view>

      <text class="pfp-l">{{ $t('editor.format.spacing') }}</text>
      <view class="pfp-row">
        <text class="pfp-u">{{ $t('editor.format.before') }}</text>
        <input class="pfp-num" type="digit" v-model="form.spaceBeforePt" @click.stop />
        <text class="pfp-u">{{ $t('editor.format.after') }}</text>
        <input class="pfp-num" type="digit" v-model="form.spaceAfterPt" @click.stop />
        <text class="pfp-u">{{ $t('editor.format.unitPt') }}</text>
      </view>

      <text class="pfp-l">{{ $t('editor.format.lineSpacing') }}</text>
      <view class="pfp-row">
        <view class="pfp-seg wrap">
          <text v-for="m in LINE_MODES" :key="m" class="pfp-seg-b" :class="{ on: form.lineMode === m }"
                @tap.stop="setLineMode(m)">{{ $t('editor.format.line.' + (m === '1.5' ? 'oneHalf' : m)) }}</text>
        </view>
        <input v-if="lineNeedsValue" class="pfp-num" type="digit" v-model="form.lineValue" @click.stop />
        <text v-if="lineNeedsValue" class="pfp-u">{{ form.lineMode === 'multiple' ? $t('editor.format.unitTimes') : $t('editor.format.unitPt') }}</text>
      </view>
    </view>

    <text v-if="err" class="pfp-err">{{ err }}</text>
    <view class="pfp-acts">
      <text class="pfp-b" @tap.stop="load">{{ $t('editor.format.reset') }}</text>
      <text class="pfp-b ok" :class="{ busy }" @tap.stop="apply">{{ $t('editor.format.apply') }}</text>
    </view>

    <!-- 全文一键排版：所内标准（确定性原语）/ 交给 AI 按描述调整 -->
    <view class="pfp-sec">
      <text class="pfp-sec-t">{{ $t('editor.format.wholeDoc') }}</text>
      <view class="pfp-row">
        <text class="pfp-b" @tap.stop="houseStyle">{{ $t('editor.format.houseStyle') }}</text>
        <text class="pfp-hint">{{ $t('editor.format.houseStyleHint') }}</text>
      </view>
      <textarea class="pfp-ta" v-model="aiText" :placeholder="$t('editor.format.aiPlaceholder')" @click.stop />
      <view class="pfp-presets">
        <text v-for="k in AI_CHIPS" :key="k" class="pfp-chip sm" @tap.stop="aiText = $t('editor.format.aiChips.' + k)">{{ $t('editor.format.aiChipLabels.' + k) }}</text>
      </view>
      <view class="pfp-acts">
        <text class="pfp-b ai" @tap.stop="askAi">{{ $t('editor.format.askAi') }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 段落格式快捷面板（编辑器工具栏「段落」下拉的内容体）。
// 用户反馈：首行缩进 / 缩进方式 / 段前段后「不知道在哪」——LO 的段落对话框在本 WASM
// 构建上键盘关不掉（doc-editor.md「LO 对话框不许挂进自建菜单」），所以自建 DOM 面板。
// 读：get_formatting（当前段落）；写：set_paragraph_format（作用于光标所在/选中的段落，
// 修订开着时同样记格式修订）。单位换算与预设全在 utils/paragraphFormat.js（纯函数，有单测）。
// 「全文」一节：apply_house_style（所内标准，确定性原语，可撤销）与「交给 AI」——后者
// 只把需求拼成一句话交给宿主发到 AI 对话（AI 用 doc_set_paragraph_format 等工具落地）。
import {
  ALIGNMENTS, INDENT_MODES, LINE_MODES, PARAGRAPH_PRESETS,
  emptyForm, formFromFormatting, paramsFromForm, applyPreset,
} from '@/utils/paragraphFormat.js'

const AI_CHIPS = ['official', 'contract', 'brief']

export default {
  name: 'ParagraphFormatPanel',
  emits: ['applied', 'ai-format', 'house-style'],
  props: {
    // (action, params) => Promise<result>，由工具栏注入（同一个 executor）。
    call: { type: Function, required: true },
    hasSelection: { type: Boolean, default: false },
  },
  data() {
    return { form: emptyForm(), chPt: 12, err: '', busy: false, aiText: '' }
  },
  computed: {
    ALIGNMENTS: () => ALIGNMENTS, INDENT_MODES: () => INDENT_MODES,
    PRESETS: () => PARAGRAPH_PRESETS, AI_CHIPS: () => AI_CHIPS,
    LINE_MODES: () => LINE_MODES,
    lineNeedsValue() { return ['multiple', 'atLeast', 'exactly'].includes(this.form.lineMode) },
  },
  mounted() { this.load() },
  methods: {
    async load() {
      this.err = ''
      const r = await this.call('get_formatting', {})
      if (!r || r.success === false) { this.err = (r && r.message) || this.$t('editor.format.readFailed'); return }
      const { form, chPt } = formFromFormatting(r)
      this.form = form
      this.chPt = chPt
    },
    setLineMode(m) {
      const defaults = { multiple: 1.25, atLeast: 12, exactly: 28 }
      if (m !== this.form.lineMode && defaults[m] != null) this.form.lineValue = defaults[m]
      this.form.lineMode = m
    },
    async apply() {
      if (this.busy) return null
      this.err = ''
      const { params, error } = paramsFromForm(this.form, this.chPt)
      if (error) { this.err = this.$t('editor.format.' + error); return null }
      this.busy = true
      try {
        const r = await this.call('set_paragraph_format', params)
        if (!r || r.success !== true) { this.err = (r && r.message) || this.$t('editor.format.applyFailed'); return r }
        this.$emit('applied', r)
        return r
      } finally { this.busy = false }
    },
    usePreset(key) {
      this.form = applyPreset(this.form, key)
      return this.apply()
    },
    houseStyle() { this.$emit('house-style') },
    askAi() {
      this.$emit('ai-format', { requirement: String(this.aiText || '').trim(), scope: this.hasSelection ? 'selection' : 'document' })
    },
  },
}
</script>

<style scoped>
.pfp { display: flex; flex-direction: column; gap: 8px; padding: 4px 4px 2px; }
.pfp-head { display: flex; align-items: baseline; justify-content: space-between; }
.pfp-title { font-size: 13px; font-weight: 600; color: var(--awd-text); }
.pfp-scope { font-size: 11px; color: var(--awd-text-3); }
.pfp-presets { display: flex; flex-wrap: wrap; gap: 5px; }
.pfp-chip { padding: 3px 8px; border: 1px solid var(--awd-border); border-radius: 11px; font-size: 11px;
  color: var(--awd-text-2); background: var(--awd-surface); }
.pfp-chip:hover { border-color: var(--awd-mint); color: var(--awd-accent-text); }
.pfp-chip.sm { padding: 2px 7px; font-size: 10.5px; }
.pfp-grid { display: grid; grid-template-columns: 64px 1fr; align-items: center; gap: 7px 8px; }
.pfp-l { font-size: 12px; color: var(--awd-text-2); }
.pfp-row { display: flex; align-items: center; gap: 5px; flex-wrap: wrap; }
.pfp-seg { display: flex; border: 1px solid var(--awd-border); border-radius: 5px; overflow: hidden; }
.pfp-seg-b { padding: 3px 7px; font-size: 11.5px; color: var(--awd-text-2); border-right: 1px solid var(--awd-border); }
.pfp-seg-b:last-child { border-right: none; }
.pfp-seg-b.on { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
.pfp-num { width: 44px; height: 24px; padding: 0 5px; box-sizing: border-box; font-size: 12px; color: var(--awd-text);
  border: 1px solid var(--awd-border); border-radius: 4px; background: var(--awd-surface); }
.pfp-u { font-size: 11px; color: var(--awd-text-3); }
.pfp-dash { font-size: 11px; color: var(--awd-text-3); padding: 0 2px; }
.pfp-seg.wrap { flex-wrap: wrap; }
.pfp-acts { display: flex; justify-content: flex-end; gap: 6px; }
.pfp-b { padding: 3px 11px; border: 1px solid var(--awd-border); border-radius: 5px; font-size: 12px; color: var(--awd-text-2); }
.pfp-b:hover { border-color: var(--awd-border-strong); }
.pfp-b.ok { border-color: var(--awd-mint); color: var(--awd-accent-text); background: var(--awd-accent-soft); }
.pfp-b.ai { border-color: var(--awd-mint); color: var(--awd-accent-text); }
.pfp-b.busy { opacity: 0.6; }
.pfp-err { padding: 4px 7px; border-radius: 5px; background: var(--awd-danger-soft); color: var(--awd-danger-text); font-size: 11px; }
.pfp-sec { display: flex; flex-direction: column; gap: 6px; padding-top: 8px; border-top: 1px solid var(--awd-border); }
.pfp-sec-t { font-size: 12px; font-weight: 600; color: var(--awd-text-2); }
.pfp-hint { font-size: 10.5px; color: var(--awd-text-3); flex: 1; min-width: 0; }
.pfp-ta { width: 100%; height: 54px; padding: 5px 7px; box-sizing: border-box; font-size: 12px; line-height: 1.45; color: var(--awd-text);
  border: 1px solid var(--awd-border); border-radius: 5px; background: var(--awd-surface); }
</style>
