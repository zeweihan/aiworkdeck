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

<style lang="scss" scoped>
.title-card {
  margin-bottom: 4px;
  padding: 0 4px;
}

.title-card h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  font-family: $awd-font-serif;
  color: $awd-text-on-dark;
  padding: 6px 8px 2px;
}
</style>
