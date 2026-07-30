// project-overview.vue 的内嵌 LibreOffice 保活池/活跃实例指针/LRU 淘汰与编辑器宿主事件。
// 模式说明见 .claude/agents/sidebar-shell.md 与 PR#151/#159。
// 经展开进组件 methods（纯搬移，Phase 1 外置），`this` 即 project-overview 页面实例。

// 内嵌 LibreOffice 实例保活上限（每个 LOWA 实例数百 MB 内存）。超过后按
// LRU 淘汰最久未激活的实例——淘汰前自动保存（见 evictLibreInstance）。
const LIBRE_KEEPALIVE_MAX = 3

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
            this.touchLibreLru(pane, file.id)
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
        this._libreSpareTimer = setTimeout(() => this.initLibreSpare(), 4000)
    },
    initLibreSpare() {
        // 页面栈多实例守卫：只有活跃 overview 实例建备胎（PR#148/#151 模式）。
        if (typeof this.isActiveOverviewInstance === 'function' && !this.isActiveOverviewInstance()) return
        const api = typeof window !== 'undefined' && window.checkbaDesktop && window.checkbaDesktop.zetaoffice
        if (!api) return // 非桌面版没有 LOWA
        if (this.libreSpares.some(sp => !sp.file)) return // 已有空闲备胎
        this._libreSpareSeq = (this._libreSpareSeq || 0) + 1
        this.libreSpares.push({ key: this._libreSpareSeq, file: null })
        console.log('[ProjectOverview] LibreOffice spare booting (#' + this._libreSpareSeq + ')')
    },
    maybeAdoptLibreSpare(file) {
        if (!file || !this.useLibreEditor(file)) return
        const id = String(file.id)
        if (this.libreSpares.some(sp => sp.file && String(sp.file.id) === id)) return // 已是过继实例
        if (this.libreLruKeys.includes('left:' + file.id)) return // 常规池里已有活实例
        const spare = this.libreSpares.find(sp => !sp.file)
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
        if (sp.file) {
            this.onLibreReady(executor, 'left', sp.file.id)
            this.scheduleLibreSpare() // 过继完成、引擎空闲——补一个新备胎
        } else {
            console.log('[ProjectOverview] LibreOffice spare warm (blank ready)')
        }
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
    },
    touchLibreLru(pane, fileId) {
        const key = pane + ':' + fileId
        // 触达置顶，顺带清掉已关闭文件的残留记账
        const keys = [key].concat(this.libreLruKeys.filter(k => k !== key && this.isLibreKeyOpen(k)))
        this.libreLruKeys = keys
        keys.slice(LIBRE_KEEPALIVE_MAX).forEach(k => { this.evictLibreInstance(k) })
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
        if (inst && inst.ready && !inst.isError && inst.file) {
            try { await inst.flushSave() } catch (e) { console.warn('[ProjectOverview] evict auto-save failed:', e) }
        }
        // 保存耗时期间可能又被激活/关闭：仍在上限内或已是活动文件则不淘汰
        const idx = this.libreLruKeys.indexOf(key)
        if (idx === -1 || idx < LIBRE_KEEPALIVE_MAX) return
        if (key === 'left:' + this.activeFileIdLeft || key === 'right:' + this.activeFileIdRight) return
        this.libreLruKeys = this.libreLruKeys.filter(k => k !== key)
        this.pruneLibreSpare(key)
        console.log('[ProjectOverview] LibreOffice keep-alive evicted (LRU):', key)
    },

    // 后端就地改了某文件的内容（版本退回 / 检查点恢复），而该文件正显示在某个
    // 窗格里：这个实例逐不出保活池（活动文件必进池），也不会因文件信息变化重
    // 挂载，必须显式命令它就地重载。非活动实例由调用方摘出 LRU 卸载即可。
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

    // (#79) 文档内超链接点击：编辑器把 LO 的 window.open 经 lo-relay 转发上来。
    // 内部链接（包装 https 或裸 checkba:）走 __checkbaHandleInternalLink（关联
    // 文件/网核定位，含解包），普通网页开工作区浏览器 tab。
    onLibreOpenUrl(url) {
      const u = String(url || '')
      if (!u) return
      const isWrapped = this.WPS_INTERNAL_HTTP_LINK_BASE && u.startsWith(this.WPS_INTERNAL_HTTP_LINK_BASE)
      if (isWrapped || u.startsWith('checkba:')) {
        try {
          if (typeof window !== 'undefined' && window.__checkbaHandleInternalLink) window.__checkbaHandleInternalLink(u)
        } catch (e) {
          console.error('内部链接处理失败:', e)
        }
        return
      }
      if (/^https?:\/\//i.test(u)) this.openBrowserTab(u)
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
