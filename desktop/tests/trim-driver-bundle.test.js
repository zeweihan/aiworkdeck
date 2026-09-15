// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const zlib = require('node:zlib')
const { execFileSync } = require('node:child_process')
const { driverPlatformDir, findDriverBundleJar, shouldKeepEntry, trimDriverBundleJar } =
  require('../scripts/trim-driver-bundle')

// 安装包瘦身 dev-board#528：Playwright 的 driver-bundle jar 并排装着五套平台驱动，
// 运行时只读 driver/<platformDir>/ 那一套（DriverJar#getDriverResourceURI）。
// 这组用例守两件事：① 只有本平台目录留下来，其余平台一条不剩；
// ② driver/ 以外的条目（DriverJar.class、META-INF/…）与本平台目录下的每个字节原样保留，
// 且压缩方式不退化成 store——退化会让 jar 反而变大，是这类"重写 zip"最容易犯的错。

// ---- 合成 zip（不依赖 zip/jar 外部命令，Windows runner 上也能跑）----
const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()

function crc32(buf) {
  let c = 0 ^ -1
  for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ CRC_TABLE[(c ^ buf[i]) & 0xff]
  return ((c ^ -1) >>> 0)
}

/** entries: [{ name, data?, store? }]；name 以 / 结尾即目录条目。 */
function writeZip(file, entries) {
  const locals = []
  const centrals = []
  let offset = 0
  for (const e of entries) {
    const name = Buffer.from(e.name, 'utf8')
    const raw = e.data == null ? Buffer.alloc(0) : Buffer.from(e.data)
    const store = e.store || raw.length === 0
    const data = store ? raw : zlib.deflateRawSync(raw, { level: 9 })
    const method = store ? 0 : 8
    const lfh = Buffer.alloc(30)
    lfh.writeUInt32LE(0x04034b50, 0)
    lfh.writeUInt16LE(20, 4)
    lfh.writeUInt16LE(0x0800, 6)
    lfh.writeUInt16LE(method, 8)
    lfh.writeUInt32LE(crc32(raw), 14)
    lfh.writeUInt32LE(data.length, 18)
    lfh.writeUInt32LE(raw.length, 22)
    lfh.writeUInt16LE(name.length, 26)
    locals.push(lfh, name, data)

    const cd = Buffer.alloc(46)
    cd.writeUInt32LE(0x02014b50, 0)
    cd.writeUInt16LE(20, 4)
    cd.writeUInt16LE(20, 6)
    cd.writeUInt16LE(0x0800, 8)
    cd.writeUInt16LE(method, 10)
    cd.writeUInt32LE(crc32(raw), 16)
    cd.writeUInt32LE(data.length, 20)
    cd.writeUInt32LE(raw.length, 24)
    cd.writeUInt16LE(name.length, 28)
    cd.writeUInt32LE((((e.name.endsWith('/') ? 0o40755 : 0o100644) << 16) >>> 0), 38)
    cd.writeUInt32LE(offset, 42)
    centrals.push(cd, name)
    offset += lfh.length + name.length + data.length
  }
  const cdBuf = Buffer.concat(centrals)
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(entries.length, 8)
  eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(cdBuf.length, 12)
  eocd.writeUInt32LE(offset, 16)
  fs.writeFileSync(file, Buffer.concat([Buffer.concat(locals), cdBuf, eocd]))
}

