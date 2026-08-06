<template>
  <view class="browser-pane">
    <view class="browser-toolbar">
      <view class="browser-btn" @tap="goBack" :class="{ disabled: !canGoBack }" title="后退">
        <text class="btn-icon">←</text>
      </view>
      <view class="browser-btn" @tap="goForward" :class="{ disabled: !canGoForward }" title="前进">
        <text class="btn-icon">→</text>
      </view>
      <view class="browser-btn" @tap="reload" title="刷新">
        <text class="btn-icon">↻</text>
      </view>

      <input
        v-model="inputUrl"
        class="url-input"
        placeholder="输入网址（https://...）"
        @confirm="navigate(inputUrl)"
      />
      <view class="browser-btn primary" @tap="navigate(inputUrl)" title="打开">
        <text class="btn-icon">↵</text>
      </view>

      <view class="browser-btn" @tap="openInAppNewTab" title="新标签页">
        <text class="btn-icon">⧉</text>
      </view>

      <view class="browser-btn" :class="{ primary: isMobileMode }" @tap="toggleMobileMode" :title="isMobileMode ? '切换回桌面版' : '切换移动版 (解决网页过宽)'">
        <svg class="btn-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in (isMobileMode ? ICONS.phone : ICONS.desktop)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
    </view>

    <view class="browser-body">
      <!-- Desktop(Electron): 使用 BrowserView（不在 DOM 内渲染，这里仅作为占位与计算 bounds） -->
      <view v-if="isDesktopBrowser" ref="desktopMount" class="browser-desktop-mount"></view>

      <!-- H5: 使用 iframe 做最小可用网页展示 -->
      <!-- #ifdef H5 -->
      <!-- 代理把第三方 HTML 以本应用同源的形式返回，sandbox 绝不能带 allow-same-origin：
           它与 allow-scripts 同时出现时沙箱失效，被访问站点即可读取本应用 origin 下的会话凭证 -->
      <iframe
        v-if="!isDesktopBrowser"
        class="browser-iframe"
        :src="iframeSrc"
        @load="onIframeLoad"
        referrerpolicy="no-referrer"
        sandbox="allow-forms allow-scripts allow-top-navigation-by-user-activation"
      ></iframe>
      <!-- #endif -->

      <!-- 非 H5：占位（后续可扩展 web-view） -->
      <!-- #ifndef H5 -->
      <view class="browser-fallback">
        <text>当前平台暂不支持浏览器控件（仅 H5）</text>
      </view>
      <!-- #endif -->
    </view>
  </view>
</template>

<script>
import { getApiBaseUrl } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { host } from '@/services/host.js'

