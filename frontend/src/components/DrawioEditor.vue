<template>
  <view class="drawio-editor">
    <view v-if="phase === 'loading'" class="drawio-status">
      <text class="drawio-status-text">{{ $t('editor.drawio.opening') }}</text>
    </view>

    <!-- 这次构建没烙 draw.io 资源，或者资源在但那个 origin 探不通。说清楚比挂一个
         永远转圈的 iframe、或者让浏览器的 404 页占满整个编辑区诚实。
         桌面态且探明是"资源包没装"（packId 有值）时给一条能当场解决的路——
         v0.21.0 起 draw.io 摘出安装包改走 native pack，见 litigation-visual.md。 -->
    <view v-else-if="phase === 'unavailable'" class="drawio-status">
      <text class="drawio-status-text">{{ $t('editor.drawio.unavailable') }}</text>
      <text class="drawio-status-hint">
        {{ showInstallAction ? $t('editor.drawio.installHint') : $t('editor.drawio.downloadHint', { name: (file && file.name) || '' }) }}
      </text>
      <view class="drawio-actions">
        <view
          v-if="showInstallAction"
          class="drawio-btn primary"
          :class="{ disabled: installButtonDisabled }"
          role="button"
          @tap="installPack"
        >{{ installButtonText }}</view>
        <view class="drawio-btn" role="button" @tap="download">{{ $t('editor.drawio.downloadFile') }}</view>
        <view class="drawio-btn" role="button" @tap="boot">{{ $t('editor.drawio.retry') }}</view>
      </view>
    </view>

    <view v-else-if="phase === 'error'" class="drawio-status">
      <text class="drawio-status-text">{{ errorText }}</text>
      <view class="drawio-btn" role="button" @tap="boot">{{ $t('editor.drawio.retry') }}</view>
    </view>

    <template v-else>
      <view class="drawio-bar">
        <text class="drawio-name">{{ file && file.name }}</text>
        <text class="drawio-dirty" v-if="dirty">{{ $t('editor.drawio.unsaved') }}</text>
        <text class="drawio-saved" v-else-if="savedTip">{{ savedTip }}</text>
      </view>
      <!-- iframe 不加 sandbox：draw.io 要同源读自己的资源，sandbox 会把它按跨源
           处理，图形面板与本地存储全废。它加载的是随包的本地文件、且带 stealth=1
           禁止外发，风险面本来就是封闭的。 -->
      <iframe
        ref="frame"
        :src="editorUrl"
        class="drawio-frame"
        frameborder="0"
      ></iframe>
    </template>
  </view>
</template>

<script>
// 内嵌 draw.io 编辑器。
//
// 「诉讼可视化」一张图落四个文件：.svg（母版）/.png（插进文书用）/.drawio（可继续
// 编辑）/.map.json（语义地图）。本组件负责 .drawio 那一份——没有它，"可编辑版"
// 只是个必须装别的软件才能打开的死文件。
//
// 协议：draw.io 的 embed + proto=json（postMessage）。
//   iframe -> init            编辑器就绪，此时才能喂内容
//   host   -> load{xml}       载入图形
//   iframe -> save{xml}       用户点了保存
//   host   -> export{svg}     要一份 SVG（客户端渲染，离线可用）
//   iframe -> export{data}    data: URI 形式的 SVG
//   iframe -> exit            用户点了退出
//
// 落盘由后端一次做完三份（.drawio / .svg / .png），见
// LitigationVisualPanelService.saveDrawio。**PNG 不用 draw.io 自己导的位图**——
// 随包中文字体的注册与字体栈兜底都在服务端 Batik 那条路上，绕过它标题在干净的
// Windows 上会变成方块（记在案的地雷）。
import { getFileDownloadUrl, saveDrawioDiagram, packStatus, packInstall } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'
import { host, isDesktopHost } from '@/services/host.js'
import { createSerialQueue } from '@/utils/asyncSerialize.js'

