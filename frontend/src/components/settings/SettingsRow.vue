<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  设置页里「左标签右控件」的一行（dev-board#892）。网格 minmax(160px,220px) 1fr，
  行间 1px 底线，行高最小 36px；窄容器（<720px，用容器查询而不是视口宽度——
  设置页可能嵌在工作台标签里，也可能是独立页，二者视口宽不一样）折成上下两行。
  纯展示组件，不带业务逻辑。
-->
<template>
  <view class="awd-set-row">
    <view class="awd-set-row-label-wrap">
      <text class="awd-set-row-label">{{ label }}</text>
      <text v-if="hint" class="awd-set-row-hint">{{ hint }}</text>
    </view>
    <view class="awd-set-row-control">
      <slot />
    </view>
  </view>
</template>

<script>
export default {
  name: 'SettingsRow',
  props: {
    label: { type: String, default: '' },
    hint: { type: String, default: '' },
  },
}
</script>

<style lang="scss" scoped>
.awd-set-row {
  display: grid;
  grid-template-columns: minmax(160px, 220px) 1fr;
  align-items: center;
  gap: 8px 16px;
  min-height: 36px;
  padding: 8px 0;
  border-bottom: 1px solid var(--awd-border);

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }

  &:first-child {
    padding-top: 0;
  }
}

.awd-set-row-label-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.awd-set-row-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--awd-text);
}

.awd-set-row-hint {
  display: block;
  font-size: 12px;
  line-height: 1.5;
  color: var(--awd-text-3);
}

.awd-set-row-control {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 容器查询而非媒体查询：设置页两个宿主（工作台标签 / 独立页）视口宽不同，
   决定要不要折行的是这一行所在容器的实测宽度。父级需声明
   container-type: inline-size; container-name: awd-settings（settings.scss 里
   .awd-set-container 已经给了）。 */
@container awd-settings (max-width: 720px) {
  .awd-set-row {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
}
</style>
