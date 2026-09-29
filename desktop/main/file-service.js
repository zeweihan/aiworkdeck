// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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

    // Handler: 回收站「彻底删除」把磁盘上的文件送进系统废纸篓（dev-board#1051）。
    // 路径由后端 GET .../permanent/disk-paths 按项目 localRoot 解析，渲染层原样转交；
    // 送废纸篓可从 Finder/资源管理器还原，与 showItemInFolder 同档，不另设白名单。
    ipcMain.handle('fs:trashItems', async (event, { paths } = {}) => {
        return trashItems(paths);
    });
}

/**
 * 逐个 shell.trashItem，返回逐项成败 { ok, results: [{ path, ok, missing?, reason? }] }。
 * ok 仅在每一项都成功（或本来就不在）时为真——调用方据此决定要不要去清数据库行。
 *
 * 路径闸：只收绝对路径；normalize 后必须与原串一致（不接受 ".." 之类要靠解析才知道落点的写法）；
 * 不收文件系统根、用户主目录本身及其祖先（一条坏数据把整个主目录送进废纸篓的代价太大）。
 * 本来就不在的路径算成功（已达成），这样行照常清掉、不会在回收站里留幽灵。
 * 不做 realpath 比对：本机文件夹项目的 localRoot 可以经符号链接打开（外置盘、同步盘），
 * 父链有链接就拒绝会让这类项目永远删不掉；条目自身是链接时 trashItem 送走的是链接本身。
 */
async function trashItems(paths, deps = {}) {
    const sh = deps.shell || shell;
    const fsp = deps.fs || fs.promises;
    const home = path.resolve(deps.homedir || require('os').homedir());
    if (!Array.isArray(paths)) return { ok: false, reason: 'bad-paths', results: [] };
    const results = [];
    for (const p of paths) {
        results.push(await trashOne(p, sh, fsp, home));
    }
    return { ok: results.every((r) => r.ok), results };
}

async function trashOne(p, sh, fsp, home) {
    if (typeof p !== 'string' || !p || !path.isAbsolute(p)) return { path: p, ok: false, reason: 'not-absolute' };
    const resolved = path.resolve(p);
    const root = path.parse(resolved).root;
    if (resolved === root || resolved === home || home.startsWith(resolved + path.sep)) {
        return { path: p, ok: false, reason: 'protected' };
    }
    // 原串（去掉结尾分隔符）必须就是解析结果：带 ".."、"."、重复分隔符的一律不收
    if (p.replace(/[\\/]+$/, '') !== resolved) return { path: p, ok: false, reason: 'not-normalized' };
    try {
        await fsp.lstat(resolved);
    } catch (e) {
        if (e && e.code === 'ENOENT') return { path: p, ok: true, missing: true };
        return { path: p, ok: false, reason: 'stat-failed' };
    }
    try {
        await sh.trashItem(resolved);
        return { path: p, ok: true };
    } catch (e) {
        return { path: p, ok: false, reason: 'trash-failed', message: String((e && e.message) || e) };
    }
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
    trashItems,
    psQuote,
    windowsClipboardCommand,
    WECHAT_REGISTRY_CANDIDATES,
};
