<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  AI 输入框的模型下拉（dev-board#853 从 ChatInterface 两处重复分支里抽出来）。

  纯展示：清单、当前选中、价格口径全部由 ChatInterface 按 props 传入，选中只 emit('select')，
  持久化与「看不了图」提示仍在 ChatInterface.selectModel 里——这里不碰任何状态。
  清单唯一来源是 GET /api/ai/models，本组件**不许**硬编码模型或自己算档位。

  价格：每行只写两个数（前输入、后输出），单位与口径统一放到底部脚注；
  贵贱层级靠价格数字的颜色深浅 + 字重 + 一个 4 格小条，档位名称放在悬停提示与脚注里。
-->
<template>
  <view class="model-dropdown" :class="placement" role="listbox">
    <view v-for="g in groups" :key="g.key" class="model-group">
      <view class="model-group-head">
        <text class="model-group-vendor">{{ g.vendor }}</text>
        <text v-if="g.region === 'INTERNATIONAL'" class="model-region-tag">{{ $t('chat.intlNetworkRequired') }}</text>
      </view>
      <view v-for="m in g.models" :key="m.id"
            class="model-option"
            :class="{ active: currentModelId === m.id }"
            role="option" tabindex="0" :aria-selected="currentModelId === m.id ? 'true' : 'false'"
            @keydown.enter.stop="onOptionKey($event, m)"
            @keydown.space.stop="onOptionKey($event, m)"
            @tap.stop="$emit('select', m)">
        <view class="model-option-row">
          <text class="model-option-name">{{ m.name }}</text>
          <view class="model-option-price" :class="'lv-' + priceLevelOf(m)"
                :title="levelTitle(m)" :aria-label="priceAria(m)">
            <view v-if="priceLevelOf(m)" class="model-level-bar" aria-hidden="true">
              <text v-for="i in levelCount" :key="i" class="model-level-cell" :class="{ on: i <= priceLevelOf(m) }"></text>
            </view>
            <text class="model-price-num">{{ priceOf(m).input }}</text>
            <text class="model-price-sep">/</text>
            <text class="model-price-num">{{ priceOf(m).output }}</text>
          </view>
        </view>
        <view v-if="m.tiered || m.vision === false" class="model-option-tags">
          <text v-if="m.tiered" class="model-tier-tag">{{ $t('chat.tieredPricing') }}</text>
          <!-- 严格判 false：vision 缺字段是「未知」，标出来等于造谣 -->
          <text v-if="m.vision === false" class="model-novision-tag">{{ $t('chat.noVisionTag') }}</text>
        </view>
      </view>
    </view>
    <view v-if="!groups.length" class="model-empty">{{ $t('chat.noModels') }}</view>
    <view class="model-footnote">
      <template v-if="groups.length">
        <text class="model-footnote-line">{{ unitLine }}</text>
        <text class="model-footnote-line">{{ basisLine }}</text>
        <text class="model-footnote-line">{{ $t('chat.modelPriceLevelLegend') }}</text>
      </template>
      <text v-if="networkRegionBasis" class="model-footnote-line">{{ $t('chat.networkBasis', { basis: networkRegionBasis }) }}</text>
    </view>
  </view>
</template>

<script>
import {
  PRICE_LEVEL_COUNT,
  formatFactor,
  formatRateDate,
  modelPriceTexts,
  normalizePriceDisplay,
  priceLevelOf,
} from '@/utils/modelPricing.js'

export default {
  name: 'ModelSelectorDropdown',
  props: {
    groups: { type: Array, default: () => [] },
    currentModelId: { type: String, default: '' },
    // GET /api/ai/models 的 priceDisplay 原样传入；旧后端没有这个字段时按美元标价
    priceDisplay: { type: Object, default: null },
    networkRegionBasis: { type: String, default: '' },
    // 'down'：新对话页（输入框在上方）；'up'：对话中（输入框在底部）
    placement: { type: String, default: 'up' },
  },
  emits: ['select'],
  data() {
    return { levelCount: PRICE_LEVEL_COUNT }
  },
  computed: {
    display() {
      return normalizePriceDisplay(this.priceDisplay)
    },
    unitLine() {
      return this.$t(this.display.currency === 'CNY' ? 'chat.modelPriceUnitCny' : 'chat.modelPriceUnitUsd')
    },
    basisLine() {
      const d = this.display
      if (d.basis === 'charged') {
        const key = d.currency === 'CNY' ? 'chat.modelPriceChargedCny' : 'chat.modelPriceChargedUsd'
        const sourceKey = { live: 'chat.modelPriceSourceLive', manual: 'chat.modelPriceSourceManual', default: 'chat.modelPriceSourceDefault' }[d.rateSource]
        const date = formatRateDate(d.rateUpdatedAt)
        const meta = [sourceKey ? this.$t(sourceKey) : '', date ? this.$t('chat.modelPriceUpdatedOn', { date }) : '']
          .filter(Boolean).join(this.$t('chat.modelPriceMetaSep'))
        const split = d.exchangeRate && d.marginMultiplier
        const main = split
          ? this.$t(d.currency === 'CNY' ? 'chat.modelPriceChargedSplitCny' : 'chat.modelPriceChargedSplitUsd',
            { rate: formatFactor(d.exchangeRate), margin: formatFactor(d.marginMultiplier) })
          : this.$t(key, { rate: formatFactor(d.factor) })
        return meta ? this.$t('chat.modelPriceWithMeta', { main, meta }) : main
      }
      const listKey = {
        platform: 'chat.modelPriceListPlatform',
        byok: 'chat.modelPriceListByok',
        local: 'chat.modelPriceListLocal',
      }[d.channel] || 'chat.modelPriceListUnknown'
      return this.$t(listKey)
    },
  },
  methods: {
    priceLevelOf,
    priceOf(m) {
      return modelPriceTexts(m, this.display)
    },
    levelTitle(m) {
      const lv = priceLevelOf(m)
      return lv ? this.$t('chat.modelPriceLevelTitle', { name: this.$t(`chat.modelPriceLevel${lv}`) }) : ''
    },
    priceAria(m) {
      const p = this.priceOf(m)
      return this.$t('chat.modelPriceAria', { input: p.input, output: p.output })
    },
    onOptionKey(e, m) {
      e.preventDefault()
      this.$emit('select', m)
    },
  },
}
</script>