/** 读回 zip：返回 { name -> { method, externalAttrs, content } }。 */
function readZip(file) {
  const buf = fs.readFileSync(file)
  let i = buf.length - 22
  while (i >= 0 && buf.readUInt32LE(i) !== 0x06054b50) i--
  assert.ok(i >= 0, 'EOCD 必须存在')
  const n = buf.readUInt16LE(i + 10)
  let p = buf.readUInt32LE(i + 16)
  const out = {}
  for (let k = 0; k < n; k++) {
    assert.strictEqual(buf.readUInt32LE(p), 0x02014b50, `中央目录条目 ${k} 签名`)
    const method = buf.readUInt16LE(p + 10)
    const compSize = buf.readUInt32LE(p + 20)
    const uncompSize = buf.readUInt32LE(p + 24)
    const nameLen = buf.readUInt16LE(p + 28)
    const extraLen = buf.readUInt16LE(p + 30)
    const commentLen = buf.readUInt16LE(p + 32)
    const externalAttrs = buf.readUInt32LE(p + 38)
    const off = buf.readUInt32LE(p + 42)
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen)
    assert.strictEqual(buf.readUInt32LE(off), 0x04034b50, `${name} 的本地头签名`)
    const ds = off + 30 + buf.readUInt16LE(off + 26) + buf.readUInt16LE(off + 28)
    const raw = buf.subarray(ds, ds + compSize)
    const content = method === 0 ? raw : zlib.inflateRawSync(raw)
    assert.strictEqual(content.length, uncompSize, `${name} 解压后长度`)
    out[name] = { method, externalAttrs, content }
    p += 46 + nameLen + extraLen + commentLen
  }
  return out
}

// 每套平台驱动给一段"能压得动"的可辨认内容，用来验证留下来的字节没被串位
function platformPayload(tag) {
  return Buffer.from(`#!/usr/bin/env node\n// ${tag} driver payload\n`.repeat(200))
}

function makeBundle(dir) {
  const jar = path.join(dir, 'driver-bundle-1.43.0.jar')
  const entries = [
    { name: 'META-INF/MANIFEST.MF', data: 'Manifest-Version: 1.0\n' },
    { name: 'com/microsoft/playwright/impl/driver/jar/DriverJar.class', data: Buffer.from([0xca, 0xfe, 0xba, 0xbe, 1, 2, 3]) },
    { name: '.gitignore', data: '*\n' },
    { name: 'driver/', store: true },
  ]
  for (const p of ['mac', 'mac-arm64', 'win32_x64', 'linux', 'linux-arm64']) {
    entries.push({ name: `driver/${p}/`, store: true })
    entries.push({ name: `driver/${p}/node`, data: platformPayload(p) })
    entries.push({ name: `driver/${p}/package/`, store: true })
    entries.push({ name: `driver/${p}/package/cli.js`, data: `console.log('${p}')\n` })
  }
  writeZip(jar, entries)
  return jar
}

function withTmp(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'trim-driver-bundle-test-'))
  try { return fn(dir) } finally { fs.rmSync(dir, { recursive: true, force: true }) }
}

test('platformDir 与 Playwright DriverJar#platformDir 的字符串逐字一致', () => {
  assert.strictEqual(driverPlatformDir('darwin', 'arm64'), 'mac-arm64')
  assert.strictEqual(driverPlatformDir('darwin', 'x64'), 'mac')
  assert.strictEqual(driverPlatformDir('win32', 'x64'), 'win32_x64')
  assert.strictEqual(driverPlatformDir('linux', 'x64'), 'linux')
  assert.strictEqual(driverPlatformDir('linux', 'arm64'), 'linux-arm64')
  assert.throws(() => driverPlatformDir('aix', 'ppc64'), /unsupported platform/)
})

test('shouldKeepEntry：只放行本平台目录，别的平台一律拦下', () => {
  assert.strictEqual(shouldKeepEntry('com/microsoft/playwright/impl/driver/jar/DriverJar.class', 'mac-arm64'), true)
  assert.strictEqual(shouldKeepEntry('driver/', 'mac-arm64'), true)
  assert.strictEqual(shouldKeepEntry('driver/mac-arm64/', 'mac-arm64'), true)
  assert.strictEqual(shouldKeepEntry('driver/mac-arm64/package/cli.js', 'mac-arm64'), true)
  // 前缀相近的两个目录不能互相误伤（mac 与 mac-arm64）
  assert.strictEqual(shouldKeepEntry('driver/mac/node', 'mac-arm64'), false)
  assert.strictEqual(shouldKeepEntry('driver/mac-arm64/node', 'mac'), false)
  assert.strictEqual(shouldKeepEntry('driver/linux-arm64/node', 'linux'), false)
})

