<template>
  <!-- 挂在页面树之外（见 utils/recordingIndicator.js），只能用原生标签，禁 uni 组件 -->
  <div v-if="visible" class="mri-pill" :class="{ paused: state.status === 'paused' }">
    <span class="mri-dot"></span>
    <span class="mri-time">{{ timeText }}</span>
    <span class="mri-label">{{ state.status === 'paused' ? '已暂停' : '录音中' }}</span>
    <button class="mri-btn" type="button" @click="togglePause">
      {{ state.status === 'paused' ? '继续' : '暂停' }}
    </button>
    <button class="mri-btn stop" type="button" :disabled="state.status === 'stopping'" @click="stop">
      {{ state.status === 'stopping' ? '保存中' : '停止' }}
    </button>
  </div>
</template>

<script>
import {
  recorderState, stopRecording, pauseRecording, resumeRecording, formatSeconds
} from '@/utils/meetingRecorder.js'

export default {
  name: 'MeetingRecordingIndicator',
  data() {
    return { state: recorderState }
  },
  computed: {
    visible() {
      return this.state.status === 'recording' || this.state.status === 'paused'
        || this.state.status === 'stopping'
    },
    timeText() {
      return formatSeconds(this.state.seconds)
    }
  },
  methods: {
    togglePause() {
      if (this.state.status === 'paused') resumeRecording()
      else pauseRecording()
    },
    async stop() {
      const meeting = await stopRecording()
      // 面板（若开着）靠这个事件刷新列表；uni.* API 在页面树外可用
      try { uni.$emit('awd:meeting-recording-stopped', meeting) } catch (e) { /* ignore */ }
    }
  }
}
</script>

<style scoped>
.mri-pill {
  position: fixed;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 99997; /* 低于反馈浮窗（99998），互不遮挡（一个在顶部一个在右下） */
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 12px;
  background: #FFFFFF;
  border: 1px solid #E5E7EB;
  border-radius: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 12px;
  color: #333333;
}

.mri-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #E5484D;
  animation: mri-pulse 1.2s ease-in-out infinite;
}

.mri-pill.paused .mri-dot {
  animation: none;
  background: #B0B4BA;
}

@keyframes mri-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.mri-time {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.mri-label {
  color: #6B7280;
}

.mri-btn {
  border: 1px solid #D1D5DB;
  background: #FFFFFF;
  color: #374151;
  border-radius: 10px;
  height: 22px;
  padding: 0 10px;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
}

.mri-btn:hover {
  background: #F3F4F6;
}

.mri-btn.stop {
  border-color: #E5484D;
  color: #E5484D;
}

.mri-btn.stop:hover {
  background: #FDF2F2;
}

.mri-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
