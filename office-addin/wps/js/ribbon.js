// AI WorkDeck WPS 加载项 ribbon 回调（薄壳）：唯一职责是开/关任务窗格。
// 回调按 ribbon.xml 里写的全局函数名查找（WPS 机制，无注册 API）。
// 业务全部活在任务窗格网页（ui/taskpane-wps.html，Vue 应用）里。

var AWD_PANE_KEY = "awd_taskpane_id"

function OnAddinLoad(ribbonUI) {
    if (typeof (window.Application.ribbonUI) != "object") {
        window.Application.ribbonUI = ribbonUI
    }
    if (typeof (window.Application.Enum) != "object") {
        // 旧版宿主没有内置枚举表时用本地兜底（官方模板同款做法）
        window.Application.Enum = WPS_Enum
    }
    return true
}

function OnAction(control) {
    if (control.Id === "btnAwdPane") {
        ToggleAwdPane()
    }
    return true
}

function ToggleAwdPane() {
    var app = window.Application
    var tsId = app.PluginStorage.getItem(AWD_PANE_KEY)
    if (tsId) {
        try {
            var pane = app.GetTaskPane(tsId)
            if (pane) {
                pane.Visible = !pane.Visible
                return
            }
        } catch (e) {
            // 句柄失效（文档窗口重建等）：走重建
        }
    }
    var created = app.CreateTaskPane(GetUrlPath() + "/ui/taskpane-wps.html")
    app.PluginStorage.setItem(AWD_PANE_KEY, created.ID)
    created.Visible = true
}

function GetImage(control) {
    return "images/icon-32.png"
}
