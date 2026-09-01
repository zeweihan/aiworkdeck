// AI WorkDeck WPS 加载项 ribbon 回调（薄壳）：唯一职责是开/关任务窗格。
// 回调按 ribbon.xml 里写的全局函数名查找（WPS 机制，无注册 API）。
// 业务全部活在任务窗格网页（ui/taskpane-wps.html，Vue 应用）里。
//
// 两条纪律（2026-08-29 排查 dev-board#270 期间加固；**都不是 #270 的根因**，
// 那次的根因是本机注册态坏掉、重装安装页才恢复，见 .claude/agents/office-addin.md）：
// 1. **任何一步都不许把异常抛出回调之外**。ribbon 回调是 WPS 侧调进来的，
//    抛出去只会被宿主吞掉，用户看不到任何报错，我们也拿不到线索；这里失败的每一步
//    都只影响该步本身，不该连累同一个回调里的其余动作。
// 2. **任务窗格 id 必须按宿主分键**。三个宿主（文字/表格/演示）共用一个 addin
//    注册与一份 PluginStorage，而窗格 id 是各宿主进程内从 1 开始自增的。用同一个键
//    的话，文字里存下的 id=1 会被表格拿去 GetTaskPane(1)——那是表格自己的 1 号窗格，
//    可能压根是别家加载项的，于是点我们的按钮去开/关了别人的窗格。

var AWD_PANE_KEY_BASE = "awd_taskpane_id"

/**
 * 当前宿主标签：wps（文字）/ et（表格）/ wpp（演示）。
 * 判据是三个宿主各自独有的顶层集合——真机实测（WPS 12.1.0.28043）三者恰好各有
 * 一个非空：文字有 Documents、表格有 Workbooks、演示有 Presentations。
 * 兜底再看 Application.Name（实测分别返回 Microsoft Word / Excel / PowerPoint
 * ——WPS 为兼容 Word VBA 宏刻意这么报，所以它判不了「是不是 WPS」，
 * 但判「是哪个宿主」是准的）。
 */
function AwdHostTag() {
    var app = null
    try { app = window.Application } catch (e) { return "unknown" }
    if (!app) return "unknown"
    try { if (app.Presentations) return "wpp" } catch (e) { /* 非演示宿主 */ }
    try { if (app.Workbooks) return "et" } catch (e) { /* 非表格宿主 */ }
    try { if (app.Documents) return "wps" } catch (e) { /* 非文字宿主 */ }
    try {
        var n = String(app.Name || "")
        if (n.indexOf("PowerPoint") >= 0) return "wpp"
        if (n.indexOf("Excel") >= 0) return "et"
        if (n.indexOf("Word") >= 0) return "wps"
    } catch (e) { /* 连 Name 都取不到就认了 */ }
    return "unknown"
}

/** 任务窗格 id 在 PluginStorage 里的键——按宿主分，任务窗格侧用同一套后缀 */
function AwdPaneKey() {
    return AWD_PANE_KEY_BASE + "_" + AwdHostTag()
}

function OnAddinLoad(ribbonUI) {
    // 往宿主 Application 上挂属性属于官方模板的可选便利。各自 try/catch：
    // 万一某个宿主/版本不让挂，也不该连累同一个回调里的另一步。
    try {
        if (typeof (window.Application.ribbonUI) != "object") {
            window.Application.ribbonUI = ribbonUI
        }
    } catch (e) { /* 该宿主不允许挂属性，不影响回调本身 */ }
    try {
        if (typeof (window.Application.Enum) != "object") {
            // 旧版宿主没有内置枚举表时用本地兜底（官方模板同款做法）
            window.Application.Enum = WPS_Enum
        }
    } catch (e) { /* 同上 */ }
    return true
}

function OnAction(control) {
    var id = ""
    try { id = control.Id } catch (e) { /* 取不到 id 就当没点中 */ }
    try {
        if (id === "btnAwdPane") ToggleAwdPane()
    } catch (e) {
        // 异常冒出回调只会被宿主静默吞掉，用户与我们都拿不到线索——弹一个最简提示，
        // 至少让用户知道点击没生效以及为什么
        try { window.alert("AI 助手窗格打开失败：" + ((e && e.message) || e)) } catch (e2) { /* alert 也不可用就放弃 */ }
    }
    return true
}

function ToggleAwdPane() {
    var app = window.Application
    var key = AwdPaneKey()
    var tsId = null
    try { tsId = app.PluginStorage.getItem(key) } catch (e) { tsId = null }
    if (tsId) {
        try {
            var pane = app.GetTaskPane(tsId)
            if (pane) {
                // 写回读校验：句柄失效时（宿主重启、窗口重建）有的版本不抛异常、
                // 只是写不进去。写完读回来确认生效了才算复用成功，否则走重建。
                var want = !pane.Visible
                pane.Visible = want
                if (pane.Visible === want) return
            }
        } catch (e) { /* 句柄失效：走重建 */ }
    }
    var created = app.CreateTaskPane(GetUrlPath() + "/ui/taskpane-wps.html")
    try { app.PluginStorage.setItem(key, created.ID) } catch (e) { /* 存不下只影响下次复用 */ }
    created.Visible = true
}

function GetImage(control) {
    return "images/icon-32.png"
}