<style scoped>
.model-dropdown {
  position: absolute;
  /* 锚点是 .input-card（position:relative，见 ChatInterface 里 .input-card 的定义）而不是
     .model-selector 自己——固定 268px 的 min-width 摆在只有内容宽的选择器上，AI 面板收到最窄
     240px 时无论往哪边对齐都放不下，会被 .chat-interface 的 overflow:hidden 裁掉一截。
     改成跟随输入卡自身宽度（left/right 都钉到 0），永不溢出。 */
  left: 0;
  right: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  z-index: 1001;
  min-width: 0;
  max-height: 320px;
  overflow-y: auto;
  padding: 4px 0;
  /* 下拉挂在输入栏工具条里，祖先有 white-space:nowrap，不重置的话脚注整句不换行、撑出横向滚动 */
  white-space: normal;
}
/* 向下展开 (新对话页面) */
.model-dropdown.down {
  top: calc(100% + 4px);
}
/* 向上展开 (对话中) */
.model-dropdown.up {
  bottom: calc(100% + 4px);
}

/* ===== 按厂商分组，国际档在后并标注需国际网络 ===== */
.model-group + .model-group {
  border-top: 1px solid var(--awd-border-subtle);
}
.model-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px 2px;
}
.model-group-vendor {
  font-size: 11px;
  color: var(--awd-text-3);
  letter-spacing: 0.5px;
}
.model-region-tag {
  font-size: 10px;
  color: var(--awd-warning-text);
  background: var(--awd-warning-soft);
  border-radius: 3px;
  padding: 1px 4px;
}

.model-option {
  padding: 6px 14px;
  cursor: pointer;
  font-size: 13px;
  color: var(--awd-text);
  transition: background 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.model-option:hover {
  background: var(--awd-accent-soft);
}
.model-option.active {
  color: var(--awd-accent-text);
  font-weight: 500;
  background: var(--awd-accent-wash);
}
.model-option-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.model-option-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 窄面板下标签放第二行，允许换行，不许把 tag 裁掉 */
.model-option-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.model-tier-tag,
.model-novision-tag {
  /* 同一档中性灰，刻意不用告警色：读不了图会自动降级 OCR、长上下文涨价是计价差异，都不是错误 */
  font-size: 10px;
  font-weight: 400;
  color: var(--awd-text-2);
  background: var(--awd-surface-3);
  border-radius: 3px;
  padding: 1px 4px;
}

/* ===== 价格列：两个数，等宽数字，右对齐成一列 ===== */
.model-option-price {
  flex: none;
  display: flex;
  align-items: baseline;
  white-space: nowrap;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum' 1;
}
.model-price-num {
  /* 固定最小宽度 + 右对齐：各行的「/」落在同一竖线上 */
  display: inline-block;
  min-width: 3.4em;
  text-align: right;
}
.model-price-num + .model-price-sep + .model-price-num {
  min-width: 3.3em;
}
.model-price-sep {
  padding: 0 3px;
  color: var(--awd-text-3);
  font-weight: 400;
}
/* 贵贱层级：颜色由浅到深 + 字重由轻到重。只用墨色一族令牌——
   红绿会被读成「好/坏」，竹月青（--awd-mint）对浅底只有 2.6:1 承载不了文字。
   选中行（.active 把整行染成 accent）不影响这里：价格颜色显式给定。 */
.model-option-price.lv-0,
.model-option-price.lv-1 { color: var(--awd-text-3); font-weight: 400; }
.model-option-price.lv-2 { color: var(--awd-text-2); font-weight: 400; }
.model-option-price.lv-3 { color: var(--awd-text); font-weight: 500; }
.model-option-price.lv-4 { color: var(--awd-text); font-weight: 700; }

.model-level-bar {
  display: inline-flex;
  align-items: flex-end;
  gap: 1px;
  margin-right: 4px;
  align-self: center;
}
.model-level-cell {
  display: inline-block;
  width: 3px;
  height: 7px;
  border-radius: 1px;
  background: var(--awd-border-subtle);
}
.model-level-cell.on {
  background: currentColor;
}

.model-empty {
  padding: 10px 14px;
  font-size: 12px;
  color: var(--awd-text-3);
}
/* 统一脚注：单位、口径、档位含义、网络判定，一处说清，不逐行重复 */
.model-footnote {
  border-top: 1px solid var(--awd-border-subtle);
  margin-top: 4px;
  padding: 6px 14px 2px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.model-footnote:empty {
  display: none;
}
.model-footnote-line {
  display: block;
  font-size: 10px;
  /* 单位与口径是读懂每一行那两个数的前提，用 text-2 而不是更淡的 text-3 */
  color: var(--awd-text-2);
  line-height: 1.5;
}
</style>
