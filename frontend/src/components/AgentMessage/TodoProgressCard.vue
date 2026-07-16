<template>
  <div class="todo-progress-card" v-if="todos && todos.length > 0">
    <div class="todo-header" @click="isCollapsed = !isCollapsed">
      <div class="todo-header-left">
        <span class="todo-title">任务进度</span>
        <span class="todo-counter">{{ completedCount }}/{{ todos.length }}</span>
      </div>
      <div class="todo-header-right">
        <span v-if="currentTodo" class="todo-current-hint">{{ currentTodo.activeForm || currentTodo.content }}</span>
        <div class="chevron" :class="{ collapsed: isCollapsed }">
          <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </div>
      </div>
    </div>
    <div class="todo-list" v-if="!isCollapsed">
      <div v-for="(todo, idx) in todos" :key="idx" class="todo-row" :class="todo.status">
        <span class="todo-marker">
          <svg v-if="todo.status === 'completed'" xmlns="http://www.w3.org/2000/svg" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>
          <span v-else-if="todo.status === 'in_progress'" class="marker-spinner"></span>
          <svg v-else-if="todo.status === 'failed'" xmlns="http://www.w3.org/2000/svg" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
          <span v-else class="marker-dot"></span>
        </span>
        <span class="todo-text">{{ todo.status === 'in_progress' ? (todo.activeForm || todo.content) : todo.content }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  todos: { type: Array, default: () => [] }
})

const isCollapsed = ref(false)

const completedCount = computed(() => props.todos.filter(t => t.status === 'completed').length)
const currentTodo = computed(() => props.todos.find(t => t.status === 'in_progress') || null)
</script>

<style scoped>
.todo-progress-card {
  background: #ffffff;
  border: 1px solid #E9ECEF;
  border-radius: 8px;
  margin: 0 0 6px 0;
  overflow: hidden;
}

.todo-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 10px;
  cursor: pointer;
  background: #F8F9FA;
}

.todo-header-left {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.todo-title {
  font-size: 11px;
  font-weight: 600;
  color: #1A5336; /* Forest Green */
}

.todo-counter {
  font-size: 10px;
  font-weight: 600;
  color: #1A5336;
  background: #E6F9F0; /* Mint Lightest */
  padding: 0 6px;
  border-radius: 99px;
}

.todo-header-right {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.todo-current-hint {
  font-size: 10px;
  color: #6C757D;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}

.chevron {
  color: #ADB5BD;
  display: flex;
  transition: transform 0.2s ease;
}

.chevron.collapsed {
  transform: rotate(-90deg);
}

.todo-list {
  padding: 4px 10px 5px;
  max-height: 132px;
  overflow-y: auto;
}

.todo-row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 2px 0;
}

.todo-marker {
  width: 12px;
  height: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

.todo-row.completed .todo-marker { color: #5BD197; }
.todo-row.failed .todo-marker { color: #E74C3C; }

.marker-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #DEE2E6;
}

.marker-spinner {
  width: 8px;
  height: 8px;
  border: 1.5px solid #5BD197;
  border-top-color: transparent;
  border-radius: 50%;
  animation: todo-spin 0.8s linear infinite;
}

@keyframes todo-spin {
  to { transform: rotate(360deg); }
}

.todo-text {
  font-size: 11px;
  line-height: 1.45;
  color: #2C3338;
}

.todo-row.completed .todo-text {
  color: #ADB5BD;
  text-decoration: line-through;
}

.todo-row.in_progress .todo-text {
  color: #1A5336;
  font-weight: 500;
}

.todo-row.failed .todo-text {
  color: #C0392B;
}
</style>
