<template>
  <view v-if="visible" class="amb" @mouseleave="onLeaveBar">
    <view
      v-for="m in menus"
      :key="m.id"
      class="amb-top"
      :class="{ open: openId === m.id }"
      @tap.stop="toggle(m.id)"
      @mouseenter="onHoverTop(m.id)"
    >
      <text class="amb-top-t">{{ m.label }}</text>
      <view v-if="openId === m.id" class="amb-menu" @tap.stop>
        <template v-for="(it, i) in m.items">
          <view v-if="it.type === 'separator'" :key="'s' + i" class="amb-sep"></view>
          <view
            v-else
            :key="it.id"
            class="amb-item"
            :class="{ disabled: it.enabled === false, 'has-sub': !!it.submenu }"
            @tap.stop="pick(it)"
            @mouseenter="subOpenId = it.submenu ? it.id : ''"
          >
            <text class="amb-check">{{ it.type === 'checkbox' && it.checked ? '✓' : '' }}</text>
            <text class="amb-label">{{ it.label }}</text>
            <text v-if="it.accel" class="amb-accel">{{ pretty(it.accel) }}</text>
            <text v-else-if="it.submenu" class="amb-accel">›</text>
            <view v-if="it.submenu && subOpenId === it.id" class="amb-submenu">
              <view
                v-for="sub in it.submenu"
                :key="sub.id"
                class="amb-item"
                :class="{ disabled: sub.enabled === false }"
                @tap.stop="pick(sub)"
              >
                <text class="amb-check">{{ sub.type === 'checkbox' && sub.checked ? '✓' : '' }}</text>
                <text class="amb-label">{{ sub.label }}</text>
              </view>
            </view>
          </view>
        </template>
      </view>
    </view>
    <view v-if="openId" class="amb-mask" @tap="close"></view>
  </view>
</template>

<script>
// Windows 的自绘菜单栏。
//
// 为什么需要它：titleBarStyle:'hidden' 会让 Windows 的原生菜单栏一起消失
// （Windows 上菜单是画在窗口边框下面的，不是 mac 那样的全局菜单栏）。三条路里
// 「保留系统边框」与「干脆没有菜单」都被否了，剩下自绘——而 P3 已经产出了一份
// 命令表，把它渲染成 HTML 下拉是增量很小的事。设计见 spec §6.4。
//
// 读的是 buildMenuPayload 的产物，和 mac 上主进程渲染成 NSMenu 的**完全同一份
// 数据**，所以两个平台的菜单内容不会漂。执行也走同一条派发链（runCommandById）。
//
// mac 上整个组件不渲染（visible=false）——那边有真的系统菜单栏。

import { buildMenuPayload } from '@/config/commands/index.js'
import { getMenuState, runCommandById } from '@/utils/appMenuBridge.js'
import { getAppLanguage } from '@/utils/appLanguage.js'

