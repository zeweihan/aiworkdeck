// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Playwright 的 driver-bundle-<ver>.jar 里塞了五套平台驱动
//（mac / mac-arm64 / win32_x64 / linux / linux-arm64，各含一个 node 二进制 +
// 三百多个 package/ 文件，1.43.0 上合计 161MB），而运行时只会用其中一套：
// com.microsoft.playwright.impl.driver.jar.DriverJar#getDriverResourceURI 拿的是
// ClassLoader.getResource("driver/" + platformDir())，platformDir() 按 os.name/os.arch
// 返回上面五个名字之一，随后只对那一个目录 Files.walk 解压。
// 所以打包时把其余四套删掉对运行时零影响，安装包直接少一百多兆。
//
// 做法是 zip 条目级的「原样搬运」：按中央目录逐条判定去留，保留的条目连同它的
// 本地头与**已压缩的数据字节**整块复制到新文件，只改中央目录里的相对偏移。
// 不解压也不重压，因此压缩方式与压缩率与上游 jar 完全一致（不会退化成 store），
// node 二进制的可执行位（external attributes）也原样保留。

const fs = require('fs');
const path = require('path');

const EOCD_SIG = 0x06054b50;
const CD_SIG = 0x02014b50;
const LFH_SIG = 0x04034b50;
const ZIP64_EOCD_LOCATOR_SIG = 0x07064b50;

/** DriverJar#platformDir 的等价实现（字符串必须逐字一致，否则运行时找不到驱动）。 */
function driverPlatformDir(platform = process.platform, arch = process.arch) {
    if (platform === 'win32') return 'win32_x64';
    if (platform === 'linux') return arch === 'arm64' ? 'linux-arm64' : 'linux';
    if (platform === 'darwin') return arch === 'arm64' ? 'mac-arm64' : 'mac';
    throw new Error(`unsupported platform for playwright driver: ${platform}/${arch}`);
}

/** lib/ 里的 driver-bundle-*.jar；没有就返回 null（依赖被移除时不要炸构建）。 */
function findDriverBundleJar(libDir) {
    if (!fs.existsSync(libDir)) return null;
    const hit = fs.readdirSync(libDir).find((n) => /^driver-bundle-.*\.jar$/.test(n));
    return hit ? path.join(libDir, hit) : null;
}

/**
 * 条目去留：driver/ 之外的一律保留（DriverJar.class、META-INF、.gitignore 等）；
 * driver/ 下只保留 keep 那一套（含 "driver/" 与 "driver/<keep>" 两个目录条目本身
 * ——ClassLoader.getResource 要靠目录条目才能解析出 jar: URI）。
 */
function shouldKeepEntry(name, keep) {
    if (!name.startsWith('driver/')) return true;
    const rest = name.slice('driver/'.length);
    if (rest === '') return true;
    return rest === keep || rest.startsWith(`${keep}/`);
}

function findEocd(buf) {
    const max = Math.min(buf.length, 0xffff + 22);
    for (let i = buf.length - 22; i >= buf.length - max; i--) {
        if (i < 0) break;
        if (buf.readUInt32LE(i) === EOCD_SIG && i + 22 + buf.readUInt16LE(i + 20) === buf.length) return i;
    }
    throw new Error('EOCD not found: not a zip/jar file');
}

/**
 * 重写 jar，只留 keep 这一套驱动。返回 { keptEntries, removedEntries, beforeBytes, afterBytes }。
 * keep 目录不存在时抛错——那说明平台名对不上，静默放过会打出一个 browse_url 必挂的安装包。
 */
function trimDriverBundleJar(jarPath, keep) {
    const buf = fs.readFileSync(jarPath);
    const beforeBytes = buf.length;
    const eocd = findEocd(buf);
    if (eocd >= 20 && buf.readUInt32LE(eocd - 20) === ZIP64_EOCD_LOCATOR_SIG) {
        throw new Error('zip64 jar is not supported by trim-driver-bundle');
    }
    const total = buf.readUInt16LE(eocd + 10);
    let p = buf.readUInt32LE(eocd + 16);

    const kept = [];
    let removedEntries = 0;
    let sawKeepDir = false;
    for (let i = 0; i < total; i++) {
        if (buf.readUInt32LE(p) !== CD_SIG) throw new Error(`bad central directory signature at entry ${i}`);
        const flags = buf.readUInt16LE(p + 8);
        if (flags & 0x08) throw new Error('entries with data descriptors are not supported by trim-driver-bundle');
        const compressedSize = buf.readUInt32LE(p + 20);
        const nameLen = buf.readUInt16LE(p + 28);
        const extraLen = buf.readUInt16LE(p + 30);
        const commentLen = buf.readUInt16LE(p + 32);
        const localOffset = buf.readUInt32LE(p + 42);
        const recordLen = 46 + nameLen + extraLen + commentLen;
        const name = buf.toString('utf8', p + 46, p + 46 + nameLen);

        if (shouldKeepEntry(name, keep)) {
            if (name === `driver/${keep}` || name.startsWith(`driver/${keep}/`)) sawKeepDir = true;
            if (buf.readUInt32LE(localOffset) !== LFH_SIG) throw new Error(`bad local header for ${name}`);
            const localNameLen = buf.readUInt16LE(localOffset + 26);
            const localExtraLen = buf.readUInt16LE(localOffset + 28);
            const localEnd = localOffset + 30 + localNameLen + localExtraLen + compressedSize;
            kept.push({ record: buf.subarray(p, p + recordLen), localStart: localOffset, localEnd });
        } else {
            removedEntries++;
        }
        p += recordLen;
    }
    if (!sawKeepDir) throw new Error(`driver/${keep} not found in ${path.basename(jarPath)}`);

    const tmp = `${jarPath}.trim`;
    const fd = fs.openSync(tmp, 'w');
    try {
        let offset = 0;
        for (const entry of kept) {
            const chunk = buf.subarray(entry.localStart, entry.localEnd);
            fs.writeSync(fd, chunk, 0, chunk.length, offset);
            entry.newOffset = offset;
            offset += chunk.length;
        }
        const cdStart = offset;
        for (const entry of kept) {
            const record = Buffer.from(entry.record);
            record.writeUInt32LE(entry.newOffset, 42);
            fs.writeSync(fd, record, 0, record.length, offset);
            offset += record.length;
        }
        const eocdBuf = Buffer.alloc(22);
        eocdBuf.writeUInt32LE(EOCD_SIG, 0);
        eocdBuf.writeUInt16LE(kept.length, 8);
        eocdBuf.writeUInt16LE(kept.length, 10);
        eocdBuf.writeUInt32LE(offset - cdStart, 12);
        eocdBuf.writeUInt32LE(cdStart, 16);
        fs.writeSync(fd, eocdBuf, 0, eocdBuf.length, offset);
        offset += eocdBuf.length;
    } finally {
        fs.closeSync(fd);
    }
    fs.rmSync(jarPath);
    fs.renameSync(tmp, jarPath);
    return { keptEntries: kept.length, removedEntries, beforeBytes, afterBytes: fs.statSync(jarPath).size };
}

module.exports = { driverPlatformDir, findDriverBundleJar, shouldKeepEntry, trimDriverBundleJar };
