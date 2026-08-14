<template>
  <Transition name="slide-up">
    <div v-if="hasActiveTasks" class="background-task-indicator" :class="{ 'is-minimized': isMinimized }">
      <!-- Minimized Circular View -->
      <div v-if="isMinimized" class="minimized-circle" @click="isMinimized = false" :title="$t('chat.viewTaskDetails')">
        <svg class="progress-ring" viewBox="0 0 40 40">
          <circle
            class="progress-ring__bg"
            stroke="rgba(255, 255, 255, 0.1)"
            stroke-width="3"
            fill="transparent"
            r="16"
            cx="20"
            cy="20"
          />
          <circle
            class="progress-ring__fill"
            :stroke="brandMint"
            stroke-width="3"
            :stroke-dasharray="ringCircumference"
            :stroke-dashoffset="ringDashoffset"
            stroke-linecap="round"
            fill="transparent"
            r="16"
            cx="20"
            cy="20"
          />
        </svg>
        <div class="percentage-text">{{ Math.round(overallProgress) }}%</div>
        <div class="task-count-badge">{{ activeTaskCount }}</div>
      </div>

      <!-- Full Task Panel -->
      <div v-else class="task-panel">
        <div class="panel-header">
          <span class="header-title">
            <i class="fas fa-tasks"></i>
            {{ $t('chat.backgroundTasksHeader') }}
          </span>
          <div class="header-right">
            <span class="task-count">{{ activeTaskCount }}</span>
            <button class="minimize-btn" @click="isMinimized = true" :title="$t('chat.minimize')">
              <span class="minimize-icon"></span>
            </button>
          </div>
        </div>
        
        <div class="task-list">
          <div 
            v-for="task in sortedTasks" 
            :key="task.taskId" 
            class="task-item"
            :class="{ 
              'is-completed': task.status === 'completed',
              'is-failed': task.status === 'failed'
            }"
          >
            <div class="task-header">
              <span class="task-type">{{ getTaskTypeName(task.type) }}</span>
              <span class="task-status" :class="task.status">
                {{ getStatusText(task) }}
              </span>
              <!-- 已结束的任务不再自动消失（改成结果要留着给用户核对），所以必须给一个
                   关闭入口，否则这张卡关不掉。仍在跑的没有关闭按钮：任务与 SSE 无关还在
                   跑，藏起来是骗人。 -->
              <button
                v-if="task.status !== 'running'"
                class="task-dismiss-btn"
                :title="$t('chat.dismissRecord')"
                @click="$emit('dismiss', task.taskId)"
              >×</button>
            </div>
            
            <div class="progress-container">
              <div class="progress-bar">
                <div 
                  class="progress-fill" 
                  :style="{ width: task.progress + '%', background: brandGradient }"
                  :class="{ 
                    'is-indeterminate': task.progress === 0 && task.status === 'running'
                  }"
                ></div>
              </div>
              <span class="progress-text">{{ task.progress }}%</span>
            </div>
            
            <div class="task-message">{{ task.message }}</div>
            
            <div v-if="task.estimatedRemainingSec" class="task-eta">
              {{ $t('chat.etaRemaining', { time: formatTime(task.estimatedRemainingSec) }) }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, ref } from 'vue'
import { t } from '@/i18n'

const props = defineProps({
  backgroundTasks: {
    type: Object,
    default: () => ({})
  },
  lastHeartbeat: {
    type: Object,
    default: null
  }
})

// dismiss：关闭一条已结束的任务记录（载荷 = taskId），由宿主接到
// useAgentStream.dismissBackgroundTask 上
defineEmits(['dismiss'])

const isMinimized = ref(false)
const brandMint = '#5BD197'
const brandForest = '#1A5336'
const brandGradient = 'linear-gradient(90deg, #1A5336, #5BD197)'

const hasActiveTasks = computed(() => {
  return Object.keys(props.backgroundTasks).length > 0
})

const activeTaskCount = computed(() => {
  return Object.values(props.backgroundTasks).filter(t => t.status === 'running').length
})

const sortedTasks = computed(() => {
  return Object.values(props.backgroundTasks).sort((a, b) => {
    // Running tasks first, then by startedAt (newest first)
    if (a.status === 'running' && b.status !== 'running') return -1
    if (a.status !== 'running' && b.status === 'running') return 1
    return (b.startedAt || 0) - (a.startedAt || 0)
  })
})

const overallProgress = computed(() => {
  const runningTasks = Object.values(props.backgroundTasks).filter(t => t.status === 'running')
  if (runningTasks.length === 0) return 100
  const sum = runningTasks.reduce((acc, t) => acc + (t.progress || 0), 0)
  return sum / runningTasks.length
})

const ringCircumference = 2 * Math.PI * 16
const ringDashoffset = computed(() => {
  return ringCircumference * (1 - overallProgress.value / 100)
})