export default {
  name: 'AppMenuBar',
  props: {
    // 状态变了就 +1，触发重建（父级用 pushMenuState 的同一个时机驱动）
    refreshKey: { type: Number, default: 0 },
  },
  data() {
    // visible 必须是 data 不能是 computed：它读的是 documentElement 的 class
    // （windowChrome.js 挂的），那不是响应式源，写成 computed 会在首次求值后
    // 一直用缓存，平台判定永远停在挂载那一刻。
    return { visible: false, menus: [], openId: '', subOpenId: '' }
  },
  watch: {
    refreshKey() { this.syncVisible(); this.rebuild() },
  },
  mounted() {
    this.syncVisible()
    this.rebuild()
    // Alt 唤起第一个菜单（Windows 惯例）。只认单独按下的 Alt，避免吞掉
    // Alt+字母 这类组合——那些是加速键。
    this._keydown = (e) => {
      if (e.key === 'Alt' && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
        e.preventDefault()
        this.openId = this.openId ? '' : (this.menus[0] && this.menus[0].id) || ''
        return
      }
      if (!this.openId) return
      if (e.key === 'Escape') { e.preventDefault(); this.close() }
      else if (e.key === 'ArrowRight') { e.preventDefault(); this.step(1) }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); this.step(-1) }
    }
    document.addEventListener('keydown', this._keydown, true)
  },
  beforeUnmount() {
    if (this._keydown) document.removeEventListener('keydown', this._keydown, true)
  },
  methods: {
    syncVisible() {
      this.visible = typeof document !== 'undefined'
        && document.documentElement.classList.contains('is-win')
    },
    rebuild() {
      if (!this.visible) return
      const payload = buildMenuPayload(getMenuState(), getAppLanguage())
      // 应用菜单（mac 上那个以应用名命名的）在 Windows 上没有对应位置，
      // 它的条目（设置/检查更新/语言）并进「帮助」之前的「工具」更自然——
      // 但为了两平台内容一致，这里原样保留成一个「应用」菜单。
      this.menus = (payload.menus || []).map((m) => ({
        ...m,
        label: m.label || 'AI WorkDeck',
      }))
    },
    toggle(id) {
      this.openId = this.openId === id ? '' : id
      this.subOpenId = ''
    },
    /** 已经展开时，划过其它顶级项直接换过去（原生菜单栏的行为）。 */
    onHoverTop(id) {
      if (this.openId && this.openId !== id) { this.openId = id; this.subOpenId = '' }
    },
    onLeaveBar() { /* 保持展开：鼠标要能走进下拉里 */ },
    step(dir) {
      const i = this.menus.findIndex((m) => m.id === this.openId)
      if (i < 0) return
      this.openId = this.menus[(i + dir + this.menus.length) % this.menus.length].id
      this.subOpenId = ''
    },
    close() { this.openId = ''; this.subOpenId = '' },
    pick(item) {
      if (!item || item.enabled === false || item.submenu) return
      this.close()
      runCommandById(item.id)
    },
    /** Electron accelerator 写法 → Windows 键帽。 */
    pretty(a) {
      return String(a || '')
        .replace(/CmdOrCtrl|Command|Cmd/g, 'Ctrl')
        .replace(/Option/g, 'Alt')
        .replace(/Right/g, '→').replace(/Left/g, '←')
    },
  },
}
</script>

<style lang="scss" scoped>
.amb {
  display: flex;
  align-items: stretch;
  height: 100%;
  -webkit-app-region: no-drag; // 菜单栏本身可点，不能被顶栏的拖拽区吞掉
}

.amb-top {
  position: relative;
  display: flex;
  align-items: center;
  padding: 0 9px;
  cursor: default;
  border-radius: 4px;

  &:hover, &.open { background: rgba(0, 0, 0, 0.06); }
}

.amb-top-t {
  font-size: 13px;
  color: #3c4043;
  white-space: nowrap;
}

.amb-mask {
  position: fixed;
  inset: 0;
  z-index: 900;
}

.amb-menu {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 1000;
  min-width: 232px;
  padding: 5px 0;
  background: #fff;
  border: 1px solid #e3e6e8;
  border-radius: 6px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.16);
}

.amb-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 26px;
  padding: 0 12px;
  cursor: default;

  &:hover:not(.disabled) { background: rgba(91, 209, 151, 0.16); }
  &.disabled { opacity: 0.38; }
}

.amb-check {
  width: 12px;
  font-size: 12px;
  color: #1a5336;
  flex-shrink: 0;
}

.amb-label {
  font-size: 13px;
  color: #2c3338;
  white-space: nowrap;
  flex: 1;
}

.amb-accel {
  font-size: 12px;
  color: #9aa0a6;
  white-space: nowrap;
  flex-shrink: 0;
}

.amb-sep {
  height: 1px;
  margin: 4px 10px;
  background: #ececec;
}

.amb-submenu {
  position: absolute;
  top: -5px;
  left: 100%;
  min-width: 200px;
  padding: 5px 0;
  background: #fff;
  border: 1px solid #e3e6e8;
  border-radius: 6px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.16);
  max-height: 60vh;
  overflow-y: auto;
}
</style>
