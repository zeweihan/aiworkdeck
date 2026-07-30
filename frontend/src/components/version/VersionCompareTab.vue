<!--
  版本对比只读标签页（第 2 期 Task 3）。

  与 LibreOfficeEditor 的本质区别（存在理由：绝不能把历史版本字节写回当前真实
  文件，见 CLAUDE.md 顶部说明）——本组件：
  1. 不监听 lo-relay 的 {type:'modified'} 信号，没有任何保存/上传代码路径。
  2. 不进保活池（不注册 _libreRefs/LRU），随标签页关闭即销毁。
  3. beforeUnmount 只 dispose executor + 移除 webview。

  流程：并行下载新旧两版字节 + 启动引擎 → load_document 新版 → compare_document
  一次性生成修订并自动切只读（.uno:EditDoc 是 toggle 语义，同一实例只能调一次，
  失败重试交给父级换 key 整体重建，本组件内部不做 retry）。
-->
<template>
  <view class="vcmp-root">
    <view v-if="!ready" class="vcmp-status">
      <text>{{ statusText }}</text>
    </view>
    <view v-show="ready" :id="hostId" class="vcmp-host"></view>
    <view v-if="ready" class="vcmp-banner">
      <text>版本对比（只读）：左删右增的修订即两版差异，共 {{ redlineCount }} 处</text>
    </view>
  </view>
</template>

<script>
import { createWebviewEditorExecutor } from '@/composables/useZetaOfficeWebview.js'
import { fetchVersionFileBytes } from '@/services/api.js'

let seq = 0

export default {
  name: 'VersionCompareTab',
  props: {
    // {projectId, path, newRef, oldRef}
    compareSpec: { type: Object, required: true },
  },
  data() {
    return {
      hostId: 'vcmp-host-' + (++seq),
      ready: false,
      statusText: '正在准备对比…',
      redlineCount: 0,
      webviewEl: null,
      executor: null,
    }
  },
  async mounted() {
    try {
      const api = typeof window !== 'undefined' && window.checkbaDesktop && window.checkbaDesktop.zetaoffice
      if (!api || typeof api.getEditor !== 'function') {
        this.statusText = '版本对比仅桌面版可用'
        return
      }
      const spec = this.compareSpec
      // 字节下载与引擎启动并行——两者互不依赖，串行只会白白拉长等待。
      const bytesPromise = Promise.all([
        fetchVersionFileBytes(spec.projectId, spec.newRef, spec.path),
        fetchVersionFileBytes(spec.projectId, spec.oldRef, spec.path),
      ])
      const info = await api.getEditor() // { url, preload, partition }
      await this.mountWebview(info) // resolves once 引擎端点就绪（onReady 握手），此前不发命令
      const [newBytes, oldBytes] = await bytesPromise
      this.statusText = '正在生成对比…'
      const name = spec.path.split('/').pop() || 'compare.docx'
      const loaded = await this.executor.executeCommand('load_document', {
        bytes: newBytes, name, authorName: '版本对比',
      })
      if (!loaded || loaded.success === false) throw new Error('加载新版失败')
      // 每实例只能调一次：结尾会派发 .uno:EditDoc（toggle）切回只读。
      const cmp = await this.executor.executeCommand('compare_document', { baseBytes: oldBytes })
      if (!cmp || cmp.success !== true) throw new Error('对比生成失败')
      this.redlineCount = cmp.redlineCount || 0
      this.ready = true
    } catch (e) {
      console.warn('[VersionCompare] 失败', e)
      this.statusText = (e && e.message) || '对比生成失败，请稍后重试'
    }
  },
  beforeUnmount() {
    // 只销毁，绝无保存路径——这是与 LibreOfficeEditor 的本质区别。不进保活池，
    // 不注册 _libreRefs/LRU，销毁即销毁。
    if (this._bootTimeout) clearTimeout(this._bootTimeout)
    try { if (this.executor && typeof this.executor.dispose === 'function') this.executor.dispose() } catch (e) { /* ignore */ }
    try { if (this.webviewEl && this.webviewEl.remove) this.webviewEl.remove() } catch (e) { /* ignore */ }
    this.webviewEl = null
    this.executor = null
  },
  methods: {
    // 挂载 <webview>，等到引擎端点就绪（lo-relay 的 onReady 握手）才 resolve——
    // 与 LibreOfficeEditor.onDomReady/onEndpointReady 是同一套两段式握手：
    // dom-ready 只说明 webview 渲染进程起来了，onReady 才说明 office 端点起来、
    // 命令不会被丢——真实签名见 useZetaOfficeWebview.js:54 起
    // （createWebviewEditorExecutor(el, {onReady}) ，没有 whenReady()）。
    mountWebview(info) {
      return new Promise((resolve, reject) => {
        const host = document.getElementById(this.hostId)
        if (!host) { reject(new Error('host missing')); return }
        // 引擎启动超时：150秒（WASM 编译 + 排版引擎约 90秒，留余量）。
        // onReady / did-fail-load 先到时清除，防止引擎挂死时 Promise 永不 settle。
        this._bootTimeout = setTimeout(() => {
          reject(new Error('对比准备超时，请关闭后重试'))
        }, 150000)
        const wv = document.createElement('webview')
        wv.setAttribute('partition', info.partition)
        if (info.preload) wv.setAttribute('preload', info.preload)
        wv.setAttribute('webpreferences', 'contextIsolation=yes,nodeIntegration=no')
        wv.style.width = '100%'; wv.style.height = '100%'; wv.style.border = '0'
        wv.addEventListener('dom-ready', () => {
          if (this.executor) return // dom-ready 可能因页内导航重复触发
          try {
            // 只读宿主：不订阅 lo-relay 的 modified 信号（没有自动保存这回事）。
            this.executor = createWebviewEditorExecutor(wv, {
              onReady: () => {
                clearTimeout(this._bootTimeout)
                resolve()
              }
            })
          } catch (e) {
            clearTimeout(this._bootTimeout)
            reject(e)
          }
        })
        wv.addEventListener('did-fail-load', (e) => {
          clearTimeout(this._bootTimeout)
          reject(new Error('did-fail-load: ' + (e.errorDescription || e.errorCode)))
        })
        wv.src = info.url
        host.appendChild(wv)
        this.webviewEl = wv
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.vcmp-root { display: flex; flex-direction: column; height: 100%; }
.vcmp-status { flex: 1; display: flex; align-items: center; justify-content: center; color: #888; font-size: 26rpx; }
.vcmp-host { flex: 1; min-height: 0; }
.vcmp-banner {
  padding: 10rpx 20rpx; background: #F7F5F0; border-top: 1px solid #eee;
  font-size: 23rpx; color: #666;
}
</style>
