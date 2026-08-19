<template>
  <view class="plugin-pane">
    <iframe
      v-if="url"
      ref="pluginFrame"
      :src="url"
      :sandbox="sandboxAttr"
      class="plugin-iframe"
      frameborder="0"
      @load="onFrameLoad"
    ></iframe>
    <view v-else class="plugin-error">
      <text>{{ $t('panels.ppLoadFailed') }}</text>
    </view>
  </view>
</template>

<script>
// 插件面板：承载 manifest.frontendEntry 指向的页面。两种形态，行为刻意不同。
//
// 1) 绝对 http(s) URL（旧形态）：iframe 直接打开，不加 sandbox、不发握手、不响应桥调用。
//    这类插件从来就是"内嵌一个外部网页"，改动它只会打断存量插件。
// 2) web/ 相对路径（规范 v2.3 的 Web 插件）：后端 /api/plugin-web/ 静态服务，
//    iframe 带 sandbox="allow-scripts allow-forms"，与宿主只通过 postMessage 桥通信。
//
// **sandbox 绝不能加 allow-same-origin。** iframe 一旦与应用同源，插件脚本就能读到
// localStorage 里的 X-Session-Id 并打全部 /api/*，等于把宿主的全部权限白送给插件；
// 没有它，iframe 是 opaque origin，除了这座桥没有第二条路。桥上每个方法都按
// manifest.permissions 逐调用校验——权限声明在这里第一次成为真实的执行边界
// （JAR 插件同 JVM 同权限，做不到这一点）。
//
// 桥协议（与 sdk/plugin-sdk/awd-plugin-sdk.js 及官网模板/宿主模拟器同一份契约，
// 三处任何一处单独改动都会让插件跑不起来）：
//   握手  宿主 -> 插件   { awd: 1, type: 'init', context: { pluginId, projectId, language, theme } }
//   请求  插件 -> 宿主   { awd: 1, type: 'call', seq, method, params }
//   响应  宿主 -> 插件   { awd: 1, type: 'result', seq, ok, result | error: { code, message } }
import { getProjectFiles, getFileText } from '@/services/api.js'
import { getAppLanguage } from '@/utils/appLanguage.js'

/** 桥协议版本 */
const PROTOCOL = 1

/** files.read 的文本上限，与 SDK 契约/官网宿主模拟器一致 */
const READ_LIMIT = 5 * 1024 * 1024

/** 插件级 KV 的总量上限（序列化后字节数近似），与 SDK 契约一致 */
const STORAGE_LIMIT = 64 * 1024

/** 插件级 KV 在宿主 localStorage 里的键前缀 */
const STORAGE_PREFIX = 'awd_plugin_kv_'

// files.read 允许读取的扩展名。后端 /api/files/{id}/text 会对 docx/pdf 这类做文本抽取，
// 所以白名单不止纯文本；名单之外一律当二进制拒绝，避免把一份 zip 的字节当"内容"塞回插件。
const READABLE_EXTS = new Set([
  'txt', 'md', 'markdown', 'json', 'csv', 'tsv', 'log', 'xml', 'html', 'htm',
  'yml', 'yaml', 'js', 'ts', 'css', 'sql', 'ini', 'conf', 'properties',
  'doc', 'docx', 'rtf', 'pdf', 'ppt', 'pptx', 'xls', 'xlsx'
])

function extOf(name) {
  const dot = String(name || '').lastIndexOf('.')
  return dot < 0 ? '' : String(name).slice(dot + 1).toLowerCase()
}

