// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// electron-builder 的 afterPack 钩子（安装包瘦身 dev-board#528）。
//
// ── 为什么需要它 ──
// package.json 的 `electronLanguages: ["en-US", "zh-CN"]` 在 mac 上**只裁**
// `Contents/Resources/*.lproj`——那批目录里除了 InfoPlist.strings 什么都没有，
// 删掉几乎不省体积。真正的语言包在
// `Contents/Frameworks/Electron Framework.framework/Versions/A/Resources/*.lproj`
// 里（每个 lproj 一份 locale.pak），Electron 30.5.1 上是 55 个、合计约 37 MB，
// electron-builder 的 electronLanguages 完全不碰它。
// 所以这一刀只能自己补。
//
// ── 为什么放在 afterPack ──
// app-builder-lib 24.13.3 的 `out/platformPackager.js` 里，
// 第 232 行 `await this.info.afterPack(packContext)` 早于
// 第 239 行 `await this.doSignAfterPack(...)`；mac 的 universal 分支同理，
// `out/macPackager.js` 第 125 行 afterPack、第 126 行 doSignAfterPack。
// 也就是钩子跑完才签名，删文件不会破坏 code signature。
// **升级 electron-builder 后要回头核这两处的先后**——若签名跑到前面，
// 这个钩子会把签好的 framework 改脏，Gatekeeper 直接判无效。
//
// 只在 darwin 上动手：Windows 的语言包是 `locales/*.pak` 平铺在 resources 旁边，
// electron-builder 的 electronLanguages 已经裁得掉，不需要这一刀。

const fs = require('fs');
const path = require('path');

/** 保留的语言目录。Base.lproj 当前 Electron 版本里没有，留着是防它哪天回来。 */
const KEEP_LPROJ = ['en.lproj', 'zh_CN.lproj', 'Base.lproj'];

const FRAMEWORK_RESOURCES = path.join(
    'Contents',
    'Frameworks',
    'Electron Framework.framework',
    'Versions',
    'A',
    'Resources',
);

/** 递归求一个目录的字节数（lproj 里目前只有 locale.pak，但不假设）。 */
function dirBytes(dir) {
    let total = 0;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) total += dirBytes(full);
        else if (entry.isFile()) total += fs.statSync(full).size;
    }
    return total;
}

/**
 * 删掉 resourcesDir 下 keep 之外的所有 *.lproj。
 *
 * @param {string} resourcesDir Electron Framework 的 Versions/A/Resources 目录
 * @param {string[]} keep 要保留的目录名（形如 'en.lproj'）
 * @returns {{ removed: string[], keptCount: number, bytes: number }}
 *          removed = 被删掉的目录名，keptCount = 保留下来的 lproj 个数，
 *          bytes = 删掉的字节数
 *
 * 目录不存在直接抛：这一步默默 no-op 的话，Electron 改了布局也没人知道，
 * 37 MB 的回归会一路混进发版包（这类"只做不断言"的步骤在本仓栽过）。
 */
function pruneFrameworkLocales(resourcesDir, keep = KEEP_LPROJ) {
    if (!fs.existsSync(resourcesDir)) {
        throw new Error(`Electron Framework Resources not found: ${resourcesDir}`);
    }
    const keepSet = new Set(keep);
    const removed = [];
    let keptCount = 0;
    let bytes = 0;

    for (const name of fs.readdirSync(resourcesDir)) {
        if (!name.endsWith('.lproj')) continue;
        if (keepSet.has(name)) {
            keptCount++;
            continue;
        }
        const full = path.join(resourcesDir, name);
        bytes += dirBytes(full);
        fs.rmSync(full, { recursive: true, force: true });
        removed.push(name);
    }
    return { removed, keptCount, bytes };
}

/** electron-builder afterPack 钩子。 */
module.exports = async (context) => {
    if (context.electronPlatformName !== 'darwin') return;

    const resourcesDir = path.join(
        context.appOutDir,
        `${context.packager.appInfo.productFilename}.app`,
        FRAMEWORK_RESOURCES,
    );
    const { removed, keptCount, bytes } = pruneFrameworkLocales(resourcesDir, KEEP_LPROJ);
    console.log(
        `  • prune framework locales  removed=${removed.length} kept=${keptCount} ` +
            `freed=${(bytes / 1024 / 1024).toFixed(1)} MB`,
    );
};

module.exports.pruneFrameworkLocales = pruneFrameworkLocales;
module.exports.KEEP_LPROJ = KEEP_LPROJ;
