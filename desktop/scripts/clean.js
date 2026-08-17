const fs = require('fs');
const path = require('path');
const os = require('os');

// Helper to remove directory recursively
function removeDir(dirPath) {
    if (fs.existsSync(dirPath)) {
        fs.readdirSync(dirPath).forEach((file, index) => {
            const curPath = path.join(dirPath, file);
            if (fs.lstatSync(curPath).isDirectory()) { // recurse
                removeDir(curPath);
            } else { // delete file
                fs.unlinkSync(curPath);
            }
        });
        fs.rmdirSync(dirPath);
        console.log(`Successfully removed: ${dirPath}`);
    } else {
        console.log(`Directory not found (nothing to clean): ${dirPath}`);
    }
}

// 必须跟 Electron 的 app.name 一致，而 app.name 取自 package.json：有顶层 productName
// 就用它、没有才用 name。本项目刻意不设顶层 productName（补上会连带把 userData 目录改名、
// 丢存量用户登录态，理由见 main/app-menu.js 顶部），所以读 name 就是对的。别写死，会漂。
const appName = require('../package.json').name;
let userDataPath;

if (process.platform === 'darwin') {
    userDataPath = path.join(os.homedir(), 'Library', 'Application Support', appName);
} else if (process.platform === 'win32') {
    userDataPath = path.join(os.homedir(), 'AppData', 'Roaming', appName);
} else {
    userDataPath = path.join(os.homedir(), '.config', appName);
}

console.log(`Cleaning user data at: ${userDataPath}`);

try {
    removeDir(userDataPath);
    console.log('Clean complete. You can now restart the app with "npm run dev".');
} catch (err) {
    console.error(`Error cleaning directory: ${err.message}`);
}
