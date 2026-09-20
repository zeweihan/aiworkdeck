// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// project-overview.vue 的内嵌 LibreOffice 保活池/活跃实例指针/LRU 淘汰与编辑器宿主事件。
// 模式说明见 .claude/agents/sidebar-shell.md 与 PR#151/#159。
// 经展开进组件 methods（纯搬移，Phase 1 外置），`this` 即 project-overview 页面实例。

import { host, isDesktopHost } from '@/services/host.js'

// 内嵌 LibreOffice 保活池按文档体积计权（尽调模块 P3 稳定性余项 #2，
// dev-board#100）：固定 LRU=3 与文档体积无关，三个 150 页/6.6MB 级大文档同时
// 驻留会把页面内存吃到约 2.4GB（实测基线，见
// docs/superpowers/specs/2026-08-21-due-diligence-module-proposal.md §3）。
// 改成"总权重上限固定，大文档占更多权重"：LIBRE_SIZE_UNIT_BYTES 是 1 个权重单位
// 的体积（保守值 2MB），LIBRE_WEIGHT_BUDGET 是总权重上限（保守值 6）。
// 150 页/6.6MB 文档权重 = ceil(6.6MB / 2MB) = 4，两份这样的文档已经超预算，
// 天然把更旧的实例挤出去；而普通几百 KB 的文档权重恒为 1，同时保活的数量
// 不降反升（旧固定 3 → 最多可到 6）。体积信息缺失（未知/新建文件）按最小
// 权重 1 处理，退化成旧的"按数量"语义，不会异常淘汰。
const LIBRE_SIZE_UNIT_BYTES = 2 * 1024 * 1024
const LIBRE_WEIGHT_BUDGET = 6
const LOW_MEMORY_BYTES = 8 * 1024 ** 3
const LOW_MEMORY_INSTANCE_BUDGET = 2

// 引擎固定基座远大于小文档本身；低内存设备不能只按压缩后的文件体积记账。
// 用宿主真实物理内存，旧壳/网页缺失时保守处理，不依赖浏览器截断的 deviceMemory。
function useConservativeLibrePool() {
    const total = Number(host.systemMemory?.totalBytes)
    return !Number.isFinite(total) || total <= LOW_MEMORY_BYTES
}

function libreInstanceWeight(fileSizeBytes) {
    const n = Number(fileSizeBytes)
    if (!(n > 0)) return 1
    return Math.max(1, Math.ceil(n / LIBRE_SIZE_UNIT_BYTES))
}

