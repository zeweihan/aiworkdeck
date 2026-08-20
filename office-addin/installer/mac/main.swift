// AI WorkDeck Office 插件安装器（用户态 .app，随 DMG 分发）。
// 把 Contents/Resources/manifest.xml 拷进 Word/Excel/PowerPoint 三容器的 wef sideload 目录。
// 为什么是用户态 app 而不是 pkg（dev-board#68）：pkg 的 root 脚本被 macOS 26+
// 应用容器保护直接 EPERM（无授权弹窗），只有用户会话内的 app 能经
// 「访问其他 App 的数据」授权弹窗拿到写入权。
// 卸载 = 删掉三个 wef 目录里的 aiworkdeck-manifest.xml。
import AppKit

let app = NSApplication.shared
app.setActivationPolicy(.regular)
app.activate(ignoringOtherApps: true)

let fm = FileManager.default
let home = fm.homeDirectoryForCurrentUser
guard let manifest = Bundle.main.url(forResource: "manifest", withExtension: "xml") else {
    let alert = NSAlert()
    alert.messageText = "安装包不完整"
    alert.informativeText = "未找到 manifest.xml 资源，请重新下载安装包。"
    alert.runModal()
    exit(1)
}

let hosts: [(name: String, bundle: String)] = [
    ("Word", "com.microsoft.Word"),
    ("Excel", "com.microsoft.Excel"),
    ("PowerPoint", "com.microsoft.Powerpoint"),
]
var failed: [(name: String, wef: URL)] = []
for host in hosts {
    let wef = home.appendingPathComponent("Library/Containers/\(host.bundle)/Data/Documents/wef")
    do {
        try fm.createDirectory(at: wef, withIntermediateDirectories: true)
        let dst = wef.appendingPathComponent("aiworkdeck-manifest.xml")
        // 用读内容重写而不是 copyItem：避免把 DMG 的 quarantine 扩展属性带进容器
        let data = try Data(contentsOf: manifest)
        try data.write(to: dst, options: .atomic)
    } catch {
        failed.append((host.name, wef))
    }
}

let alert = NSAlert()
alert.alertStyle = .informational
if failed.isEmpty {
    alert.messageText = "安装完成"
    alert.informativeText = "完全退出并重新打开 Word / Excel / PowerPoint 后，功能区会出现 AI WorkDeck 按钮。"
    alert.addButton(withTitle: "完成")
    alert.runModal()
} else {
    let names = failed.map { $0.name }.joined(separator: "、")
    alert.messageText = "还差一步授权"
    alert.informativeText = "系统未允许写入 \(names) 的插件目录（macOS 隐私保护）。\n\n"
        + "推荐做法：打开「系统设置 → 隐私与安全」，找到「安装 AI WorkDeck Office 插件」，"
        + "打开 Word / Excel / PowerPoint 的访问开关，然后重新运行本安装器即可。\n\n"
        + "或手动完成：点击「打开目标文件夹」，把随后高亮显示的 manifest.xml 拷进每个打开的"
        + "文件夹（如果里面没有 wef 文件夹，先新建一个再拷入）。"
    alert.addButton(withTitle: "打开目标文件夹")
    alert.addButton(withTitle: "取消")
    if alert.runModal() == .alertFirstButtonReturn {
        for item in failed {
            // TCC 拒绝时 wef 可能建不出来，退而打开其 Documents 上级（Finder 有自己的访问权）
            let target = fm.fileExists(atPath: item.wef.path)
                ? item.wef : item.wef.deletingLastPathComponent()
            NSWorkspace.shared.open(target)
        }
        // 优先高亮 DMG 根目录那份 manifest（用户拖起来直观）；没有再退回包内资源
        let dmgManifest = Bundle.main.bundleURL.deletingLastPathComponent()
            .appendingPathComponent("manifest.xml")
        let reveal = fm.fileExists(atPath: dmgManifest.path) ? dmgManifest : manifest
        NSWorkspace.shared.activateFileViewerSelecting([reveal])
    }
}
exit(0)
