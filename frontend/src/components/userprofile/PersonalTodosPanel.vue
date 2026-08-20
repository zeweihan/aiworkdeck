<!--
  「我的代办」栏目（UI 占位，后端尚无待办实体）。2026-08-20 从
  components/userprofile/UserProfilePane.vue 原样搬出，内容一行没改。
-->
<template>
  <view class="panel-placeholder">
    <view class="empty-state">
      <view class="empty-icon-circle">
        <svg class="empty-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.docText" :key="gi" :d="d" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
      <text class="empty-title">{{ $t('account.tabTodos') }}</text>
      <text class="empty-desc">{{ $t('account.emptyTodosDesc') }}</text>
    </view>
  </view>
</template>

<script>
import { ICONS } from '@/config/icons.js'

export default {
  name: 'PersonalTodosPanel',
  computed: {
    ICONS() { return ICONS },
  },
}
</script>

<style lang="scss" scoped>
.panel-placeholder {
  background: #FFFFFF;
  border-radius: 12px;
  min-height: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 12px rgba(18, 52, 77, 0.04);
}

.empty-icon {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  color: #C9D4CE;
}
</style>
