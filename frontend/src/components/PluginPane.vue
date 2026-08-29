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
//   握手  宿主 -> 插件   { awd: 1, type: 'init', context: { pluginId, projectId, language, theme, themeTokens } }
//   请求  插件 -> 宿主   { awd: 1, type: 'call', seq, method, params }
//   响应  宿主 -> 插件   { awd: 1, type: 'result', seq, ok, result | error: { code, message } }
//   主题  宿主 -> 插件   { awd: 1, type: 'theme', theme, tokens }（v2.6，切换时推送；
//   老 SDK 不认识这个 type 会静默忽略，新 SDK 在老宿主上收不到推送则停在握手快照）
//   事件  宿主 -> 插件   { awd: 1, type: 'event', event, data }（v2.7，显式 events.subscribe 后才推送；
//   payload 刻意为空——事件是「该重拉了」的信号，数据由插件按各自权限闸拉取）
//
// v2.7 实验 API：x- 前缀方法只对本机 dev 免签直装的插件开放（devInstalled prop），
// 广场装的插件调用一律 experimental_not_allowed——运行时闸是真保证，受理扫描只是辅助。
import {
  getProjectFiles, getFileText, invokePluginTool, pluginAiComplete,
  createEvidenceLink, addEvidenceTargets, getEvidenceLink, listEvidenceLinks
} from '@/services/api.js'
import { PLUGIN_DOC_ACTIONS } from '@/config/pluginDocActions.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { getResolvedTheme, collectThemeTokens, APP_THEME_EVENT } from '@/utils/appTheme.js'
import { resolveAnchor, toPluginLink, toTargetInputs } from '@/utils/pluginEvidence.js'
import { createEvidenceLinkForSelection } from '@/pages/project-overview/evidenceLinkCore.js'
import { WPS_INTERNAL_HTTP_LINK_BASE } from '@/config/workbenchActions.js'

/** 桥协议版本 */
const PROTOCOL = 1

/** files.read 的文本上限，与 SDK 契约/官网宿主模拟器一致 */
const READ_LIMIT = 5 * 1024 * 1024

/** 插件级 KV 的总量上限（序列化后字节数近似），与 SDK 契约一致 */
const STORAGE_LIMIT = 64 * 1024

/** 插件级 KV 在宿主 localStorage 里的键前缀 */
const STORAGE_PREFIX = 'awd_plugin_kv_'

/** ai.request 的 prompt+system 合计字符上限（与后端 PluginController.AI_REQUEST_MAX_CHARS 一致） */
const AI_REQUEST_MAX_CHARS = 16000

/**
 * 事件通道（v2.7）：事件名 -> { permission: 订阅所需权限（null=不需要）, throttleMs: 转发合并窗口 }。
 * 未达权限的事件名在 subscribe 时静默剔除（回声集合里自然缺席），与老宿主 unknown_method 降级同一取向。
 */
