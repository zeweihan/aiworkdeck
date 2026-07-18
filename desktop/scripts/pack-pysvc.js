#!/usr/bin/env node
/*
 * 把 bundled/<plat>/pysvc（上万个小文件）打成单个 pysvc.tar.gz + pysvc.meta.json，
 * electron-builder 只打包这两个文件（见 package.json extraResources），首次启动由
 * main/services/pysvc-runtime.js 解压到用户数据目录。
 *
 * macOS 动机：.app 内每个 Mach-O 都要单独 codesign + Apple 时间戳，上万文件导致
 * "The timestamp service is not available" 抖动使 release 构建频繁失败（run
 * 29552522258）。逐文件签名照旧在打包前完成（sign-mac-natives.sh）——Apple 公证
 * 会扫描嵌套压缩包，未签名二进制照样被拒；本脚本只是让 electron-builder 的
 * 签名/装订/公证阶段从上万个文件变成一个文件。
 *
 * 必须在 sign-mac-natives.sh 与各 pysvc 冒烟测试之后运行（两者都要读目录原样）。
 *
 * 用法：node scripts/pack-pysvc.js --bundle bundled/mac-arm64
 */
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

function parseArgs() {
  const out = {}
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--') && i + 1 < argv.length) out[argv[i].slice(2)] = argv[++i]
  }
  if (!out.bundle) {
    console.error('missing --bundle <dir> (e.g. bundled/mac-arm64)')
    process.exit(1)
  }
  return out
}

function dirStats(root) {
  let totalBytes = 0
  let fileCount = 0
  const stack = [root]
  while (stack.length) {
    const cur = stack.pop()
    for (const en of fs.readdirSync(cur, { withFileTypes: true })) {
      const fp = path.join(cur, en.name)
      if (en.isDirectory()) stack.push(fp)
      else if (en.isFile()) { totalBytes += fs.statSync(fp).size; fileCount++ }
    }
  }
  return { totalBytes, fileCount }
}

function main() {
  const args = parseArgs()
  const bundleDir = path.resolve(args.bundle)
  const pysvcDir = path.join(bundleDir, 'pysvc')
  if (!fs.existsSync(pysvcDir)) {
    console.error(`pysvc dir not found: ${pysvcDir}`)
    process.exit(1)
  }
  const stats = dirStats(pysvcDir)
  // totalBytes 供运行时解压进度条当分母（已落盘字节 / totalBytes）
  fs.writeFileSync(path.join(bundleDir, 'pysvc.meta.json'), JSON.stringify(stats))
  // cwd + 相对路径：Windows 上 GNU tar 会把 "D:\..." 的冒号当远程主机（host:file 语法）
  execFileSync('tar', ['-czf', 'pysvc.tar.gz', 'pysvc'], { cwd: bundleDir, stdio: 'inherit' })
  const archiveBytes = fs.statSync(path.join(bundleDir, 'pysvc.tar.gz')).size
  console.log(`packed pysvc: ${stats.fileCount} files, ${(stats.totalBytes / 1048576).toFixed(0)} MB -> pysvc.tar.gz ${(archiveBytes / 1048576).toFixed(0)} MB`)
}

main()
