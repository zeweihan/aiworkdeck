<!--
  AwdSwitch — 桌面形制的开关。

  替掉 uni 的 <switch>：那个组件的尺寸写死在内部，调用点只能靠
  `style="transform: scale(0.7)"` 硬缩（广场两处、插件详情一处都是这么写的），
  缩完描边和圆角一起变形，跟旁边的按钮/输入框对不齐。这里直接自绘，
  尺寸由 CSS 说了算，颜色跟森林绿主色走。

  change 抛布尔值本身，不是 uni 事件对象的 event.detail.value 形状。
-->
<template>
  <view
    class="awd-switch"
    :class="{ 'is-on': checked, 'is-disabled': disabled }"
    role="switch"
    :aria-checked="checked ? 'true' : 'false'"
    @tap.stop="toggle"
  >
    <view class="awd-switch-knob"></view>
  </view>
</template>

<script>
export default {
  name: 'AwdSwitch',
  props: {
    checked: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
  },
  emits: ['change'],
  methods: {
    toggle() {
      if (this.disabled) return
      this.$emit('change', !this.checked)
    },
  },
}
</script>

<style scoped>
.awd-switch {
  position: relative;
  flex: none;
  width: 38px;
  height: 22px;
  border-radius: 11px;
  background: var(--awd-surface-3);
  cursor: pointer;
  transition: background 0.18s ease;
}

.awd-switch.is-on {
  background: var(--awd-accent);
}

.awd-switch.is-disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.awd-switch-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--awd-surface);
  box-shadow: 0 1px 3px rgba(18, 52, 77, 0.28);
  transition: transform 0.18s ease;
}

.awd-switch.is-on .awd-switch-knob {
  transform: translateX(16px);
}
</style>