const PLUGIN_EVENTS = {
  'files.changed': { permission: 'file_read', throttleMs: 500 },
  'selection.changed': { permission: 'editor', throttleMs: 300 },
  'project.switched': { permission: null, throttleMs: 0 }
}

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
  // chat.send 与 PluginGuidePane 的快捷按钮走同一条 kickoff 路：
  // prompt 作为可见的用户消息进入 AI 对话，用户随时可停，不存在静默注入
  emits: ['kickoff'],
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
    },
    /** 是否本机 dev 免签直装（后端按 .awd-dev 标记判定）：实验 API（x- 前缀方法）只对它开放 */
    devInstalled: {
      type: Boolean,
      default: false
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
      this.resetEventChannel()
    },
    pluginId() {
      this.sessionGeneration++
      this.resetEventChannel()
    },
    // 当前架构下切项目走 uni.reLaunch 整页重建，本 watch 极少触发；
    // 语义为未来面板持久化预留（规范 v2.7 事件表），触发了就如实推送
    projectId(val) {
      this.forwardEvent('project.switched', { projectId: val == null ? '' : String(val) })
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
  created() {
    // 事件通道状态（v2.7）：刻意不进 data——不驱动渲染，Set/定时器也不适合响应式代理
    this._subscribedEvents = new Set()
    this._eventTimers = {}
  },
  mounted() {
    window.addEventListener('message', this.onMessage)
    this._onThemeChanged = () => this.pushTheme()
    uni.$on(APP_THEME_EVENT, this._onThemeChanged)
    // 事件源（v2.7）：FileTree.loadFiles 成功后与编辑器选区变化时各发一个应用级事件，
    // 这里按插件的订阅集合转发进 iframe（未订阅不推，见 forwardEvent）
    this._onFilesChanged = () => this.forwardEvent('files.changed', {})
    this._onSelectionChanged = () => this.forwardEvent('selection.changed', {})
    uni.$on('awd:files-changed', this._onFilesChanged)
    uni.$on('awd:selection-changed', this._onSelectionChanged)
  },
  beforeUnmount() {
    window.removeEventListener('message', this.onMessage)
    if (this._onThemeChanged) { uni.$off(APP_THEME_EVENT, this._onThemeChanged); this._onThemeChanged = null }
    if (this._onFilesChanged) { uni.$off('awd:files-changed', this._onFilesChanged); this._onFilesChanged = null }
    if (this._onSelectionChanged) { uni.$off('awd:selection-changed', this._onSelectionChanged); this._onSelectionChanged = null }
    this.resetEventChannel()
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
        // 外壳自 dev-board#223 起有深浅两态，握手把当前生效的那个告诉插件
        theme: getResolvedTheme(),
        // v2.6：语义色令牌整表随握手注入（SDK 收到即写成 iframe 里的 CSS 变量，
        // 照 VS Code 给 webview 注入 --vscode-* 的机制）。切换时走 pushTheme 推送。
        themeTokens: collectThemeTokens()
      }
    },

    /** 主题切换推送（v2.6）：插件面板开着时切深浅色，iframe 里的令牌跟着换 */
    pushTheme() {
      if (!this.isWebPlugin) return
      const frame = this.$refs.pluginFrame
      if (!frame || !frame.contentWindow) return
      frame.contentWindow.postMessage(
        { awd: PROTOCOL, type: 'theme', theme: getResolvedTheme(), tokens: collectThemeTokens() },
        '*'
      )
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
      // 实验 API 闸（v2.7）：x- 前缀方法只对 dev 免签直装插件开放。
      // dev 插件的 x- 方法继续落进 switch（当前没有任何实验方法，得到 unknown_method）。
      if (method.indexOf('x-') === 0 && !this.devInstalled) {
        return {
          ok: false,
          error: { code: 'experimental_not_allowed', message: '实验方法仅对本机开发安装的插件开放：' + method }
        }
      }
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

        // ==== 规范 v2.5 新增的三个方法 ====

        case 'tools.invoke': {
          // 直调本插件自己的 JAR 工具：权限/配额/projectId 都在后端端点与 ToolRegistry 里闸，
          // 桥这里只负责转发；插件不可能借这条路调到别的插件或宿主内置工具（端点按 manifest 校验）
          const name = String(params.name == null ? '' : params.name).trim()
          if (!name) return { ok: false, error: { code: 'invalid_params', message: '缺少工具名 name' } }
          let res
          try {
            res = await invokePluginTool(this.pluginId, name, this.projectId, params.args || {})
          } catch (e) {
            return { ok: false, error: { code: 'invoke_failed', message: (e && e.message) || '工具调用失败' } }
          }
          const body = res && res.output !== undefined ? res : (res && res.data) || {}
          if (body.code !== 0) {
            return { ok: false, error: { code: 'invoke_failed', message: String(body.output || body.message || '工具调用失败') } }
          }
          return { ok: true, result: { output: body.output == null ? '' : String(body.output) } }
        }

        case 'chat.send': {
          const prompt = String(params.prompt == null ? '' : params.prompt).trim()
          if (!prompt) return { ok: false, error: { code: 'invalid_params', message: 'prompt 不能为空' } }
          if (prompt.length > 4000) return { ok: false, error: { code: 'quota_exceeded', message: 'prompt 超过 4000 字上限' } }
          this.$emit('kickoff', { prompt })
          return { ok: true, result: {} }
        }

        case 'ui.openFile': {
          if (!this.hasPermission('file_read')) return this.denied('file_read')
          const path = String(params.path == null ? '' : params.path)
          const hit = (await this.listProjectFiles()).find(f => f.path === path)
          if (!hit) return { ok: false, error: { code: 'not_found', message: '文件不存在：' + path } }
          // 与 evidence.locate 打开底稿同一条路：工作台监听后走 openFileLinkTarget
          uni.$emit('awd:open-evidence-target', { fileId: hit.id, locator: null, linkKey: null })
          return { ok: true, result: {} }
        }

        // ==== 规范 v2.7 新增：doc.* / events.* / ai.request ====

        case 'doc.exec': {
          if (!this.hasPermission('editor')) return this.denied('editor')
          const action = String(params.action == null ? '' : params.action).trim()
          if (!action) return { ok: false, error: { code: 'invalid_params', message: '缺少 action' } }
          if (!PLUGIN_DOC_ACTIONS.has(action)) {
            return { ok: false, error: { code: 'action_not_allowed', message: '该原语不对插件开放：' + action } }
          }
          const ed = this.activeEditor()
          if (!ed) return this.noActiveDocument('没有打开的文档，doc.exec 只作用于当前聚焦的编辑器')
          // __agent: 修订署名 "AI WorkDeck"、Writer 修订模式行为与 AI 管线一致——
          // 插件写入可被用户逐条接受/拒绝（executor 之后还有 EDITOR_ACTIONS 第二道既有闸）
          const r = await ed.executor(action, Object.assign({}, params.params || {}, { __agent: true }))
          return { ok: true, result: { result: r == null ? {} : r } }
        }

        case 'doc.active': {
          if (!this.hasPermission('editor')) return this.denied('editor')
          const ed = this.activeEditor()
          if (!ed) return { ok: true, result: { fileId: null, kind: null } }
          let kind = null
          try {
            const r = await ed.executor('get_doc_kind', {})
            kind = (r && (r.kind || (r.result && r.result.kind))) || null
          } catch (e) { /* 诊断失败不影响主返回 */ }
          return { ok: true, result: { fileId: ed.fileId, kind } }
        }

        case 'events.subscribe':
        case 'events.unsubscribe': {
          const names = Array.isArray(params.events) ? params.events.map(e => String(e || '')) : []
          names.forEach(name => {
            const def = PLUGIN_EVENTS[name]
            if (!def) return // 未知事件名静默忽略（向前兼容：新事件名在老宿主上不报错）
            if (def.permission && !this.hasPermission(def.permission)) return // 权限不足静默剔除
            if (method === 'events.subscribe') this._subscribedEvents.add(name)
            else this._subscribedEvents.delete(name)
          })
          return { ok: true, result: { subscribed: Array.from(this._subscribedEvents) } }
        }

        case 'ai.request': {
          if (!this.hasPermission('ai')) return this.denied('ai')
          const prompt = String(params.prompt == null ? '' : params.prompt)
          const system = params.system == null ? '' : String(params.system)
          if (!prompt.trim()) return { ok: false, error: { code: 'invalid_params', message: 'prompt 不能为空' } }
          if (prompt.length + system.length > AI_REQUEST_MAX_CHARS) {
            return { ok: false, error: { code: 'quota_exceeded', message: 'prompt+system 超过 ' + AI_REQUEST_MAX_CHARS + ' 字符上限' } }
          }
          let res
          try {
            res = await pluginAiComplete(this.pluginId, this.projectId, {
              prompt, system, purpose: String(params.purpose == null ? '' : params.purpose).slice(0, 64)
            })
          } catch (e) {
            return { ok: false, error: { code: 'ai_failed', message: (e && e.message) || 'AI 请求失败' } }
          }
          const body = res && res.code !== undefined ? res : (res && res.data) || {}
          if (body.code !== 0) {
            return { ok: false, error: { code: body.errorCode || 'ai_failed', message: String(body.message || 'AI 请求失败') } }
          }
          return { ok: true, result: { text: body.text == null ? '' : String(body.text), modelId: body.modelId || '' } }
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

    // ==== 事件通道（v2.7）====

    /** 换插件/卸载时清空订阅与在途定时器：新插件必须自己重新 subscribe */
    resetEventChannel() {
      this._subscribedEvents = new Set()
      const timers = this._eventTimers || {}
      Object.keys(timers).forEach(k => clearTimeout(timers[k]))
      this._eventTimers = {}
    },

    /**
     * 向已订阅的插件推送事件（trailing 合并：窗口内多次触发只推最后一次）。
     * payload 刻意为空/极小——事件是「该重拉了」的信号，数据由插件按各自权限闸拉取。
     */
    forwardEvent(name, data) {
      if (!this.isWebPlugin) return
      if (!this._subscribedEvents || !this._subscribedEvents.has(name)) return
      const def = PLUGIN_EVENTS[name]
      const fire = () => {
        delete this._eventTimers[name]
        // 定时器落地时可能已换插件：订阅集合在 resetEventChannel 里清过，再查一次
        if (!this._subscribedEvents || !this._subscribedEvents.has(name)) return
        const frame = this.$refs.pluginFrame
        if (!frame || !frame.contentWindow) return
        frame.contentWindow.postMessage(
          { awd: PROTOCOL, type: 'event', event: name, data: data || {} }, '*'
        )
      }
      const throttleMs = def ? def.throttleMs : 0
      if (!throttleMs) { fire(); return }
      if (this._eventTimers[name]) clearTimeout(this._eventTimers[name])
      this._eventTimers[name] = setTimeout(fire, throttleMs)
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
          // 查找留下的 __ai_anchor_* 书签不能残留在 docx 里
          try { await ed.executor('clear_anchors', {}) } catch (e) { /* ignore */ }
          return { ok: false, error: { code: 'anchor_ambiguous', message: (sel && sel.message) || '引文无法选中' } }
        }
      }

      // 与拖放建链同一份流程（复核 F2）：选区已带 filelink?k= 则复用 linkKey 追加 target，
      // 否则 bookmark_selection + set_selection_hyperlink 成对写入，再落库。
      let res
      try {
        res = await createEvidenceLinkForSelection({
          exec: ed.executor,
          api: { createEvidenceLink, addEvidenceTargets },
          projectId: this.projectId,
          docFileId: ed.fileId,
          internalBase: WPS_INTERNAL_HTTP_LINK_BASE,
          targets: ti.targets,
          createdByKind: 'plugin'
        })
      } finally {
        if (a.mode === 'quote') {
          try { await ed.executor('clear_anchors', {}) } catch (e) { /* ignore */ }
        }
      }
      if (!res.ok) {
        return { ok: false, error: { code: 'no_selection', message: res.message || '无法在选区上建立锚点' } }
      }
      const view = res.view && res.view.linkKey ? res.view : (res.view && res.view.data) || {}
      // 编辑器的 _evidenceCache 与证据面板都只在这个事件上刷新，不发就要重开文档才看得到
      try { uni.$emit('awd:evidence-changed', { docFileId: ed.fileId, linkKey: res.linkKey, source: 'plugin' }) } catch (e) { /* ignore */ }
      return {
        ok: true,
        result: {
          linkKey: view.linkKey || res.linkKey,
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
  background-color: var(--awd-surface);
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
  color: var(--awd-text-3);
}
</style>
