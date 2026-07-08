import { ref, reactive, nextTick } from 'vue'
import { getApiBaseUrl, getConversationMetadata } from '@/services/api.js'
import { getSessionId } from '@/utils/auth.js'

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

    // POINTER: The current bubble we are writing to (Assistant)
    const currentAssistantBubble = ref(null)

    // Abort Controllers
    let sseAbortController = null
    let messageAbortController = null

    // Parser State (Local to the current stream)
    let parserBuffer = ''
    let activeTag = null
    let activeProcessId = null
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
        artifacts: [],
        walkthrough: '',
        content: '', // Main Answer (from <final> tag)
        rawLog: '',
        isStreaming: false
    })

    const createUserBubble = (content, images = [], contextFiles = [], contentHtml = '') => ({
        id: `msg-${Date.now()}`,
        role: 'USER',
        content: content,
        contentHtml: contentHtml, // HTML with inline file tags for display
        images: images,
        contextFiles: contextFiles
    })

    // --- RESET PARSER STATE ---
    const resetParser = () => {
        parserBuffer = ''
        activeTag = null
        activeProcessId = null
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
                resolve()

                const reader = response.body.getReader()
                const decoder = new TextDecoder('utf-8')
                let buffer = ''

                while (true) {
                    const { done, value } = await reader.read()
                    if (done) break

                    const chunk = decoder.decode(value, { stream: true })
                    // 调试日志：显示接收到的 chunk 时间戳
                    console.log('[SSE] Chunk received at:', new Date().toISOString(), 'size:', chunk.length)
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
                        currentAssistantBubble.value.content += '\n\n*[连接中断]*'
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
                    // SSE 连接结束时（包括正常结束），确保状态正确
                    if (isStreaming.value && currentAssistantBubble.value) {
                        // 如果仍在流式状态但连接已结束，说明可能是意外断开
                        console.warn('[AgentStream] SSE connection ended while still streaming')
                        currentAssistantBubble.value.isStreaming = false
                        isStreaming.value = false
                    }
                }
            }
        })
    }

    const sendMessage = async ({ prompt, contentHtml = '', fileList = [], projectId, modelId = 'default', assistantId, mode = 'AGENT', activeContext = null, _userImages = [], _userContextFiles = [] }) => {
        // 防重入：流式进行中再触发发送（回车/连点）会产生重复气泡和并发请求
        if (isStreaming.value) {
            console.warn('[AgentStream] sendMessage ignored: already streaming')
            return
        }
        // Clear file changes for new turn
        fileChanges.value = []

        // 1. Add User Message with images and context files for display
        bubbles.value.push(createUserBubble(prompt, _userImages, _userContextFiles, contentHtml))

        // 2. Prepare Assistant Bubble
        const newBubble = createAssistantBubble()
        newBubble.isStreaming = true
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
                } : null
            }

            const chatResp = await fetch(`${getApiBaseUrl()}/api/agent/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-Session-Id': getSessionId() || '' },
                body: JSON.stringify(payload),
                signal: messageAbortController.signal
            })
            // fetch 对 4xx/5xx 不 reject，需显式校验，否则会话过期/后端错误时加载态永久卡死
            if (!chatResp.ok) {
                throw new Error(`对话请求失败: HTTP ${chatResp.status}`)
            }

        } catch (err) {
            if (err.name !== 'AbortError') {
                error.value = err.message
                if (currentAssistantBubble.value) {
                    currentAssistantBubble.value.content += `\n**Error**: ${err.message}`
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
            // 添加已停止标记
            if (currentAssistantBubble.value.content) {
                currentAssistantBubble.value.content += '\n\n*[已停止]*'
            } else if (currentAssistantBubble.value.walkthrough) {
                currentAssistantBubble.value.walkthrough += '\n\n*[已停止]*'
            }
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

        if (!currentAssistantBubble.value) return

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
        } else if (evt === 'step_update') {
            try {
                const d = JSON.parse(dataStr)
                handleStepUpdate(d)
            } catch (e) {
                console.error('Failed to parse step_update', e)
            }
        } else if (evt === 'subtask_progress') {
            // Sub-agent subtask lifecycle (Phase 3C): show as a one-line status step
            try {
                const d = JSON.parse(dataStr)
                handleStepUpdate({
                    status: d.stage === 'started' ? 'loading' : 'done',
                    message: d.message || `子任务${d.stage === 'started' ? '开始' : '结束'}`
                })
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
        } else if (evt === 'wps_stream_data') {
            // Live document streaming (backend event name kept for contract
            // stability, #79). Routed up the same client_action seam so the page
            // can feed the embedded LibreOffice editor (buffered insert).
            try {
                // Mark current bubble as doc-streaming to suppress chat duplication
                if (currentAssistantBubble.value && !currentAssistantBubble.value.isEditorStreaming) {
                    currentAssistantBubble.value.isEditorStreaming = true
                    // Add a placeholder message if content is empty
                    if (!currentAssistantBubble.value.content) {
                        currentAssistantBubble.value.content = '*(Streaming content to document...)*'
                    }
                }

                const d = JSON.parse(dataStr)
                if (clientActionHandler.value) {
                    clientActionHandler.value({ action: 'wps_stream_data', content: d.content || '' })
                }
            } catch (e) {
                console.error('Failed to handle wps_stream_data', e)
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
            // 不需要在这里添加 [已停止] 标记，因为 abort 函数已经添加了
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
            // Ensure error is visible
            if (evt === 'error') {
                const errMsg = dataStr || "Unknown Error"
                currentAssistantBubble.value.walkthrough += `\n\n> [!CAUTION]\n> **Error**: ${errMsg}\n`

                // FORCE UPDATE: Mark active tool as error if any
                if (activeProcessId) {
                    const proc = currentAssistantBubble.value.processes.find(p => p.id === activeProcessId)
                    if (proc && proc.items.length > 0) {
                        const lastItem = proc.items[proc.items.length - 1]
                        if (lastItem.type === 'tool' && lastItem.status === 'loading') {
                            lastItem.status = 'error'
                            lastItem.output += `\n[System Error: ${errMsg}]`
                        }
                    }
                }
            }
        }
        if (evt === 'bubble_end' || evt === 'cancelled') isStreaming.value = false

        // ==================== BACKGROUND TASK EVENTS ====================

        // Handle task progress updates
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
        }

        // Handle background task start
        if (evt === 'background_task_start') {
            try {
                const d = JSON.parse(dataStr)
                console.log('[SSE] background_task_start:', d.taskId, d.taskType)
                backgroundTasks.value[d.taskId] = {
                    taskId: d.taskId,
                    type: d.taskType,
                    conversationId: d.conversationId,
                    progress: 0,
                    message: '任务开始...',
                    stage: 'starting',
                    startedAt: Date.now(),
                    estimatedDurationSec: d.estimatedDurationSec,
                    status: 'running'
                }
            } catch (e) {
                console.error('Failed to parse background_task_start', e)
            }
        }

        // Handle background task completion
        if (evt === 'background_task_complete') {
            try {
                const d = JSON.parse(dataStr)
                console.log('[SSE] background_task_complete:', d.taskId, d.eventType)
                if (d.taskId && backgroundTasks.value[d.taskId]) {
                    backgroundTasks.value[d.taskId].status = d.success ? 'completed' : 'failed'
                    backgroundTasks.value[d.taskId].progress = d.success ? 100 : backgroundTasks.value[d.taskId].progress
                    backgroundTasks.value[d.taskId].message = d.success ? '任务完成' : (d.error || '任务失败')
                    backgroundTasks.value[d.taskId].result = d.result
                    backgroundTasks.value[d.taskId].error = d.error

                    // Auto-remove after 5 seconds
                    setTimeout(() => {
                        delete backgroundTasks.value[d.taskId]
                    }, 5000)
                }
            } catch (e) {
                console.error('Failed to parse background_task_complete', e)
            }
        }

        // Handle heartbeat
        if (evt === 'heartbeat') {
            try {
                const d = JSON.parse(dataStr)
                lastHeartbeat.value = {
                    source: d.source, // 'LLM_LOOP' or 'PPTX_SERVICE'
                    conversationId: d.conversationId,
                    taskId: d.taskId,
                    currentOperation: d.currentOperation,
                    timestamp: d.timestamp || Date.now()
                }
            } catch (e) {
                console.error('Failed to parse heartbeat', e)
            }
        }

        // Handle state recovery (reconnection)
        if (evt === 'state_recovery') {
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
                }

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

    const handleStepUpdate = (data) => {
        // data: { status: 'loading'|'done', message: '...' }
        const bubble = currentAssistantBubble.value
        if (!bubble) return

        // Ensure we have an active process to attach this step to
        // If no active process, create a "System Tools" process
        let proc = bubble.processes.find(p => p.id === activeProcessId)
        if (!proc) {
            // Create default process
            const pid = `proc-sys-${Date.now()}`
            proc = {
                id: pid,
                title: 'System Actions',
                isExpanded: true,
                steps: [],
                content: ''
            }
            bubble.processes.push(proc)
            activeProcessId = pid
            // Do NOT set activeTag='process' to avoid interfering with XML parser state if mixed
        }

        // Add or Update Step
        // Strategy: append new step for every update? Or update last step?
        // AgentOrchestrator sends: "Executing tools...", then "Tools executed."
        // We probably want 2 steps or 1 updated step.
        // Simple: Append new step
        proc.steps.push({
            status: data.status === 'done' ? 'done' : 'doing',
            text: data.message
        })

        // If done, maybe collapse? No, keep expanded.
    }

    // --- PARSER HELPERS ---

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
                fileName: evt.name ? evt.name : (evt.type === 'task_list' ? 'Task List' : 'Plan')
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
                    lastItem.code += text
                }
            }
        } else if (activeTag === 'tool_output') {
            const p = bubble.processes.find(x => x.id === activeProcessId)
            if (p) {
                // Attach output to the LAST tool item
                // Use reverse search
                const toolItem = [...p.items].reverse().find(i => i.type === 'tool')
                if (toolItem) {
                    toolItem.output += text
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
            }
        } else if (activeTag === 'walkthrough') {
            bubble.walkthrough += text
        } else if (activeTag === 'final') {
            bubble.content += text
        } else if (activeTag === 'question') {
            bubble.content += text
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

            // [Modified] If we are streaming to the document editor, suppress untagged content from chat bubble
            if (bubble.isEditorStreaming) {
                return
            }

            bubble.content += text
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
                    bubble.thinking.startTime = Date.now()
                    activeTag = 'thinking'
                }
            }
        } else if (tagName === 'title') {
            if (isClose) activeTag = null
            else {
                activeTag = 'title'
                bubble.title = ''
            }
        } else if (tagName === 'process') {
            if (isClose) {
                // NOTE: 不要在这里自动标记工具为成功！
                // 后端在 process 关闭后才发送 <tool_output status="...">
                // tool_output handler 会正确更新状态

                activeTag = null
                activeProcessId = null
            } else {
                const pid = `proc-${Date.now()}`
                // Only collapse others if this is a NEW top-level process?
                // For now keep behavior: collapse others
                bubble.processes.forEach(p => p.isExpanded = false)

                const processTitle = attributes['name'] || 'Processing...'

                bubble.processes.push({
                    id: pid,
                    title: processTitle,
                    isExpanded: true,
                    items: [], // CHANGED: from steps -> items
                    content: ''
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
                activeTag = 'process'
                // Check if tool output contains file creation success JSON (legacy check, keep for now)
                // Search in activeProcessId first, then fallback to all processes
                let toolItem = null
                const p = bubble.processes.find(x => x.id === activeProcessId)
                if (p) {
                    toolItem = [...p.items].reverse().find(i => i.type === 'tool')
                }
                // Fallback: search all processes for the last tool
                if (!toolItem) {
                    for (let i = bubble.processes.length - 1; i >= 0; i--) {
                        const lastTool = [...bubble.processes[i].items].reverse().find(item => item.type === 'tool')
                        if (lastTool) {
                            toolItem = lastTool
                            break
                        }
                    }
                }
                if (toolItem && toolItem.output) {
                    if (toolItem.output.includes('"wps_file_id":') && toolItem.output.includes('"status":"success"')) {
                        console.log('[AgentStream] Detected file creation')
                        if (clientActionHandler.value) clientActionHandler.value({ action: 'refresh_files' })
                    }
                }
            } else {
                // Open Tag: Parse Status Attribute
                const statusAttr = attributes['status'] // Expect "SUCCESS" or "FAILURE"

                // Find the tool item to update - first try activeProcessId, then search all
                let toolItem = null
                const p = bubble.processes.find(x => x.id === activeProcessId)
                if (p) {
                    toolItem = [...p.items].reverse().find(i => i.type === 'tool')
                }
                // Fallback: search all processes for the most recent tool
                // 工具可能还是 'loading' 或已被更新
                if (!toolItem) {
                    for (let i = bubble.processes.length - 1; i >= 0; i--) {
                        const recentTool = [...bubble.processes[i].items].reverse().find(
                            item => item.type === 'tool'
                        )
                        if (recentTool) {
                            toolItem = recentTool
                            activeProcessId = bubble.processes[i].id // Update activeProcessId for subsequent content
                            break
                        }
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
                activeTag = 'tool_output'
            }
        } else if (tagName === 'walkthrough') {
            if (isClose) activeTag = null
            else activeTag = 'walkthrough'
        } else if (tagName === 'final') {
            if (isClose) activeTag = null
            else activeTag = 'final'
        } else if (tagName === 'question') {
            if (isClose) activeTag = null
            else activeTag = 'question'
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

        const tagRegex = /<(\/?)(thinking|title|process|step|tool_code|tool_output|walkthrough|final|question|artifact)(\s+[^>]*)?>/g

        while (true) {
            const match = tagRegex.exec(parserBuffer)
            if (!match) break

            const [fullTag, isSlash, tagName] = match
            const index = match.index

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
