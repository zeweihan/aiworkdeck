import { ref, reactive, nextTick } from 'vue'
import { getApiBaseUrl, getConversationMetadata } from '@/services/api.js'
import { getSessionId } from '@/utils/auth.js'
import { createProtocolTagRegex, decodeProtocolTags } from '@/composables/agentTagProtocol.mjs'
import { t } from '@/i18n'

// 网络恢复/页面回前台时触发重连的激活实例指针（模块级单例）。
// 页面栈会多次实例化本 composable（PR#148 重复订阅地雷），window 监听只挂一次，
// 回调经该指针分发到最近建连的实例。
let activeNetworkRecoveryHook = null
if (typeof window !== 'undefined' && !window.__awdSseNetworkHooks) {
    window.__awdSseNetworkHooks = true
    window.addEventListener('online', () => {
        if (activeNetworkRecoveryHook) activeNetworkRecoveryHook('network-online')
    })
    if (typeof document !== 'undefined') {
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && activeNetworkRecoveryHook) {
                activeNetworkRecoveryHook('page-visible')
            }
        })
    }
}

export function useAgentStream() {
    // STATE: List of all bubbles (history + active)
    const bubbles = ref([])
    const isConnected = ref(false)
    const isStreaming = ref(false)
    const error = ref(null)
    const currentConversationId = ref(null)
    // STATE: Token Usage Tracking (Session Cumulative)
    // STATE: Token Usage Tracking (Session Cumulative)
    const tokenUsage = ref({ promptTokens: 0, completionTokens: 0, totalTokens: 0 })

    // STATE: File Changes Tracking (Current Turn)
    const fileChanges = ref([]) // Array of { fileName, changeType }

    // STATE: Background task tracking for long-running operations
    const backgroundTasks = ref({}) // taskId -> { type, progress, message, stage, startedAt, estimatedDuration }
    const lastHeartbeat = ref(null) // { source, conversationId, timestamp }

    // STATE: Agent 任务清单（todo_write 驱动的常驻进度卡），plan_update 事件整表覆写
    const planTodos = ref([]) // Array of { content, activeForm, status }

    // STATE: 任务待续跑——步数超限暂停（bubble_end status=paused）或上次进程被杀
    // （run_state status=INTERRUPTED）。前端据此渲染一键「继续」按钮
    const agentPaused = ref(null) // null | { reason: 'max_depth' | 'process_interrupted' | ... }

    // STATE: 当前会话的后端运行状态（run_state/bubble_end 驱动）
    // 'RUNNING' | 'PAUSED' | 'AWAITING_APPROVAL' | 'AWAITING_INPUT' | 'FINISHED' | 'ERROR'
    // | 'CANCELLED' | 'INTERRUPTED'(上次进程执行中被杀，重启后回收) | null(无任务)
    const agentRunStatus = ref(null)

    // STATE: 模型反问（<question> 标签）后停机等用户回答。
    // 刻意不复用 agentPaused：那个 ref 驱动「继续」按钮（ChatInterface 的 continue-bar），
    // 而反问要的是「回答」而不是「继续」——共用一个 ref 会给用户一个点了没用的按钮。
    // 答案是下一轮普通用户消息（后端不做阻塞式挂起），所以 sendMessage 里一律清零。
    const agentAwaitingInput = ref(false)

    // POINTER: The current bubble we are writing to (Assistant)
    const currentAssistantBubble = ref(null)

    // Abort Controllers
    let sseAbortController = null
    let messageAbortController = null

    // --- 断线自动重连状态（F-05）---
    let reconnectAttempts = 0
    let reconnectTimer = null
    let heartbeatMonitor = null
    let lastSseActivityAt = 0 // 任何 SSE 字节到达都刷新（后端心跳 15s 一跳兜底保活）
    const HEARTBEAT_STALE_MS = 45000 // 连续 3 个心跳周期无任何字节判定连接已死

    const stopHeartbeatMonitor = () => {
        if (heartbeatMonitor) { clearInterval(heartbeatMonitor); heartbeatMonitor = null }
    }

    const startHeartbeatMonitor = () => {
        stopHeartbeatMonitor()
        heartbeatMonitor = setInterval(() => {
            if (!isConnected.value) return
            if (Date.now() - lastSseActivityAt > HEARTBEAT_STALE_MS) {
                console.warn('[AgentStream] SSE 心跳超时，判定连接已死，强制断开并自动重连')
                stopHeartbeatMonitor()
                try { if (sseAbortController) sseAbortController.abort() } catch (e) { /* ignore */ }
                scheduleReconnect('heartbeat-stale')
            }
        }, 10000)
    }

    // 指数退避自动重连：1s/2s/4s…封顶 30s；建连成功即清零。
    // 重连后由后端 connect 端点推 run_state（+RUNNING 时的 state_recovery）恢复 UI 态。
    const scheduleReconnect = (reason) => {
        if (reconnectTimer) return
        if (!currentConversationId.value) return
        const delay = Math.min(30000, 1000 * Math.pow(2, reconnectAttempts))
        reconnectAttempts++
        console.warn(`[AgentStream] SSE 断开（${reason}），${delay}ms 后自动重连（第 ${reconnectAttempts} 次）`)
        reconnectTimer = setTimeout(async () => {
            reconnectTimer = null
            if (isConnected.value || !currentConversationId.value) return
            try {
                await connectSSE(currentConversationId.value)
            } catch (e) {
                scheduleReconnect('retry-failed')
            }
        }, delay)
    }

    // Parser State (Local to the current stream)
    let parserBuffer = ''
    let activeTag = null
    let activeProcessId = null
    // 当前 <tool_output> 归属的 tool 条目：一轮多工具时后端按调用顺序补发多个
    // tool_output，必须 FIFO 归属到「第一个仍在 loading 的 tool」——按"最后一个
    // tool"归属会把所有结果都错挂到最后一个调用上。
    let activeToolItem = null
    // Event parser state
    let currentEventName = null
    let currentEventData = ''

    // --- HELPER: Create a new Assistant Bubble Structure ---
    const createAssistantBubble = () => ({
        id: `msg-${Date.now()}`,
        role: 'ASSISTANT',
        thinking: { status: 'idle', content: '', duration: 0, startTime: 0, endTime: 0 },
        title: '',
        processes: [],
        // 本轮的任务清单快照（plan_update 时写入）：计划卡随消息流内联展示，
        // 历史消息也能保留自己那轮的计划（planTodos 全局值只代表最新一轮）。
        planTodos: [],
        artifacts: [],
        walkthrough: '',
        content: '', // Main Answer (from <final> tag)
        // 模型反问（<question> 标签）解析结果，供问题卡渲染：
        // { text: 正文, options: [选项文字…], answered: 是否已作答 }。
        // 正文刻意不再并进 content——点选项时用户气泡里不该出现代拟的机器口吻长句，
        // 问题正文与选项要作为结构化数据交给问题卡（决策 D 的显示通道）。
        question: null,
        rawLog: '',
        isStreaming: false
    })

    // displayContent = 「显示内容 ≠ 发送内容」通道（契约 D）：content 永远是模型看到的原文，
    // displayContent 为空则回退 content。渲染侧一律 displayContent || content，
    // 与 GET /api/ai/history 返回体的同名字段口径一致（否则刷新页面文案会变）。
    const createUserBubble = (content, images = [], contextFiles = [], contentHtml = '', displayContent = '') => ({
        id: `msg-${Date.now()}`,
        role: 'USER',
        content: content,
        displayContent: displayContent || '',
        contentHtml: contentHtml, // HTML with inline file tags for display
        images: images,
        contextFiles: contextFiles
    })

    // --- RESET PARSER STATE ---
    const resetParser = () => {
        parserBuffer = ''
        activeTag = null
        activeProcessId = null
        activeToolItem = null
    }

    // --- RESET SSE CONNECTION STATE ---
    // Call this when switching conversations to ensure clean state
    const resetSSE = () => {
        console.log('[AgentStream] Resetting SSE state')
        // Abort any existing connections
        if (sseAbortController) {
            try { sseAbortController.abort() } catch (e) { }
            sseAbortController = null
        }
        if (messageAbortController) {
            try { messageAbortController.abort() } catch (e) { }
            messageAbortController = null
        }
        // Reset connection states
        isConnected.value = false
        isStreaming.value = false
        // Reset parser state
        resetParser()
        // Reset event parser state
        // Reset event parser state
        currentEventName = null
        currentEventData = ''
        // Reset Token Usage (start fresh for new chat context? Or keep per session? Usually per chat.)
        // Ideally we keep it during the chat session. resetSSE is called when switching conversations.
        tokenUsage.value = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
        // 切换会话时清空任务清单进度卡（重连后由后端 plan_update 恢复）
        planTodos.value = []
        agentPaused.value = null
        agentRunStatus.value = null
        agentAwaitingInput.value = false
        // 后台任务完成态现在不再自动销毁（改为保留可查），切换会话时必须把已结束的条目摘掉，
        // 否则上一个会话的「已完成」会挂在新会话的任务面板上。仍在跑的不动——
        // 那些任务与 SSE 无关，还在后台跑着，藏起来才是骗人。
        Object.keys(backgroundTasks.value).forEach(taskId => {
            if (backgroundTasks.value[taskId]?.status !== 'running') delete backgroundTasks.value[taskId]
        })
        // Clear bubble pointer (will be set fresh on next send)
        currentAssistantBubble.value = null
    }

    // --- CLEAR BUBBLES ---
    const clearBubbles = () => {
        bubbles.value.splice(0, bubbles.value.length)
    }

    // --- SET CONVERSATION ID (with auto-reset) ---
    const setConversationIdWithReset = (id) => {
        // Only reset if actually changing conversations
        if (currentConversationId.value !== id) {
            console.log('[AgentStream] Conversation changing:', currentConversationId.value, '->', id)
            resetSSE()
        }
        currentConversationId.value = id
    }

    // Instant（后端 Jackson 默认序列化成 ISO-8601 字符串）与毫秒数都要能吃，
    // 任务面板按 startedAt 做数值排序，给它字符串会排出随机顺序。
    const toEpochMs = (v) => {
        if (typeof v === 'number') return v
        const parsed = v ? Date.parse(v) : NaN
        return Number.isNaN(parsed) ? Date.now() : parsed
    }

    // 建连/重连后重建「正在跑的后台任务」进度条。GET /api/agent/tasks/active 只返回
    // status=RUNNING 的任务（后端 isActive 过滤），失败一律静默——这是恢复性补拉，
    // 拉不到只是看不见进度条，不该给用户报错。
    const restoreActiveTasks = async (conversationId) => {
        if (!conversationId) return
        try {
            const url = `${getApiBaseUrl()}/api/agent/tasks/active?conversationId=${encodeURIComponent(conversationId)}`
            const resp = await fetch(url, {
                method: 'GET',
                headers: { 'Content-Type': 'application/json', 'X-Session-Id': getSessionId() || '' }
            })
            if (!resp.ok) return
            const tasks = await resp.json()
            if (!Array.isArray(tasks)) return
            tasks.forEach(task => {
                if (!task || !task.taskId) return
                const existing = backgroundTasks.value[task.taskId]
                if (existing) {
                    // 已有条目（本次会话内起跑过）：服务端的进度才是权威，本地那份停在断线那一刻
                    Object.assign(existing, {
                        progress: task.progress || 0,
                        message: task.message || existing.message,
                        status: 'running',
                        lastUpdate: Date.now()
                    })
                    return
                }
                backgroundTasks.value[task.taskId] = {
                    taskId: task.taskId,
                    type: task.taskType,
                    conversationId: task.conversationId,
                    progress: task.progress || 0,
                    message: task.message || t('agentStream.taskInProgress'),
                    stage: 'running',
                    startedAt: toEpochMs(task.startedAt),
                    estimatedDurationSec: task.estimatedDurationSec,
                    status: 'running'
                }
            })
            if (tasks.length > 0) console.log('[AgentStream] Restored active background tasks:', tasks.length)
        } catch (e) {
            console.warn('[AgentStream] Failed to restore active background tasks:', e)
        }
    }

    // --- SSE Connection ---
    const connectSSE = (conversationId) => {
        if (sseAbortController && isConnected.value) return Promise.resolve()

        sseAbortController = new AbortController()
        // 持有本次连接自己的 controller 引用：旧连接结束时的清理（catch/finally）
        // 只允许作用于"自己还是当前连接"的情况，否则会把新连接的状态清掉（切换会话竞态）
        const myController = sseAbortController
        const baseUrl = getApiBaseUrl()
        const url = `${baseUrl}/api/agent/connect/${conversationId}`
        const sessionId = getSessionId()

        return new Promise(async (resolve, reject) => {
            try {
                console.log('[AgentStream] Connecting SSE:', url)
                const response = await fetch(url, {
                    method: 'GET',
                    headers: { 'Content-Type': 'application/json', 'X-Session-Id': sessionId || '' },
                    signal: myController.signal
                })

                if (!response.ok) throw new Error(`SSE Connection Failed: ${response.status}`)

                isConnected.value = true
                reconnectAttempts = 0
                lastSseActivityAt = Date.now()
                startHeartbeatMonitor()
                // 网络恢复/回前台时经模块级单例回调触发本实例重连
                activeNetworkRecoveryHook = (reason) => {
                    if (!isConnected.value && currentConversationId.value) scheduleReconnect(reason)
                }
                resolve()

                // 建连即补拉一次在跑的后台任务：background_task_start 只在任务起跑那一刻发一次，
                // 断线重连或切回会话的用户此前完全看不到「PPT 还在生成」，进度条要等下一个
                // task_progress 才可能出现（而 task_progress 只更新已存在的条目，永远等不到）。
                restoreActiveTasks(conversationId)

                const reader = response.body.getReader()
                const decoder = new TextDecoder('utf-8')
                let buffer = ''

                while (true) {
                    const { done, value } = await reader.read()
                    if (done) break

                    const chunk = decoder.decode(value, { stream: true })
                    // 调试日志：显示接收到的 chunk 时间戳
                    console.log('[SSE] Chunk received at:', new Date().toISOString(), 'size:', chunk.length)
                    lastSseActivityAt = Date.now()
                    buffer += chunk

                    const lines = buffer.split(/\r?\n/)
                    buffer = lines.pop() // Keep incomplete line

                    for (const line of lines) {
                        // 调试日志：显示解析的 SSE 行
                        if (line.trim()) {
                            console.log('[SSE] Processing line:', line.substring(0, 80) + (line.length > 80 ? '...' : ''))
                        }
                        parseSSELineFull(line)
                    }
                }
            } catch (err) {
                // 关键：若本连接已被替换（切换会话后新连接已建立），不得清理全局状态
                const isCurrent = sseAbortController === myController
                if (err.name !== 'AbortError') {
                    console.error('[AgentStream] SSE Error:', err)
                    if (!isConnected.value && !isStreaming.value) reject(err)
                    // SSE 连接出错时，确保结束当前 bubble 的加载状态
                    if (isCurrent && currentAssistantBubble.value && currentAssistantBubble.value.isStreaming) {
                        currentAssistantBubble.value.isStreaming = false
                        currentAssistantBubble.value.content += '\n\n' + t('agentStream.connectionInterrupted')
                    }
                }
                if (isCurrent) {
                    isConnected.value = false
                    isStreaming.value = false
                }
            } finally {
                // 同上：只有"自己仍是当前连接"时才清理，避免旧连接收尾时踩掉新连接
                if (sseAbortController === myController) {
                    sseAbortController = null
                    isConnected.value = false
                    stopHeartbeatMonitor()
                    // SSE 连接结束时（包括正常结束），确保状态正确
                    if (isStreaming.value) {
                        // 仍在流式状态但连接已结束 = 意外断开。后台 @Async 循环并不依赖
                        // SSE，多半还在跑——自动重连续流（run_state/state_recovery 恢复气泡）。
                        console.warn('[AgentStream] SSE connection ended while still streaming, scheduling reconnect')
                        if (currentAssistantBubble.value) currentAssistantBubble.value.isStreaming = false
                        isStreaming.value = false
                        scheduleReconnect('stream-ended')
                    }
                }
            }
        })
    }

    // displayText（可选，契约 D）：模型收到 prompt，用户气泡里显示 displayText。
    // 用于「点一个按钮却要回喂一大段细节给模型」的场景（计划审批卡、反问选项）——
    // 缺省 null 时行为与此前完全一致。
    const sendMessage = async ({ prompt, displayText = '', contentHtml = '', fileList = [], projectId, modelId = 'default', assistantId, mode = 'AGENT', activeContext = null, pinnedSkillId = '', _userImages = [], _userContextFiles = [] }) => {
        // 防重入：流式进行中再触发发送（回车/连点）会产生重复气泡和并发请求。
        // 必须给用户可见反馈——静默吞掉就是"点了发送什么都没发生"（F-07）
        if (isStreaming.value) {
            console.warn('[AgentStream] sendMessage ignored: already streaming')
            try {
                if (typeof uni !== 'undefined' && uni.showToast) {
                    uni.showToast({ title: t('agentStream.alreadyStreamingToast'), icon: 'none' })
                }
            } catch (e) { /* ignore */ }
            return
        }
        // Clear file changes for new turn
        fileChanges.value = []
        // 新一轮开始即清除暂停态（无论是点「继续」还是发新消息）
        agentPaused.value = null
        agentRunStatus.value = 'RUNNING'
        agentAwaitingInput.value = false
        // 这一轮就是上一问的答案（点选项或自己打字都算）：封掉历史上所有未作答的问题卡，
        // 只有最新一条助手消息上的反问可操作——与审批卡「仅最新一条可操作」同口径。
        bubbles.value.forEach(b => {
            if (b.role === 'ASSISTANT' && b.question && !b.question.answered) b.question.answered = true
        })

        // 1. Add User Message with images and context files for display
        bubbles.value.push(createUserBubble(prompt, _userImages, _userContextFiles, contentHtml, displayText))

        // 2. Prepare Assistant Bubble
        const newBubble = createAssistantBubble()
        newBubble.isStreaming = true
        // 思考计时从「发送」那一刻起算（用户感知的等待包含网络/排队），而不是
        // 等 <thinking> 标签到达才起算——否则经常显示 0 秒且卡顿期间不读秒。
        newBubble.thinking.status = 'thinking'
        newBubble.thinking.startTime = Date.now()
        bubbles.value.push(newBubble)
        currentAssistantBubble.value = newBubble

        isStreaming.value = true
        resetParser()

        // 3. Ensure Conversation
        if (!currentConversationId.value) {
            currentConversationId.value = `conv-${Date.now()}`
        }
        const conversationId = currentConversationId.value

        try {
            await connectSSE(conversationId)

            // 4. Send POST
            messageAbortController = new AbortController()
            const payload = {
                projectId: typeof projectId === 'string' ? parseInt(projectId) : projectId,
                conversationId,
                message: prompt,
                // 只有真给了才带字段：null/缺省在后端就是 displayContent 为空、回退 content
                displayText: displayText || null,
                model: modelId,
                mode: mode, // Agent 模式: ASK, PLAN, AGENT
                // Send full context metadata for folder support
                contextItems: fileList.map(f => ({
                    id: String(f.id),
                    name: f.fileName || f.name || 'Unknown',
                    isDir: f.isDir === true,
                    fileType: f.fileType || ''
                })),
                fileIds: fileList.map(f => f.id), // Legacy compatibility
                // NEW: Active context (auto-detected current tab)
                activeContext: activeContext ? {
                    id: String(activeContext.id),
                    name: activeContext.name || 'Unknown',
                    fileType: activeContext.fileType || '',
                    wpsFileId: activeContext.wpsFileId || null
                } : null,
                // 用户钉选的 Skill；为空则后端走触发词自动匹配
                pinnedSkillId: pinnedSkillId || null
            }

            const chatResp = await fetch(`${getApiBaseUrl()}/api/agent/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-Session-Id': getSessionId() || '' },
                body: JSON.stringify(payload),
                signal: messageAbortController.signal
            })
            // fetch 对 4xx/5xx 不 reject，需显式校验，否则会话过期/后端错误时加载态永久卡死
            if (!chatResp.ok) {
                throw new Error(t('agentStream.chatRequestFailed', { status: chatResp.status }))
            }

        } catch (err) {
            if (err.name !== 'AbortError') {
                error.value = err.message
                if (currentAssistantBubble.value) {
                    currentAssistantBubble.value.content += '\n' + t('agentStream.errorWithMessage', { message: err.message })
                    currentAssistantBubble.value.isStreaming = false
                }
                isStreaming.value = false
            }
        }
    }

    const abort = async () => {
        // 1. 向后端发送取消请求
        const conversationId = currentConversationId.value
        if (conversationId) {
            try {
                await fetch(`${getApiBaseUrl()}/api/agent/cancel/${conversationId}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'X-Session-Id': getSessionId() || '' }
                })
                console.log('[AgentStream] Cancel request sent for:', conversationId)
            } catch (e) {
                console.warn('[AgentStream] Failed to send cancel request:', e)
            }
        }

        // 2. 中断前端连接
        if (messageAbortController) messageAbortController.abort()
        if (sseAbortController) sseAbortController.abort()

        // 3. 更新状态
        isStreaming.value = false
        if (currentAssistantBubble.value) {
            currentAssistantBubble.value.isStreaming = false
            // 终态收敛：停止后不允许卡片停留在"执行中"
            finalizeProcesses('error')
            // 停止标记（必须写 content，walkthrough 当前未渲染）。措辞只说「正在停止」：
            // 取消打不断已经发出去的 HTTP 读，在途的那一次调用还可能回一小段，
            // 写「已停止」就是对用户说谎
            currentAssistantBubble.value.content += '\n\n' + t('agentStream.stopping')
        }
    }

    // --- PARSER LOGIC --- (Using currentAssistantBubble.value)\n
    const parseSSELineFull = (line) => {
        if (!line.trim()) {
            if (currentEventData) {
                handleEvent(currentEventName, currentEventData)
            }
            currentEventName = null
            currentEventData = ''
            return
        }

        if (line.startsWith('event:')) {
            currentEventName = line.substring(6).trim()
        } else if (line.startsWith('data:')) {
            let val = line.substring(5)
            if (val.startsWith(' ')) val = val.substring(1)
            currentEventData += (currentEventData ? '\n' : '') + val
        }
    }

    const handleEvent = (evt, dataStr) => {
        // 调试日志：显示事件处理
        console.log('[SSE] handleEvent:', evt, 'dataLen:', dataStr?.length || 0, 'time:', new Date().toISOString())

        // 任务清单更新：不依赖活跃气泡（重连恢复时也要能收到），放在气泡守卫之前
        if (evt === 'plan_update') {
            try {
                const d = JSON.parse(dataStr)
                planTodos.value = Array.isArray(d.todos) ? d.todos : []
                // 同步快照到当前气泡：计划卡在消息流里按时序内联展示（线性结构）。
                // 重连/切回历史会话时气泡指针为空，兜底挂到最后一个助手气泡。
                let target = currentAssistantBubble.value
                if (!target) {
                    const last = bubbles.value[bubbles.value.length - 1]
                    if (last && last.role === 'ASSISTANT') target = last
                }
                if (target) target.planTodos = [...planTodos.value]
            } catch (e) {
                console.error('Failed to parse plan_update', e)
            }
            return
        }

        // 会话运行状态（connect 时后端必发）：切回后台运行中的会话时，靠它恢复
        // 「运行中/已暂停」的 UI 态。必须在气泡守卫之前——重连时气泡指针为 null。
        if (evt === 'run_state') {
            try {
                const d = JSON.parse(dataStr)
                agentRunStatus.value = d.status || null
                if (d.status === 'RUNNING') {
                    isStreaming.value = true // 后台在跑：封发送框，等续流
                } else if (d.status === 'PAUSED') {
                    agentPaused.value = { reason: 'max_depth' } // 切回后恢复「继续」按钮
                } else if (d.status === 'INTERRUPTED') {
                    // 上次进程执行中被杀（关 app/崩溃/断电），启动回收标记出来的：
                    // 半截回复已带 [进程中断] 说明，这里补上同一条「继续」入口
                    agentPaused.value = { reason: 'process_interrupted' }
                } else if (d.status === 'AWAITING_INPUT') {
                    // 模型反问后停机等答案：切回会话时要看得出「AI 在等你回答」。
                    // 不置 isStreaming——后台没有任何东西在跑，输入框必须可用。
                    agentAwaitingInput.value = true
                }
            } catch (e) {
                console.error('Failed to parse run_state', e)
            }
            return
        }

        // 状态恢复（重连续流）：同样必须在气泡守卫之前——切回会话时
        // currentAssistantBubble 为 null，处理器会自建/复用末尾 ASSISTANT 气泡。
        if (evt === 'state_recovery') {
            handleStateRecovery(dataStr)
            return
        }

        // ==================== BACKGROUND TASK EVENTS ====================
        // 三个后台任务事件与气泡无关（PPT 生成这类长任务跨会话切换、跨断线重连都在跑），
        // 必须在气泡守卫之前处理：切回会话时 currentAssistantBubble 为 null，
        // 挂在守卫后面等于「重连后进度条再也不动」。

        if (evt === 'background_task_start') {
            try {
                const d = JSON.parse(dataStr)
                console.log('[SSE] background_task_start:', d.taskId, d.taskType)
                backgroundTasks.value[d.taskId] = {
                    taskId: d.taskId,
                    type: d.taskType,
                    conversationId: d.conversationId,
                    progress: 0,
                    message: t('agentStream.taskStarting'),
                    stage: 'starting',
                    startedAt: Date.now(),
                    estimatedDurationSec: d.estimatedDurationSec,
                    status: 'running'
                }
            } catch (e) {
                console.error('Failed to parse background_task_start', e)
            }
            return
        }

        if (evt === 'task_progress') {
            try {
                const d = JSON.parse(dataStr)
                console.log('[SSE] task_progress:', d.progress + '%', d.message)
                if (d.taskId && backgroundTasks.value[d.taskId]) {
                    Object.assign(backgroundTasks.value[d.taskId], {
                        progress: d.progress || 0,
                        message: d.message || '',
                        stage: d.stage || '',
                        estimatedRemainingSec: d.estimatedRemainingSec,
                        lastUpdate: Date.now()
                    })
                }
            } catch (e) {
                console.error('Failed to parse task_progress', e)
            }
            return
        }

        if (evt === 'background_task_complete') {
            try {
                const d = JSON.parse(dataStr)
                console.log('[SSE] background_task_complete:', d.taskId, d.eventType)
                if (d.taskId && backgroundTasks.value[d.taskId]) {
                    backgroundTasks.value[d.taskId].status = d.success ? 'completed' : 'failed'
                    backgroundTasks.value[d.taskId].progress = d.success ? 100 : backgroundTasks.value[d.taskId].progress
                    backgroundTasks.value[d.taskId].message = d.success ? t('agentStream.taskCompleted') : (d.error || t('agentStream.taskFailed'))
                    backgroundTasks.value[d.taskId].result = d.result
                    backgroundTasks.value[d.taskId].error = d.error
                    backgroundTasks.value[d.taskId].completedAt = Date.now()
                    // 刻意不再「5 秒后 delete 整条」：长任务的结果（成功与否、失败原因）
                    // 是用户唯一能核对的凭据，看一眼的时间都不给等于没做过。
                    // 已结束的条目在切换会话（resetSSE）时清理，或由 UI 侧显式关闭。
                }
            } catch (e) {
                console.error('Failed to parse background_task_complete', e)
            }
            return
        }

        // 心跳：与气泡无关（重连判活依据），必须在气泡守卫之前处理
        if (evt === 'heartbeat') {
            try {
                const d = JSON.parse(dataStr)
                lastHeartbeat.value = {
                    source: d.source,
                    conversationId: d.conversationId,
                    taskId: d.taskId,
                    currentOperation: d.currentOperation,
                    timestamp: d.timestamp || d.ts || Date.now()
                }
            } catch (e) {
                lastHeartbeat.value = { timestamp: Date.now() }
            }
            return
        }

        if (!currentAssistantBubble.value) {
            // 终态事件不能因气泡指针缺失而丢弃：重连落在"RUNNING 但快照未建"窗口时
            // 若把 bubble_end/error/cancelled 一并吞掉，isStreaming 永久锁死、输入框
            // 永久禁用（F-06/F-07 确定性 hang）。这里至少要解锁全局状态。
            if (evt === 'bubble_end' || evt === 'error' || evt === 'cancelled') {
                isStreaming.value = false
                try {
                    const d = JSON.parse(dataStr || '{}')
                    agentPaused.value = (evt === 'bubble_end' && d.status === 'paused')
                        ? { reason: d.reason || '' } : null
                    // 反问停机（AWAITING_INPUT）：这一路是「重连落在快照未建窗口」的兜底，
                    // 少认一个状态的表现是会话切回来既没有「等你回答」提示、状态又显示成已完成
                    agentAwaitingInput.value = (evt === 'bubble_end' && d.status === 'awaiting_input')
                    agentRunStatus.value = evt === 'error' ? 'ERROR'
                        : evt === 'cancelled' ? 'CANCELLED'
                        : d.status === 'paused' ? 'PAUSED'
                        : d.status === 'awaiting_approval' ? 'AWAITING_APPROVAL'
                        : d.status === 'awaiting_input' ? 'AWAITING_INPUT' : 'FINISHED'
                } catch (e) {
                    agentPaused.value = null
                    agentAwaitingInput.value = false
                    agentRunStatus.value = evt === 'error' ? 'ERROR' : 'FINISHED'
                }
            }
            return
        }

        if (evt === 'text_delta') {
            try {
                const d = JSON.parse(dataStr)
                // 调试日志：显示 text_delta 内容
                console.log('[SSE] text_delta content:', (d.content || '').substring(0, 50))
                processTextStream(d.content || '')
            } catch (e) {
                processTextStream(dataStr)
            }
        } else if (evt === 'token_usage') {
            try {
                const d = JSON.parse(dataStr)
                // Accumulate usage for this session
                tokenUsage.value.promptTokens += d.promptTokens || 0
                tokenUsage.value.completionTokens += d.completionTokens || 0
                tokenUsage.value.totalTokens += d.totalTokens || 0
                console.log('[AgentStream] Token Usage Updated:', tokenUsage.value)
            } catch (e) {
                console.error('Failed to parse token_usage', e)
            }
        } else if (evt === 'subtask_progress') {
            // Sub-agent subtask lifecycle (Phase 3C): show as a one-line status step
            try {
                const d = JSON.parse(dataStr)
                appendStatusStep(
                    d.message || (d.stage === 'started' ? t('agentStream.subtaskStarted') : t('agentStream.subtaskEnded')),
                    d.stage !== 'started'
                )
            } catch (e) {
                console.error('Failed to parse subtask_progress', e)
            }
        } else if (evt === 'artifact') {
            try {
                const d = JSON.parse(dataStr)
                handleArtifactEvent(d)
            } catch (e) {
                // 单个 artifact 事件解析失败不应打断整条 SSE 流（否则气泡卡加载、后续事件全丢）
                console.error('Failed to parse artifact event', e)
            }
        } else if (evt === 'client_action') {
            try {
                const d = JSON.parse(dataStr)
                // Trigger registered callbacks
                if (clientActionHandler.value) {
                    clientActionHandler.value(d)
                }
            } catch (e) {
                console.error('Failed to parse client_action', e)
            }
        } else if (evt === 'title_update') {
            // Handle conversation title update from backend
            try {
                const d = JSON.parse(dataStr)
                if (titleUpdateHandler.value && d.title) {
                    titleUpdateHandler.value(d.title)
                }
            } catch (e) {
                console.error('Failed to parse title_update', e)
            }
        } else if (evt === 'doc_stream_data' || evt === 'wps_stream_data') {
            // Live document streaming. Dual-track migration (AI_ARCHITECTURE.md
            // Phase 3): new backends emit doc_stream_data first then the legacy
            // wps_stream_data with the same content; both are routed up the
            // client_action seam and deduped there by the "new-name-first" latch.
            try {
                // Mark current bubble as doc-streaming to suppress chat duplication
                if (currentAssistantBubble.value && !currentAssistantBubble.value.isEditorStreaming) {
                    currentAssistantBubble.value.isEditorStreaming = true
                    // Add a placeholder message if content is empty
                    if (!currentAssistantBubble.value.content) {
                        currentAssistantBubble.value.content = t('agentStream.docStreamingPlaceholder')
                    }
                }

                const d = JSON.parse(dataStr)
                if (clientActionHandler.value) {
                    clientActionHandler.value({ action: evt, content: d.content || '' })
                }
            } catch (e) {
                console.error('Failed to handle ' + evt, e)
            }
        } else if (evt === 'doc_stream_end') {
            // 流式写入结束信号：让消费端冲缓冲并命令 worker 收尾（写尾行/建尾表/复位）
            if (clientActionHandler.value) {
                clientActionHandler.value({ action: 'doc_stream_end' })
            }
        } else if (evt === 'cancelled') {
            // 处理取消事件
            console.log('[SSE] Received cancelled event')
            flushRemainingBuffer()
            currentAssistantBubble.value.isStreaming = false
            const thinking = currentAssistantBubble.value.thinking
            if (thinking.status === 'thinking') {
                thinking.status = 'done'
                if (!thinking.duration || thinking.duration === 0) {
                    thinking.duration = (Date.now() - thinking.startTime) / 1000
                }
            }
            finalizeProcesses('error')
            // 不需要在这里添加 [正在停止] 标记，因为 abort 函数已经添加了
            isStreaming.value = false
        } else if (evt === 'bubble_end' || evt === 'error') {
            // Flush any remaining content in parserBuffer before ending
            flushRemainingBuffer()
            currentAssistantBubble.value.isStreaming = false
            const thinking = currentAssistantBubble.value.thinking
            if (thinking.status === 'thinking') {
                thinking.status = 'done'
                // Only calculate if not already done (avoid overwriting)
                if (!thinking.duration || thinking.duration === 0) {
                    thinking.duration = (Date.now() - thinking.startTime) / 1000
                }
            }
            // 终态收敛：流结束后不允许任何卡片停留在"执行中/加载中"
            finalizeProcesses(evt === 'error' ? 'error' : 'success')
            // 步数超限暂停（后端 status=paused）：置位供 UI 渲染「继续」按钮。
            // 反问停机（status=awaiting_input）走 agentAwaitingInput，不复用「继续」按钮。
            if (evt === 'bubble_end') {
                try {
                    const d = JSON.parse(dataStr || '{}')
                    agentPaused.value = d.status === 'paused' ? { reason: d.reason || '' } : null
                    agentAwaitingInput.value = d.status === 'awaiting_input'
                    agentRunStatus.value = d.status === 'paused' ? 'PAUSED'
                        : d.status === 'awaiting_approval' ? 'AWAITING_APPROVAL'
                        : d.status === 'awaiting_input' ? 'AWAITING_INPUT' : 'FINISHED'
                } catch (e) {
                    agentPaused.value = null
                    agentAwaitingInput.value = false
                    agentRunStatus.value = 'FINISHED'
                }
            } else {
                agentRunStatus.value = 'ERROR'
                agentAwaitingInput.value = false // 出错就不是「在等你回答」了，提示必须撤掉
            }
            // Ensure error is visible
            // 注意：错误必须写入 content（walkthrough 卡片当前未渲染，写那里用户永远看不到）
            if (evt === 'error') {
                const errMsg = dataStr || "Unknown Error"
                // 地域拒绝（后端 LlmErrorClassifier.REGION_BLOCKED_MARKER）：上游返回的是一句英文
                // 「This model is not available in your region」，原样拼给用户等于没有信息。
                // 这里换成中文引导。注意载荷前面还拼着「Stream Error: 」，所以用 includes 而不是前缀判断。
                // 走到这一步说明后端连区域无关的备用模型也没换成（换成了会走文本流的切换提示，不是 error）。
                if (errMsg.includes('AI_REGION_BLOCKED')) {
                    currentAssistantBubble.value.content +=
                        '\n\n' + t('agentStream.regionBlockedNotice') + '\n'
                } else if (errMsg.includes('AI_QUOTA_EXHAUSTED')) {
                    // 配额耗尽（后端 LlmErrorClassifier.QUOTA_EXHAUSTED_MARKER）：终局错误，
                    // 后端不会退避重试也不会换模型——换哪个模型都是同一个没钱的账户
                    currentAssistantBubble.value.content +=
                        '\n\n' + t('agentStream.quotaExhaustedNotice') + '\n'
                } else if (errMsg.includes('AI_CONTEXT_OVERFLOW')) {
                    // 上下文超窗（后端 LlmErrorClassifier.CONTEXT_OVERFLOW_MARKER）：
                    // 走到这里说明后端强制压缩后仍装不下（或压不动）
                    currentAssistantBubble.value.content +=
                        '\n\n' + t('agentStream.contextOverflowNotice') + '\n'
                } else {
                    currentAssistantBubble.value.content += '\n\n' + t('agentStream.executionInterrupted', { message: errMsg }) + '\n'
                }
            }
        }
        if (evt === 'bubble_end' || evt === 'cancelled') isStreaming.value = false

        // heartbeat 与三个后台任务事件已在气泡守卫之前处理（见上）

        // Handle File Changes (Added/Modified)
        if (evt === 'file_change') {
            try {
                const d = JSON.parse(dataStr)
                // d: { fileName: "...", changeType: "ADDED" | "MODIFIED" }
                // Avoid duplicates?
                const exists = fileChanges.value.some(f => f.fileName === d.fileName && f.changeType === d.changeType)
                if (!exists) {
                    fileChanges.value.push(d)
                }
                console.log('[AgentStream] File Change:', d)
            } catch (e) {
                console.error('Failed to parse file_change', e)
            }
        }
    }

    // 状态恢复（重连续流）：后端把本轮已生成的全量文本快照重放给前端。
    // 从 handleEvent 内联块提出来，因为它必须在气泡守卫之前被调用（切回会话场景）。
    const handleStateRecovery = (dataStr) => {
            try {
                const d = JSON.parse(dataStr)
                console.log('[SSE] State Recovery:', d.content.length, 'chars')

                // 1. Ensure we have an active Assistant Bubble
                // Check if last bubble is Assistant
                let bubble = bubbles.value.length > 0 ? bubbles.value[bubbles.value.length - 1] : null

                // If last bubble is USER, create new ASSISTANT bubble
                if (!bubble || bubble.role !== 'ASSISTANT') {
                    console.log('[AgentStream] Recovery: Creating new assistant bubble')
                    bubble = createAssistantBubble()
                    bubbles.value.push(bubble)
                } else {
                    console.log('[AgentStream] Recovery: reusing last assistant bubble')
                    // Optional: Clear content if we want to re-parse from scratch to ensure consistency
                    // But maybe the DB already loaded some? 
                    // To be safe and avoid duplication/conflict, let's RESET this bubble's content
                    // and let the snapshot re-fill it.
                    bubble.content = ''
                    bubble.thinking = { status: 'idle', content: '', duration: 0, startTime: 0, endTime: 0 }
                    bubble.processes = []
                    bubble.artifacts = []
                    bubble.walkthrough = ''
                    // 快照会把 <question> 整块重放，这里必须一起清掉，否则旧问题卡与重建的并存
                    bubble.question = null
                }
                // 重连恢复：把已收到的任务清单快照挂回本轮气泡（plan_update 可能先到）
                bubble.planTodos = Array.isArray(planTodos.value) ? [...planTodos.value] : []

                currentAssistantBubble.value = bubble
                currentAssistantBubble.value.isStreaming = true
                isStreaming.value = true

                // 2. Reset Parser State
                resetParser()

                // 3. Process the full snapshot
                // Treat it like a huge chunk of text
                if (d.content) {
                    parserBuffer += d.content
                    parseTags()
                }

            } catch (e) {
                console.error('Failed to parse state_recovery', e)
            }
    }

    /**
     * 往过程卡追加一行状态文字（子任务开始/结束这类无工具调用的进度）。
     * 必须推 items 而不是 steps：ProcessCard 里 items 与 steps 是 v-if/v-else-if 关系，
     * 有 items 的过程卡（解析器建的全都有）里推 steps 永远不会显示。
     * 刻意不改 activeProcessId/activeTag——那是 XML 解析器的状态，SSE 事件不该动它。
     */
    const appendStatusStep = (text, isDone) => {
        const bubble = currentAssistantBubble.value
        if (!bubble) return

        let proc = bubble.processes.find(p => p.id === activeProcessId)
            || bubble.processes[bubble.processes.length - 1]
        if (!proc) {
            // 尚无任何过程卡（子任务在首个 <process> 之前就起跑）：建一张无归属的
            // ProcessCard.vue 按标题串识别系统卡（'系统操作' / 'System Actions'），locale 值不得改措辞
            proc = { id: `proc-sys-${Date.now()}`, title: t('agentStream.systemOperation'), isExpanded: true, items: [], content: '' }
            bubble.processes.push(proc)
        }
        if (!proc.items) proc.items = []
        proc.items.push({ type: 'step', status: isDone ? 'done' : 'doing', text })
    }

    // --- PARSER HELPERS ---

    /**
     * 终态收敛：流结束（正常/出错/取消）时，把所有仍处于进行中的条目落到终态。
     * finalToolStatus: 'success'（正常结束）| 'error'（出错/取消）
     */
    const finalizeProcesses = (finalToolStatus) => {
        const bubble = currentAssistantBubble.value
        if (!bubble || !bubble.processes) return
        bubble.processes.forEach(proc => {
            const items = proc.items || []
            items.forEach(item => {
                if (item.type === 'step' && item.status === 'doing') {
                    item.status = 'done'
                } else if (item.type === 'tool' && item.status === 'loading') {
                    item.status = finalToolStatus
                } else if (item.type === 'thinking' && item.status === 'thinking') {
                    item.status = 'done'
                    if (item.startTime && !item.duration) {
                        item.duration = (Date.now() - item.startTime) / 1000
                    }
                }
            })
        })
    }

    // Flush any remaining content in parserBuffer (called when stream ends)
    const flushRemainingBuffer = () => {
        if (parserBuffer && parserBuffer.trim()) {
            console.log('[AgentStream] Flushing remaining buffer:', parserBuffer.length, 'chars')
            flushContent(parserBuffer)
            parserBuffer = ''
        }
    }

    const handleArtifactEvent = (evt) => {
        if (!currentAssistantBubble.value) return
        if (evt.operation === 'create') {
            currentAssistantBubble.value.artifacts.push({
                id: evt.id,
                type: evt.type,
                status: evt.status,
                data: evt.data,
                fileName: evt.name ? evt.name : (evt.type === 'task_list' ? t('agentStream.taskListArtifact') : t('agentStream.planArtifact'))
            })
        }
    }

    const flushContent = (text) => {
        const bubble = currentAssistantBubble.value
        if (!bubble || !text) return

        if (activeTag === 'thinking') {
            // Check if we are inside a process -> Nested Thinking
            if (activeProcessId) {
                const proc = bubble.processes.find(p => p.id === activeProcessId)
                if (proc && proc.items.length > 0) {
                    const lastItem = proc.items[proc.items.length - 1]
                    if (lastItem.type === 'thinking') {
                        lastItem.content += text
                    }
                }
            } else {
                // If we have existing processes, this might be "Interim Thinking" between steps
                if (bubble.processes.length > 0) {
                    const lastProc = bubble.processes[bubble.processes.length - 1]
                    let lastItem = lastProc.items.length > 0 ? lastProc.items[lastProc.items.length - 1] : null

                    if (!lastItem || lastItem.type !== 'thinking' || lastItem.status === 'done') {
                        // Create new thinking item in this process
                        lastProc.items.push({
                            type: 'thinking',
                            status: 'thinking',
                            content: text,
                            startTime: Date.now()
                        })
                        activeProcessId = lastProc.id
                    } else {
                        lastItem.content += text
                    }
                } else {
                    // Root Level Thinking (Initial Ghost)
                    bubble.thinking.content += text
                }
            }
        } else if (activeTag === 'title') {
            bubble.title += text
        } else if (activeTag === 'process') {
            // Process tag itself has no content
        } else if (activeTag === 'step') {
            const currentProc = bubble.processes.find(p => p.id === activeProcessId)
            if (currentProc && currentProc.items.length > 0) {
                const lastItem = currentProc.items[currentProc.items.length - 1]
                if (lastItem.type === 'step') {
                    lastItem.text += text
                }
            }
        } else if (activeTag === 'tool_code') {
            const p = bubble.processes.find(x => x.id === activeProcessId)
            if (p && p.items.length > 0) {
                const lastItem = p.items[p.items.length - 1]
                if (lastItem.type === 'tool') {
                    // 解转义放在累加之后：&lt; 可能被切在两段字节之间，
                    // 对整段已累加的文本还原才不会漏（decodeProtocolTags 幂等）
                    lastItem.code = decodeProtocolTags(lastItem.code + text)
                }
            }
        } else if (activeTag === 'tool_output') {
            // FIFO 归属：tool_output 打开时已锁定目标条目（activeToolItem）
            let toolItem = activeToolItem
            if (!toolItem) {
                const p = bubble.processes.find(x => x.id === activeProcessId)
                if (p) toolItem = [...p.items].reverse().find(i => i.type === 'tool')
            }
            if (toolItem) {
                // 同 tool_code：对整段已累加的文本解转义，跨 chunk 的 &lt; 才不会漏
                toolItem.output = decodeProtocolTags(toolItem.output + text)
                // ONLY use heuristic if status wasn't set by backend (i.e., still 'loading')
                // If backend already set status via <tool_output status="..."> attribute, do NOT override
                if (toolItem.status === 'loading') {
                    // Fallback heuristic for legacy backends without status attribute
                    if (toolItem.output.includes('Error') || toolItem.output.includes('Exception')) {
                        toolItem.status = 'error'
                    }
                    // Do NOT set to 'success' here - wait for tag close or explicit status
                }
            }
        } else if (activeTag === 'walkthrough') {
            bubble.walkthrough += text
        } else if (activeTag === 'final') {
            bubble.content += text
        } else if (activeTag === 'question') {
            // 反问正文进结构化字段而不是 content：问题卡要把正文与选项分开渲染，
            // 并且答案是「下一轮普通用户消息」，正文混进 content 后无法只封问题卡
            if (!bubble.question) bubble.question = { text: '', options: [], answered: false }
            bubble.question.text += text
        } else if (activeTag === 'option') {
            if (bubble.question && bubble.question.options.length > 0) {
                bubble.question.options[bubble.question.options.length - 1] += text
            }
        } else if (activeTag === 'artifact') {
            const artifacts = bubble.artifacts
            if (artifacts.length > 0) {
                const lastArt = artifacts[artifacts.length - 1]
                if (!lastArt.data) lastArt.data = { content: '' }
                lastArt.data.content += text
            }
        } else {
            // Untagged text -> Main Content
            if (!bubble.content && !text.trim()) return

            // 可见正文开始流出 = 思考阶段结束（无 <final> 标签的旧格式也要收敛读秒）
            settleRootThinking(bubble)

            // [Modified] If we are streaming to the document editor, suppress untagged content from chat bubble
            if (bubble.isEditorStreaming) {
                return
            }

            bubble.content += text
        }
    }

    // 根级思考收敛：首个可见产出（标题/过程/正文）出现时，思考阶段自然结束。
    // 计时从发送起算，所以这里的 duration = 用户真实等待的「思考+排队」时长。
    const settleRootThinking = (bubble) => {
        const th = bubble && bubble.thinking
        if (th && th.status === 'thinking') {
            th.status = 'done'
            th.endTime = Date.now()
            th.duration = th.startTime ? (th.endTime - th.startTime) / 1000 : 0
        }
    }

    const handleTag = (tagName, isClose, attrs, fullTag) => {
        const bubble = currentAssistantBubble.value
        if (!bubble) return

        // Extract attributes
        let attributes = {}
        if (!isClose && fullTag) {
            const attrRegex = /(\w+)="([^"]*)"/g
            let match
            while ((match = attrRegex.exec(fullTag)) !== null) {
                attributes[match[1]] = match[2]
            }
        }

        if (tagName === 'thinking') {
            if (isClose) {
                // Close thinking
                if (activeProcessId) {
                    const proc = bubble.processes.find(p => p.id === activeProcessId)
                    if (proc) {
                        const lastItem = proc.items[proc.items.length - 1]
                        if (lastItem && lastItem.type === 'thinking') {
                            lastItem.status = 'done'
                            // Calculate duration for per-segment timing
                            lastItem.endTime = Date.now()
                            lastItem.duration = (Date.now() - lastItem.startTime) / 1000
                        }
                    }
                } else {
                    bubble.thinking.status = 'done'
                    // Calculate this segment's duration (not cumulative)
                    bubble.thinking.endTime = Date.now()
                    bubble.thinking.duration = (bubble.thinking.endTime - bubble.thinking.startTime) / 1000
                }
                activeTag = activeProcessId ? 'process' : null // Return to process scope or null
            } else {
                // Open thinking
                if (activeProcessId) {
                    const proc = bubble.processes.find(p => p.id === activeProcessId)
                    if (proc) {
                        proc.items.push({
                            type: 'thinking',
                            status: 'thinking',
                            content: '',
                            startTime: Date.now()
                        })
                    }
                    activeTag = 'thinking'
                } else if (bubble.processes.length > 0) {
                    // IMPORTANT: If processes exist, attach thinking to LAST process
                    // instead of root. This prevents root thinking from accumulating
                    // time from subsequent thinking phases.
                    const lastProc = bubble.processes[bubble.processes.length - 1]
                    lastProc.items.push({
                        type: 'thinking',
                        status: 'thinking',
                        content: '',
                        startTime: Date.now()
                    })
                    activeProcessId = lastProc.id
                    activeTag = 'thinking'
                } else {
                    // Only root thinking if NO processes exist yet
                    bubble.thinking.status = 'thinking'
                    // 发送时已打过 startTime（读秒从发送起算），这里只兜底补齐
                    if (!bubble.thinking.startTime) bubble.thinking.startTime = Date.now()
                    activeTag = 'thinking'
                }
            }
        } else if (tagName === 'title') {
            if (isClose) activeTag = null
            else {
                settleRootThinking(bubble)
                activeTag = 'title'
                bubble.title = ''
            }
        } else if (tagName === 'process') {
            if (isClose) {
                // NOTE: 不要在这里自动标记工具为成功！
                // 后端在 process 关闭后才发送 <tool_output status="...">
                // tool_output handler 会正确更新状态

                // 但 step（文字步骤）到 process 关闭即算完成，否则卡片徽标永远停在"执行中"
                const closingProc = bubble.processes.find(p => p.id === activeProcessId)
                if (closingProc) {
                    closingProc.items.forEach(item => {
                        if (item.type === 'step' && item.status === 'doing') item.status = 'done'
                    })
                }

                activeTag = null
                activeProcessId = null
            } else {
                settleRootThinking(bubble)
                const pid = `proc-${Date.now()}`
                // 新任务开始 = 之前所有任务的文字步骤都已结束（兜底收敛，防止历史卡片停留"执行中"）
                bubble.processes.forEach(p => p.items?.forEach(item => {
                    if (item.type === 'step' && item.status === 'doing') item.status = 'done'
                }))
                // Only collapse others if this is a NEW top-level process?
                // For now keep behavior: collapse others
                bubble.processes.forEach(p => p.isExpanded = false)

                const processTitle = attributes['name'] || 'Processing...'

                // 归组到 plan 步骤：plan_update 与文本流在同一 SSE 流里按序到达，
                // 新 process 打开那一刻的 in_progress 项就是它所属的步骤。
                // （todo_write 自己所在的 process 会归到上一步/未分组，可接受。）
                const stepIdx = planTodos.value.findIndex(t => t.status === 'in_progress')

                bubble.processes.push({
                    id: pid,
                    title: processTitle,
                    isExpanded: true,
                    items: [], // CHANGED: from steps -> items
                    content: '',
                    stepIndex: stepIdx,
                    stepTitle: stepIdx >= 0 ? (planTodos.value[stepIdx].content || '') : ''
                })
                activeProcessId = pid
                activeTag = 'process'
            }
        } else if (tagName === 'step') {
            if (!isClose) {
                const currentProc = bubble.processes.find(p => p.id === activeProcessId)
                if (currentProc) {
                    // Push generic step item
                    currentProc.items.push({ type: 'step', status: 'doing', text: '' })
                }
                activeTag = 'step'
            } else {
                // Close step
                activeTag = 'process'
            }
        } else if (tagName === 'tool_code') {
            if (isClose) {
                activeTag = 'process'
            } else {
                const currentProc = bubble.processes.find(p => p.id === activeProcessId)
                if (currentProc) {
                    currentProc.items.push({
                        type: 'tool',
                        code: '',
                        output: '',
                        status: 'loading'
                    })
                }
                activeTag = 'tool_code'
            }
        } else if (tagName === 'tool_output') {
            if (isClose) {
                // 关键修复：tool_output 通常在 </process> 之后才由后端补发，此时 process 已关闭。
                // 这里必须回到"无标签"状态（而不是 'process'），否则后续所有普通文本都会被
                // flushContent 的 process 分支静默丢弃 —— 表现为工具执行后正文再也不更新（面板假死）。
                activeTag = null
                const borrowedProc = bubble.processes.find(p => p.id === activeProcessId)
                if (borrowedProc) {
                    borrowedProc.items.forEach(item => {
                        if (item.type === 'step' && item.status === 'doing') item.status = 'done'
                    })
                }
                activeProcessId = null
                // Check if tool output contains file creation success JSON (legacy check, keep for now)
                const closedItem = activeToolItem
                if (closedItem && closedItem.output) {
                    if (closedItem.output.includes('"wps_file_id":') && closedItem.output.includes('"status":"success"')) {
                        console.log('[AgentStream] Detected file creation')
                        if (clientActionHandler.value) clientActionHandler.value({ action: 'refresh_files' })
                    }
                }
                activeToolItem = null
            } else {
                // Open Tag: Parse Status Attribute
                const statusAttr = attributes['status'] // Expect "SUCCESS" or "FAILURE"

                // FIFO 归属：一轮多工具时后端按调用顺序补发多个 tool_output，
                // 目标是「文档序最早、仍在 loading」的 tool 条目；没有 loading 的
                // 再退回「最后一个 tool」（兼容旧的单工具流）。
                let toolItem = null
                outer:
                for (const proc of bubble.processes) {
                    for (const item of proc.items) {
                        if (item.type === 'tool' && item.status === 'loading') { toolItem = item; break outer }
                    }
                }
                if (!toolItem) {
                    for (let i = bubble.processes.length - 1; i >= 0 && !toolItem; i--) {
                        toolItem = [...bubble.processes[i].items].reverse().find(item => item.type === 'tool') || null
                    }
                }

                if (toolItem) {
                    if (statusAttr === 'SUCCESS') {
                        toolItem.status = 'success'
                    } else if (statusAttr === 'FAILURE') {
                        toolItem.status = 'error'
                    }
                    // If no status attribute, keep current status (loading) for heuristics/fallback
                }
                activeToolItem = toolItem
                activeTag = 'tool_output'
            }
        } else if (tagName === 'walkthrough') {
            if (isClose) activeTag = null
            else activeTag = 'walkthrough'
        } else if (tagName === 'final') {
            if (isClose) activeTag = null
            else { settleRootThinking(bubble); activeTag = 'final' }
        } else if (tagName === 'question') {
            if (isClose) {
                // 收尾去掉正文两端空白：模型习惯在标签后换行，问题卡首行会多一个空行
                if (bubble.question) {
                    bubble.question.text = (bubble.question.text || '').trim()
                    bubble.question.options = bubble.question.options
                        .map(o => (o || '').trim())
                        .filter(o => o.length > 0)
                }
                activeTag = null
            } else {
                settleRootThinking(bubble)
                // 一轮内出现第二个 <question> 时整块覆盖：只保留可作答的最后一问
                bubble.question = { text: '', options: [], answered: false }
                activeTag = 'question'
            }
        } else if (tagName === 'option') {
            // 选项只在 <question> 内有意义；模型偶发裸发 <option> 时惰性补一个空问题体，
            // 否则选项文字会掉进「无标签正文」分支泄漏成散句
            if (!bubble.question) bubble.question = { text: '', options: [], answered: false }
            if (isClose) {
                activeTag = 'question' // 回到问题体作用域，后续正文继续拼在 text 上
            } else {
                bubble.question.options.push('')
                activeTag = 'option'
            }
        } else if (tagName === 'artifact') {
            if (!isClose) {
                const typeMatch = (attrs || '').match(/type="([^"]+)"/)
                const type = typeMatch ? typeMatch[1] : (attributes['type'] || 'unknown')
                const name = attributes['name'] || null
                activeTag = 'artifact'

                const aid = `art-${Date.now()}`
                handleArtifactEvent({ operation: 'create', id: aid, type, name, status: 'draft', data: { content: '' } })
            } else {
                activeTag = null
            }
        }
    }

    // --- XML STREAM PROCESSOR ---
    const processTextStream = (text) => {
        // FILTER: Detect and strip orphaned JSON content artifacts (e.g. {"content":""} or {"content":"..."})
        // This mitigates the issue where the model echoes the hidden JSON protocol
        if (text.trim().startsWith('{"content":') && text.trim().endsWith('}')) {
            try {
                const json = JSON.parse(text)
                // If it parsed, we use the inner content if present, or just drop it
                if (json.content !== undefined) {
                    text = json.content
                }
            } catch (e) {
                // Not valid JSON, let it pass or strip if it looks like the artifact
                // The artifact usually looks like: {"content":""} which is empty.
                if (text.includes('{"content":""}')) {
                    text = text.replace('{"content":""}', '')
                }
            }
        }

        parserBuffer += text

        // FILTER: Strip markdown code block wrappers
        parserBuffer = parserBuffer.replace(/^```(?:xml|html|markdown)?\s*\n?/gm, '')
        parserBuffer = parserBuffer.replace(/\n?```\s*$/gm, '')
        parserBuffer = parserBuffer.replace(/```(?:xml|html|markdown)?\s*\n/g, '')
        parserBuffer = parserBuffer.replace(/\n```/g, '')

        // 标签清单在 agentTagProtocol.mjs（与后端 AgentTagProtocol.TAGS 同一份）：
        // option 是 question 的子标签，不认它的话选项文字会当正文流出去，用户会看到裸的 <option> 源码；
        // 后端按同一份清单中和工具载荷，载荷里的 </tool_output> 之类不会在这里顶掉外层标签
        const tagRegex = createProtocolTagRegex()

        while (true) {
            const match = tagRegex.exec(parserBuffer)
            if (!match) break

            const [fullTag, isSlash, tagName] = match
            const index = match.index

            // <option> 只在 question 作用域内才是标签。正文里出现字面量 <option>
            // （比如给用户讲 HTML 表单）不能凭它凭空造出一张问题卡——问题卡会把
            // 会话显示成「AI 在等你回答」，误报的代价比多显示一段源码大。
            if (tagName === 'option' && activeTag !== 'question' && activeTag !== 'option') {
                flushContent(parserBuffer.substring(0, index + fullTag.length))
                parserBuffer = parserBuffer.substring(index + fullTag.length)
                tagRegex.lastIndex = 0
                continue
            }

            // Emit text before tag
            if (index > 0) {
                flushContent(parserBuffer.substring(0, index))
            }

            handleTag(tagName, isSlash === '/', null, fullTag)

            // Slice buffer
            parserBuffer = parserBuffer.substring(index + fullTag.length)
            tagRegex.lastIndex = 0
        }
    }



    const clientActionHandler = ref(null)
    const titleUpdateHandler = ref(null)

    /**
     * 回退到指定消息
     * 删除该消息及其之后的所有bubbles，返回被回退消息的内容
     * @param messageIndex 要回退到的消息在bubbles数组中的索引
     * @returns 被回退消息的内容（用于放入输入框）
     */
    const rollbackToMessage = (messageIndex) => {
        if (messageIndex < 0 || messageIndex >= bubbles.value.length) {
            console.error('[AgentStream] Invalid rollback index:', messageIndex)
            return ''
        }

        const targetMessage = bubbles.value[messageIndex]
        const content = targetMessage.content || ''

        // 删除目标消息及之后的所有消息
        bubbles.value.splice(messageIndex)

        console.log('[AgentStream] Rolled back to message index:', messageIndex, 'remaining:', bubbles.value.length)

        return content
    }

    return {
        bubbles,
        isStreaming,
        sendMessage,
        abort,
        setConversationId: setConversationIdWithReset,
        resetSSE,
        clearBubbles,
        currentConversationId,
        // Rollback support
        rollbackToMessage,
        // Background task tracking
        onClientAction: (cb) => { clientActionHandler.value = cb },
        onTitleUpdate: (cb) => { titleUpdateHandler.value = cb },
        tokenUsage,
        fileChanges,
        backgroundTasks,
        lastHeartbeat,
        planTodos,
        agentPaused,
        agentRunStatus,
        agentAwaitingInput,
        // 已结束的后台任务不再自动销毁（完成态要保留可查），供任务面板做「关闭」按钮
        dismissBackgroundTask: (taskId) => {
            if (taskId && backgroundTasks.value[taskId]) delete backgroundTasks.value[taskId]
        },
        // 切回会话时重连 SSE：后端 connect 会推 run_state（运行中还会推 state_recovery 续流）。
        reattachSSE: async (conversationId) => {
            if (!conversationId) return
            try { await connectSSE(conversationId) } catch (e) { console.warn('[AgentStream] reattach failed', e) }
        },
        // Load metadata for historical conversations
        loadConversationMetadata: async (conversationId) => {
            if (!conversationId) return
            try {
                const resp = await getConversationMetadata(conversationId)
                // resp: { fileChanges: [...], tokenUsage: { promptTokens, completionTokens, totalTokens } }
                if (resp.fileChanges) {
                    fileChanges.value = resp.fileChanges
                }
                if (resp.tokenUsage) {
                    tokenUsage.value = resp.tokenUsage
                }
                console.log('[AgentStream] Loaded metadata for conversation:', conversationId, resp)
            } catch (e) {
                console.warn('[AgentStream] Failed to load conversation metadata:', e)
            }
        }
    }
}
