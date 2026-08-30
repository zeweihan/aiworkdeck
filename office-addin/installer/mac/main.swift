// AI WorkDeck Office 插件安装器（用户态 .app，随 DMG 分发）。
// 把 Contents/Resources/manifest.xml 拷进 Word/Excel/PowerPoint 三容器的 wef sideload 目录。
// 为什么是用户态 app 而不是 pkg（dev-board#68）：pkg 的 root 脚本被 macOS 26+
// 应用容器保护直接 EPERM（无授权弹窗），只有用户会话内的 app 能经
// 「访问其他 App 的数据」授权弹窗拿到写入权。
// 卸载 = 删掉三个 wef 目录里的 aiworkdeck-manifest.xml。
//
// UI：单窗口 AppKit（不用 SwiftUI，目标 macOS 11）。安装由按钮触发而非启动即写——
// 用户先看到要发生什么，TCC 授权弹窗也有了上下文；失败态在窗口内给指引而不是弹一串 Alert。
// 外观锁 light（与桌面端 native-appearance 红线一致），配色对齐产品令牌（森林绿/薄荷/纸面）。
import AppKit

// MARK: - 品牌色（对齐 frontend/src/uni.scss 的 awd 令牌）
func rgb(_ hex: UInt32, _ alpha: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}
let colForest = rgb(0x1A5336)
let colForestDark = rgb(0x123A26)
let colMintDeep = rgb(0x2D7A52)
let colInk = rgb(0x212529)
let colGray = rgb(0x868E96)
let colCard = rgb(0xF8F9FA)
let colLine = rgb(0xE9ECEF)
let colIdleDot = rgb(0xCED4DA)
let colAmber = rgb(0xB7791F)
let colError = rgb(0xC0392B)

final class FlippedView: NSView { override var isFlipped: Bool { true } }

let hosts: [(name: String, bundle: String)] = [
    ("Word", "com.microsoft.Word"),
    ("Excel", "com.microsoft.Excel"),
    ("PowerPoint", "com.microsoft.Powerpoint"),
]

final class Installer: NSObject, NSApplicationDelegate {
    enum State { case idle, done, failed, broken }
    var state: State = .idle
    var failed: [(name: String, wef: URL)] = []

    let window = NSWindow(
        contentRect: NSRect(x: 0, y: 0, width: 440, height: 580),
        styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
        backing: .buffered, defer: false)
    var dots: [NSView] = []
    var statusLabels: [NSTextField] = []
    var infoLabel: NSTextField!
    var primaryButton: NSButton!
    var secondaryButton: NSButton!

