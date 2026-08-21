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
import {
  getProjectFiles, getFileText,
  createEvidenceLink, getEvidenceLink, listEvidenceLinks
} from '@/services/api.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { resolveAnchor, newLinkKey, toPluginLink, toTargetInputs } from '@/utils/pluginEvidence.js'

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
    },
    /**
     * 宿主注入的「当前聚焦的 Word 编辑器」适配器：() => { executor(action, params), fileId } | null。
     * evidence.link / evidence.locate 要打书签、跳书签，PluginPane 自己拿不到编辑器，
     * 与 VariablePanel 从 project-overview 拿 getEditor 适配器是同一做法。不给就是没有活动文档。
     */
    getActiveEditor: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      // 会话代次：调用点没给 :key，同一个面板槽位换插件时组件被复用、iframe 只换 src，
      // 于是 A 发起的桥调用可能在 B 已经载入之后才 resolve。响应按 seq 匹配，而新插件的
      // 序号也从 1 起，投错窗口就是把 A 的数据（可能含 B 无权读的文件内容）兑现给 B。
      // 换插件时自增，回响应前比对，代次对不上就丢弃。
      sessionGeneration: 0
    }
  },
  watch: {
    url() {
      this.sessionGeneration++
    },
    pluginId() {
      this.sessionGeneration++
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

      // 发起调用时的代次，await 之后据此判断这条响应还属不属于当前插件
      const generation = this.sessionGeneration
      let out
      try {
        out = await this.handleCall(String(msg.method || ''), msg.params || {})
      } catch (e) {
        out = { ok: false, error: { code: 'internal_error', message: (e && e.message) || '调用失败' } }
      }
      this.reply(msg.seq, out, generation)
    },

    reply(seq, out, generation) {
      // 代次已过期：调用发出后面板换了插件，这条响应属于上一个插件，丢弃
      if (generation !== undefined && generation !== this.sessionGeneration) return
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

        case 'evidence.list':
          if (!this.hasPermission('file_read')) return this.denied('file_read')
          return await this.evidenceList(params)

        case 'evidence.link':
          if (!this.hasPermission('editor')) return this.denied('editor')
          return await this.evidenceLink(params)

        case 'evidence.locate':
          if (!this.hasPermission('editor')) return this.denied('editor')
          return await this.evidenceLocate(params)

        default:
          return { ok: false, error: { code: 'unknown_method', message: '未知方法：' + method } }
      }
    },

    // ==== 证据链接（evidence.*）====

    noActiveDocument(message) {
      return { ok: false, error: { code: 'no_active_document', message: message || '没有打开的 Word 文档' } }
    },

    activeEditor() {
      if (typeof this.getActiveEditor !== 'function') return null
      const ed = this.getActiveEditor()
      if (!ed || typeof ed.executor !== 'function' || !ed.fileId) return null
      return { executor: ed.executor, fileId: Number(ed.fileId) }
    },

    /** 一次拉全树，同时给出 path->id 与 id->path 两张表（evidence.* 都要双向查） */
    async pathTables() {
      const files = await this.listProjectFiles()
      const idByPath = new Map()
      const pathById = new Map()
      files.forEach(f => { idByPath.set(f.path, f.id); pathById.set(f.id, f.path) })
      return { idByPath, pathById }
    },

    async evidenceList(params) {
      const { idByPath, pathById } = await this.pathTables()
      let query
      if (params.path) {
        const fileId = idByPath.get(String(params.path))
        if (!fileId) return { ok: false, error: { code: 'not_found', message: '文件不存在：' + params.path } }
        query = { fileId }
      } else {
        let docFileId
        if (params.docPath) {
          docFileId = idByPath.get(String(params.docPath))
          if (!docFileId) return { ok: false, error: { code: 'not_found', message: '文件不存在：' + params.docPath } }
        } else {
          const ed = this.activeEditor()
          if (!ed) return this.noActiveDocument()
          docFileId = ed.fileId
        }
        query = { docFileId, status: params.status, sectionPath: params.sectionPath }
      }
      const res = await listEvidenceLinks(this.projectId, query)
      const links = Array.isArray(res) ? res : (res && res.data) || []
      return { ok: true, result: { links: links.map(l => toPluginLink(l, pathById)) } }
    },

    async evidenceLink(params) {
      const ed = this.activeEditor()
      if (!ed) return this.noActiveDocument()
      const { idByPath } = await this.pathTables()
      if (params.docPath) {
        // 书签只能打在当前聚焦的编辑器里，docPath 指向别的文档无法代劳
        const want = idByPath.get(String(params.docPath))
        if (!want) return { ok: false, error: { code: 'not_found', message: '文件不存在：' + params.docPath } }
        if (want !== ed.fileId) return this.noActiveDocument('docPath 不是当前聚焦的文档，请先切到该文档')
      }
      const ti = toTargetInputs(params.targets, idByPath)
      if (ti.error) return { ok: false, error: ti.error }

      const a = await resolveAnchor(ed.executor, params.anchor)
      if (a.error) return { ok: false, error: a.error }
      if (a.mode === 'quote') {
        const sel = await ed.executor('set_selection', { anchor: a.anchorId })
        if (!sel || !sel.success) {
          return { ok: false, error: { code: 'anchor_ambiguous', message: (sel && sel.message) || '引文无法选中' } }
        }
      }

      const linkKey = newLinkKey()
      const bm = await ed.executor('bookmark_selection', { name: linkKey })
      if (!bm || !bm.success) {
        return { ok: false, error: { code: 'no_selection', message: (bm && bm.message) || '无法在选区上建立锚点' } }
      }
      let ctx = null
      try { ctx = await ed.executor('get_bookmark_context', { name: linkKey }) } catch (e) { ctx = null }
      const link = await createEvidenceLink(this.projectId, {
        docFileId: ed.fileId,
        linkKey,
        anchorText: bm.text || a.text,
        sectionPath: ctx && ctx.success ? ctx.sectionPath || null : null,
        sectionTitle: ctx && ctx.success ? ctx.sectionTitle || null : null,
        createdByKind: 'plugin',
        targets: ti.targets
      })
      const view = link && link.linkKey ? link : (link && link.data) || {}
      return {
        ok: true,
        result: {
          linkKey: view.linkKey || linkKey,
          targetIds: (Array.isArray(view.targets) ? view.targets : []).map(t => t.id)
        }
      }
    },

    async evidenceLocate(params) {
      const linkKey = String(params.linkKey || '')
      let link = null
      try {
        const res = await getEvidenceLink(this.projectId, linkKey)
        link = res && res.linkKey ? res : (res && res.data) || null
      } catch (e) {
        link = null
      }
      if (!link) return { ok: false, error: { code: 'not_found', message: '链接不存在：' + linkKey } }
      const targets = Array.isArray(link.targets) ? link.targets : []
      const tgt = params.targetId != null && params.targetId !== ''
        ? targets.find(t => Number(t.id) === Number(params.targetId))
        : null
      if (params.targetId != null && params.targetId !== '' && !tgt) {
        return { ok: false, error: { code: 'not_found', message: '底稿位置不存在：' + params.targetId } }
      }
      if (tgt) {
        // 打开底稿并定位：由工作台（project-overview）监听后调 openFileLinkTarget
        uni.$emit('awd:open-evidence-target', { fileId: tgt.fileId, locator: tgt.locator || null, linkKey })
        return { ok: true, result: {} }
      }
      const ed = this.activeEditor()
      if (!ed || ed.fileId !== Number(link.docFileId)) {
        return this.noActiveDocument('该链接所在文档不是当前聚焦的文档')
      }
      const r = await ed.executor('goto_bookmark', { name: link.linkKey })
      if (!r || !r.success) {
        return { ok: false, error: { code: 'not_found', message: (r && r.message) || '文档里找不到该锚点' } }
      }
      return { ok: true, result: {} }
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
