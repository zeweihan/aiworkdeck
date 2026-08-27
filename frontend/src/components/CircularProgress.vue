<template>
  <view class="circular-progress" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
      <circle
        class="progress-bg"
        :cx="center"
        :cy="center"
        :r="radius"
        stroke="#e5e7eb"
        :stroke-width="strokeWidth"
        fill="none"
      />
      <circle
        class="progress-bar"
        :cx="center"
        :cy="center"
        :r="radius"
        :stroke="color"
        :stroke-width="strokeWidth"
        fill="none"
        :stroke-dasharray="circumference"
        :stroke-dashoffset="dashOffset"
        stroke-linecap="round"
        transform="rotate(-90)"
        :transform-origin="`${center} ${center}`"
      />
    </svg>
    <view class="progress-content">
      <slot></slot>
    </view>
  </view>
</template>

<script>
export default {
  name: 'CircularProgress',
  props: {
    percentage: {
      type: Number,
      default: 0
    },
    size: {
      type: Number,
      default: 40
    },
    strokeWidth: {
      type: Number,
      default: 4
    },
    color: {
      type: String,
      default: '#2563eb'
    }
  },
  computed: {
    center() {
      return this.size / 2
    },
    radius() {
      return (this.size - this.strokeWidth) / 2
    },
    circumference() {
      return 2 * Math.PI * this.radius
    },
    dashOffset() {
      return this.circumference * (1 - this.percentage / 100)
    }
  }
}
</script>

<style scoped>
.circular-progress {
  position: relative;
  display: inline-flex;
  justify-content: center;
  align-items: center;
}

.progress-bar {
  transition: stroke-dashoffset 0.3s ease;
}

.progress-content {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 10px;
  color: var(--awd-text-2);
}
</style>
