<template>
  <view class="browser-pane">
    <view class="browser-toolbar">
      <view class="browser-btn" @tap="goBack" :class="{ disabled: !canGoBack }" :title="$t('panels.bpBack')">
        <text class="btn-icon">←</text>
      </view>
      <view class="browser-btn" @tap="goForward" :class="{ disabled: !canGoForward }" :title="$t('panels.bpForward')">
        <text class="btn-icon">→</text>
      </view>
      <view class="browser-btn" @tap="reload" :title="$t('panels.bpRefresh')">
        <text class="btn-icon">↻</text>
      </view>

      <input
        v-model="inputUrl"
        class="url-input"
        :placeholder="$t('panels.bpUrlPlaceholder')"
        @confirm="navigate(inputUrl)"
      />
      <view class="browser-btn primary" @tap="navigate(inputUrl)" :title="$t('panels.bpOpen')">
        <text class="btn-icon">↵</text>
      </view>

      <!-- 收藏本页：已收藏时星形实心（再点只提示，不重复入库；删除仍去收藏面板坞位） -->
      <view
        class="browser-btn"
        :class="{ on: isCurrentFavorited, disabled: !canFavorite }"
        @tap="favoriteCurrentPage"
        :title="isCurrentFavorited ? $t('panels.bpFavorited') : $t('panels.bpAddFavorite')"
      >
        <svg class="btn-icon-svg" viewBox="0 0 24 24" :fill="isCurrentFavorited ? 'currentColor' : 'none'" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.star" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>

      <!-- 收藏夹/快捷方式抽屉开关 -->
      <view class="browser-btn" :class="{ on: shelfOpen }" @tap="toggleShelf" :title="$t('panels.bpShelf')">
        <svg class="btn-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.bookmark" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>

      <view class="browser-btn" @tap="openInAppNewTab" :title="$t('panels.bpNewTab')">
        <text class="btn-icon">⧉</text>
      </view>

      <view class="browser-btn" :class="{ primary: isMobileMode }" @tap="toggleMobileMode" :title="isMobileMode ? $t('panels.bpSwitchToDesktop') : $t('panels.bpSwitchToMobile')">
        <svg class="btn-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in (isMobileMode ? ICONS.phone : ICONS.desktop)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
      </view>
    </view>

    <!-- 收藏夹/快捷方式抽屉（shelf）。第一红线：必须参与文档流、挤压 .browser-body
         （从而挤压 .browser-desktop-mount）的高度——桌面端 BrowserView 是原生层，
         恒盖住所有 DOM，absolute 浮层会被整个盖住（toast 事故先例）。参与布局后
         mount 高度变化由 ResizeObserver 触发 syncDesktopBounds，BrowserView 自动让位。 -->
    <view v-if="shelfOpen" class="browser-shelf">
      <view class="shelf-section">
        <view class="shelf-head">
          <text class="shelf-title">{{ $t('panels.bpShortcuts') }}</text>
          <view class="shelf-action" :class="{ disabled: !canPin }" @tap="pinCurrentPage" :title="$t('panels.bpPinCurrent')">
            <svg class="shelf-action-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.plus" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
            <text>{{ $t('panels.bpPinCurrent') }}</text>
          </view>
        </view>
        <view v-if="shortcuts.length === 0" class="shelf-empty">
          <text>{{ $t('panels.bpShortcutsEmpty') }}</text>
        </view>
        <view v-else class="shelf-items">
          <view v-for="(sc, si) in shortcuts" :key="'sc-' + si" class="shelf-card" @tap="openShelfUrl(sc.url)">
            <view class="shelf-card-main">
              <text class="shelf-card-title">{{ sc.title || hostOf(sc.url) || sc.url }}</text>
              <text class="shelf-card-host">{{ hostOf(sc.url) }}</text>
            </view>
            <view class="shelf-card-x" :title="$t('panels.bpUnpin')" @tap.stop="removeShortcut(si)">
              <text>✕</text>
            </view>
          </view>
        </view>
      </view>

      <view class="shelf-section">
        <view class="shelf-head">
          <text class="shelf-title">{{ $t('panels.bpShelfFavorites') }}</text>
        </view>
        <view v-if="favLoading && shelfFavorites.length === 0" class="shelf-empty">
          <text>{{ $t('panels.pfLoading') }}</text>
        </view>
        <view v-else-if="shelfFavorites.length === 0" class="shelf-empty">
          <text>{{ $t('panels.bpShelfFavEmpty') }}</text>
        </view>
        <view v-else class="shelf-items">
          <view v-for="fav in shelfFavorites" :key="'fv-' + fav.id" class="shelf-card" @tap="openShelfUrl(fav.sourceUrl)">
            <view class="shelf-card-main">
              <text class="shelf-card-title">{{ fav.title || fav.sourceHost || fav.sourceUrl }}</text>
              <text class="shelf-card-host">{{ fav.sourceHost || hostOf(fav.sourceUrl) }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <view class="browser-body">
      <!-- Desktop(Electron): 使用 BrowserView（不在 DOM 内渲染，这里仅作为占位与计算 bounds） -->
      <view v-if="isDesktopBrowser" ref="desktopMount" class="browser-desktop-mount"></view>
      <!-- BrowserView 创建失败时主进程没有对应 view（僵尸 tab）：面板会一直空白、
           截图按 viewId 查表报 view not found。原生层不存在时这层 DOM 提示才可见 -->
      <view v-if="isDesktopBrowser && desktopCreateError" class="browser-create-error">
        <text class="bce-text">{{ $t('panels.bpCreateFailed') }}</text>
        <text class="bce-detail">{{ desktopCreateError }}</text>
        <view class="bce-retry" @tap="retryDesktopCreate"><text>{{ $t('panels.bpRetry') }}</text></view>
      </view>

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
        <text>{{ $t('panels.bpUnsupportedPlatform') }}</text>
      </view>
      <!-- #endif -->
    </view>
  </view>
</template>

<script>
import { getApiBaseUrl, createProjectFavorite, getProjectFavorites, getMyFavorites } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { host } from '@/services/host.js'

// 快捷方式存本机（uni 本地存储，全局共享、不分项目）：桌面单机形态够用
const SHORTCUTS_STORAGE_KEY = 'awd_browser_shortcuts'

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
    },
    // 收藏落库的目标项目；缺席时星形按钮禁用（收藏是项目维度的）
    projectId: {
      type: [Number, String],
      default: null
    }
  },
  data() {
    return {
      history: [],
      index: -1,
      // currentUrl = 地址栏/标签显示的地址；iframeUrl = iframe 真正被要求加载的地址。
      // 两个必须分开：页内跳转之后 currentUrl 要跟着走（否则切回来退回打开时那个地址），
      // 但 iframe 已经站在那一页上了，把新地址回写进 src 会让它整个重新加载一遍——
      // 页面状态、滚动位置、填了一半的表单全没，正是这次要修的病。
      currentUrl: 'about:blank',
      iframeUrl: 'about:blank',
      // 这一次加载是我们自己发起的（地址栏/前进后退），用来区分「站点自己重定向」
      // 和「用户点了链接」：前者该替换历史栈顶，后者该新增一条
      _pendingNav: false,
      inputUrl: '',
      iframeToken: `br_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      _messageHandler: null,
      _desktopUnsub: null,
      _desktopResizeObs: null,
      _desktopViewId: '',
      // 桌面端：BrowserView 自己维护历史，前进/后退可用性由主进程推过来
      _desktopReady: false,
      viewCanGoBack: false,
      viewCanGoForward: false,
      isMobileMode: false,
      desktopCreateError: '',
      // 当前页面标题（桌面端由主进程 title-updated / adoptViewState 喂进来；
      // H5 代理只回报 URL_CHANGED，拿不到标题，收藏时退回域名）
      pageTitle: '',
      // 收藏夹/快捷方式抽屉
      shelfOpen: false,
      shortcuts: [],
      favorites: [],
      favLoading: false,
      // 本次会话里刚收藏成功的 URL（乐观记账，列表刷新前星形就要实心）
      localFavUrls: [],
      _favSaving: false
    }
  },
  computed: {
    ICONS() { return ICONS },
    canGoBack() {
      // 桌面端不看组件里那份 history：面板一卸载它就没了，而 BrowserView 是保活的，
      // 真实历史只有主进程知道
      if (this.isDesktopBrowser) return this.viewCanGoBack
      return this.index > 0
    },
    canGoForward() {
      if (this.isDesktopBrowser) return this.viewCanGoForward
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
      // iframe 永远加载 proxy 地址（保证可拦截 _blank / window.open，且导航保持在工作区内）。
      // 读 iframeUrl 而不是 currentUrl：页内跳转只动 currentUrl，这里不能跟着变，
      // 否则每跳一次都会被 Vue 改一次 src、把刚打开的那一页重新加载掉。
      if (!this.iframeUrl || this.iframeUrl === 'about:blank') return 'about:blank'
      const raw = String(this.iframeUrl).trim()
      if (!raw || raw.startsWith('about:')) return 'about:blank'
      if (raw.startsWith('http://') || raw.startsWith('https://')) {
        const base = getApiBaseUrl().replace(/\/$/, '')
        return `${base}/api/browser/proxy?url=${encodeURIComponent(raw)}&token=${encodeURIComponent(this.iframeToken)}`
      }
      return 'about:blank'
    },
    canFavorite() {
      return !!this.projectId && /^https?:\/\//.test(this.currentUrl || '')
    },
    canPin() {
      // 快捷方式只存本机，不需要项目
      return /^https?:\/\//.test(this.currentUrl || '')
    },
    isCurrentFavorited() {
      const key = this.favUrlKey(this.currentUrl)
      if (!key) return false
      if (this.localFavUrls.includes(key)) return true
      return this.favorites.some(f => f && this.favUrlKey(f.sourceUrl) === key)
    },
    // 抽屉里的「收藏的页面」：只要 webmark 类（有 sourceUrl），并按 URL 去重
    // （同一页的多条文字摘录只显示一张卡）
    shelfFavorites() {
      const seen = new Set()
      const out = []
      for (const f of this.favorites) {
        if (!f || !f.sourceUrl) continue
        const key = this.favUrlKey(f.sourceUrl)
        if (!key || seen.has(key)) continue
        seen.add(key)
        out.push(f)
      }
      return out
    }
  },
  watch: {
    url: {
      immediate: true,
      handler(val) {
        if (!val || val === this.currentUrl) return
        // 桌面端首帧只把初值抄进组件，不去驱动 BrowserView：切回一个保活着的标签时
        // 重新 loadURL 就等于把用户翻到的那一页丢掉（本组件修的正是这个）。
        // 真正的创建/复用在 setupDesktopBrowser 里，之后这个 watcher 才恢复导航职责。
        if (this.isDesktopBrowser && !this._desktopReady) {
          this.currentUrl = this.normalizeUrl(val)
          this.inputUrl = this.currentUrl === 'about:blank' ? '' : this.currentUrl
          return
        }
        this.navigate(val, true)
      }
    }
  },
  mounted() {
    this.loadShortcuts()
    // 星形按钮的实心态要在抽屉从未打开过时也正确，进来先拉一次收藏列表（轻量端点）
    this.loadFavorites()
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
      if (data.type === 'URL_CHANGED' && data.url) {
        // 代理注入的脚本在每次文档加载时回报真实地址（页内点链接、站点自己 302）。
        // 这是 Web/H5 下标签地址跟随导航的唯一来源——iframe 是不透明源，父窗口
        // 读不到它的 location（sandbox 不带 allow-same-origin，也绝不能带）。
        this.adoptPageUrl(String(data.url))
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
    // BrowserView 创建失败此前只 console.warn 一声：tab 留在前端列表里但主进程
    // 注册表查无此 view（僵尸 tab）——面板空白、截图报 view not found，且当次
    // 会话内无任何自愈手段（用户反馈14）。失败必须落成可见错误态并给重试。
    async createDesktopView() {
      const api = host.browser
      if (!api) return
      try {
        const res = await api.create({ id: this._desktopViewId, url: this.normalizeUrl(this.currentUrl || this.url) })
        this.adoptViewState(res)
        this.desktopCreateError = ''
      } catch (e) {
        this.desktopCreateError = (e && e.message) ? String(e.message) : String(e || 'create failed')
        // eslint-disable-next-line no-console
        console.warn('desktop browser create failed', e)
      }
    },
    async retryDesktopCreate() {
      await this.createDesktopView()
      if (!this.desktopCreateError) {
        this.$nextTick(() => requestAnimationFrame(() => this.syncDesktopBounds()))
      }
    },
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
          if (title) {
            // 组件自己也留一份：收藏本页/钉快捷方式要用当前标题
            this.pageTitle = title
            this.$emit('title-change', title)
          }
        } catch (e) {
          // ignore
        }
      }) : null

      // 监听页内跳转（点链接、搜索、SPA 换路由）：BrowserView 自己跳的，渲染层此前
      // 完全不知情，标签因此一直记着「打开时那个地址」——重建时就退回了默认首页。
      this._desktopUrlUnsub = api.onUrlUpdated ? api.onUrlUpdated((data) => {
        if (!data) return
        if (data.id && String(data.id) !== String(this._desktopViewId)) return
        this.adoptViewState(data)
      }) : null

      // 创建（已有同 id 的保活 view 时是复用，主进程不会重新加载）
      await this.createDesktopView()
      this._desktopReady = true

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
      try {
        if (this._desktopUrlUnsub) this._desktopUrlUnsub()
      } catch (e) {
        // ignore
      }
      this._desktopUrlUnsub = null
      this._desktopReady = false

      // 只从窗口摘下，不销毁：切走再切回来时同一个 view 原样接着用，页内跳转、
      // 滚动位置、填了一半的表单、页面里的登录态都还在。
      // 真正的销毁在标签关闭时（project-overview 的 closeFile / 页面卸载）。
      try {
        const api = host.browser
        if (api && this._desktopViewId) api.detach({ id: this._desktopViewId })
      } catch (e) {
        // ignore
      }
    },

    // 把 BrowserView 此刻的真实状态抄回工具栏与标签（复用旧 view 时尤其重要：
    // 组件是新的，它对这个网页一无所知，只能问主进程）
    adoptViewState(state) {
      if (!state) return
      const url = state.url ? String(state.url) : ''
      this.viewCanGoBack = !!state.canGoBack
      this.viewCanGoForward = !!state.canGoForward
      if (typeof state.mobile === 'boolean') this.isMobileMode = state.mobile
      if (url && url !== 'about:blank' && url !== this.currentUrl) {
        this.currentUrl = url
        this.inputUrl = url
        // 换了页，旧标题作废（真标题随下面的 state.title 或 title-updated 补回来）
        this.pageTitle = ''
        this.$emit('url-change', url)
      }
      // url-change 会把标签名退成域名（父级按 host 命名），标题随后补回来。
      // 复用旧 view 不会重新加载，等不到 page-title-updated，所以这里要主动带上。
      const title = state.title ? String(state.title) : ''
      if (title) {
        this.pageTitle = title
        this.$emit('title-change', title)
      }
    },
    // H5：iframe 里的文档换了一页（点链接 / 站点自己重定向）。只更新"显示与记账"那一侧，
    // 绝不回写 iframeUrl —— 那会让已经站在新页上的 iframe 再加载一次。
    // 与桌面端的 adoptViewState 是同一件事的两种实现（那边问主进程，这边等页面回报）。
    adoptPageUrl(url) {
      const next = String(url || '').trim()
      if (!next || next === this.currentUrl) {
        this._pendingNav = false
        return
      }
      this.currentUrl = next
      this.inputUrl = next
      this.pageTitle = ''
      if (this._pendingNav && this.index >= 0) {
        // 我们刚要求加载的那一页被站点重定向走了：替换栈顶而不是新增一条，
        // 否则「后退」会回到那个只会再跳一次的地址，按钮看着能用其实在原地打转
        this.history.splice(this.index, 1, next)
      } else {
        if (this.index < this.history.length - 1) this.history = this.history.slice(0, this.index + 1)
        this.history.push(next)
        this.index = this.history.length - 1
      }
      this._pendingNav = false
      this.$emit('url-change', next)
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
      this.pageTitle = ''

      // Desktop：直接导航 BrowserView。历史由 view 自己维护（见 goBack/goForward），
      // 组件里那份 history 数组只服务 H5 的 iframe 模式。
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
        this.$emit('url-change', next)
        return
      }

      // H5：这一条才是真正让 iframe 去加载
      this.iframeUrl = next
      this._pendingNav = true

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
    // 后退/前进/刷新：桌面端交给 BrowserView 自己的历史。
    // 此前这三个按钮在桌面端只改了地址栏文字、从没驱动过 BrowserView（点了没反应）；
    // 而且组件里的 history 数组会随面板卸载清空，保活之后更没法当历史用。
    desktopHistory(action) {
      try {
        const api = host.browser
        if (!api || !api.history || !this._desktopViewId) return false
        Promise.resolve(api.history({ id: this._desktopViewId, action })).catch(() => {})
        return true
      } catch (e) {
        return false
      }
    },
    reload() {
      if (this.isDesktopBrowser) {
        this.desktopHistory('reload')
        return
      }
      // H5：重新设置 src 触发刷新。刷的是 currentUrl（用户此刻看的这一页），
      // 不是 iframe 当初被要求加载的那一页——页内跳转之后两者已经不是一回事了
      const u = this.currentUrl
      this.iframeUrl = 'about:blank'
      this.$nextTick(() => {
        this.iframeUrl = u
        this._pendingNav = true
      })
    },
    goBack() {
      if (!this.canGoBack) return
      if (this.isDesktopBrowser) {
        this.desktopHistory('back')
        return
      }
      this.index -= 1
      this.goToHistoryEntry()
    },
    goForward() {
      if (!this.canGoForward) return
      if (this.isDesktopBrowser) {
        this.desktopHistory('forward')
        return
      }
      this.index += 1
      this.goToHistoryEntry()
    },
    // H5 前进/后退：history 里那一条既要显示出来，也要真的让 iframe 去加载
    goToHistoryEntry() {
      this.currentUrl = this.history[this.index]
      this.inputUrl = this.currentUrl
      this.iframeUrl = this.currentUrl
      this._pendingNav = true
      this.$emit('url-change', this.currentUrl)
    },
    openInAppNewTab() {
      // 这里 emit 原始 url（不是 iframeSrc）
      this.$emit('open-new-tab', this.currentUrl)
    },
    onIframeLoad(e) {
      // 由后端 proxy 注入脚本统一处理 _blank / window.open；这里无需再做同源注入
    },

    // ===== 收藏本页 / 收藏夹与快捷方式抽屉 =====
    // URL 判同的口径：忽略末尾斜杠（http://a.com 与 http://a.com/ 是同一页）
    favUrlKey(u) {
      const raw = String(u || '').trim()
      if (!raw || !/^https?:\/\//.test(raw)) return ''
      return raw.replace(/\/$/, '')
    },
    hostOf(u) {
      try {
        return u ? new URL(String(u)).host : ''
      } catch (e) {
        return ''
      }
    },
    toggleShelf() {
      this.shelfOpen = !this.shelfOpen
      // 每次展开都重读一遍：快捷方式可能在别的浏览器标签实例里刚钉过（保活池里
      // 每个标签一个组件实例、各持一份内存态），收藏也随时会从别的入口新增
      if (this.shelfOpen) {
        this.loadShortcuts()
        this.loadFavorites()
      }
    },
    async loadFavorites() {
      if (this.favLoading) return
      this.favLoading = true
      try {
        const pid = this.projectId ? (typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId) : null
        const list = pid ? await getProjectFavorites(pid, '', 80) : await getMyFavorites()
        const arr = Array.isArray(list) ? list : ((list && list.data) || [])
        this.favorites = arr.filter(f => f && f.sourceUrl)
      } catch (e) {
        // 列表拉不到只影响星形态与抽屉展示，静默（抽屉里显示空态）
      } finally {
        this.favLoading = false
      }
    },
    async favoriteCurrentPage() {
      if (!this.canFavorite || this._favSaving) return
      if (this.isCurrentFavorited) {
        // 取「提示已收藏」而不是「再点取消收藏」：星形态来自按 URL 去重的列表，
        // 同一页可能有多条文字摘录收藏，一键反删会连用户手工摘的内容一起删掉
        uni.showToast({ title: this.$t('panels.bpAlreadyFavorited'), icon: 'none' })
        return
      }
      this._favSaving = true
      const url = this.currentUrl
      try {
        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const h = this.hostOf(url)
        const title = (this.pageTitle || '').trim() || h || url
        const created = await createProjectFavorite(pid, {
          title,
          sourceUrl: url,
          content: title,
          imageBase64: '',
          // 卡片右下角的来源域名读的是 meta.sourceHost，不写就永远空白（与 onWebMark 同口径）
          meta: JSON.stringify({ kind: 'webmark', capturedAt: new Date().toISOString(), sourceUrl: url, title, sourceHost: h })
        })
        const favId = created && created.id ? created.id : (created && created.data && created.data.id ? created.data.id : null)
        const key = this.favUrlKey(url)
        if (key && !this.localFavUrls.includes(key)) this.localFavUrls.push(key)
        this.loadFavorites()
        // 可见反馈交给父级：打开收藏面板并高亮新卡片（toast 在桌面端会被原生
        // BrowserView 盖住，星形变实心 + 面板高亮才是真实可见的反馈）
        this.$emit('favorite-added', { id: favId, url, title })
      } catch (e) {
        // 失败提示同 onWebMark：桌面端走不被 BrowserView 遮挡的原生弹窗
        if (host.app && host.app.confirm) {
          host.app.confirm({ title: this.$t('panels.bpFavoriteFailed'), content: (e && e.message) || '' }).catch(() => {})
        } else {
          uni.showToast({ title: (e && e.message) || this.$t('panels.bpFavoriteFailed'), icon: 'none' })
        }
      } finally {
        this._favSaving = false
      }
    },
    loadShortcuts() {
      try {
        const raw = uni.getStorageSync(SHORTCUTS_STORAGE_KEY)
        const arr = typeof raw === 'string' && raw ? JSON.parse(raw) : raw
        this.shortcuts = Array.isArray(arr) ? arr.filter(s => s && s.url) : []
      } catch (e) {
        this.shortcuts = []
      }
    },
    saveShortcuts() {
      try {
        uni.setStorageSync(SHORTCUTS_STORAGE_KEY, this.shortcuts)
      } catch (e) {
        // ignore
      }
    },
    pinCurrentPage() {
      if (!this.canPin) return
      const url = this.currentUrl
      const key = this.favUrlKey(url)
      if (!key) return
      if (this.shortcuts.some(s => this.favUrlKey(s.url) === key)) {
        uni.showToast({ title: this.$t('panels.bpAlreadyPinned'), icon: 'none' })
        return
      }
      const title = (this.pageTitle || '').trim() || this.hostOf(url) || url
      this.shortcuts.push({ url, title })
      this.saveShortcuts()
    },
    removeShortcut(index) {
      this.shortcuts.splice(index, 1)
      this.saveShortcuts()
    },
    openShelfUrl(url) {
      if (!url) return
      this.shelfOpen = false
      this.navigate(url)
    },
    async toggleMobileMode() {
      if (!this.isDesktopBrowser) return
      this.isMobileMode = !this.isMobileMode
      const api = host.browser
      if (!api || !this._desktopViewId) return
      
      const mobileUA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1'
      const desktopUA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      
      try {
        // mobile 一并告诉主进程：view 是保活的，切回来时工具栏要照它的真实 UA 回填
        await api.setUA({
          id: this._desktopViewId,
          ua: this.isMobileMode ? mobileUA : desktopUA,
          mobile: this.isMobileMode
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

/* 星形已收藏 / 抽屉展开：森林绿点缀，保持克制 */
.browser-btn.on {
  color: #1A5336;
  border-color: rgba(26, 83, 54, 0.45);
  background: #f0f7f3;
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

/* 收藏夹/快捷方式抽屉：普通文档流元素（flex 列的一段），展开即挤压下方
   .browser-body 的高度——绝不能 absolute 浮层（会被原生 BrowserView 盖住）。
   flex-basis 240px、允许收缩：面板很矮时抽屉与 body 一起让步。 */
.browser-shelf {
  flex: 0 1 240px;
  min-height: 96px;
  overflow-y: auto;
  padding: 10px 12px 12px;
  background: #fbfcfd;
  border-bottom: 1px solid #e5e7eb;
}

.shelf-section + .shelf-section {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid rgba(148, 163, 184, 0.25);
}

.shelf-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.shelf-title {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  letter-spacing: 0.02em;
}

.shelf-action {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid rgba(26, 83, 54, 0.35);
  border-radius: 999px;
  color: #1A5336;
  font-size: 12px;
  cursor: pointer;
  background: #ffffff;
}

.shelf-action:hover {
  background: #f0f7f3;
  border-color: #1A5336;
}

.shelf-action.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.shelf-action-icon {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
}

.shelf-empty {
  font-size: 12px;
  color: #94a3b8;
  padding: 6px 2px;
}

.shelf-items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.shelf-card {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 190px;
  padding: 7px 9px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 8px;
  background: #ffffff;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.shelf-card:hover {
  border-color: rgba(26, 83, 54, 0.55);
  box-shadow: 0 1px 4px rgba(26, 83, 54, 0.12);
}

.shelf-card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.shelf-card-title {
  font-size: 12px;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.shelf-card-host {
  font-size: 11px;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.shelf-card-x {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 11px;
  visibility: hidden;
}

.shelf-card:hover .shelf-card-x {
  visibility: visible;
}

.shelf-card-x:hover {
  background: rgba(148, 163, 184, 0.18);
  color: #475569;
}

.browser-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  /* 供 .browser-create-error 绝对定位铺满 */
  position: relative;
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

.browser-create-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: #f8fafc;
  padding: 24px;
  text-align: center;
}

.bce-text {
  font-size: 14px;
  color: #475569;
}

.bce-detail {
  font-size: 12px;
  color: #94a3b8;
  word-break: break-all;
  max-width: 420px;
}

.bce-retry {
  margin-top: 4px;
  padding: 7px 18px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #ffffff;
  color: #1A5336;
  font-size: 13px;
  cursor: pointer;
}

.bce-retry:hover {
  border-color: #1A5336;
  background: #f0f7f3;
}

.btn-icon-svg {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}
</style>


