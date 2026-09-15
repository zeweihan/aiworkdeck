<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <!-- 薄壳页：navigationStyle custom，仅提供整页高度容器；本体在 components/MarketPane.vue -->
  <view class="page-plugin-market">
    <MarketPane :standalone="true" />
  </view>
</template>

<script>
import MarketPane from '@/components/MarketPane.vue'

export default {
  name: 'PluginMarketPage',
  components: { MarketPane },
}
</script>

<style lang="scss" scoped>
.page-plugin-market {
  height: 100vh;
  overflow: hidden;
}
</style>