export default {
  name: 'PluginPane',
  props: {
    url: {
      type: String,
      default: ''
    },
    /** 插件 id（不是左栏的 plugin-<id> 面板 key）：进握手上下文，也是 KV 的分区键 */
    pluginId: {
      type: String,
      default: ''
    },
    /** manifest.permissions，桥按它逐调用裁剪能力 */
    permissions: {
      type: Array,
      default: () => []
    },
    /** 当前项目 id：files.* 的作用域，同时进握手上下文 */
    projectId: {
      type: [String, Number],
      default: ''
    }
  },
  computed: {
    // 只有走 /api/plugin-web/ 的 Web 插件才 sandbox + 握手。
    // 绑定 null 会让 Vue 移除该属性，绝对 URL 形态因此与改造前逐字节一致。
    isWebPlugin() {
      return String(this.url || '').indexOf('/api/plugin-web/') >= 0
    },
    sandboxAttr() {
      return this.isWebPlugin ? 'allow-scripts allow-forms' : null
    }
  },
  mounted() {
    window.addEventListener('message', this.onMessage)
  },
  beforeUnmount() {
    window.removeEventListener('message', this.onMessage)
  },
  methods: {
    onFrameLoad() {
      if (!this.isWebPlugin) return
      const frame = this.$refs.pluginFrame
      if (!frame || !frame.contentWindow) return
      // 时序：load 之后才握手。插件侧 SDK 是同步 <script>，此刻监听已就位。
      frame.contentWindow.postMessage(
        { awd: PROTOCOL, type: 'init', context: this.buildContext() },
        '*'
      )
    },

    buildContext() {
      return {
        pluginId: this.pluginId || '',
        projectId: this.projectId == null ? '' : String(this.projectId),
        language: getAppLanguage(),
        // 外壳恒为浅色（配色红线），字段保留是为了插件侧不必分支处理
        theme: 'light'
      }
    },

    async onMessage(event) {
      if (!this.isWebPlugin) return
      const frame = this.$refs.pluginFrame
      // 唯一的来源校验：只认本 iframe 的窗口。插件侧发的 targetOrigin 是 '*'
      // （opaque origin 拿不到宿主 origin），所以不能靠 event.origin 判断。
      if (!frame || !frame.contentWindow || event.source !== frame.contentWindow) return
      const msg = event.data
      if (!msg || msg.awd !== PROTOCOL || msg.type !== 'call') return

      let out
      try {
        out = await this.handleCall(String(msg.method || ''), msg.params || {})
      } catch (e) {
        out = { ok: false, error: { code: 'internal_error', message: (e && e.message) || '调用失败' } }
      }
      this.reply(msg.seq, out)
    },

    reply(seq, out) {
      const frame = this.$refs.pluginFrame
      if (!frame || !frame.contentWindow) return
      const msg = { awd: PROTOCOL, type: 'result', seq, ok: !!out.ok }
      if (out.ok) msg.result = out.result
      else msg.error = out.error
      frame.contentWindow.postMessage(msg, '*')
    },

    hasPermission(name) {
      return Array.isArray(this.permissions) && this.permissions.indexOf(name) >= 0
    },

    denied(permission) {
      return {
        ok: false,
        error: { code: 'permission_denied', message: 'manifest.permissions 未声明 ' + permission }
      }
    },

    async handleCall(method, params) {
      switch (method) {
        case 'context.get':
          return { ok: true, result: this.buildContext() }

        case 'files.list':
          if (!this.hasPermission('file_read')) return this.denied('file_read')
          return { ok: true, result: { files: (await this.listProjectFiles()).map(f => ({
            path: f.path, name: f.name, size: f.size
          })) } }

        case 'files.read':
          if (!this.hasPermission('file_read')) return this.denied('file_read')
          return await this.readProjectFile(String(params.path || ''))

        case 'ui.toast':
          uni.showToast({
            title: String(params.message == null ? '' : params.message),
            icon: 'none'
          })
          return { ok: true, result: {} }

        case 'storage.get': {
          const store = this.readStore()
          const key = String(params.key == null ? '' : params.key)
          return { ok: true, result: { key, value: store[key] === undefined ? null : store[key] } }
        }

        case 'storage.set': {
          const store = this.readStore()
          const next = Object.assign({}, store)
          next[String(params.key == null ? '' : params.key)] = params.value
          const serialized = JSON.stringify(next)
          if (serialized.length > STORAGE_LIMIT) {
            return { ok: false, error: { code: 'quota_exceeded', message: '插件存储超过 64 KB 上限' } }
          }
          this.writeStore(serialized)
          return { ok: true, result: {} }
        }

        default:
          return { ok: false, error: { code: 'unknown_method', message: '未知方法：' + method } }
      }
    },

    // ==== 项目文件 ====

    // 后端返回的是一份扁平全树（每条带 parentId），这里就地拼出项目内相对路径——
    // 插件拿到的 path 必须是人看得懂、且能原样喂回 files.read 的东西，不是数据库 id。
    async listProjectFiles() {
      if (!this.projectId) return []
      const res = await getProjectFiles(this.projectId, null, true)
      const rows = Array.isArray(res) ? res : (res && res.data) || []
      const byId = new Map()
      rows.forEach(r => byId.set(r.id, r))
      const pathOf = (row) => {
        const parts = []
        let cur = row
        let guard = 0
        while (cur && guard++ < 64) {
          parts.unshift(cur.name || String(cur.id))
          cur = cur.parentId ? byId.get(cur.parentId) : null
        }
        return parts.join('/')
      }
      return rows
        .filter(r => r && !r.isFolder)
        .map(r => ({ id: r.id, path: pathOf(r), name: r.name || '', size: r.fileSize || 0 }))
    },

    async readProjectFile(path) {
      if (!path) {
        return { ok: false, error: { code: 'not_found', message: '文件不存在：' + path } }
      }
      const hit = (await this.listProjectFiles()).find(f => f.path === path)
      if (!hit) {
        return { ok: false, error: { code: 'not_found', message: '文件不存在：' + path } }
      }
      if (!READABLE_EXTS.has(extOf(hit.name))) {
        return {
          ok: false,
          error: {
            code: 'permission_denied',
            message: '不是可读取的文本格式，files.read 只支持文本与可抽取文本的文档：' + hit.name
          }
        }
      }
      const res = await getFileText(hit.id)
      const raw = res && res.data != null ? String(res.data) : ''
      const truncated = raw.length > READ_LIMIT
      return {
        ok: true,
        result: { path: hit.path, content: truncated ? raw.slice(0, READ_LIMIT) : raw, truncated }
      }
    },

    // ==== 插件级 KV ====

    storageKey() {
      return STORAGE_PREFIX + (this.pluginId || 'unknown')
    },

    readStore() {
      try {
        const raw = window.localStorage.getItem(this.storageKey())
        const parsed = raw ? JSON.parse(raw) : null
        return parsed && typeof parsed === 'object' ? parsed : {}
      } catch (e) {
        return {}
      }
    },

    writeStore(serialized) {
      try {
        window.localStorage.setItem(this.storageKey(), serialized)
      } catch (e) {
        console.warn('[PluginPane] 写入插件存储失败:', e)
      }
    }
  }
}
</script>

<style scoped>
.plugin-pane {
  width: 100%;
  height: 100%;
  background-color: #fff;
  display: flex;
  flex-direction: column;
}

.plugin-iframe {
  flex: 1;
  width: 100%;
  height: 100%;
  border: none;
}

.plugin-error {
  padding: 40px;
  text-align: center;
  color: #999;
}
</style>
