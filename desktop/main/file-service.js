const { ipcMain, dialog, shell } = require('electron');

/**
 * Initialize local file service handlers.
 *
 * 注：原 fs:readFile / fs:writeFile（任意路径读/写）渲染进程从未调用（死暴露），且任意写可覆盖
 * 用户 dotfile 实现代码执行，已移除。仅保留文件选择对话框；实际文件读取走 checkba:fs-read-file
 * （见 main.js，已加敏感路径拦截与大小上限）。
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
}

module.exports = {
    initLocalFileService
};
