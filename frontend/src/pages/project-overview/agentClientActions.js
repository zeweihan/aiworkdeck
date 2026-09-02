// project-overview.vue 的 AI 指令路由：SSE client_action 分发（含 doc_*/wps_* 双轨去重）、
// doc 流式写入缓冲、编辑器打开/重载/命令执行与结果回传。
// 经展开进组件 methods（纯搬移，Phase 1 外置），`this` 即 project-overview 页面实例。
import { sendEditorResult, getFileDetail } from '@/services/api.js'
import { createSerialQueue } from '@/utils/asyncSerialize.js'

// CRITICAL（审计 dev-board#74）：流式缓冲与它究竟该写进哪个文档必须绑在一起才能判断
// "现在还能不能落字"。syncLibreExecutor（librePool.js）会把 libreOfficeExecutor 重指到
// "当前活动文件"——生成期间用户切一次 tab，flush 就会把 AI 写的内容悄悄插进一份无关文档。
// targetFileId 是流式会话在 doc_open_file_sync 时绑定的文件；currentFileId 是 executor
// 此刻实际服务的文件（resolveLibreExecutorFileId 反查）。两者对不上就不许写。
// targetFileId 为空（理论上不会发生，流式协议恒先 open_sync 才有 stream_data）时放行，
// 与改动前的行为保持一致，不无谓收紧。
export function shouldFlushDocStream(targetFileId, currentFileId) {
    if (targetFileId == null) return true
    return currentFileId != null && String(currentFileId) === String(targetFileId)
}

