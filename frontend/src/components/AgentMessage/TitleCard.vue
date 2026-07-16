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
  color: #2c3e50;
  padding: 6px 8px 2px;
}
</style>