export default {
  name: 'BrowserPane',
  props: {
    url: {
      type: String,
      default: ''
    },
    tabId: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      history: [],
      index: -1,
      currentUrl: 'about:blank',
      inputUrl: '',
      iframeToken: `br_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      _messageHandler: null,
      _desktopUnsub: null,
      _desktopResizeObs: null,
      _desktopViewId: '',
      isMobileMode: false
    }
  },
  computed: {
    ICONS() { return ICONS },
    canGoBack() {
      return this.index > 0
    },
    canGoForward() {
      return this.index >= 0 && this.index < this.history.length - 1
    },
    isDesktopBrowser() {
      try {
        // 宿主是否具备 BrowserView 能力（Web 态该字段缺席）
        return host.browser
      } catch (e) {
        return false
      }
    },
    iframeSrc() {
      // iframe 永远加载 proxy 地址（保证可拦截 _blank / window.open，且导航保持在工作区内）
      if (!this.currentUrl || this.currentUrl === 'about:blank') return 'about:blank'
      const raw = String(this.currentUrl).trim()
      if (!raw || raw.startsWith('about:')) return 'about:blank'
      if (raw.startsWith('http://') || raw.startsWith('https://')) {
        const base = getApiBaseUrl().replace(/\/$/, '')
        return `${base}/api/browser/proxy?url=${encodeURIComponent(raw)}&token=${encodeURIComponent(this.iframeToken)}`
      }
      return 'about:blank'
    }
  },
  watch: {
    url: {
      immediate: true,
      handler(val) {
        if (val && val !== this.currentUrl) {
          this.navigate(val, true)
        }
      }
    }
  },
  mounted() {
    if (this.isDesktopBrowser) {
      this._desktopViewId = (this.tabId || this.iframeToken || '').toString()
      this.setupDesktopBrowser()
      return
    }
    // 接收 proxy 注入脚本 postMessage（跨域也可用）
    this._messageHandler = (msgEvt) => {
      const data = msgEvt && msgEvt.data
      if (!data || data.__checkbaBrowser !== true) return
      if (data.token !== this.iframeToken) return
      if (data.type === 'OPEN_NEW_TAB' && data.url) {
        this.$emit('open-new-tab', String(data.url))
      }
      if (data.type === 'DEBUG' && data.url) {
        // 打点：用于排查“点了没反应”（例如 CSP 禁止注入 / 链接不是 <a target=_blank> / window.open 被覆盖）
        // eslint-disable-next-line no-console
        console.log('[BrowserProxy]', String(data.url))
      }
    }
    try {
      if (typeof window !== 'undefined') {
        window.addEventListener('message', this._messageHandler)
      }
    } catch (e) {
      // ignore
    }
  },
  beforeUnmount() {
    if (this.isDesktopBrowser) {
      this.teardownDesktopBrowser()
      return
    }
    try {
      if (this._messageHandler) {
        window.removeEventListener('message', this._messageHandler)
      }
    } catch (e) {
      // ignore
    }
    this._messageHandler = null
    this._messageBound = false
  },
  methods: {
    async setupDesktopBrowser() {
      const api = host.browser
      if (!api) return

      // 监听 window.open/_blank => 工作区新 tab
      this._desktopUnsub = api.onOpenNewTab((data) => {
        if (!data || !data.url) return
        // 只处理属于自己的 view
        if (data.id && String(data.id) !== String(this._desktopViewId)) return
        this.$emit('open-new-tab', String(data.url))
      })

      // 监听标题变化：用于 tab 展示更友好（不只显示域名）
      this._desktopTitleUnsub = api.onTitleUpdated ? api.onTitleUpdated((data) => {
        try {
          if (!data) return
          if (data.id && String(data.id) !== String(this._desktopViewId)) return
          const title = data.title ? String(data.title) : ''
          if (title) this.$emit('title-change', title)
        } catch (e) {
          // ignore
        }
      }) : null

      // 创建/加载
      try {
        await api.create({ id: this._desktopViewId, url: this.normalizeUrl(this.currentUrl || this.url) })
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('desktop browser create failed', e)
      }

      // 绑定尺寸变化：把 DOM 的 rect 传给主进程作为 BrowserView bounds
      const mountRef = this.$refs.desktopMount
      const el = mountRef && mountRef.$el ? mountRef.$el : mountRef
      if (el && typeof ResizeObserver !== 'undefined' && typeof el.getBoundingClientRect === 'function') {
        this._desktopResizeObs = new ResizeObserver(() => {
          this.syncDesktopBounds()
        })
        this._desktopResizeObs.observe(el)
      }
      // 首帧强制同步一次，避免 BrowserView 初始为 0x0 导致“啥都打不开”
      this.$nextTick(() => {
        requestAnimationFrame(() => this.syncDesktopBounds())
      })
      if (typeof window !== 'undefined') {
        window.addEventListener('resize', this.syncDesktopBounds, { passive: true })
      }
    },
    teardownDesktopBrowser() {
      try {
        if (this._desktopResizeObs) this._desktopResizeObs.disconnect()
      } catch (e) {
        // ignore
      }
      this._desktopResizeObs = null
      try {
        if (typeof window !== 'undefined') {
          window.removeEventListener('resize', this.syncDesktopBounds)
        }
      } catch (e) {
        // ignore
      }
      try {
        if (this._desktopUnsub) this._desktopUnsub()
      } catch (e) {
        // ignore
      }
      this._desktopUnsub = null
      try {
        if (this._desktopTitleUnsub) this._desktopTitleUnsub()
      } catch (e) {
        // ignore
      }
      this._desktopTitleUnsub = null

      // MVP：组件卸载即销毁 view（后续可由“tab close”统一管理，避免丢历史）
      try {
        const api = host.browser
        if (api && this._desktopViewId) api.destroy({ id: this._desktopViewId })
      } catch (e) {
        // ignore
      }
    },
    syncDesktopBounds() {
      const api = host.browser
      const mountRef = this.$refs.desktopMount
      const el = mountRef && mountRef.$el ? mountRef.$el : mountRef
      if (!api || !el || !this._desktopViewId) return
      try {
        if (typeof el.getBoundingClientRect !== 'function') return
        const rect = el.getBoundingClientRect()
        // eslint-disable-next-line no-console
        console.log('[DesktopBrowserView] bounds', this._desktopViewId, rect.left, rect.top, rect.width, rect.height)
        api.setBounds({
          id: this._desktopViewId,
          bounds: { x: rect.left, y: rect.top, width: rect.width, height: rect.height }
        })
        // 不在 resize/bounds 同步时反复 setActive：会导致 BrowserView 频繁重挂载，从而打断导航/右键事件
      } catch (e) {
        // ignore
      }
    },
    normalizeUrl(u) {
      const raw = (u || '').trim()
      if (!raw) return 'about:blank'
      if (raw.startsWith('http://') || raw.startsWith('https://') || raw.startsWith('about:')) return raw
      return `https://${raw}`
    },
    navigate(u, replace = false) {
      const next = this.normalizeUrl(u)
      this.currentUrl = next
      this.inputUrl = next === 'about:blank' ? '' : next

      // Desktop：直接导航 BrowserView
      if (this.isDesktopBrowser) {
        try {
          const api = host.browser
          if (api && this._desktopViewId) {
            // 不让 invoke rejection 冒泡到控制台（主进程可能返回 ok=false / ERR_ABORTED）
            Promise.resolve(api.navigate({ id: this._desktopViewId, url: next })).catch(() => {})
          }
        } catch (e) {
          // ignore
        }
      }

      if (replace && this.index >= 0) {
        this.history.splice(this.index, 1, next)
      } else {
        // 丢弃 forward 栈
        if (this.index < this.history.length - 1) {
          this.history = this.history.slice(0, this.index + 1)
        }
        this.history.push(next)
        this.index = this.history.length - 1
      }

      this.$emit('url-change', next)
    },
    reload() {
      // 重新设置 src 触发刷新
      const u = this.currentUrl
      this.currentUrl = 'about:blank'
      this.$nextTick(() => {
        this.currentUrl = u
      })
    },
    goBack() {
      if (!this.canGoBack) return
      this.index -= 1
      this.currentUrl = this.history[this.index]
      this.inputUrl = this.currentUrl
      this.$emit('url-change', this.currentUrl)
    },
    goForward() {
      if (!this.canGoForward) return
      this.index += 1
      this.currentUrl = this.history[this.index]
      this.inputUrl = this.currentUrl
      this.$emit('url-change', this.currentUrl)
    },
    openInAppNewTab() {
      // 这里 emit 原始 url（不是 iframeSrc）
      this.$emit('open-new-tab', this.currentUrl)
    },
    onIframeLoad(e) {
      // 由后端 proxy 注入脚本统一处理 _blank / window.open；这里无需再做同源注入
    },
    async toggleMobileMode() {
      if (!this.isDesktopBrowser) return
      this.isMobileMode = !this.isMobileMode
      const api = host.browser
      if (!api || !this._desktopViewId) return
      
      const mobileUA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'
      const desktopUA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      
      try {
        await api.setUA({
          id: this._desktopViewId,
          ua: this.isMobileMode ? mobileUA : desktopUA
        })
      } catch (e) {
        // ignore
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.browser-pane {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: #ffffff;
}

.browser-toolbar {
  height: 40px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border-bottom: 1px solid #e5e7eb;
  background: #ffffff;
}

.browser-btn {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(148, 163, 184, 0.35);
  background: #fff;
  color: #1a1a1a;
  cursor: pointer;
}

.browser-btn.primary {
  background: #12344D;
  color: #fff;
  border-color: transparent;
}

.browser-btn.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.btn-icon {
  font-size: 14px;
  font-weight: 700;
}

.url-input {
  flex: 1;
  height: 32px;
  border-radius: 8px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  padding: 0 10px;
  font-size: 13px;
  background: #fff;
}

.browser-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.browser-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.browser-desktop-mount {
  width: 100%;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.browser-fallback {
  padding: 16px;
  color: #666666;
}

.btn-icon-svg {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}
</style>