    let fm = FileManager.default
    var manifest: URL? { Bundle.main.url(forResource: "manifest", withExtension: "xml") }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func buildUI() {
        window.title = "安装 AI WorkDeck Office 插件"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isMovableByWindowBackground = true
        window.backgroundColor = .white
        window.center()

        let root = FlippedView(frame: NSRect(x: 0, y: 0, width: 440, height: 580))
        window.contentView = root

        let icon = NSImageView(frame: NSRect(x: (440 - 84) / 2, y: 48, width: 84, height: 84))
        icon.image = NSApp.applicationIconImage
        icon.imageScaling = .scaleProportionallyUpOrDown
        root.addSubview(icon)

        let title = label("AI WorkDeck Office 插件", size: 21, weight: .semibold, color: colInk)
        title.alignment = .center
        title.frame = NSRect(x: 0, y: 148, width: 440, height: 28)
        root.addSubview(title)

        let subtitle = label("将 AI WorkDeck 任务窗格安装到 Microsoft Office", size: 13, weight: .regular, color: colGray)
        subtitle.alignment = .center
        subtitle.frame = NSRect(x: 0, y: 182, width: 440, height: 18)
        root.addSubview(subtitle)

        // 宿主清单卡片
        let card = FlippedView(frame: NSRect(x: 40, y: 218, width: 360, height: 138))
        card.wantsLayer = true
        card.layer?.backgroundColor = colCard.cgColor
        card.layer?.cornerRadius = 10
        card.layer?.borderWidth = 1
        card.layer?.borderColor = colLine.cgColor
        root.addSubview(card)
        for (i, host) in hosts.enumerated() {
            let y = CGFloat(i) * 46
            let dot = NSView(frame: NSRect(x: 20, y: y + 18, width: 10, height: 10))
            dot.wantsLayer = true
            dot.layer?.backgroundColor = colIdleDot.cgColor
            dot.layer?.cornerRadius = 5
            card.addSubview(dot)
            dots.append(dot)

            let name = label(host.name, size: 14, weight: .medium, color: colInk)
            name.frame = NSRect(x: 44, y: y + 13, width: 180, height: 20)
            card.addSubview(name)

            let status = label("待安装", size: 12, weight: .regular, color: colGray)
            status.alignment = .right
            status.frame = NSRect(x: 200, y: y + 15, width: 140, height: 17)
            card.addSubview(status)
            statusLabels.append(status)

            if i < hosts.count - 1 {
                let sep = NSView(frame: NSRect(x: 20, y: y + 46, width: 320, height: 1))
                sep.wantsLayer = true
                sep.layer?.backgroundColor = colLine.cgColor
                card.addSubview(sep)
            }
        }

        infoLabel = label("安装仅写入一份插件清单文件，不会修改 Office 本身；卸载时删除该文件即可。",
                          size: 12, weight: .regular, color: colGray)
        infoLabel.alignment = .center
        infoLabel.frame = NSRect(x: 40, y: 372, width: 360, height: 104)
        infoLabel.cell?.wraps = true
        root.addSubview(infoLabel)

        primaryButton = filledButton("开始安装", action: #selector(primaryAction))
        primaryButton.frame = NSRect(x: 40, y: 492, width: 360, height: 44)
        root.addSubview(primaryButton)

        secondaryButton = NSButton(title: "打开目标文件夹", target: self, action: #selector(openFolders))
        secondaryButton.bezelStyle = .rounded
        secondaryButton.controlSize = .large
        secondaryButton.frame = NSRect(x: 228, y: 492, width: 172, height: 44)
        secondaryButton.isHidden = true
        root.addSubview(secondaryButton)

        if manifest == nil {
            state = .broken
            infoLabel.stringValue = "安装包不完整：未找到 manifest.xml，请重新下载安装包。"
            infoLabel.textColor = colError
            setPrimary(title: "无法安装", enabled: false)
        }
        window.makeKeyAndOrderFront(nil)
    }

    func label(_ text: String, size: CGFloat, weight: NSFont.Weight, color: NSColor) -> NSTextField {
        let l = NSTextField(labelWithString: text)
        l.font = .systemFont(ofSize: size, weight: weight)
        l.textColor = color
        l.lineBreakMode = .byWordWrapping
        l.maximumNumberOfLines = 0
        return l
    }

    func filledButton(_ title: String, action: Selector) -> NSButton {
        let b = NSButton(title: title, target: self, action: action)
        b.isBordered = false
        b.wantsLayer = true
        b.layer?.backgroundColor = colForest.cgColor
        b.layer?.cornerRadius = 8
        b.attributedTitle = NSAttributedString(string: title, attributes: [
            .font: NSFont.systemFont(ofSize: 15, weight: .semibold),
            .foregroundColor: NSColor.white,
        ])
        return b
    }

    func setPrimary(title: String, enabled: Bool = true) {
        primaryButton.attributedTitle = NSAttributedString(string: title, attributes: [
            .font: NSFont.systemFont(ofSize: 15, weight: .semibold),
            .foregroundColor: NSColor.white,
        ])
        primaryButton.isEnabled = enabled
        primaryButton.layer?.backgroundColor = (enabled ? colForest : colIdleDot).cgColor
    }

    @objc func primaryAction() {
        switch state {
        case .idle, .failed: runInstall()
        case .done: NSApp.terminate(nil)
        case .broken: break
        }
    }

    func runInstall() {
        guard let manifest else { return }
        failed = []
        let home = fm.homeDirectoryForCurrentUser
        for (i, host) in hosts.enumerated() {
            let wef = home.appendingPathComponent("Library/Containers/\(host.bundle)/Data/Documents/wef")
            do {
                try fm.createDirectory(at: wef, withIntermediateDirectories: true)
                let dst = wef.appendingPathComponent("aiworkdeck-manifest.xml")
                // 用读内容重写而不是 copyItem：避免把 DMG 的 quarantine 扩展属性带进容器
                let data = try Data(contentsOf: manifest)
                try data.write(to: dst, options: .atomic)
                dots[i].layer?.backgroundColor = colMintDeep.cgColor
                statusLabels[i].stringValue = "已安装"
                statusLabels[i].textColor = colMintDeep
            } catch {
                failed.append((host.name, wef))
                dots[i].layer?.backgroundColor = colAmber.cgColor
                statusLabels[i].stringValue = "需要授权"
                statusLabels[i].textColor = colAmber
            }
        }
        if failed.isEmpty {
            state = .done
            infoLabel.stringValue = "安装完成。完全退出并重新打开 Word / Excel / PowerPoint 后，"
                + "功能区会出现「AI WorkDeck」按钮。"
            infoLabel.textColor = colForest
            infoLabel.alignment = .center
            secondaryButton.isHidden = true
            primaryButton.frame.size.width = 360
            primaryButton.frame.origin.x = 40
            setPrimary(title: "完成")
        } else {
            state = .failed
            let names = failed.map { $0.name }.joined(separator: "、")
            infoLabel.stringValue = "系统未允许写入 \(names) 的插件目录（macOS 隐私保护）。\n"
                + "推荐：打开「系统设置 → 隐私与安全」，允许本安装器访问对应 Office 应用的数据，"
                + "然后点「重试」。\n或点「打开目标文件夹」，把随后高亮的 manifest.xml 拷进每个"
                + "打开的文件夹（没有 wef 文件夹就先新建一个）。"
            infoLabel.textColor = colInk
            infoLabel.alignment = .left
            primaryButton.frame = NSRect(x: 40, y: 492, width: 172, height: 44)
            setPrimary(title: "重试")
            secondaryButton.isHidden = false
        }
    }

    @objc func openFolders() {
        guard let manifest else { return }
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

let app = NSApplication.shared
app.setActivationPolicy(.regular)
app.appearance = NSAppearance(named: .aqua)  // 外壳锁 light，与桌面端一致
let installer = Installer()
app.delegate = installer
installer.buildUI()
app.activate(ignoringOtherApps: true)
app.run()