export const agentClientActionMethods = {
    handleClientAction(action) {
        console.log('[ProjectOverview] Client Action:', action)

        // 双轨迁移（docs/AI_ARCHITECTURE.md Phase 3）：新后端对每条指令按"新名在前、旧名在后"
        // 各发一份。一旦见到任一新名即判定为新后端（SSE 单连接有序），此后丢弃所有旧名事件，
        // 保证新旧后端搭配下每条指令都恰好执行一次。
        const isNewName = action.tool === 'editor_command' ||
            ['doc_open_file', 'doc_reload_file', 'doc_stream_data'].includes(action.action)
        const isLegacyName = action.tool === 'wps_command' ||
            ['wps_open_file', 'wps_reload_file', 'wps_stream_data'].includes(action.action)
        if (isNewName) this._editorContractV2 = true
        if (isLegacyName && this._editorContractV2) return

        // 插件后台任务进度（规范 v2.4 §11 Jobs）：状态已在 useAgentStream 的 client_action 入口
        // 写进 backgroundTasks（ChatInterface 的 BackgroundTaskIndicator 消费），页面这层无事可做。
        // 显式占一个分支是为了让读者在这张路由表上能找到它，而不是靠"什么都没匹配上"兜底。
        if (action.action === 'plugin_job_progress') {
            return
        }

        // 插件宿主 Docs.openFile(fileId, locator)（规范 v2.4 §11）：locator 非空时后端在
        // doc_open_file 之后追发这一条。载荷 {fileId, locator} 与 TargetView 形状兼容，
        // 直接交给 evidenceLinkActions.js 的 openFileLinkTarget(target) 打开并定位——
        // 与审阅面板「查看底稿」同一条路。
        if (action.action === 'plugin_open_locator') {
            if (!action.fileId) {
                console.warn('[ProjectOverview] No fileId in plugin_open_locator action')
                return
            }
            this.openFileLinkTarget({ fileId: action.fileId, locator: action.locator || null }, this.focusedPane || 'left')
            return
        }

        if (action.action === 'refresh_files') {
            if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
                console.log('[ProjectOverview] Refreshing File Tree...')
                this.$refs.fileTree.loadFiles()
                uni.showToast({ title: this.$t('workbenchOps.fileUpdated'), icon: 'none' })
            }
        }
        // AI Agent 请求打开文件
        else if (action.action === 'doc_open_file' || action.action === 'wps_open_file') {
            this.handleEditorOpenFile(action)
        }
        // AI Agent 请求重新加载文件（用于后端修改文件后刷新编辑器）
        else if (action.action === 'doc_reload_file' || action.action === 'wps_reload_file') {
            this.handleEditorReloadFile(action)
        }
        // AI 后端直改了纯文本文件（text_write_file / text_find_replace，dev-board#37）：
        // 刷新打开中的文本标签。单名新契约，无 wps_* 旧名双轨。
        else if (action.action === 'text_reload_file') {
            this.handleTextReloadFile(action)
        }
        // AI Agent 请求执行编辑器命令
        else if (action.tool === 'editor_command' || action.tool === 'wps_command') {
            // 特殊处理同步打开命令（新建文件流式写入）
            if (action.action === 'doc_open_file_sync' || action.action === 'wps_open_file_sync') {
                this.handleEditorOpenFileSync(action)
            } else {
                this.handleEditorCommand(action)
            }
        }
        // 后端流式写入数据（doc_start_stream 工具）：缓冲后经 LibreOffice 执行器落字
        else if (action.action === 'doc_stream_data' || action.action === 'wps_stream_data') {
            this.handleDocStreamData(action.content || '')
        }
        // 后端流式写入结束：冲掉本地缓冲后让 worker 收尾（写掉尾行/尾表并复位状态机）
        else if (action.action === 'doc_stream_end') {
            this.handleDocStreamEnd()
        }
    },

    // --- 流式写入（#79：LibreOffice 消费端，替代原 useWpsBridge.handleWpsStreamData）---
    // 与原 WPS 实现同构：本地缓冲 + 定时批量 flush，减少 worker 往返。
    handleDocStreamData(content) {
        if (!content) return
        this._docStreamBuffer = (this._docStreamBuffer || '') + content
        if (!this._docStreamTimer) {
            this._docStreamTimer = setTimeout(() => {
                this._docStreamTimer = null
                this.flushDocStreamBuffer()
            }, 150)
        }
    },
    async flushDocStreamBuffer() {
        if (!this._docStreamBuffer || this._docStreamBusy) return
        if (!this.libreOfficeActive || !this.libreOfficeExecutor) return
        // CRITICAL：落字前核对这份 executor 现在服务的是不是流式会话自己打开的那个
        // 文件——不一致说明用户切走了/换文档了，缓冲原样保留（不丢数据，等切回来
        // 或被下一次 open_sync 的 reset 清掉），如实报告，绝不写进错的文档。
        const currentFileId = this.resolveLibreExecutorFileId(this.libreOfficeExecutor)
        if (!shouldFlushDocStream(this._docStreamTargetFileId, currentFileId)) {
            console.error('[ProjectOverview] doc stream target mismatch, refusing to write into unrelated document:',
                { targetFileId: this._docStreamTargetFileId, currentFileId })
            return
        }
        this._docStreamBusy = true
        const text = this._docStreamBuffer
        this._docStreamBuffer = ''
        try {
            // stream_insert：worker 端按行剥离 markdown 标记并按标准格式落字
            //（楷体_GB2312/Arial、段后 18 磅、首行缩进 2 字符、表格 Grid 1.5 磅等）
            // __agent：流式落字是 AI 写的，修订要署名 AI WorkDeck——与 handleEditorCommand
            // 同一标记；漏了它，这一路的修订全记在用户名下（dev-board#367）。
            await this.libreOfficeExecutor.executeCommand('stream_insert', { text, __agent: true })
        } catch (e) {
            console.error('[ProjectOverview] doc stream insert error:', e)
        } finally {
            this._docStreamBusy = false
            if (this._docStreamBuffer && !this._docStreamTimer) {
                this._docStreamTimer = setTimeout(() => {
                    this._docStreamTimer = null
                    this.flushDocStreamBuffer()
                }, 150)
            }
        }
    },

    // 流式结束：等在飞的 flush 落地、补冲残余缓冲，再让 worker stream_flush 收尾
    //（写掉未换行的尾行、未闭合的尾表，并复位 markdown 状态机）。
    async handleDocStreamEnd() {
        if (this._docStreamTimer) { clearTimeout(this._docStreamTimer); this._docStreamTimer = null }
        for (let i = 0; i < 100 && this._docStreamBusy; i++) {
            await new Promise(resolve => setTimeout(resolve, 50))
        }
        await this.flushDocStreamBuffer()
        for (let i = 0; i < 100 && this._docStreamBusy; i++) {
            await new Promise(resolve => setTimeout(resolve, 50))
        }
        if (this.libreOfficeActive && this.libreOfficeExecutor) {
            try {
                // 收尾会把未换行的尾行/尾表真正写进文档，同样是 AI 的笔迹
                await this.libreOfficeExecutor.executeCommand('stream_flush', { __agent: true })
            } catch (e) {
                console.error('[ProjectOverview] doc stream flush error:', e)
            }
        }
    },

    /**
     * 处理 AI Agent 的同步打开文件请求 (用于流式写入)
     * 打开文件，等待内置 LibreOffice 编辑器就绪后返回结果给后端（#79）
     *
     * 只是一层重入闸：真正的活儿在 _handleEditorOpenFileSyncImpl 里，这里把每次调用
     * 接进 _docOpenSyncQueue 串行化（同款做法见 DrawioEditor.persist 的 _persistQueue）。
     * handleClientAction 是同步分发、不认在飞标记——后端重试丢失 ack 的请求，或新一轮
     * 生成在上一轮最长 90s 的 editor-ready 等待还没完时到达，都会让两次调用并发执行；
     * 不串行的话，后到的那次第 5 步"重置流式缓冲"会在先到的那次仍在写的时候把它的
     * 缓冲区冲掉/丢弃，静默丢字且后端毫无感知。串行化后两次调用绝不交叉，各自完整
     * 跑完（含各自的 sendEditorResult ack）才轮到下一个。
     */
    async handleEditorOpenFileSync(action) {
        if (!this._docOpenSyncQueue) this._docOpenSyncQueue = createSerialQueue()
        return this._docOpenSyncQueue(() => this._handleEditorOpenFileSyncImpl(action))
    },
    async _handleEditorOpenFileSyncImpl(action) {
        console.log('[ProjectOverview] Open File Sync:', action)
        const { params, requestId, conversationId } = action

        try {
            if (!params || !params.fileId) {
                console.error('[ProjectOverview] No fileId in doc_open_file_sync')
                await sendEditorResult(conversationId, requestId, false, null, '缺少文件ID')
                return
            }

            // 1. 刷新文件列表以获取最新文件
            if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
                await this.$refs.fileTree.loadFiles()
            }

            // 2. 获取文件详情
            const file = await getFileDetail(this.projectId, params.fileId)
            if (!file) {
                console.error('[ProjectOverview] File not found:', params.fileId)
                await sendEditorResult(conversationId, requestId, false, null, '文件不存在')
                return
            }

            console.log('[ProjectOverview] Opening file for streaming:', file.name)

            // 3. 打开文件（挂载/激活内置 LibreOffice 编辑器）
            await this.openFile(file)

            // 4. 等待编辑器就绪（onLibreReady 置位，最多等待 90 秒——LOWA 首次 boot 较慢）
            let editorReady = false
            for (let i = 0; i < 180; i++) {
                await new Promise(resolve => setTimeout(resolve, 500))
                if (this.libreOfficeActive && this.libreOfficeExecutor) {
                    editorReady = true
                    console.log('[ProjectOverview] LibreOffice editor ready after', (i + 1) * 500, 'ms')
                    break
                }
            }

            if (!editorReady) {
                console.error('[ProjectOverview] LibreOffice editor not ready after timeout')
                await sendEditorResult(conversationId, requestId, false, null, '编辑器未就绪')
                return
            }

            // 5. 重置流式缓冲，准备接收新的流式数据
            this._docStreamBuffer = ''
            if (this._docStreamTimer) { clearTimeout(this._docStreamTimer); this._docStreamTimer = null }
            this._docStreamBusy = false
            // 这条流式会话正式绑定到这份文件——flushDocStreamBuffer 落字前必须核对
            // executor 此刻服务的还是不是它，见文件头 shouldFlushDocStream。
            this._docStreamTargetFileId = file.id
            // worker 端 markdown 状态机也要硬清（上一条流若异常中断会留下半张表/半行）
            try { await this.libreOfficeExecutor.executeCommand('stream_flush', { discard: true }) } catch (e) {}
            // 项目模板画像（后端只在非 house-default 时附带）：流式落字前先换画像，stream_insert
            // 才按项目模板排版；失败只记日志（退回 house-default 落字，不让整条流断掉）
            if (params.styleProfile) {
                try { await this.libreOfficeExecutor.executeCommand('set_style_profile', { profile: params.styleProfile }) }
                catch (e) { console.error('[ProjectOverview] set_style_profile before streaming failed:', e) }
            }
            console.log('[ProjectOverview] Stream state reset, ready for streaming')

            // 6. 返回成功给后端
            console.log('[ProjectOverview] Open File Sync success')
            await sendEditorResult(conversationId, requestId, true, {
                fileId: file.id,
                fileName: file.name,
                status: 'ready'
            }, null)

            uni.showToast({ title: this.$t('workbenchOps.openedNamed', { name: file.name }), icon: 'none' })

        } catch (e) {
            console.error('[ProjectOverview] handleEditorOpenFileSync error:', e)
            await sendEditorResult(conversationId, requestId, false, null, e.message)
        }
    },

    /**
     * doc_open_file 附带的项目画像：等该 fileId 的编辑器 ready（最多 90s，口径同 open_sync）
     * 再发 set_style_profile。按 fileId 反查 executor，不信 libreOfficeExecutor 指针——
     * 用户这期间切走别的标签，指针就指向别的文档了。
     */
    async applyStyleProfileWhenReady(fileId, profile) {
        for (let i = 0; i < 180; i++) {
            const map = this.getLibreExecutorMap()
            const exec = map['left:' + fileId] || map['right:' + fileId] || null
            if (exec) {
                try {
                    await exec.executeCommand('set_style_profile', { profile })
                    console.log('[ProjectOverview] style profile applied to opened file', fileId)
                } catch (e) {
                    console.error('[ProjectOverview] set_style_profile after open failed:', e)
                }
                return
            }
            await new Promise(resolve => setTimeout(resolve, 500))
        }
        console.warn('[ProjectOverview] editor for file', fileId, 'not ready within 90s; style profile not applied')
    },
    /**
     * 处理 AI Agent 的打开文件请求
     */
    async handleEditorOpenFile(action) {
        console.log('[ProjectOverview] WPS Open File:', action)
        try {
            const fileId = action.fileId
            if (!fileId) {
                console.warn('[ProjectOverview] No fileId in doc_open_file action')
                return
            }

            // 获取文件详情
            const file = await getFileDetail(this.projectId, fileId)
            if (!file) {
                console.error('[ProjectOverview] File not found:', fileId)
                uni.showToast({ title: this.$t('workbenchOps.fileNotFound'), icon: 'none' })
                return
            }

            // 打开文件（action.locator：EvidenceLink 定位符，后端 sendOpenFileAction 已允许该字段）
            this.openFile(file, { locator: action.locator || null })

            // 提示用户
            uni.showToast({ title: this.$t('workbenchOps.openedNamed', { name: file.name }), icon: 'none' })

            // 项目模板画像（后端只在非 house-default 时附带）：等这份文件的编辑器就绪后追发
            // set_style_profile——worker 按文档实例起，画像必须打在它自己的 worker 上。
            if (action.styleProfile) this.applyStyleProfileWhenReady(file.id, action.styleProfile)

        } catch (e) {
            console.error('[ProjectOverview] handleEditorOpenFile error:', e)
            uni.showToast({ title: this.$t('workbenchOps.openFileFailed'), icon: 'none' })
        }
    },

    /**
     * 处理 AI Agent 的重新加载文件请求
     * 当后端修改了文件后，需要通知前端刷新编辑器以显示最新内容
     *
     * 工作原理：
     * 1. 后端修改文件后会更新 wpsFileId（通用文件 ID，添加版本时间戳）
     * 2. 前端获取最新文件信息，更新 leftFiles/rightFiles 中的 wpsFileId
     * 3. LibreOfficeEditor 以文件为 key/prop，检测到变化后重新加载
     *
     * opts.forceActive —— 两条调用路径语义不同，不能一视同仁：
     * - **版本退回**（true）：律师刚亲手点了「退回到这一版」，他要的就是回到过去。
     *   正在显示的那个实例必须就地换文档，在途的未保存输入被丢弃是语义本身；
     *   不换的话下一次 autosave 会把「旧内容 + 新编辑」写回，把退回冲掉（真机复现过）。
     * - **AI 改文件 / 检查点恢复**（默认 false）：律师此刻可能正在这份文档里打字，
     *   静默强刷等于替他丢弃未保存内容还弹一句「文件已更新」。只逐非活动的保活
     *   实例（下次激活自然重挂载拉新字节），当前画面不动。
     *
     * opts.silentSuccessToast —— 只吞「文件已更新」这一句成功提示，由调用方聚合成
     * 一句（见 fileOpenTabs.js 的 onVersionReloadFiles，一次版本操作可能改写好几份
     * 打开中的文件）。失败提示照旧逐份弹出，绝不静音。返回值 = 这一份是否重载成功，
     * 供聚合层统计；早先的调用方不看返回值，语义不变。
     */
    async handleEditorReloadFile(action, opts = {}) {
        const forceActive = !!opts.forceActive
        const silentSuccessToast = !!opts.silentSuccessToast
        console.log('[ProjectOverview] WPS Reload File:', action)
        try {
            const fileId = action.fileId
            if (!fileId) {
                console.warn('[ProjectOverview] No fileId in doc_reload_file action')
                return false
            }

            // 获取文件详情（确保获取最新信息，包括新的 wpsFileId）
            const file = await getFileDetail(this.projectId, fileId)
            if (!file) {
                console.error('[ProjectOverview] File not found:', fileId)
                uni.showToast({ title: this.$t('workbenchOps.fileNotFound'), icon: 'none' })
                return false
            }

            console.log('[ProjectOverview] Got updated file info:', {
                id: file.id,
                name: file.name,
                wpsFileId: file.wpsFileId
            })

            // 同时更新 leftFiles 和 rightFiles 中的文件信息
            // 这样可以确保所有打开的相同文件都能获得新的 wpsFileId
            let updated = false

            // 更新左侧窗格
            const existingLeft = this.leftFiles.find(f => f.id === file.id)
            if (existingLeft) {
                const oldWpsFileId = existingLeft.wpsFileId
                Object.assign(existingLeft, file)
                console.log('[ProjectOverview] Updated leftFiles:', {
                    oldWpsFileId,
                    newWpsFileId: file.wpsFileId
                })
                updated = true
            }

            // 更新右侧窗格
            const existingRight = this.rightFiles.find(f => f.id === file.id)
            if (existingRight) {
                const oldWpsFileId = existingRight.wpsFileId
                Object.assign(existingRight, file)
                console.log('[ProjectOverview] Updated rightFiles:', {
                    oldWpsFileId,
                    newWpsFileId: file.wpsFileId
                })
                updated = true
            }

            // 保活池实例不再"切回即重挂载"：后台常驻实例里还是旧内容。把该
            // 文件的非活动保活实例逐出 LRU（卸载），下次激活时重挂载并拉取
            // 新字节。
            let reloadOk = true
            if (updated) {
                this.libreLruKeys = this.libreLruKeys.filter(k => {
                    if (!k.endsWith(':' + file.id)) return true
                    return k === 'left:' + this.activeFileIdLeft || k === 'right:' + this.activeFileIdRight
                })
                // 当前正显示的实例逐不掉（保活池"活动文件必进池"），而它既不
                // watch file 也不以 wpsFileId 为模板 key——上面 Object.assign 进
                // pane 列表对它毫无作用，画布上还是改前的内容。律师接着编辑，
                // autosave 就会把「旧内容 + 新编辑」写回去，把版本退回冲掉。所以
                // 退回路径显式命令活动实例就地重载（换文档前它自己会取消在飞的
                // 自动保存并清脏，见 reloadFromBackend）。AI/检查点路径不做这件事，
                // 理由见方法头 opts.forceActive 的注释。
                if (forceActive) {
                    reloadOk = await this.reloadActiveLibreInstances(file.id)
                }
            }

            // 如果文件不在任何窗格中打开，则打开它
            if (!updated) {
                console.log('[ProjectOverview] File not open, opening:', file.name)
                this.openFile(file)
            }

            if (reloadOk) {
                if (!silentSuccessToast) {
                    uni.showToast({ title: this.$t('workbenchOps.fileUpdatedNamed', { name: file.name }), icon: 'success' })
                }
            } else {
                // 画布上仍是旧内容（该实例已自己拦下保存），不能报"已更新"。
                uni.showToast({ title: this.$t('workbenchOps.reloadFailedNamed', { name: file.name }), icon: 'none', duration: 4000 })
            }

            // 刷新文件树以更新文件信息
            if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
                this.$refs.fileTree.loadFiles()
            }

            return reloadOk
        } catch (e) {
            console.error('[ProjectOverview] handleEditorReloadFile error:', e)
            uni.showToast({ title: this.$t('workbenchOps.refreshFileFailed'), icon: 'none' })
            return false
        }
    },

    /**
     * AI text_* 工具后端直改纯文本文件后的前端刷新（text_reload_file）。
     * 与 handleEditorReloadFile 的分工：那条链服务 LOWA 保活池（逐 LRU、强刷活动
     * 实例），文本编辑器是 v-if 单实例——只有"正激活显示"的标签有组件，就地重载
     * 它（丢弃本地未保存态，AI 刚写进后端的才是权威版本）；未打开/未激活的什么都
     * 不做（下次挂载自然拉新内容），也不把文件硬拉出来打开。
     */
    async handleTextReloadFile(action) {
        try {
            const fileId = action.fileId
            if (!fileId) return
            await this.reloadPlainTextInstances(fileId)
            // 文件大小/修改时间变了，树上的元数据跟着刷
            if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
                this.$refs.fileTree.loadFiles()
            }
        } catch (e) {
            console.warn('[ProjectOverview] handleTextReloadFile error:', e)
        }
    },

    /**
     * 处理 AI Agent 的编辑器命令请求（#79：LibreOffice 是唯一执行器；
     * 结果经 sendEditorResult 回传后端，路由 /editor-result，双轨迁移见 Phase 3）
     */
    async handleEditorCommand(action) {
        console.log('[ProjectOverview] ========== Editor Command Start ==========')
        console.log('[ProjectOverview] Editor Command:', JSON.stringify(action))

        const { action: commandAction, params, requestId, conversationId } = action
        console.log('[ProjectOverview] commandAction:', commandAction, 'requestId:', requestId)

        if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
            console.error('[ProjectOverview] No embedded editor available')
            await sendEditorResult(conversationId, requestId, false, null, '编辑器未就绪，请先打开一个文档')
            return
        }

        try {
            // __agent 标记：worker 据此把这条命令产生的修订署名为 AI WorkDeck
            //（用户本人的 IME 输入等不带标记，署用户名），修订面板里可区分来源。
            const result = await this.libreOfficeExecutor.executeCommand(
                commandAction, Object.assign({}, params, { __agent: true }))
            const successFlag = result && result.success !== false
            // 失败原因优先取 error，没有就退到 message：worker 里大量失败分支只填 message
            //（如 delete_match 的「match index out of range」），只取 error 的话模型收到的是
            // {"error": "null"}，等于没告诉它哪里错了，它只能瞎猜着重试。
            const failReason = result ? (result.error || result.message || null) : null
            await sendEditorResult(conversationId, requestId, successFlag, result, successFlag ? (result && result.error) || null : failReason)
        } catch (e) {
            console.error('[ProjectOverview] LibreOffice command error:', e)
            await sendEditorResult(conversationId, requestId, false, null, e.message)
        }
        console.log('[ProjectOverview] ========== Editor Command End ==========')
    },
}
