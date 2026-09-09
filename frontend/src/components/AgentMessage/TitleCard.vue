<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <div class="title-card" v-if="title">
    <h3>{{ title }}</h3>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' }
})
</script>

<style scoped>
.title-card {
  margin-bottom: 4px;
  padding: 0 4px;
}

.title-card h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
  padding: 6px 8px 2px;
}
</style>
