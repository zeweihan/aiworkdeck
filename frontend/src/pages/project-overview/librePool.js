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
    onActiveOfficeFileChanged(pane, file) {
        if (file && this.useLibreEditor(file)) this.touchLibreLru(pane, file.id)
        this.syncLibreExecutor()
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
        console.log('[ProjectOverview] LibreOffice keep-alive evicted (LRU):', key)
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
