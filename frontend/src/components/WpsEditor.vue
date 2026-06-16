<template>
  <view class="wps-editor-wrapper">
    <!-- 编辑器容器：始终渲染，确保 DOM 元素存在 -->
    <view class="wps-container">
      <!-- 使用 ref 和 id 双重绑定，确保 uni-app 能正确识别 -->
      <view :id="finalContainerId" :ref="'wpsContainer'" class="wps-editor-container" :style="containerStyle"></view>
      
      <!-- 加载状态覆盖层 -->
      <view v-if="loading" class="wps-status-overlay wps-loading">
        <text class="loading-text">正在加载编辑器...</text>
      </view>

      <!-- 错误状态覆盖层 -->
      <view v-else-if="error" class="wps-status-overlay wps-error">
        <text class="error-text">{{ error }}</text>
        <button v-if="showRetry" type="primary" size="mini" @tap="handleRetry" style="margin-top: 16rpx;">
          重试
        </button>
      </view>

      <!-- WPS 未配置：降级为只读占位 + 去设置，替代白屏/500（#18 T6） -->
      <view v-else-if="notConfigured" class="wps-status-overlay wps-not-configured">
        <text class="error-text">文档在线编辑需配置 WPS WebOffice，当前暂不可编辑。</text>
        <text class="error-text">Online editing requires WPS WebOffice (not configured).</text>
        <button type="primary" size="mini" @tap="goToSettings" style="margin-top: 16rpx;">
          去设置 / Configure
        </button>
      </view>
    </view>
  </view>
</template>

<script>
import { initWpsEditor } from '@/utils/wps-sdk.js'
import { createWpsSession } from '@/services/api.js'

/**
 * WPS 在线编辑器组件
 * 
 * 使用示例：
 * <WpsEditor
 *   :file-id="'project_123_doc_1'"
 *   :file-name="'项目文档.docx'"
 *   :app-id="'AK20251215TTJNYB'"
 *   :mode="'edit'"
 *   :user-id="'user_123'"
 *   @ready="onEditorReady"
 *   @error="onEditorError"
 * />
 */