test('重写后只剩本平台驱动，其余条目原样保留且压缩没退化', () => withTmp((dir) => {
  const jar = makeBundle(dir)
  const before = readZip(jar)
  const beforeSize = fs.statSync(jar).size

  const r = trimDriverBundleJar(jar, 'mac-arm64')

  const after = readZip(jar)
  const names = Object.keys(after)
  // 四套外平台一条不剩
  for (const p of ['mac', 'win32_x64', 'linux', 'linux-arm64']) {
    assert.ok(!names.some((n) => n.startsWith(`driver/${p}/`)), `driver/${p}/ 必须被删干净：${names}`)
  }
  // 本平台目录条目（getResource("driver/mac-arm64") 靠它解析 jar: URI）与内容都在
  assert.deepStrictEqual(names.sort(), [
    '.gitignore',
    'META-INF/MANIFEST.MF',
    'com/microsoft/playwright/impl/driver/jar/DriverJar.class',
    'driver/',
    'driver/mac-arm64/',
    'driver/mac-arm64/node',
    'driver/mac-arm64/package/',
    'driver/mac-arm64/package/cli.js',
  ])
  // 字节级一致（内容 + 压缩方式 + 权限位）
  for (const n of names) {
    assert.deepStrictEqual(after[n].content, before[n].content, `${n} 内容`)
    assert.strictEqual(after[n].method, before[n].method, `${n} 压缩方式`)
    assert.strictEqual(after[n].externalAttrs, before[n].externalAttrs, `${n} 外部属性（可执行位）`)
  }
  // 压缩没退化成 store：能压的那两个条目仍是 deflate
  assert.strictEqual(after['driver/mac-arm64/node'].method, 8)
  assert.strictEqual(after['META-INF/MANIFEST.MF'].method, 8)
  assert.strictEqual(r.keptEntries, 8)
  assert.strictEqual(r.removedEntries, 16)
  assert.strictEqual(r.beforeBytes, beforeSize)
  assert.ok(r.afterBytes < beforeSize, `重写后应当变小：${r.afterBytes} < ${beforeSize}`)
}))

test('平台名对不上时必须报错，不能静默产出没有驱动的 jar', () => withTmp((dir) => {
  const jar = makeBundle(dir)
  assert.throws(() => trimDriverBundleJar(jar, 'mac-x64'), /driver\/mac-x64 not found/)
}))

test('findDriverBundleJar：认得出 lib/ 里的 driver-bundle-*.jar，认不出别的', () => withTmp((dir) => {
  const lib = path.join(dir, 'lib')
  fs.mkdirSync(lib)
  assert.strictEqual(findDriverBundleJar(lib), null)
  fs.writeFileSync(path.join(lib, 'playwright-1.43.0.jar'), '')
  assert.strictEqual(findDriverBundleJar(lib), null)
  fs.writeFileSync(path.join(lib, 'driver-bundle-1.43.0.jar'), '')
  assert.strictEqual(findDriverBundleJar(lib), path.join(lib, 'driver-bundle-1.43.0.jar'))
  assert.strictEqual(findDriverBundleJar(path.join(dir, 'nope')), null)
}))

// 真 JDK 在场时用 `jar -tf` 复核一遍：自研 zip 写出来的东西必须是 Java 认的 jar，
// 不然"我的读法自洽"证明不了运行时能用（DriverJar 走的是 JDK 的 zipfs）。
test('产物能被 JDK 的 jar 工具读出来（无 JDK 时跳过）', { skip: !process.env.JAVA_HOME }, () => withTmp((dir) => {
  const jar = makeBundle(dir)
  trimDriverBundleJar(jar, 'linux')
  const jarTool = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar')
  if (!fs.existsSync(jarTool)) return
  const listed = execFileSync(jarTool, ['-tf', jar], { encoding: 'utf8' }).split(/\r?\n/).filter(Boolean).sort()
  assert.deepStrictEqual(listed, [
    '.gitignore',
    'META-INF/MANIFEST.MF',
    'com/microsoft/playwright/impl/driver/jar/DriverJar.class',
    'driver/',
    'driver/linux/',
    'driver/linux/node',
    'driver/linux/package/',
    'driver/linux/package/cli.js',
  ])
}))
