// 官方 wpsjs 模板同款工具：枚举兜底表 + 取当前加载项部署目录。
// 注：官方注释称「后续版本 wps.Enum 会自动支持全部枚举」，现阶段人工定义所需子集。
var WPS_Enum = {
    msoCTPDockPositionLeft: 0,
    msoCTPDockPositionRight: 2
}

function GetUrlPath() {
    let e = document.location.toString()
    return -1 != (e = decodeURI(e)).indexOf("/") && (e = e.substring(0, e.lastIndexOf("/"))), e
}
