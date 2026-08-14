<template>
  <view class="drawio-editor">
    <view v-if="phase === 'loading'" class="drawio-status">
      <text class="drawio-status-text">{{ $t('editor.drawio.opening') }}</text>
    </view>

    <!-- 这次构建没烙 draw.io 资源（Web 部署未放 dist/drawio，或壳层版本旧）。
         说清楚比挂一个永远转圈的 iframe 诚实。 -->
    <view v-else-if="phase === 'unavailable'" class="drawio-status">
      <text class="drawio-status-text">{{ $t('editor.drawio.unavailable') }}</text>
      <text class="drawio-status-hint">
        {{ $t('editor.drawio.downloadHint', { name: (file && file.name) || '' }) }}
      </text>
      <view class="drawio-btn" role="button" @tap="download">{{ $t('editor.drawio.downloadFile') }}</view>
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
import { getFileDownloadUrl, saveDrawioDiagram } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'
import { host } from '@/services/host.js'

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
      pendingExport: null     // 保存链路里等 SVG 的那个 resolve
    }
  },
  mounted() {
    window.addEventListener('message', this.onMessage)
    this.boot()
  },
  beforeUnmount() {
    window.removeEventListener('message', this.onMessage)
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
      try {
        const api = host.drawio
        if (!api || typeof api.getEditor !== 'function') {
          this.phase = 'unavailable'
          return
        }
        const info = await api.getEditor()
        if (!info || !info.available) {
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

    // 直接取原始字节读成文本。**不能走 /api/files/{id}/text** —— 那条路会过
    // Tika 抽取，对 .drawio 这种未知扩展名可能把 XML 揉成纯文本，喂回编辑器就是
    // 一张空白图。
    async loadXml() {
      const id = this.file && (this.file.wpsFileId || this.file.id)
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

    async persist(xml) {
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
      const id = this.file && (this.file.wpsFileId || this.file.id)
      if (!id) return
      if (host.shell && typeof host.shell.openExternal === 'function') {
        host.shell.openExternal(getFileDownloadUrl(id))
        return
      }
      window.open(getFileDownloadUrl(id), '_blank')
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
</style>