export default {
  name: 'WpsEditor',
  props: {
    // 文件唯一标识（必填）
    fileId: {
      type: String,
      required: true
    },
    // 文件名（必填，用于判断文件类型）
    fileName: {
      type: String,
      required: true
    },
    // WPS AppID（必填）
    appId: {
      type: String,
      required: true
    },
    // 编辑模式：'edit' 或 'view'（默认：'edit'）
    mode: {
      type: String,
      default: 'edit',
      validator: (value) => ['edit', 'view'].includes(value)
    },
    // 用户 ID（可选，用于生成 token）
    userId: {
      type: String,
      default: null
    },
    // 容器元素 ID（可选，默认自动生成）
    containerId: {
      type: String,
      default: null
    },
    // 容器样式（可选）
    containerStyle: {
      type: Object,
      default: () => ({
        width: '100%',
        height: '100%'
      })
    },
    // 是否自动加载（默认：true）
    autoLoad: {
      type: Boolean,
      default: true
    },
    // 是否显示重试按钮（默认：true）
    showRetry: {
      type: Boolean,
      default: true
    }
  },
  data() {
    return {
      loading: false,
      error: null,
      // WPS 未配置时降级为只读占位（#18 T6）
      notConfigured: false,
      instance: null,
      // 自动生成容器 ID，避免多个组件实例冲突
      generatedContainerId: null,
      // 选区缓存（用于解决拖拽时焦点丢失问题）
      selectionCache: {
        start: 0,
        end: 0,
        text: '',
        ts: 0
      },
      // 选区轮询定时器
      selectionTimer: null
    }
  },
  computed: {
    // 最终使用的容器 ID
    finalContainerId() {
      return this.containerId || this.generatedContainerId
    }
  },
  watch: {
    // 监听 fileId 变化，重新加载编辑器
    fileId(newVal, oldVal) {
      if (newVal && newVal !== oldVal) {
        console.log('WpsEditor: fileId 变化，重新加载', { newVal, oldVal })
        this.reload()
      }
    },
    // 监听 mode 变化
    mode(newVal, oldVal) {
      if (newVal && newVal !== oldVal) {
        console.log('WpsEditor: mode 变化，重新加载', { newVal, oldVal })
        this.reload()
      }
    }
  },
  created() {
    // 在 created 阶段生成容器 ID，确保在模板渲染前就有值
    if (!this.containerId) {
      this.generatedContainerId = `wps-editor-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
    }
  },
  mounted() {
    // 自动加载
    if (this.autoLoad) {
      // 延迟加载，确保 DOM 完全渲染
      this.$nextTick(() => {
        setTimeout(() => {
          this.load()
        }, 200)
      })
    }
  },
  beforeUnmount() {
    // 组件销毁时，销毁 WPS 实例
    this.destroy()
  },
  methods: {
    /**
     * [API] 尝试把焦点切到编辑器（用于拖拽落点插入前）
     * 说明：WPS WebOffice 多数情况是 iframe，且可能跨域，无法操作其内部 DOM；
     * 但 iframe.focus() 能保证后续 JSAPI 插入更稳定。
     */
    focusEditor() {
      try {
        const refContainer = this.$refs.wpsContainer
        let el = refContainer
        if (Array.isArray(el) && el.length) el = el[0]
        if (el && el.$el) el = el.$el
        if (!el || typeof el.querySelector !== 'function') return
        const iframe = el.querySelector('iframe')
        if (iframe && typeof iframe.focus === 'function') iframe.focus()
      } catch (e) {
        // ignore
      }
    },
    /**
     * 加载编辑器
     */
    async load() {
      if (this.loading || this.instance) {
        return
      }

      const startTime = Date.now()
      console.log(`WpsEditor [${this.fileName}]: 开始加载...`, startTime)

      this.loading = true
      this.error = null
      this.notConfigured = false

      try {
        // 1. 创建会话获取 token
        let token = ''
        try {
          const session = await createWpsSession({
            fileId: this.fileId,
            userId: this.userId
          })
          if (session && session.token) {
            token = session.token
          }
        } catch (e) {
          if (e && e.featureNotConfigured) {
            // WPS 未配置：降级为只读占位，不再尝试初始化编辑器（否则白屏/报错）（#18 T6）
            console.warn('WPS 未配置，降级为只读占位:', e)
            this.loading = false
            this.notConfigured = true
            this.$emit('not-configured', e)
            return
          }
          console.warn('创建 WPS 会话失败，将使用空 token:', e)
          // 不阻断流程，允许在无 token 情况下继续
        }

        // 2. 等待 DOM 渲染完成
        // 在 uni-app 中，需要多次 nextTick 确保 DOM 完全渲染
        // 注意：容器元素现在始终渲染，所以不需要等待条件判断
        await this.$nextTick()
        await this.$nextTick() // 双重 nextTick，确保 DOM 完全渲染
        
        // 3. 确认容器元素存在
        // 在 uni-app 中，优先使用 ref 获取元素，如果不行再用 getElementById
        let containerElement = null
        
        // 方法1：尝试通过 ref 获取（uni-app 推荐方式）
        const refContainer = this.$refs.wpsContainer
        if (refContainer) {
          // 在 H5 环境下，ref 返回的是 DOM 元素
          containerElement = refContainer
          // 如果是数组（某些情况下），取第一个
          if (Array.isArray(containerElement) && containerElement.length > 0) {
            containerElement = containerElement[0]
          }
          // 如果是 Vue 组件实例，获取其 $el
          if (containerElement && containerElement.$el) {
            containerElement = containerElement.$el
          }
        }
        
        // 方法2：如果 ref 获取失败，使用 getElementById（轮询方式，最多等待 1 秒）
        if (!containerElement && typeof document !== 'undefined') {
          const maxAttempts = 10
          const delayMs = 100
          
          for (let i = 0; i < maxAttempts; i++) {
            containerElement = document.getElementById(this.finalContainerId)
            if (containerElement) {
              break
            }
            // 如果找不到，等待一段时间后重试
            if (i < maxAttempts - 1) {
              await new Promise(resolve => setTimeout(resolve, delayMs))
            }
          }
        }
        
        // 方法3：如果还是找不到，尝试使用 uni-app 的 createSelectorQuery
        if (!containerElement && typeof uni !== 'undefined' && uni.createSelectorQuery) {
          try {
            await new Promise((resolve) => {
              const query = uni.createSelectorQuery().in(this)
              query.select(`#${this.finalContainerId}`).boundingClientRect((data) => {
                if (data && typeof document !== 'undefined') {
                  // 如果通过 uni 选择器找到了，再尝试用 getElementById
                  containerElement = document.getElementById(this.finalContainerId)
                }
                resolve()
              }).exec()
            })
          } catch (e) {
            console.warn('使用 uni.createSelectorQuery 查找元素失败:', e)
          }
        }
        
        if (!containerElement) {
          // 输出调试信息
          console.error('容器元素查找失败')
          console.error('尝试查找的元素ID:', this.finalContainerId)
          console.error('组件状态:', {
            containerId: this.containerId,
            generatedContainerId: this.generatedContainerId,
            finalContainerId: this.finalContainerId,
            hasRef: !!this.$refs.wpsContainer,
            refValue: this.$refs.wpsContainer
          })
          
          // 尝试查找所有可能的容器元素（用于调试）
          if (typeof document !== 'undefined') {
            const allElements = document.querySelectorAll('[id*="wps-editor"]')
            console.error('找到的所有 wps-editor 相关元素:', Array.from(allElements).map(el => el.id))
          }
          
          throw new Error(`容器元素 ${this.finalContainerId} 未找到，请确保组件已正确挂载`)
        }

        // 4. 初始化编辑器
        this.instance = await initWpsEditor({
          containerId: this.finalContainerId,
          appId: this.appId,
          fileId: this.fileId,
          fileName: this.fileName,
          mode: this.mode,
          token: token
        })

        // 5. 监听编辑器事件
        this.setupEventListeners()

        // 6. 触发 ready 事件
        this.$emit('ready', this.instance)

        const duration = Date.now() - startTime
        console.log(`WpsEditor [${this.fileName}]: 初始化完成，耗时 ${duration}ms`, {
          fileId: this.fileId,
          fileName: this.fileName,
          mode: this.mode
        })
      } catch (error) {
        console.error('WPS 编辑器加载失败:', error)
        console.error('错误详情:', {
          message: error.message,
          stack: error.stack,
          name: error.name,
          toString: error.toString()
        })
        // 显示详细错误信息
        let errorMessage = '加载编辑器失败，请稍后重试'
        if (error.message) {
          if (error.message.includes('容器元素')) {
            errorMessage = '编辑器容器未找到，请刷新页面重试'
          } else if (error.message.includes('SDK')) {
            errorMessage = 'WPS SDK 加载失败，请检查网络连接'
          } else {
            errorMessage = `加载编辑器失败: ${error.message}`
          }
        }
        this.error = errorMessage
        this.$emit('error', error)
      } finally {
        this.loading = false
      }
    },

    /**
     * 设置事件监听器
     */
    setupEventListeners() {
      if (!this.instance || !this.instance.ApiEvent) {
        return
      }
      
      // 启动选区轮询（每 800ms 记录一次，用于拖拽时的 Fallback）
      this.startSelectionPolling()

      // 文件打开事件
      this.instance.ApiEvent.AddApiEventListener('fileOpen', (data) => {
        console.log('WPS 文件打开:', data)
        this.$emit('fileOpen', data)
        if (data.success) {
          console.log('文件打开成功:', data.fileInfo)
        } else {
          console.error('文件打开失败:', data.msg)
          this.$emit('error', new Error(data.msg || '文件打开失败'))
        }
      })

      // 说明：部分 WPS 版本会对未知事件名直接抛出 "Invalid event name"
      // fileSave/fileRename/HyperLinkOpen 在当前环境下不稳定，先不绑定，避免刷屏影响排查其它问题

      // 错误事件
      this.instance.ApiEvent.AddApiEventListener('error', (data) => {
        const errorMsg = data.msg || data.message || '编辑器发生错误'
        const errorCode = data.code || data.type
        
        // 过滤掉一些已知的、不影响功能的 WPS SDK 内部错误
        const ignoredCodes = ['NETWORK_ERROR', 'TIMEOUT'] // 可以根据实际情况添加
        const shouldIgnore = ignoredCodes.includes(errorCode) || 
                            errorMsg.includes('网络') && errorMsg.includes('超时')
        
        if (!shouldIgnore) {
          // 只记录真正需要关注的错误
          console.warn('WPS 错误:', {
            msg: errorMsg,
            code: errorCode,
            type: data.type
          })
        }
        
        // 所有错误都触发 error 事件，让调用方决定如何处理
        // 但使用 warn 而不是 error，避免控制台被刷屏
        this.$emit('error', new Error(errorMsg))
      })

      // 同上：不绑定 fileRename，避免 Invalid event name 刷屏

      // 监听复制事件：用于“剪贴板历史”入库（按 WPS 官方 WebOffice 事件：ClipboardCopy）
      // 参考：https://solution.wps.cn/docs/client/api/summary.html/web/events
      try {
        this.instance.ApiEvent.AddApiEventListener('ClipboardCopy', (data) => {
          const text = (data && (data.text || data.Text)) || ''
          const payload = { ...(data || {}), text }
          // uni-app / Vue 模板里推荐用 kebab-case
          this.$emit('clipboard-copy', payload)
          // 兼容可能存在的 camelCase 绑定
          this.$emit('clipboardCopy', payload)
        })
      } catch (e) {
        console.warn('WPS SDK 不支持 ClipboardCopy 事件监听', e)
      }

      // 超链接拦截：桌面端统一交给 Electron 的 setWindowOpenHandler/will-navigate 拦截 checkba: 协议
      // 这样不依赖 WPS 的 ApiEvent（避免 Invalid event name）

      // 注意：WPS 的保存和关闭事件是通过后端回调接口 /v3/3rd/notify 接收的
      // 而不是通过前端事件监听器。这些事件会在后端 WpsController.notify() 方法中处理
      // 如果需要在前端感知保存/关闭，可以通过轮询后端状态或使用其他机制
    },

    /**
     * 销毁编辑器实例
     */
    destroy() {
      // 清理轮询定时器
      if (this.selectionTimer) {
        clearInterval(this.selectionTimer)
        this.selectionTimer = null
      }

      if (this.instance) {
        try {
          if (typeof this.instance.destroy === 'function') {
            this.instance.destroy()
          } else if (typeof this.instance.Free === 'function') {
            this.instance.Free()
          }
        } catch (e) {
          console.warn('销毁 WPS 实例时出错:', e)
        }
        this.instance = null
      }
    },

    /**
     * 重试加载
     */
    handleRetry() {
      this.destroy()
      this.load()
    },

    /**
     * 跳转到设置页配置 WPS（#18 T6 未配置降级时使用）
     */
    goToSettings() {
      uni.navigateTo({ url: '/pages/admin/admin' })
    },

    /**
     * 获取编辑器实例（供外部调用）
     */
    getInstance() {
      return this.instance
    },

    /**
     * 重新加载编辑器
     */
    reload() {
      this.destroy()
      this.load()
    },

    /**
     * [Internal] 启动选区轮询
     * 说明：WPS WebOffice SDK 的 SelectionChange 事件不可靠，且拖拽时焦点会丢失。
     * 必须通过轮询来持续记录“最后一次有效选区”，以便在 Drop 时使用。
     */
    startSelectionPolling() {
      if (this.selectionTimer) clearInterval(this.selectionTimer)
      this.selectionTimer = setInterval(async () => {
        if (!this.instance) return
        try {
          // 只在页面可见时轮询，节省性能
          if (typeof document !== 'undefined' && document.hidden) return

          const app = this.instance.Application
          const selection = await app.ActiveDocument.Selection
          const range = await selection.Range
          const start = await range.Start
          const end = await range.End
          
          // 仅缓存有效且非空的选区
          if (typeof start === 'number' && typeof end === 'number' && end > start) {
            const text = await range.Text
            if (text) {
              this.selectionCache = {
                start,
                end,
                text: String(text),
                ts: Date.now()
              }
              // console.log('WpsEditor Cached Selection:', this.selectionCache)
            }
          }
        } catch (e) {
          // ignore polling errors
        }
      }, 800)
    },

    /**
     * [API] 获取最后一次已知的有效选区（Fallback 机制）
     * 如果当前能获取到实时选区，优先返回实时；否则返回缓存。
     */
    async getLastKnownSelection() {
      // 1. 尝试获取实时选区
      const current = await this.getSelectionRange()
      const currentText = await this.getSelectionText()
      
      if (current && current.end > current.start && currentText) {
        return {
          start: current.start,
          end: current.end,
          text: currentText,
          isRealtime: true
        }
      }
      
      // 2. 检查缓存是否有效（例如 2 分钟内）
      const cache = this.selectionCache
      if (cache && cache.ts && (Date.now() - cache.ts < 120 * 1000)) {
        console.log('Using cached selection fallback:', cache)
        return {
          start: cache.start,
          end: cache.end,
          text: cache.text,
          isRealtime: false
        }
      }
      
      return null
    },

    /**
     * [API] 获取当前选区文本
     */
    async getSelectionText() {
      if (!this.instance) return ''
      try {
        const app = this.instance.Application
        // 尝试方法 1: 直接获取 Selection.Text
        const selection = await app.ActiveDocument.Selection
        let text = await selection.Text
        
        // 调试日志
        console.log('尝试获取选区文本 (Selection.Text):', text)

        // 如果为空，尝试方法 2: 通过 Range 获取
        if (!text) {
             const range = await selection.Range
             text = await range.Text
             console.log('尝试获取选区文本 (Selection.Range.Text):', text)
        }
        
        // 如果 text 可能是 undefined，转为空字符串
        return text || ''
      } catch (e) {
        console.error('获取选区文本失败', e)
        // 不抛出错误，而是返回空字符串，由上层处理提示
        return ''
      }
    },

    /**
     * [API] 获取当前选区的 Range 起止位置（用于官方文档中的 Range: {Start, End}）
     * 注意：WebOffice JSAPI 的属性访问也是异步的，需要 await range.Start / await range.End
     * 返回 { start, end }，失败时返回 null
     * 
     * 注意：只有 Word 文档 (doc/docx) 才支持 Selection 选区操作，
     * 对于 pptx/xlsx/pdf 等文件会静默返回 null，不报错。
     */
    async getSelectionRange() {
      if (!this.instance) return null
      try {
        const app = this.instance.Application
        if (!app) return null
        
        // 检查 ActiveDocument 是否存在
        const activeDoc = await app.ActiveDocument
        if (!activeDoc) return null
        
        // 检查 Selection 是否存在（只有 Word 文档支持）
        const selection = await activeDoc.Selection
        if (!selection) return null
        
        const range = await selection.Range
        if (!range) return null
        
        const start = await range.Start
        const end = await range.End
        if (typeof start !== 'number' || typeof end !== 'number') {
          // 静默返回 null，这是正常情况（如未选中任何内容）
          return null
        }
        return { start, end }
      } catch (e) {
        // 静默返回 null，避免轮询时刷屏报错
        // 常见场景：打开的不是 Word 文档，或者文档尚未完全加载
        return null
      }
    },

    /**
     * [API] 获取选区所在的超链接地址（若选区不在超链接内，则返回空）
     * 说明：不同版本 JSAPI 对 Range.Hyperlinks 的支持不一致，这里 best-effort。
     */
    async getSelectionHyperlinkUrl() {
      if (!this.instance) return ''
      try {
        const app = this.instance.Application
        const selection = await app.ActiveDocument.Selection
        const range = await selection.Range
        // 方案 1：range.Hyperlinks
        try {
          const hyperlinks = await range.Hyperlinks
          const c = hyperlinks && hyperlinks.Count ? await hyperlinks.Count : 0
          if (c > 0) {
            const item = await hyperlinks.Item(1)
            const addr = item && (await item.Address)
            return addr ? String(addr) : ''
          }
        } catch (e) {
          // ignore
        }
        // 兜底：返回空
        return ''
      } catch (e) {
        return ''
      }
    },

    /**
     * [API] 在指定 Range 上设置超链接（保持原文本，尽量不改变内容）
     * 遵循官方文档：先 Select 选区，再调用 Hyperlinks.Add({ Address, TextToDisplay })
     */
    async setHyperlinkAtRange(start, end, url, displayText = '') {
      if (!this.instance) return false
      const u = String(url || '')
      if (!u) return false
      
      console.log('setHyperlinkAtRange:', { start, end, url, displayText })

      // 关键修复：确保编辑器获得焦点，否则 Select 可能无效
      this.focusEditor()

      try {
        const app = this.instance.Application
        const doc = app.ActiveDocument
        const hyperlinks = await doc.Hyperlinks

        // 策略 1：强制恢复选区，然后添加超链接
        // 这是官方推荐做法：Selection.Hyperlinks.Add
        try {
          const rangeObj = await doc.Range(start, end)
          if (rangeObj) {
            await rangeObj.Select()
            // 给予少量缓冲时间让 Select 生效
            await new Promise(r => setTimeout(r, 50))
            
            // 使用 Application.Selection 添加，最为稳妥
            const selection = await app.Selection
            const selHyperlinks = await selection.Hyperlinks
            await selHyperlinks.Add({
              Address: u,
              TextToDisplay: displayText ? String(displayText) : undefined
            })
            
            console.log('setHyperlinkAtRange: 策略1(Selection.Hyperlinks)成功')
            return true
          }
        } catch (e0) {
           console.warn('setHyperlinkAtRange: 策略1失败', e0)
        }

        // 策略 2：直接在 Range 对象上操作 (如果 selection 失败)
        try {
          const rangeObj = await doc.Range(start, end)
          if (rangeObj) {
             // 尝试 Doc.Hyperlinks.Add(Anchor=Range)
             try {
               await hyperlinks.Add({
                 Address: u,
                 TextToDisplay: displayText ? String(displayText) : undefined,
                 Anchor: rangeObj
               })
               console.log('setHyperlinkAtRange: 策略2(Anchor Param)成功')
               return true
             } catch (errAnchor) {
               // ignore
             }
          }
        } catch (e1) {
          console.warn('setHyperlinkAtRange: 策略2失败', e1)
        }
        
        console.error('setHyperlinkAtRange: 所有策略均失败')
        return false

      } catch (e) {
        console.error('setHyperlinkAtRange 失败:', e)
        return false
      }
    },
    /**
     * [API] 获取文档纯文本（用于 AI 上下文）
     * 参考 docs/wpsmanual.md 的“前端集成”部分，使用 ActiveDocument.Content.Text 读取
     */
    async getDocumentPlainText(maxLength = 8000) {
      if (!this.instance) return ''
      try {
        const app = this.instance.Application
        if (!app || !app.ActiveDocument) return ''
        const content = await app.ActiveDocument.Content
        if (!content) return ''
        let text = await content.Text
        if (!text) return ''
        const normalized = String(text)
          .replace(/\u00A0/g, ' ')
          .replace(/\r\n/g, '\n')
          .replace(/[ \t]{2,}/g, ' ')
          .replace(/\n{3,}/g, '\n\n')
          .trim()
        if (!maxLength || normalized.length <= maxLength) {
          return normalized
        }
        return `${normalized.slice(0, maxLength)}\n...[上下文截断 ${normalized.length - maxLength} 字]`
      } catch (e) {
        console.error('获取文档全文失败', e)
        return ''
      }
    },

    /**
     * [API] 替换当前选区文本（用于 AI “替换选区”能力）
     * 说明：按官方 JSAPI 思路，直接对 Selection.Range.Text 赋值即可完成替换
     */
    async replaceSelectionText(text) {
      if (!this.instance) return false
      try {
        const app = this.instance.Application
        const selection = await app.ActiveDocument.Selection
        const range = await selection.Range
        range.Text = String(text || '')
        return true
      } catch (e) {
        console.error('替换选区文本失败', e)
        throw e
      }
    },

    /**
     * [API] 在当前选区创建书签
     * @param {string} name 书签名称
     */
    async addBookmark(name) {
       if (!this.instance) return false
       try {
         const app = this.instance.Application
         const bookmarks = await app.ActiveDocument.Bookmarks
         
         // 调试：打印即将创建的书签
         console.log('准备创建书签:', name)
         
         if (!name) throw new Error('书签名称不能为空')

         // 1. 检查是否已存在
         const exists = await bookmarks.Exists(name)
         if (exists) {
           console.log(`变量 "${name}" 已存在，视为创建成功`)
           return true
         }
         
         // 2. 关键修复：临时创建书签
         // 直接在当前光标位置创建，不显式传 Range，让 WPS 自己决定
         // 这样可以规避 Range 失效问题
         await bookmarks.Add({
           Name: name
         })
         
         console.log('书签创建成功:', name)
         return true
       } catch (e) {
         console.error('创建书签失败详情:', e)
         if (typeof e === 'object' && (e.msg || e.message)) {
            console.error('SDK Error Msg:', e.msg || e.message)
         }
         throw e
       }
    },
    
    /**
     * [API] 在指定 Range 位置创建书签（严格按照官方文档 Range: {Start, End}）
     * @param {string} name 书签名称
     * @param {number} start 起始位置
     * @param {number} end 结束位置
     */
    async addBookmarkAtRange(name, start, end) {
      if (!this.instance) return false
      try {
        const app = this.instance.Application
        const bookmarks = await app.ActiveDocument.Bookmarks

        if (!name) throw new Error('书签名称不能为空')
        if (typeof start !== 'number' || typeof end !== 'number') {
          throw new Error('无效的 Range 位置')
        }

        const exists = await bookmarks.Exists(name)
        if (exists) {
          console.log(`变量 "${name}" 已存在，视为创建成功`)
          return true
        }

        // 按照官方文档示例传入 Range: { Start, End }
        await bookmarks.Add({
          Name: name,
          Range: {
            Start: start,
            End: end
          }
        })

        console.log('书签创建成功:', name, 'Range:', start, end)
        return true
      } catch (e) {
        console.error('addBookmarkAtRange 失败:', e)
        if (typeof e === 'object' && (e.msg || e.message)) {
          console.error('SDK Error Msg:', e.msg || e.message)
        }
        throw e
      }
    },
    
    /**
     * [API] 在光标处插入文本并创建书签
     * @param {string} text 文本内容
     * @param {string} bookmarkName 书签名称
     */
    async insertTextWithBookmark(text, bookmarkName) {
       if (!this.instance) return false
       try {
         const app = this.instance.Application
         const selection = await app.ActiveDocument.Selection
         const range = await selection.Range

         // 先在当前选区位置插入文本
         range.Text = text

         // 再获取插入后该 Range 的 Start / End，用于按官方文档指定 Range 创建书签
         const start = await range.Start
         const end = await range.End

         const bookmarks = await app.ActiveDocument.Bookmarks
         const exists = await bookmarks.Exists(bookmarkName)
         if (!exists) {
           await bookmarks.Add({
             Name: bookmarkName,
             Range: {
               Start: start,
               End: end
             }
           })
         }
         return true
       } catch (e) {
         console.error('插入变量失败', e)
         throw e
       }
    },

    /**
     * [API] 在光标处插入图片
     * @param {string} url 图片URL
     */
    async insertImage(url) {
      if (!this.instance) return false
      try {
        const app = this.instance.Application
        const selection = await app.ActiveDocument.Selection
        const inlineShapes = await selection.InlineShapes
        
        await inlineShapes.AddPicture(url)
        return true
      } catch (e) {
        console.error('插入图片失败', e)
        throw e
      }
    },

    /**
     * [API] 插入“网核证据链接”（带书签名，且尽量以超链接样式呈现）
     * 说明：WPS WebOffice 的不同版本对 Hyperlinks.Add 的参数形态支持不完全一致；
     * 这里做“尽力而为”，失败则退化为普通书签文本。
     */
    async insertEvidenceLink(text, bookmarkName, url) {
      if (!this.instance) return false
      const t = String(text || '')
      const u = String(url || '')
      if (!t) return false
      try {
        const app = this.instance.Application
        const selection = await app.ActiveDocument.Selection
        const range = await selection.Range

        // 先插入文本
        range.Text = t
        const start = await range.Start
        const end = await range.End

        // 创建书签（用于后续定位）
        try {
          const bookmarks = await app.ActiveDocument.Bookmarks
          const exists = await bookmarks.Exists(bookmarkName)
          if (!exists) {
            await bookmarks.Add({
              Name: bookmarkName,
              Range: { Start: start, End: end }
            })
          }
        } catch (e) {
          // ignore bookmark failure
        }

        // 尝试创建超链接（失败则保持普通文本）
        if (u) {
          try {
            const hyperlinks = await app.ActiveDocument.Hyperlinks
            // 形态 1：对象参数（更像 WebOffice 的 async 风格）
            try {
              await hyperlinks.Add({
                Address: u,
                TextToDisplay: t,
                Range: { Start: start, End: end }
              })
            } catch (e1) {
              // 形态 2：可能要求 Anchor
              try {
                await hyperlinks.Add({
                  Address: u,
                  TextToDisplay: t,
                  Anchor: range
                })
              } catch (e2) {
                // ignore hyperlink failures
              }
            }
          } catch (e) {
            // ignore hyperlink failures
          }
        }
        return true
      } catch (e) {
        console.error('insertEvidenceLink 失败:', e)
        // 兜底：至少插入书签文本
        return await this.insertTextWithBookmark(t, bookmarkName)
      }
    },

    /**
     * [API] 更新书签内容
     * @param {string} name 书签名称
     * @param {string} text 新文本
     */
    async updateBookmark(name, text) {
        if (!this.instance) return false
        try {
            const app = this.instance.Application
            const bookmarks = await app.ActiveDocument.Bookmarks
            const exists = await bookmarks.Exists(name)
            if (exists) {
                // 使用官方推荐的 ReplaceBookmark 接口
                await bookmarks.ReplaceBookmark([
                    {
                        name: name,
                        type: 'text',
                        value: text
                    }
                ])
                return true
            } else {
                console.warn(`更新书签失败：书签 "${name}" 不存在`)
                return false
            }
        } catch (e) {
             console.error('更新书签失败', e)
             throw e
        }
    },
    
    /**
     * [API] 同步所有书签内容
     * @param {Array} variables 变量列表 [{name, value}]
     * @returns {Object} 结果统计 { updated: 0, total: 0 }
     */
    async syncAllBookmarks(variables) {
      if (!this.instance || !variables || variables.length === 0) return { updated: 0, total: 0 }
      
      console.log('开始同步书签, 变量总数:', variables.length)
      
      try {
        const app = this.instance.Application
        const bookmarks = await app.ActiveDocument.Bookmarks
        
        let updatedCount = 0
        
        for (const v of variables) {
           const exists = await bookmarks.Exists(v.name)
           if (exists) {
             await bookmarks.ReplaceBookmark([
                {
                  name: v.name,
                  type: 'text',
                  value: v.value
                }
             ])
             updatedCount++
           }
        }
        
        console.log(`同步完成: 更新了 ${updatedCount} 个变量`)
        return { updated: updatedCount, total: variables.length }
        
      } catch (e) {
        console.error('同步书签失败', e)
        throw e
      }
    },

    // =========================
    // 文本变量（域/公文域）实现
    // 说明：
    // - 书签在协同/编辑中容易被误删或 Range 漂移
    // - 改用“公文域/域”思想：每次插入都创建一个带唯一ID的字段实例，字段名中包含 scope+变量名，便于枚举与批量回填
    // - 由于不同版本 SDK 的 API 命名可能存在差异，这里做了能力探测与容错；若不支持，会抛出明确错误给上层提示
    // =========================

    _getDocumentFieldsApi(app) {
      // 兼容：ActiveDocument.DocumentFields / ActiveDocument.DocumentField(s)
      return (
        (app && app.ActiveDocument && app.ActiveDocument.DocumentFields) ||
        (app && app.ActiveDocument && app.ActiveDocument.DocumentField) ||
        null
      )
    },

    _normalizeFieldName(scope, varName, uid) {
      const safeScope = String(scope || 'D').toUpperCase()
      const safeName = String(varName || '').trim().replace(/\s+/g, '_').replace(/[^\w\u4e00-\u9fa5\-]/g, '_')
      const safeUid = String(uid || Date.now())
      return `${safeScope}__${safeName}__${safeUid}`
    },

    _parseFieldName(fieldName) {
      const raw = String(fieldName || '')
      const m = raw.match(/^([A-Z])__([^_].*?)__(\d+.*)$/)
      if (!m) return { scope: 'D', varName: raw, uid: '' }
      return { scope: m[1], varName: m[2].replace(/_/g, ' '), uid: m[3] }
    },

    async listVariableFields() {
      if (!this.instance) return []
      try {
        const app = this.instance.Application
        const documentFields = this._getDocumentFieldsApi(app)
        if (!documentFields) return []

        // 兼容 collection：Count + Item(i)
        const count = await documentFields.Count
        const total = typeof count === 'number' ? count : 0
        const result = []
        for (let i = 1; i <= total; i++) {
          try {
            const item = await documentFields.Item(i)
            const name = await item.Name
            const range = await item.Range
            const text = await range.Text
            result.push({
              id: name,
              ...this._parseFieldName(name),
              text: text || ''
            })
          } catch (e) {
            // 单条失败不影响整体
            console.warn('读取公文域条目失败:', e)
          }
        }
        return result
      } catch (e) {
        console.error('listVariableFields 失败:', e)
        return []
      }
    },

    async insertTextWithDocumentField(text, scope, varName) {
      if (!this.instance) return false
      const t = String(text || '')
      const fieldName = this._normalizeFieldName(scope, varName, Date.now())
      try {
        const app = this.instance.Application
        const selection = await app.ActiveDocument.Selection
        const range = await selection.Range

        // 用选中文本/变量值替换当前选区
        range.Text = t
        const start = await range.Start
        const end = await range.End

        const documentFields = this._getDocumentFieldsApi(app)
        if (!documentFields) {
          throw new Error('当前 WPS SDK 不支持公文域(DocumentFields)能力')
        }

        // 尝试按书签类似的 Add({Name, Range}) 方式创建
        await documentFields.Add({
          Name: fieldName,
          Range: { Start: start, End: end }
        })

        return { ok: true, fieldName }
      } catch (e) {
        console.error('insertTextWithDocumentField 失败:', e)
        throw e
      }
    },

    async updateDocumentField(fieldName, text) {
      if (!this.instance) return false
      try {
        const app = this.instance.Application
        const documentFields = this._getDocumentFieldsApi(app)
        if (!documentFields) throw new Error('当前 WPS SDK 不支持公文域(DocumentFields)能力')

        // 兼容：不同版本可能命名为 ReplaceDocumentField / ReplaceDocumentFields
        if (documentFields.ReplaceDocumentField) {
          await documentFields.ReplaceDocumentField([
            { name: fieldName, type: 'text', value: String(text || '') }
          ])
          return true
        }
        if (documentFields.ReplaceDocumentFields) {
          await documentFields.ReplaceDocumentFields([
            { name: fieldName, type: 'text', value: String(text || '') }
          ])
          return true
        }

        // fallback：若无 Replace API，尝试直接取条目 Range 写入
        if (documentFields.Count && documentFields.Item) {
          const count = await documentFields.Count
          for (let i = 1; i <= count; i++) {
            const item = await documentFields.Item(i)
            const name = await item.Name
            if (name === fieldName) {
              const range = await item.Range
              range.Text = String(text || '')
              return true
            }
          }
        }

        throw new Error('当前 WPS SDK 未暴露公文域替换接口')
      } catch (e) {
        console.error('updateDocumentField 失败:', e)
        throw e
      }
    },

    async syncAllDocumentFields(getValueByScopeAndName) {
      // getValueByScopeAndName: (scope, varName, currentText) => string
      if (!this.instance) return { updated: 0, total: 0 }
      const fields = await this.listVariableFields()
      if (!fields.length) return { updated: 0, total: 0 }

      let updated = 0
      for (const f of fields) {
        try {
          const next = typeof getValueByScopeAndName === 'function'
            ? getValueByScopeAndName(f.scope, f.varName, f.text)
            : f.text
          if (typeof next === 'string' && next !== f.text) {
            await this.updateDocumentField(f.id, next)
            updated++
          }
        } catch (e) {
          console.warn('同步单个公文域失败:', f, e)
        }
      }
      return { updated, total: fields.length }
    },
  }
}
</script>

<style lang="scss" scoped>
.wps-editor-wrapper {
  width: 100%;
  height: 100%;
  position: relative;
  display: flex;
  flex-direction: column;
}

.wps-container {
  width: 100%;
  height: 100%;
  position: relative;
  flex: 1;
}

.wps-editor-container {
  width: 100%;
  height: 100%;
  
  /* 强制 iframe 响应容器大小 */
  :deep(iframe) {
    width: 100% !important;
    height: 100% !important;
    border: none;
    display: block;
  }
}

/* 状态覆盖层：显示在容器上方 */
.wps-status-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 200rpx;
  padding: 32rpx;
  box-sizing: border-box;
  z-index: 10;
}

.wps-loading {
  background-color: rgba(255, 255, 255, 0.9);
}

.wps-error {
  background-color: rgba(255, 255, 255, 0.95);
}

.loading-text,
.error-text {
  font-size: 28rpx;
  color: #666;
  text-align: center;
}

.error-text {
  color: #f56c6c;
}
</style>