export default {
  name: 'DrawioEditor',
  props: {
    file: { type: Object, default: null },
    projectId: { type: [String, Number], default: null }
  },
  emits: ['saved'],
  data() {
    return {
      phase: 'loading',       // loading | ready | unavailable | error
      editorUrl: '',
      errorText: '',
      xml: '',
      dirty: false,
      savedTip: '',
      pendingExport: null,    // 保存链路里等 SVG 的那个 resolve
      // 桌面态资源包引导安装：packId 只在 getEditor() 明确回答"资源不在场"时才有值，
      // 见 boot() 里的判定。与 LitigationVisualPanel.vue 的 native pack 状态条同一套
      // 契约（packStatus/packInstall + 轮询），docs/NATIVE_PACK_DISTRIBUTION.md §5/§7.1。
      packId: null,
      packState: null,        // packStatus() 结果 {state, bytesDownloaded, bytesTotal, error}
      packInstalling: false,
      packTimer: null,
      // 并发闸：把 save 事件串行化，堵住 exportSvg() 单槽 pendingExport 被并发调用
      // 互相覆盖、其中一次保存永久挂起的竞态（见 persist() 处注释）。
      _persistQueue: createSerialQueue()
    }
  },
  computed: {
    showInstallAction() {
      return this.phase === 'unavailable' && !!this.packId && isDesktopHost()
    },
    installButtonDisabled() {
      const s = this.packState
      return this.packInstalling || !!(s && (s.state === 'downloading' || s.state === 'installing' || s.state === 'verifying'))
    },
    installButtonText() {
      const s = this.packState
      if (!s || s.state === 'not_installed') return this.$t('editor.drawio.installPack')
      if (s.state === 'failed') return this.$t('editor.drawio.installPackRetry')
      const total = s.bytesTotal || 0
      if (total > 0) {
        return this.$t('editor.drawio.installPackProgress', {
          downloaded: ((s.bytesDownloaded || 0) / (1024 * 1024)).toFixed(1),
          total: (total / (1024 * 1024)).toFixed(1)
        })
      }
      return this.$t('editor.drawio.installPackDownloading')
    }
  },
  mounted() {
    window.addEventListener('message', this.onMessage)
    this.boot()
  },
  beforeUnmount() {
    window.removeEventListener('message', this.onMessage)
    this.stopPackPoll()
  },
  watch: {
    'file.id'() {
      this.dirty = false
      this.savedTip = ''
      this.boot()
    }
  },
  methods: {
    async boot() {
      this.phase = 'loading'
      this.errorText = ''
      this.packId = null
      this.stopPackPoll()
      try {
        const api = host.drawio
        if (!api || typeof api.getEditor !== 'function') {
          this.phase = 'unavailable'
          return
        }
        const info = await api.getEditor()
        if (!info || !info.available || !info.url) {
          // packId 只有 main.js 的 checkba:drawio-editor 明确判过资源不在场才带（见
          // desktop/main/main.js），标志着"装个 pack 就能解决"而不是别的什么坏了
          this.packId = (info && info.packId) || null
          this.phase = 'unavailable'
          if (this.packId && isDesktopHost()) this.refreshPackStatus()
          return
        }
        // 先探一下这个 URL 真的能取到再挂 iframe。宿主只回答了"资源目录里有
        // index.html"，回答不了"这个 origin 现在真的在服务它"：端口被别的进程占住、
        // 资产被前端构建清掉、Web 部署没放 dist/drawio，任何一种都会让 iframe 直接
        // 渲染一张裸 404 页占满整个编辑区——用户看到的是浏览器的错误页，
        // 而不是"这里没有编辑器"。探不通就走 unavailable 那条诚实的路。
        if (!(await this.probeEditor(info.url))) {
          this.phase = 'unavailable'
          return
        }
        this.xml = await this.loadXml()
        this.editorUrl = info.url
        this.phase = 'ready'
      } catch (e) {
        this.errorText = (e && e.message) || this.$t('editor.drawio.openFailed')
        this.phase = 'error'
      }
    },

    // 编辑器 URL 可达性探测。三点都是必要的：
    // - 探 iframe 真正要加载的那个 URL，不是别的路径；
    // - 用 GET 而不是 HEAD：静态服务未必实现 HEAD；
    // - 认一眼内容。Web 部署常见的 SPA 兜底（try_files … /index.html）会对任何
    //   不存在的路径回 200 + 本应用的首页，只看状态码会把"没部署 draw.io"判成可用，
    //   于是 iframe 里套一个自己。draw.io 的 index.html 必有 geEditor 这个容器 id。
    async probeEditor(url) {
      try {
        const res = await fetch(url, { method: 'GET', cache: 'no-store' })
        if (!res || !res.ok) return false
        const body = await res.text()
        return body.includes('geEditor')
      } catch (e) {
        return false
      }
    },

    // 下载用的文件标识。**优先数字主键**：wpsFileId 只是"这个文件是谁造的"的自由
    // 字段，同一次出图的几个产物历史上共享过同一个前缀+毫秒时间戳，而后端在按数字
    // 查不到时会退回 findByWpsFileId(...).findFirst()——撞号时"打开 .drawio"会
    // 取到同一张图的 .svg。数字 id 没有这个歧义。
    fileRef() {
      const f = this.file
      if (!f) return null
      return f.id != null ? f.id : (f.wpsFileId || null)
    },

    // 直接取原始字节读成文本。**不能走 /api/files/{id}/text** —— 那条路会过
    // Tika 抽取，对 .drawio 这种未知扩展名可能把 XML 揉成纯文本，喂回编辑器就是
    // 一张空白图。
    async loadXml() {
      const id = this.fileRef()
      if (!id) throw new Error(this.$t('editor.drawio.fileMissing'))
      const res = await fetch(getFileDownloadUrl(id), { headers: getAuthHeaders() || {} })
      if (!res.ok) throw new Error(this.$t('editor.drawio.readFailed', { status: res.status }))
      const text = await res.text()
      if (!text || !text.trim()) throw new Error(this.$t('editor.drawio.fileEmpty'))
      return text
    },

    post(msg) {
      const fr = this.$refs.frame
      if (fr && fr.contentWindow) fr.contentWindow.postMessage(JSON.stringify(msg), '*')
    },

    onMessage(e) {
      const fr = this.$refs.frame
      // 只认自己这个 iframe 发来的消息：页面上还挂着别的 iframe（浏览器面板、
      // LOWA 编辑器），不认来源的话会互相串。
      if (!fr || e.source !== fr.contentWindow) return
      let msg
      try { msg = JSON.parse(e.data) } catch (err) { return }
      if (!msg || !msg.event) return

      if (msg.event === 'init') {
        this.post({ action: 'load', autosave: 0, xml: this.xml })
        return
      }
      if (msg.event === 'change') {
        this.dirty = true
        this.savedTip = ''
        return
      }
      if (msg.event === 'save') {
        this.persist(msg.xml)
        return
      }
      if (msg.event === 'export') {
        if (this.pendingExport) {
          this.pendingExport(msg.data || '')
          this.pendingExport = null
        }
        return
      }
    },

    // 向编辑器要一份 SVG。拿不到就返回空——保存不该因为导出这一步失败而整个失败，
    // 后端收到空 svg 会只写 .drawio、不动 .svg/.png。
    exportSvg(xml) {
      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          if (this.pendingExport) {
            this.pendingExport = null
            resolve('')
          }
        }, 8000)
        this.pendingExport = (data) => {
          clearTimeout(timer)
          resolve(data)
        }
        this.post({ action: 'export', format: 'svg', xml })
      })
    },

    // 并发闸：exportSvg() 的 pendingExport 是单槽 resolver，两次 persist 同时在飞
    // （比如用户在 draw.io 里快速触发两次保存）会互相覆盖——第二次覆盖第一次的
    // resolver，draw.io 对第一次请求的回包到达时只喂得到"当前槽"（第二次的
    // resolver），第一次真正的回包随后到达时槽已是 null，被直接丢弃；第一次
    // exportSvg() 的 await 因此永久挂起，那次 persist 静默烂尾（不写盘、不报错、
    // 不改保存提示）。用队列把 save 事件串行化，保证同一时刻只有一次 exportSvg
    // 在飞，单槽也就天然安全。
    persist(xml) {
      return this._persistQueue(() => this.persistNow(xml))
    },
    async persistNow(xml) {
      if (!xml) return
      this.savedTip = this.$t('editor.drawio.saving')
      try {
        const dataUri = await this.exportSvg(xml)
        const svg = this.decodeSvg(dataUri)
        const res = await saveDrawioDiagram(this.projectId, this.file.id, { xml, svg })
        this.xml = xml
        this.dirty = false
        this.savedTip = this.$t('editor.drawio.saved')
        // 同一张图的 .svg / .png 被一起改了，已打开的标签要重拉。
        // 复用换风格那条既有广播，订阅方不用改。
        uni.$emit('awd:litviz-restyled', {
          folderId: this.file.parentId,
          svgFileId: res && res.svgFileId
        })
        this.$emit('saved', res)
      } catch (e) {
        this.savedTip = ''
        uni.showToast({ title: (e && e.message) || this.$t('editor.drawio.saveFailed'), icon: 'none' })
      }
    },

    // draw.io 的 export 给的是 data:image/svg+xml;base64,… 。base64 解出来是
    // UTF-8 字节，atob 只到 latin1，中文标签必须再解一层，否则存进去是乱码。
    decodeSvg(dataUri) {
      if (!dataUri) return ''
      const comma = dataUri.indexOf(',')
      if (comma < 0) return ''
      const payload = dataUri.slice(comma + 1)
      if (!/;base64/i.test(dataUri.slice(0, comma))) return decodeURIComponent(payload)
      try {
        const bin = atob(payload)
        const bytes = new Uint8Array(bin.length)
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
        return new TextDecoder('utf-8').decode(bytes)
      } catch (e) {
        return ''
      }
    },

    download() {
      const id = this.fileRef()
      if (!id) return
      if (host.shell && typeof host.shell.openExternal === 'function') {
        host.shell.openExternal(getFileDownloadUrl(id))
        return
      }
      window.open(getFileDownloadUrl(id), '_blank')
    },

    // ---- 图形编辑器组件（native pack）引导安装 ----
    async refreshPackStatus() {
      if (!this.packId) return
      try {
        const res = await packStatus(this.packId)
        this.packState = (res && res.status) || null
      } catch (e) {
        // 拉不到状态：旧后端没有这个端点——按「不可知」处理，用户仍可点「下载文件」兜底
        this.packState = null
        this.stopPackPoll()
        return
      }
      const state = this.packState && this.packState.state
      if (state === 'ready') {
        this.stopPackPoll()
        this.boot() // 装好了，自动重新挂编辑器，不用用户再点一次
        return
      }
      if (state === 'failed') {
        this.stopPackPoll()
        return
      }
      if (state && !this.packTimer) this.startPackPoll()
    },
    startPackPoll() {
      this.stopPackPoll()
      this.packTimer = setInterval(() => { this.refreshPackStatus() }, 1000)
    },
    stopPackPoll() {
      if (this.packTimer) { clearInterval(this.packTimer); this.packTimer = null }
    },
    async installPack() {
      if (!this.packId || this.installButtonDisabled) return
      this.packInstalling = true
      try {
        await packInstall(this.packId)
        await this.refreshPackStatus()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('editor.drawio.installPackFailed'), icon: 'none' })
      } finally {
        this.packInstalling = false
      }
    }
  }
}
</script>

