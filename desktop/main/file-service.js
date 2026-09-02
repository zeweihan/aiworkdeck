const { ipcMain, dialog, shell, BrowserWindow, ShareMenu } = require('electron');
const fs = require('fs');
const path = require('path');
const { execFile, spawn } = require('child_process');

/**
 * Initialize local file service handlers.
 *
 * 注：原 fs:readFile / fs:writeFile（任意路径读/写）渲染进程从未调用（死暴露），且任意写可覆盖
 * 用户 dotfile 实现代码执行，已移除。仅保留文件选择对话框；实际文件读取走 checkba:fs-read-file
 * （见 main.js，只放行主进程登记过的剪贴板文件路径，另有大小上限）。
 */
function initLocalFileService() {
    console.log('[LocalFileService] Initializing...');

    // Handler: Open File Dialog（仅弹系统选择框，安全）
    ipcMain.handle('fs:showOpenDialog', async (event, options) => {
        return await dialog.showOpenDialog(options);
    });

    // Handler: 在 Finder/资源管理器中显示（仅高亮已有路径，不读写内容，安全）
    ipcMain.handle('fs:showItemInFolder', async (event, { path } = {}) => {
        if (typeof path !== 'string' || !path) return { ok: false };
        shell.showItemInFolder(path);
        return { ok: true };
    });

    // Handler: 把一份已在磁盘上的文件「发送」出去（dev-board#382）。
    // 只读该路径、不改内容；路径由后端按项目 localRoot 解析，渲染层拿不到任意路径写入能力。
    ipcMain.handle('fs:shareFile', async (event, { path: filePath } = {}) => {
        if (typeof filePath !== 'string' || !filePath) return { ok: false, reason: 'bad-path' };
        if (!fs.existsSync(filePath)) return { ok: false, reason: 'not-found' };
        const win = BrowserWindow.fromWebContents(event.sender);
        return shareFile(process.platform, filePath, win);
    });
}

/**
 * 分平台的「发送」实现。
 *
 * - macOS：系统分享面板（NSSharingServicePicker）。微信 Mac 版自带分享扩展
 *   WeChatMacShare.appex（NSExtensionActivationSupportsFileWithMaxCount=9），选中后由微信
 *   自己弹「选择聊天」；邮件 / 隔空投送 / 信息也一并在面板里，我们不用接任何邮件服务。
 *   位置不传，Electron 默认落在鼠标处（用户刚点完右键菜单那一项）。
 * - Windows：微信 Windows 版没有任何第三方接口，也不是系统分享目标（Win32 程序）。
 *   退化为：把文件以「文件放置列表」放进剪贴板（微信/QQ/企业微信聊天框都接受 Ctrl+V 粘贴
 *   文件），再尽力拉起/前置微信；渲染层按 mode 提示用户去粘贴。
 * - 其他平台：不支持。
 */
function shareFile(platform, filePath, win) {
    if (platform === 'darwin') {
        const menu = new ShareMenu({ filePaths: [filePath] });
        menu.popup(win ? { window: win } : {});
        return { ok: true, mode: 'share-sheet' };
    }
    if (platform === 'win32') {
        return copyFileToClipboardWindows(filePath).then(async () => {
            const wechatLaunched = await launchWeChatWindows();
            return { ok: true, mode: 'clipboard', wechatLaunched };
        });
    }
    return { ok: false, reason: 'unsupported' };
}

/** PowerShell 单引号字符串转义：只有单引号本身要写成两个。 */
function psQuote(s) {
    return "'" + String(s).replace(/'/g, "''") + "'";
}

/** Windows：Set-Clipboard -LiteralPath 会把文件作为 CF_HDROP 放进剪贴板（5.1 自带）。 */
function windowsClipboardCommand(filePath) {
    return 'Set-Clipboard -LiteralPath ' + psQuote(filePath);
}

function copyFileToClipboardWindows(filePath) {
    return new Promise((resolve, reject) => {
        execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', windowsClipboardCommand(filePath)],
            { windowsHide: true, timeout: 10000 }, (err) => (err ? reject(err) : resolve()));
    });
}

// 微信 Windows 版安装位置：3.x 写在 Tencent\WeChat，4.0 改名 Weixin。查不到就算了，
// 剪贴板已经放好，用户自己切到微信也能粘。
const WECHAT_REGISTRY_CANDIDATES = [
    { key: 'HKCU\\Software\\Tencent\\Weixin', exe: 'Weixin.exe' },
    { key: 'HKCU\\Software\\Tencent\\WeChat', exe: 'WeChat.exe' },
];

function regQueryInstallPath(key) {
    return new Promise((resolve) => {
        execFile('reg.exe', ['query', key, '/v', 'InstallPath'], { windowsHide: true, timeout: 5000 }, (err, stdout) => {
            if (err) return resolve(null);
            const m = /InstallPath\s+REG_\w+\s+(.+)$/m.exec(stdout || '');
            resolve(m ? m[1].trim() : null);
        });
    });
}

async function launchWeChatWindows() {
    for (const c of WECHAT_REGISTRY_CANDIDATES) {
        const dir = await regQueryInstallPath(c.key);
        if (!dir) continue;
        const exe = path.join(dir, c.exe);
        if (!fs.existsSync(exe)) continue;
        try {
            // 已在运行时再启动一次只会把现有窗口前置（微信单实例）
            spawn(exe, [], { detached: true, stdio: 'ignore', windowsHide: false }).unref();
            return true;
        } catch (e) {
            return false;
        }
    }
    return false;
}

module.exports = {
    initLocalFileService,
    shareFile,
    psQuote,
    windowsClipboardCommand,
    WECHAT_REGISTRY_CANDIDATES,
};
