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
    <!-- 电平条：贴着胶囊底边的细条，靠 .mri-pill 的 overflow:hidden 借用胶囊自身的圆角裁边，
         不占布局高度（反馈8，与面板里 mr-level-bar 同一份数据源 recorderState.level） -->
    <span class="mri-level-track">
      <span class="mri-level-fill" :style="{ width: Math.round(state.level * 100) + '%' }"></span>
    </span>
  </div>
</template>

<script>
import {
  recorderState, stopRecording, pauseRecording, resumeRecording, formatSeconds, isRecordingActive
} from '@/utils/meetingRecorder.js'

export default {
  name: 'MeetingRecordingIndicator',
  data() {
    return { state: recorderState }
  },
  computed: {
    // 复用 meetingRecorder.js 的权威判据，别再手写一份状态枚举——之前这里漏了
    // 'starting'，窗口期胶囊不出现，但面板已经判定"有项目在录音"，用户被指去
    // 停止一个还看不见的胶囊。
    visible() {
      return isRecordingActive()
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
  /* 几何约束是刻意的，别为了「居中好看」把 top 调大（反馈9）：
     桌面端浏览器面板用 Electron 原生 BrowserView 渲染，独立合成层，不参与 DOM
     z-index/点击命中——胶囊只要有一点落进顶部工具条下方的 BrowserView 区域，
     那部分按钮点击就会被原生层吃掉，点了没反应。顶部工具条（.project-header）
     高度 42px（compact 模式 48px），胶囊必须整体落在这条 DOM 安全带内：
     top:4px + height:32px = 底边 36px，留出 ≥6px 余量，不贴边、不相交。 */
  position: fixed;
  top: 4px;
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
  overflow: hidden; /* 裁出电平条贴底边的那一小截，借用胶囊自身的圆角；
                       position:fixed 本身即为绝对定位子元素（电平条）建立定位基准 */
}

.mri-level-track {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 3px;
  background: transparent;
}

.mri-level-fill {
  display: block;
  height: 100%;
  max-width: 100%;
  background: #1A5336;
  transition: width 0.15s linear;
}

.mri-pill.paused .mri-level-fill {
  background: #B0B4BA;
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