export const librePoolMethods = {
    // Epic #43: embedded LibreOffice editor lifecycle. While ready, backend AI
    // commands route to it (see handleEditorCommand). Used by the inline
    // keep-alive pool (Track B)；pane/fileId 由保活池模板内联传入。
    onLibreReady(executor, pane, fileId) {
        const key = pane + ':' + fileId
        this.getLibreExecutorMap()[key] = executor
        this.syncLibreExecutor()
        console.log('[ProjectOverview] LibreOffice editor ready (' + key + ') — agent commands routed to LibreOffice')
    },
    // 实例注册表（非响应式）：executor 按 'pane:fileId' 存，供活跃实例指针
    // 同步；组件实例经函数 ref 存 _libreRefs，供 LRU 淘汰前自动保存。
    getLibreExecutorMap() {
        return this._libreExecMap || (this._libreExecMap = {})
    },
    // 反查某个 executor 此刻绑定的 fileId（按对象恒等，同 onLibreClose 的查法）。
    // syncLibreExecutor 会把 libreOfficeExecutor 重指到"当前活动文件"——AI 流式
    // 写入落字前必须核对这份 executor 现在到底服务哪个文件，见 agentClientActions.js
    // 的 flushDocStreamBuffer。找不到（executor 已被换掉/未注册）返回 null。
    resolveLibreExecutorFileId(executor) {
        if (!executor) return null
        const map = this.getLibreExecutorMap()
        for (const k of Object.keys(map)) {
            if (map[k] === executor) return k.slice(k.indexOf(':') + 1)
        }
        return null
    },
    setLibreRef(pane, fileId, el) {
        const refs = this._libreRefs || (this._libreRefs = {})
        const key = pane + ':' + fileId
        if (el) refs[key] = el
        else delete refs[key]
    },
    // 活跃实例指针（同 PR#151 WPS 编辑器模式）：AI 指令路由到焦点 pane 的
    // 活动 Office 编辑器；焦点 pane 不是 Office 文档时回退另一 pane（保持
    // 旧的"唯一打开的文档也能收指令"行为）。
    // 活动编辑器尚未 ready（boot 中）时指针为 null，handleEditorCommand
    // 照旧回"编辑器未就绪"。
    syncLibreExecutor() {
        const map = this.getLibreExecutorMap()
        const pick = (pane) => {
            const f = pane === 'right' ? this.activeFileRight : this.activeFileLeft
            return (f && this.useLibreEditor(f)) ? (map[pane + ':' + f.id] || null) : null
        }
        const exec = this.focusedPane === 'right' ? (pick('right') || pick('left')) : (pick('left') || pick('right'))
        this.libreOfficeExecutor = exec || null
        this.libreOfficeActive = !!exec
    },
    // 激活的标签变化：Office 文档记入保活 LRU（超上限触发淘汰），并同步指针。
    // 池外文档先尝试过继给预热备胎（必须在 touchLibreLru 之前判断——touch 会
    // 把 key 记入 libreLruKeys，adopt 以"不在 lru 记账"识别无实例）。
    onActiveOfficeFileChanged(pane, file) {
        if (file && this.useLibreEditor(file)) {
            if (pane === 'left') this.maybeAdoptLibreSpare(file)
            // 右窗格不能过继左侧备胎；首份文档直接右开时也不能多留一套空白引擎。
            if (useConservativeLibrePool()) this.libreSpares = this.libreSpares.filter(sp => sp.file || sp.hidden)
            this.touchLibreLru(pane, file.id, file.fileSize)
        }
        this.syncLibreExecutor()
    },

    // ---- 预热备胎（spare）：首开文档免整链冷启动 ----
    // 常驻一个后台 boot 到空白就绪的隐藏实例（借鉴 OnlyOffice"服务常驻热着"）。
    // 激活一个池外 Office 文档时把文档过继给它（组件 watch file 触发装载），
    // 省去 webview 创建 + 引擎 WASM 启动整段。过继后转正进常规记账（executor/
    // ref 均按 'left:fileId' 键，LRU 淘汰与 closeFile flush 走既有链路）。
    // 只在左窗格设备胎（webview 不能跨容器移动）；右窗格分屏冷开保持原状。
    // 代价：常驻多一个空白实例的内存（数百 MB），换首开从整链 boot 降为仅
    // load_document。
    scheduleLibreSpare() {
        // 延迟建胎：避开项目打开期（文件树/面板初始化）的资源竞争。
        clearTimeout(this._libreSpareTimer)
        if (useConservativeLibrePool() && this.hasLibreDocumentInstance()) return
        this._libreSpareTimer = setTimeout(() => this.initLibreSpare(), 4000)
    },
    initLibreSpare() {
        // 页面栈多实例守卫：只有活跃 overview 实例建备胎（PR#148/#151 模式）。
        if (typeof this.isActiveOverviewInstance === 'function' && !this.isActiveOverviewInstance()) return
        // 备胎是常驻的空白 LOWA 实例（数百 MB 内存），只在桌面壳里预热：
        // Web 态没有保活语境（页面刷新即丢），不值这个内存。
        if (!isDesktopHost() || (useConservativeLibrePool() && this.hasLibreDocumentInstance())) return
        if (this.libreSpares.some(sp => !sp.file && !sp.hidden)) return // 已有空闲备胎
        this._libreSpareSeq = (this._libreSpareSeq || 0) + 1
        this.libreSpares.push({ key: this._libreSpareSeq, file: null })
        console.log('[ProjectOverview] LibreOffice spare booting (#' + this._libreSpareSeq + ')')
    },
    // 对齐两个窗格模板：当前文档与 LRU 中的文档才会挂引擎，关闭分屏的右栏不算。
    // 不依赖 ref：用户在 4 秒计时期间打开文档，组件尚未 mounted 时也要阻止补胎。
    hasLibreDocumentInstance() {
        if (this.libreSpares.some(sp => sp.file && !sp.hidden)) return true
        const mounted = (pane, files, activeId) => files.some(f => this.useLibreEditor(f) &&
            (f.id === activeId || this.libreLruKeys.includes(pane + ':' + f.id)))
        return mounted('left', this.leftFiles, this.activeFileIdLeft) ||
            (this.splitMode && mounted('right', this.rightFiles, this.activeFileIdRight))
    },
    maybeAdoptLibreSpare(file) {
        if (!file || !this.useLibreEditor(file)) return
        const id = String(file.id)
        if (this.libreSpares.some(sp => sp.file && String(sp.file.id) === id)) return // 已是过继实例
        if (this.libreLruKeys.includes('left:' + file.id)) return // 常规池里已有活实例
        // hidden = 三方合并借走的隐藏实例（acquireLibreHiddenInstance），它正端着
        // 别人的文档字节，绝不能被过继成律师正在打开的那一份。
        const spare = this.libreSpares.find(sp => !sp.file && !sp.hidden)
        if (!spare) return
        spare.file = file
        console.log('[ProjectOverview] LibreOffice spare adopted → left:' + id)
        // 新备胎等这次过继 ready 后再补（onLibreSpareReady），避免两个引擎
        // 同时抢 CPU 拖慢文档装载。
    },
    setLibreSpareRef(sp, el) {
        // 过继后按常规键注册，closeFile/evict 的 flushSave 走同一注册表；
        // 空白备胎无人引用，不注册。
        if (sp.file) this.setLibreRef('left', sp.file.id, el)
    },
    onLibreSpareReady(sp, executor) {
        // 隐藏实例（三方合并借用）不进常规记账，executor 只挂在条目上给借用方取。
        sp.executor = executor
        if (sp.hidden) {
            console.log('[ProjectOverview] LibreOffice hidden instance ready (#' + sp.key + ')')
            return
        }
        if (sp.file) {
            this.onLibreReady(executor, 'left', sp.file.id)
            this.scheduleLibreSpare() // 过继完成、引擎空闲——补一个新备胎
        } else {
            console.log('[ProjectOverview] LibreOffice spare warm (blank ready)')
        }
    },
    // ---- 隐藏实例（三方合并借用，spec §5.2）----
    // 「不绑定标签页的引擎实例」：自动合并要在后台把三份字节装进引擎跑一遍比较 +
    // 重放，跑完就扔。形制照预热备胎（同一个 LibreOfficeEditor 组件、同一段模板、
    // file 恒为 null 所以画布上是空白、standby 类隐藏），只多两条规矩：
    //   1. hidden 标记，maybeAdoptLibreSpare 与 initLibreSpare 都跳过它——它端着
    //      别人的文档，被过继成律师正在打开的那份就是数据事故；
    //   2. 用完必须 release（条目删掉、组件卸载），否则每撞一次车就多一个常驻引擎。
    // 非桌面端没有引擎，直接回 null，调用方据此把那份文件退回整份三选一。
    async acquireLibreHiddenInstance({ timeoutMs = 180000 } = {}) {
        if (!isDesktopHost()) return null
        this._libreSpareSeq = (this._libreSpareSeq || 0) + 1
        const sp = { key: this._libreSpareSeq, file: null, hidden: true, executor: null }
        this.libreSpares.push(sp)
        const deadline = Date.now() + timeoutMs
        // 引擎冷启动实测 90 秒级（WASM 编译 + 排版），180 秒是留了余量的上限；
        // 轮询而不是等事件，是因为 onLibreSpareReady 是模板上的回调，拿不到 Promise。
        while (!sp.executor) {
            if (Date.now() > deadline) {
                this.releaseLibreHiddenInstance(sp)
                console.warn('[ProjectOverview] LibreOffice hidden instance 启动超时')
                return null
            }
            await new Promise((r) => setTimeout(r, 200))
            // 页面切走/组件卸载时条目会被清掉，别在这里空转到超时
            if (!this.libreSpares.includes(sp)) return null
        }
        return {
            run: (action, payload) => sp.executor.executeCommand(action, payload),
            _spare: sp,
        }
    },
    releaseLibreHiddenInstance(handle) {
        const sp = handle && (handle._spare || handle)
        if (!sp) return
        this.libreSpares = this.libreSpares.filter((x) => x !== sp)
    },

    // 过继实例渲染自 libreSpares，出池必须同步删条目组件才会卸载。
    // key 形如 'left:fileId'（右窗格无备胎，非 left 键直接返回）。
    pruneLibreSpare(key) {
        if (!key.startsWith('left:')) return
        const id = key.slice(5)
        this.libreSpares = this.libreSpares.filter(sp => !(sp.file && String(sp.file.id) === id))
    },
    // 关闭 tab（closeFile 已 flush 过）后清掉文件已不在左列表的过继条目。
    pruneClosedLibreSpares() {
        this.libreSpares = this.libreSpares.filter(sp => !sp.file || this.isLibreKeyOpen('left:' + sp.file.id))
        // 全部关掉后恢复首开预热；4 秒期间又打开文档，init 会再次核验。
        if (useConservativeLibrePool()) this.scheduleLibreSpare()
    },
    // fileSize 是"刚激活的这份文档"的体积（调用方 onActiveOfficeFileChanged 手头
    // 就有，直接传入，不必等组件挂载完成才能取到）；池里其它 key 的体积从
    // _libreRefs 已挂载实例的 file.fileSize 反查（libreWeightOf）。
    touchLibreLru(pane, fileId, fileSize) {
        const key = pane + ':' + fileId
        // 触达置顶，顺带清掉已关闭文件的残留记账
        const keys = [key].concat(this.libreLruKeys.filter(k => k !== key && this.isLibreKeyOpen(k)))
        this.libreLruKeys = keys
        // 按体积累计权重：从最近使用往回数，累计权重一旦超预算，从那个 key 起
        // （含它自己）全部是淘汰候选——与旧版"名次超过 LIBRE_KEEPALIVE_MAX 就淘汰"
        // 同一个"从前往后数、超了就砍"的形状，只是计数单位从"个数"换成"权重"。
        let acc = 0
        for (const k of keys) {
            acc += (k === key ? libreInstanceWeight(fileSize) : this.libreWeightOf(k))
            if (acc > LIBRE_WEIGHT_BUDGET || this.exceedsLibreInstanceBudget(k)) this.evictLibreInstance(k)
        }
    },
    // 活动窗格优先占名额，其余名额留给最近使用的后台文档。
    // 分屏时较旧的活动文档也必须先占名额，不能因其 LRU 排名低多留下一个后台实例。
    exceedsLibreInstanceBudget(key) {
        if (!useConservativeLibrePool()) return false
        // 关分屏会卸载右窗格，但标签及 LRU 还在；这些条目此刻没有驻留引擎。
        const residentKeys = this.libreLruKeys.filter(k => this.splitMode || !k.startsWith('right:'))
        const active = (k) => k === 'left:' + this.activeFileIdLeft || (this.splitMode && k === 'right:' + this.activeFileIdRight)
        if (active(key)) return false
        const activeCount = residentKeys.filter(active).length
        const inactive = residentKeys.filter(k => !active(k))
        const index = inactive.indexOf(key)
        return index >= Math.max(0, LOW_MEMORY_INSTANCE_BUDGET - activeCount)
    },
    // key 对应实例的体积权重：从已挂载的 _libreRefs 反查 file.fileSize；拿不到
    // （未挂载/无体积信息）按最小权重 1 处理。
    libreWeightOf(key) {
        const inst = (this._libreRefs || {})[key]
        return libreInstanceWeight(inst && inst.file ? inst.file.fileSize : null)
    },
    isLibreKeyOpen(key) {
        const sep = key.indexOf(':')
        const pane = key.slice(0, sep)
        const fileId = key.slice(sep + 1)
        const list = pane === 'right' ? this.rightFiles : this.leftFiles
        return list.some(f => String(f.id) === fileId && this.useLibreEditor(f))
    },
    // LRU 淘汰：先自动保存再出池（出池即卸载，走组件自身的 dispose 流程）。
    async evictLibreInstance(key) {
        const inst = (this._libreRefs || {})[key]
        // 未就绪/加载失败的实例跳过保存——画布上是空白原型，保存会覆盖真文件。
        // flushSave：等在途自动保存结束，仍有脏改动才再存（没改动就不空传）。
        if (inst && inst.ready && !inst.docLoadFailed && inst.file) {
            try { if ((await inst.flushSave({ timeoutMs: 10000 })) === false) return } catch (e) { console.warn('[ProjectOverview] evict auto-save failed:', e); return }
        }
        // 保存耗时期间可能又被激活/关闭：按此刻的名次重新核验，累计权重仍在预算内
        // 或已是活动文件则不淘汰（与旧版"idx < LIBRE_KEEPALIVE_MAX"同一防线，只是
        // 判据从"名次前 N"换成"从最近使用往回累计权重不超预算"）。
        const idx = this.libreLruKeys.indexOf(key)
        if (idx === -1) return
        if (key === 'left:' + this.activeFileIdLeft || key === 'right:' + this.activeFileIdRight) return
        let acc = 0
        for (let i = 0; i <= idx; i++) acc += this.libreWeightOf(this.libreLruKeys[i])
        if (acc <= LIBRE_WEIGHT_BUDGET && !this.exceedsLibreInstanceBudget(key)) return
        this.libreLruKeys = this.libreLruKeys.filter(k => k !== key)
        this.pruneLibreSpare(key)
        console.log('[ProjectOverview] LibreOffice keep-alive evicted (LRU):', key)
    },

    // 后端就地改了某文件的内容，而该文件**没有**显示在任何窗格里（比如律师此刻正看着
    // 合并比对稿那个标签页）：把它那些实例卸载掉，下次激活时重挂载并拉新字节。
    //
    // 两个注册表都得动，缺一个就是一次静默的"没刷新"：常规池按 libreLruKeys 渲染
    // （leftLibreFiles/rightLibreFiles），而**过继备胎**渲染自 libreSpares，且
    // leftLibreFiles 会把"有备胎顶着"的文件整个排除掉——只摘 LRU 键的话那个实例
    // 压根不会卸载，律师切回去看到的还是改前的内容（真机反馈 A1：采纳一稿裁决完，
    // 文档标签里仍是合并前的正文，关掉标签重开才对）。左窗格首开的那份文档一定是
    // 过继来的备胎（maybeAdoptLibreSpare），也就是最常见的那一种实例。
    // LRU 淘汰那条路早就两边都清了（evictLibreInstance 末尾的 pruneLibreSpare），
    // 这里补齐同一件事。
    //
    // 活动实例逐不掉也不该逐（"活动文件必进池"）——它走 reloadActiveLibreInstances
    // 就地换文档，这里原样跳过。
    unloadInactiveLibreInstances(fileId) {
        const isActiveKey = (key) =>
            key === 'left:' + this.activeFileIdLeft || key === 'right:' + this.activeFileIdRight
        this.libreLruKeys = this.libreLruKeys.filter(
            (k) => !k.endsWith(':' + fileId) || isActiveKey(k))
        if (!isActiveKey('left:' + fileId)) this.pruneLibreSpare('left:' + fileId)
    },

    // 版本记录落了新的一版（结束工作/采纳/退回…）：打开中的编辑器要重新问一次溯源。
    // 内容没变的文件走不到 reload-files 那条链（那条只管被改写的文件），但它们的
    // 段落归属照样变了——律师刚给这段工作起的名字、以及"本机未保存的改动"该消失的
    // 那些段落，都只有重新拉一次 provenance 才会更新（真机反馈 A2/C5）。
    // 逐实例尽力而为：某个实例还在 boot / 已经 dispose 都只是这一份不刷新。
    refreshLibreProvenance() {
        const refs = this._libreRefs || {}
        for (const key of Object.keys(refs)) {
            const inst = refs[key]
            if (!inst || !inst.file || typeof inst.loadProvenance !== 'function') continue
            try { inst.loadProvenance() } catch (e) { console.warn('[ProjectOverview] 溯源刷新失败:', e) }
        }
    },

    // 后端就地改了某文件的内容（版本退回 / 检查点恢复），而该文件正显示在某个
    // 窗格里：这个实例逐不出保活池（活动文件必进池），也不会因文件信息变化重
    // 挂载，必须显式命令它就地重载。非活动实例走上面的 unloadInactiveLibreInstances。
    // 绝不能走 closeFile：它在关闭脏文档前 flushSave，会把编辑器里还端着的
    // 改前字节写回后端，正好冲掉刚做的退回/恢复。
    // 返回 false 表示有活动实例没能换成新内容（画布上还是旧的，该实例已自己
    // 落下保存闸），调用方据此把提示改成告警而不是"已更新"。
    async reloadActiveLibreInstances(fileId) {
        const refs = this._libreRefs || {}
        let allOk = true
        for (const pane of ['left', 'right']) {
            const activeId = pane === 'left' ? this.activeFileIdLeft : this.activeFileIdRight
            if (String(activeId) !== String(fileId)) continue
            const inst = refs[pane + ':' + fileId]
            if (!inst || typeof inst.reloadFromBackend !== 'function') continue
            // 只刷「正在显示且确实绑着这份文件」的实例。预热备胎（PR#220）是同一个
            // LibreOfficeEditor 组件：空白备胎 file=null，setLibreSpareRef 压根不注册，
            // 正常到不了这里；这条是硬判据，防止将来备胎/接力体换了记账方式后
            // 把重载打在没绑文件的实例上（那会"成功"，真文档纹丝不动）。
            if (!inst.file || String(inst.file.id) !== String(fileId)) continue
            try {
                const ok = await inst.reloadFromBackend()
                if (!ok) allOk = false
                console.log('[ProjectOverview] LibreOffice 活动实例就地重载(' + pane + ':' + fileId + '):', ok)
            } catch (e) {
                allOk = false
                console.warn('[ProjectOverview] LibreOffice 活动实例重载失败:', e)
            }
        }
        return allOk
    },

    // 正文 Cmd/Ctrl 点击统一预览；由用户在浮窗中明确选择分屏查看。
    onLibreOpenUrl(payload) {
      this.openDocumentLinkPreview(payload)
    },
    onLibreClose(executor) {
        // An inline pool editor unmount (tab close / LRU evict) emits its
        // executor — drop it from the registry by identity and re-sync the
        // active pointer, so closing a background instance can't clobber the
        // active one.
        const map = this.getLibreExecutorMap()
        if (executor) {
            for (const k of Object.keys(map)) {
                if (map[k] === executor) delete map[k]
            }
        }
        this.syncLibreExecutor()
        if (!this.libreOfficeActive) console.log('[ProjectOverview] LibreOffice editor closed — agent commands unavailable until reopened')
    },
}