<style scoped>
.drawio-editor {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #ffffff;
}

.drawio-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-bottom: 1px solid #ebedf0;
  flex-shrink: 0;
}
.drawio-name { font-size: 12px; color: #1f2329; }
.drawio-dirty { font-size: 11px; color: #8a5a2b; }
.drawio-saved { font-size: 11px; color: #2b6a4d; }

.drawio-frame {
  flex: 1;
  width: 100%;
  border: none;
  min-height: 0;
}

.drawio-status {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px;
}
.drawio-status-text { font-size: 13px; color: #4a5058; }
.drawio-status-hint { font-size: 12px; color: #9aa0a8; text-align: center; line-height: 1.6; }
.drawio-actions { display: flex; gap: 8px; }
.drawio-btn {
  padding: 6px 16px;
  font-size: 12px;
  border-radius: 5px;
  border: 1px solid #e3e6ea;
  color: #4a5058;
  background: #fff;
  cursor: pointer;
  user-select: none;
}
.drawio-btn:hover { border-color: #c9ced6; background: #fafbfc; }
.drawio-btn.primary {
  border-color: #3a7afe;
  background: #3a7afe;
  color: #fff;
}
.drawio-btn.primary:hover { border-color: #2f68e0; background: #2f68e0; }
.drawio-btn.disabled { opacity: .6; pointer-events: none; }
</style>
