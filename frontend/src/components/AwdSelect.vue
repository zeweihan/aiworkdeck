<!--
  AwdSelect — 桌面形制的下拉选择器。

  为什么不用 uni 的 <picker mode="selector">：它在 H5 上弹的是移动端那套
  底部抽屉（滚轮 + 取消/确定），在一个桌面工作台里点开设置页的模型选择器
  却从屏幕底部升起一块半屏面板，观感与整套外壳完全对不上。收起来的样子
  各页自己写 CSS 还能凑合，展开的样子是 uni 内置组件，改不动。

  API 刻意与 <picker mode="selector"> 对齐（range 是标签数组、value 是下标），
  迁移只需换标签名；差别只有一处：change 直接抛下标，不再包一层
  event.detail.value——那层是 uni 事件对象的形状，自绘组件没有理由模仿。

  触发器默认渲染「当前项 + 角标」，也可以用默认插槽整个换掉（调用方各有各的
  按钮形制：设置页是输入框样、广场是胶囊按钮样）。

  菜单用 position: fixed + 实时算坐标，不是 absolute：调用点大多在 scroll-view
  或带 overflow 的卡片里，absolute 会被裁掉。代价是滚动/缩放时坐标会失效，
  所以这两种情况直接关闭菜单（比让它飘在错误位置好）。
-->
<template>
  <view class="awd-select" :class="{ 'is-disabled': disabled, 'is-open': open }">
    <view class="awd-select-trigger" @tap.stop="toggle">
      <slot>
        <view class="awd-select-value">
          <text class="awd-select-text">{{ currentLabel }}</text>
          <text class="awd-select-caret">▾</text>
        </view>
      </slot>
    </view>

    <!-- 蒙层负责「点外面关掉」；透明但要吃掉点击，否则关菜单的同时会点到下面的东西 -->
    <view v-if="open" class="awd-select-mask" @tap.stop="close"></view>
    <view v-if="open" class="awd-select-menu" :style="menuStyle">
      <view
        v-for="(label, i) in range"
        :key="i"
        class="awd-select-item"
        :class="{ 'is-active': i === value }"
        @tap.stop="pick(i)"
      >
        <text class="awd-select-item-text">{{ label }}</text>
        <text v-if="i === value" class="awd-select-check">✓</text>
      </view>
    </view>
  </view>
</template>

<script>
export default {
  name: 'AwdSelect',
  props: {
    /** 标签数组，与 <picker> 的 range 同义 */
    range: { type: Array, default: () => [] },
    /** 当前选中项下标 */
    value: { type: Number, default: 0 },
    disabled: { type: Boolean, default: false },
  },
  emits: ['change', 'cancel'],
  data() {
    return {
      open: false,
      menuStyle: {},
    }
  },
  computed: {
    currentLabel() {
      return this.range[this.value] !== undefined ? this.range[this.value] : ''
    },
  },
  beforeUnmount() {
    this.detachDismiss()
  },
  methods: {
    toggle() {
      if (this.disabled) return
      if (this.open) this.close()
      else this.openMenu()
    },
    openMenu() {
      this.open = true
      this.$nextTick(() => {
        this.placeMenu()
        this.attachDismiss()
      })
    },
    close() {
      if (!this.open) return
      this.open = false
      this.detachDismiss()
      this.$emit('cancel')
    },
    pick(i) {
      this.open = false
      this.detachDismiss()
      if (i !== this.value) this.$emit('change', i)
    },
    placeMenu() {
      const el = this.$el && this.$el.querySelector('.awd-select-trigger')
      if (!el) return
      const r = el.getBoundingClientRect()
      const vh = window.innerHeight || 800
      // 下方装不下就翻到上方开——设置页的下拉常常就在可视区底部
      const below = vh - r.bottom
      const openUp = below < 180 && r.top > below
      this.menuStyle = openUp
        ? { left: r.left + 'px', bottom: (vh - r.top + 4) + 'px', minWidth: r.width + 'px' }
        : { left: r.left + 'px', top: (r.bottom + 4) + 'px', minWidth: r.width + 'px' }
    },
    attachDismiss() {
      this._dismiss = (e) => {
        // 下拉菜单自己超过 280px 就会出现内部滚动条（见组件头注释）。滚动事件不
        // 冒泡，但这里用的是捕获段（第三参 true），会连菜单自己内部的滚动也一起
        // 收到——不加这道判断，用户在菜单里往下滚一下，菜单立刻把自己关掉，列表
        // 长一点根本没法用。只有目标不在菜单内部（真正可能让触发器坐标失效的
        // 外部容器滚动）时才按原逻辑关闭。
        const menuEl = this.$el && this.$el.querySelector('.awd-select-menu')
        if (menuEl && e && e.target && menuEl.contains(e.target)) return
        this.close()
      }
      // 捕获段：调用点大多在 scroll-view 里，滚动事件不冒泡到 window
      window.addEventListener('scroll', this._dismiss, true)
      window.addEventListener('resize', this._dismiss)
    },
    detachDismiss() {
      if (!this._dismiss) return
      window.removeEventListener('scroll', this._dismiss, true)
      window.removeEventListener('resize', this._dismiss)
      this._dismiss = null
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-select {
  position: relative;
  display: block;
  min-width: 0;
}

.awd-select-trigger {
  cursor: pointer;
}

.awd-select.is-disabled .awd-select-trigger {
  cursor: not-allowed;
  opacity: 0.55;
}

/* 默认触发器：与设置页表单里的输入框同形制 */
.awd-select-value {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  border: 1px solid #E6EAE8;
  border-radius: 6px;
  background-color: #fff;
  font-size: 13px;
  color: #212629;
  box-sizing: border-box;
  transition: border-color 0.15s ease;
}

.awd-select-value:hover,
.awd-select.is-open .awd-select-value {
  border-color: #5BD197;
}

.awd-select-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.awd-select-caret {
  flex: none;
  color: #8b9691;
  font-size: 12px;
  transition: transform 0.15s ease;
}

.awd-select.is-open .awd-select-caret {
  transform: rotate(180deg);
}

.awd-select-mask {
  position: fixed;
  inset: 0;
  z-index: 4000;
}

.awd-select-menu {
  position: fixed;
  z-index: 4001;
  max-height: 280px;
  overflow-y: auto;
  padding: 4px;
  background: #fff;
  border: 1px solid #E6EAE8;
  border-radius: 8px;
  box-shadow: 0 8px 28px rgba(18, 52, 77, 0.16);
}

.awd-select-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 10px;
  border-radius: 5px;
  font-size: 13px;
  color: #212629;
  cursor: pointer;
  white-space: nowrap;
}

.awd-select-item:hover {
  background: #F1F7F4;
}

.awd-select-item.is-active {
  color: #1A5336;
  font-weight: 600;
}

.awd-select-check {
  flex: none;
  color: #1A5336;
  font-size: 12px;
}
</style>