const getTaskTypeName = (type) => {
  const names = {
    'PPTX_GENERATE': t('chat.taskPptGenerate'),
    'PPTX_MODIFY': t('chat.taskPptModify'),
    'FILE_PROCESS': t('chat.taskFileProcess'),
    'WEB_FETCH': t('chat.taskWebFetch'),
    'OTHER': t('chat.taskOther')
  }
  return names[type] || type
}

const getStatusText = (task) => {
  switch (task.status) {
    case 'running': return t('chat.taskRunning')
    case 'completed': return t('chat.done')
    case 'failed': return t('chat.failed')
    case 'cancelled': return t('chat.canceled')
    default: return task.status
  }
}

const formatTime = (seconds) => {
  if (seconds < 60) return t('chat.timeSecondsCompact', { s: seconds })
  const minutes = Math.floor(seconds / 60)
  const secs = seconds % 60
  return secs > 0 ? t('chat.timeMinSec', { m: minutes, s: secs }) : t('chat.timeMinutes', { m: minutes })
}
</script>

<style scoped>
.background-task-indicator {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 1000;
  transition: all 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275);
}

.background-task-indicator.is-minimized {
  bottom: 80px; /* Position it slightly higher to avoid blocking the very bottom corner */
}

/* Full Panel Style */
.task-panel {
  background: rgba(33, 38, 41, 0.9); /* Dark BG #212629 with opacity */
  border: 1px solid rgba(91, 209, 151, 0.2); /* Subtle Mint border */
  border-radius: 12px;
  padding: 16px;
  min-width: 280px;
  max-width: 360px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(12px);
  color: #F8F9FA;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  font-size: 14px;
  font-weight: 600;
  color: #F8F9FA;
}

.header-title i {
  margin-right: 8px;
  color: #5BD197; /* Mint Green */
}

.task-count {
  background: #1A5336; /* Forest Green */
  color: white;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
  min-width: 20px;
  text-align: center;
}

.minimize-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: background 0.2s;
}

.minimize-btn:hover {
  background: rgba(255, 255, 255, 0.05);
}

.minimize-icon {
  width: 12px;
  height: 2px;
  background: #ADB5BD;
  border-radius: 1px;
}

/* Minimized Circle Style */
.minimized-circle {
  width: 48px;
  height: 48px;
  background: #212629;
  border-radius: 50%;
  border: 1px solid rgba(91, 209, 151, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  position: relative;
  transition: transform 0.2s, box-shadow 0.2s;
}

.minimized-circle:hover {
  transform: scale(1.05);
  box-shadow: 0 6px 20px rgba(91, 209, 151, 0.2);
}

.progress-ring {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
}

.progress-ring__fill {
  transition: stroke-dashoffset 0.35s;
  transform: rotate(-90deg);
  transform-origin: 50% 50%;
}

.percentage-text {
  font-size: 10px;
  font-weight: 700;
  color: #5BD197;
  z-index: 1;
}

.task-count-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  background: #E74C3C;
  color: white;
  font-size: 9px;
  font-weight: 700;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #212629;
}

/* Task List Items */
.task-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 300px;
  overflow-y: auto;
  padding-right: 4px;
}

/* Scrollbar styling */
.task-list::-webkit-scrollbar {
  width: 4px;
}
.task-list::-webkit-scrollbar-track {
  background: transparent;
}
.task-list::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 2px;
}

.task-item {
  padding: 12px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  transition: all 0.3s ease;
}

.task-item.is-completed {
  border-color: rgba(91, 209, 151, 0.2);
  background: rgba(91, 209, 151, 0.05);
}

.task-item.is-failed {
  border-color: rgba(231, 76, 60, 0.2);
  background: rgba(231, 76, 60, 0.05);
}

.task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.task-type {
  font-size: 13px;
  font-weight: 600;
  color: #FFFFFF;
  /* 状态与关闭按钮靠右成一组，所以标题吃掉剩余宽度 */
  flex: 1;
}

.task-dismiss-btn {
  margin-left: 8px;
  padding: 0 4px;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.5);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.task-dismiss-btn:hover {
  color: #FFFFFF;
}

.task-status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}

.task-status.running {
  background: rgba(91, 209, 151, 0.15);
  color: #5BD197;
}

.task-status.completed {
  background: rgba(91, 209, 151, 0.1);
  color: #5BD197;
}

.task-status.failed {
  background: rgba(231, 76, 60, 0.15);
  color: #E74C3C;
}

.progress-container {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.progress-bar {
  flex: 1;
  height: 5px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s ease;
}

.progress-fill.is-indeterminate {
  width: 30% !important;
  animation: indeterminate 1.5s infinite ease-in-out;
}

@keyframes indeterminate {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(350%); }
}

.progress-text {
  font-size: 11px;
  color: #ADB5BD;
  min-width: 30px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.task-message {
  font-size: 12px;
  color: #ADB5BD;
  margin-bottom: 4px;
  line-height: 1.4;
}

.task-eta {
  font-size: 10px;
  color: #6C757D;
}

/* Transition animations */
.slide-up-enter-active,
.slide-up-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.slide-up-enter-from,
.slide-up-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
}
</style>
