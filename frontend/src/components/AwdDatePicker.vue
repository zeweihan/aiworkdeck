<!--
  AwdDatePicker — 桌面形制的日期/时间输入框。

  为什么不用 uni 的 <input type="date">：uni-h5 的内置 <input> 组件把 type 收窄成
  ["text","number","idcard","digit","password","tel"] 一张白名单（见
  node_modules/@dcloudio/uni-h5 编译产物），'date'/'time' 不在表里，写了也只会退化
  成普通文本框，拿不到 Chromium 自带的日历/时间弹层。

  解法是绕开 uni 的模板编译劫持：容器用 <view> 占位，mounted() 里用
  document.createElement('input') 手工造一个真正的原生 <input type="date">/
  <input type="time">，append 进容器的真实 DOM 节点。因为这个节点不是 Vue 模板
  编译出来的，scoped 样式的 data-v-* 属性选择器套不到它身上，所以外观直接用内联
  style 写死（对齐 AwdSelect 的浅色专业风：36px 高/1px 边框/6px 圆角/13px 字号），
  不依赖 <style scoped>。

  modelValue 的格式与原生 input 的 value 格式对齐：date 型是 'YYYY-MM-DD'，
  time 型是 'HH:mm'——正好是本产品任务系统 dueDate/dueTime 字段的既有格式，
  调用方不需要转换。
-->
<template>
  <view class="awd-date-picker" :class="{ 'is-disabled': disabled }" ref="host"></view>
</template>

<script>
const BORDER_DEFAULT = '#E6EAE8'
const BORDER_FOCUS = '#5BD197'

export default {
  name: 'AwdDatePicker',
  props: {
    modelValue: { type: String, default: '' },
    /** 'date' | 'time' */
    type: { type: String, default: 'date' },
    placeholder: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  emits: ['update:modelValue'],
  watch: {
    modelValue(v) {
      const val = v || ''
      if (this._input && this._input.value !== val) this._input.value = val
    },
    disabled(v) {
      if (this._input) this._input.disabled = !!v
    },
  },
  mounted() {
    this.mountNativeInput()
  },
  beforeUnmount() {
    this.unmountNativeInput()
  },
  methods: {
    mountNativeInput() {
      const host = this.$refs.host && this.$refs.host.$el ? this.$refs.host.$el : this.$refs.host
      if (!host) return
      const input = document.createElement('input')
      input.type = this.type === 'time' ? 'time' : 'date'
      input.value = this.modelValue || ''
      input.disabled = !!this.disabled
      if (this.placeholder) input.setAttribute('aria-label', this.placeholder)
      input.style.cssText = [
        'box-sizing: border-box',
        'display: block',
        'width: 100%',
        'height: 36px',
        'padding: 0 10px',
        'border: 1px solid ' + BORDER_DEFAULT,
        'border-radius: 6px',
        'background-color: #fff',
        'font-size: 13px',
        'font-family: inherit',
        'color: #212629',
        'outline: none',
        'transition: border-color 0.15s ease',
      ].join(';')
      input.addEventListener('focus', this.handleFocus)
      input.addEventListener('blur', this.handleBlur)
      // 只听 change 不听 input：原生日期框键盘逐段输入时 input 事件会带不完整的
      // 中间值，直接上抛会让「改期即保存」类调用方（ProjectCalendarPane）拿中间值
      // 发请求。change 只在值完整合法（或失焦）时触发。
      input.addEventListener('change', this.handleInput)
      host.appendChild(input)
      this._input = input
    },
    handleFocus() {
      if (this._input) this._input.style.borderColor = BORDER_FOCUS
    },
    handleBlur() {
      if (this._input) this._input.style.borderColor = BORDER_DEFAULT
    },
    handleInput(e) {
      this.$emit('update:modelValue', e.target.value)
    },
    unmountNativeInput() {
      if (!this._input) return
      this._input.removeEventListener('focus', this.handleFocus)
      this._input.removeEventListener('blur', this.handleBlur)
      this._input.removeEventListener('change', this.handleInput)
      if (this._input.parentNode) this._input.parentNode.removeChild(this._input)
      this._input = null
    },
  },
}
</script>

<style scoped>
.awd-date-picker {
  display: block;
  min-width: 0;
}

.awd-date-picker.is-disabled {
  opacity: 0.55;
  pointer-events: none;
}
</style>
