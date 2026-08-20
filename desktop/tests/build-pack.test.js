// build-pack.js 的最小单测：打一个小的自造目录，断言 manifest 的 sha256/size
// 与包内 contents.sha256 都算对了，且排除规则（__pycache__/*.pyc）真的生效、
// 源目录打包后不留 contents.sha256 残留（它若是仓库里跟踪的源码目录，残留会
// 污染 git 状态——litviz 组件正是这种情况）。
//
// 直接 require 脚本导出的 packComponent()，不经 CLI 子进程、不碰真实的
// litviz/frontend/dist/drawio/desktop/bundled 目录，全程只操作临时目录。

const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { execFileSync } = require('child_process')

const { packComponent } = require('../scripts/build-pack')

function sha256Of(filePath) {
  return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex')
}

test('打包一个自造小目录：manifest 的 size/sha256 与 contents.sha256 都对，排除规则生效', (t) => {
  const workRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'build-pack-'))
  t.after(() => fs.rmSync(workRoot, { recursive: true, force: true }))

  // 一份小的自造「组件源」：两个正常文件 + 一个该被排除的 __pycache__ 目录
  // 和一个散落的 .pyc，验证 litviz 组件真实使用的排除谓词。
  const srcDir = path.join(workRoot, 'fake-litviz')
  fs.mkdirSync(path.join(srcDir, 'engine'), { recursive: true })
  fs.writeFileSync(path.join(srcDir, 'cli.py'), 'print(1)\n')
  fs.writeFileSync(path.join(srcDir, 'engine', 'core.py'), 'x = 1\n')
  fs.mkdirSync(path.join(srcDir, '__pycache__'), { recursive: true })
  fs.writeFileSync(path.join(srcDir, '__pycache__', 'cli.cpython-311.pyc'), 'binary-junk')
  fs.writeFileSync(path.join(srcDir, 'engine', 'core.pyc'), 'binary-junk')

  const exclude = [
    (relPath, name, st) => st.isDirectory() && name === '__pycache__',
    (relPath, name, st) => st.isFile() && name.endsWith('.pyc'),
  ]

  const outDir = path.join(workRoot, 'out')
  fs.mkdirSync(outDir, { recursive: true })
  const ctx = { id: 'litigation-visual', version: '0.0.1-test', outDir }

  const comp = packComponent(ctx, {
    name: 'litviz',
    srcDir,
    exclude,
    archive: 'litviz-0.0.1-test.tar.gz',
    platforms: ['*'],
  })

  assert.strictEqual(comp.name, 'litviz')
  assert.strictEqual(comp.unpackDir, 'litviz')
  assert.deepStrictEqual(comp.platforms, ['*'])
  assert.strictEqual(comp.archive, 'litviz-0.0.1-test.tar.gz')

  const archivePath = path.join(outDir, comp.archive)
  assert.ok(fs.existsSync(archivePath), '声明的 archive 文件应当存在')
  assert.strictEqual(fs.statSync(archivePath).size, comp.size, 'manifest 组件的 size 必须等于实际文件大小')
  assert.strictEqual(sha256Of(archivePath), comp.sha256, 'manifest 组件的 sha256 必须等于实际文件哈希')

  // 源目录打包后不该留下 contents.sha256 残留（对着仓库里跟踪的源码目录跑时，
  // 这条防的是「git status 多出一个文件」）。
  assert.ok(!fs.existsSync(path.join(srcDir, 'contents.sha256')), '打包后源目录不该留下 contents.sha256')

  // 包内条目必须是**裸相对路径**（不带 unpackDir 顶层目录）：安装端会先建
  // <version>/<unpackDir>/ 再解压，包里若再套一层 litviz/，落盘就成了
  // litviz/litviz/…，verifyContents 在外层找不到 contents.sha256，安装必挂
  // （真机报「组件缺少 contents.sha256 清单」，dev-board#65）。
  const entries = execFileSync('tar', ['-tzf', archivePath], { encoding: 'utf8' }).trim().split('\n')
  for (const entry of entries) {
    assert.ok(!entry.startsWith('litviz/'), `包内条目不该带 unpackDir 顶层前缀：${entry}`)
  }

  // 解开 tar，核对包内 contents.sha256 记的哈希与解出来的文件逐一一致，
  // 并且 __pycache__/*.pyc 确实被排除在外。
  const extractDir = path.join(workRoot, 'extract')
  fs.mkdirSync(extractDir, { recursive: true })
  execFileSync('tar', ['-xzf', archivePath, '-C', extractDir])

  assert.ok(!fs.existsSync(path.join(extractDir, '__pycache__')), '__pycache__ 目录不该被打进包里')
  assert.ok(!fs.existsSync(path.join(extractDir, 'engine', 'core.pyc')), '.pyc 文件不该被打进包里')
  assert.ok(fs.existsSync(path.join(extractDir, 'cli.py')), '正常文件应当在包里')

  const contentsSha = fs.readFileSync(path.join(extractDir, 'contents.sha256'), 'utf8')
  const lines = contentsSha.trim().split('\n').filter(Boolean)
  // 只有 cli.py / engine/core.py 两个正常文件，contents.sha256 不列自身
  assert.strictEqual(lines.length, 2)
  for (const line of lines) {
    const [want, rel] = line.split('  ')
    assert.ok(!rel.includes('__pycache__') && !rel.endsWith('.pyc'), `contents.sha256 不该列出被排除的文件：${rel}`)
    const got = sha256Of(path.join(extractDir, rel))
    assert.strictEqual(got, want, `contents.sha256 里 ${rel} 的哈希对不上解出来的文件`)
  }
})
