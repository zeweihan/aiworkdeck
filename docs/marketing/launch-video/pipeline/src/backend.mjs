// 起一个完全隔离的桌面后端（desktop profile）：独立 user.home / H2 文件库 / cwd /
// skills 目录拷贝，端口不是 9696。绝不附着到维护者正在跑的真实桌面后端。
//
// 解锁门：发版默认值关掉了试用码在线激活（security.license.trial-code.enabled=false），
// 走的是 frontend/tests/_lib/license-gate.mjs 记录过的同一条正路——往隔离
// user.home 播一份存量 trial 票据（legacy-grace-until 内合法，不是绕过闸）。

import { spawn, execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { BACKEND_DIR, OUT_DIR } from './config.mjs'

function findJar() {
  const targetDir = path.join(BACKEND_DIR, 'target')
  if (!fs.existsSync(targetDir)) return null
  const jars = fs.readdirSync(targetDir)
    .filter((f) => f.endsWith('.jar') && !f.endsWith('-sources.jar'))
  if (jars.length === 0) return null
  jars.sort((a, b) =>
    fs.statSync(path.join(targetDir, b)).mtimeMs - fs.statSync(path.join(targetDir, a)).mtimeMs)
  return path.join(targetDir, jars[0])
}

function resolveJavaBin() {
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', 'java')
  try {
    const home = execSync('/usr/libexec/java_home -v 21').toString().trim()
    return path.join(home, 'bin', 'java')
  } catch (e) {
    throw new Error('找不到 JDK 21（本机 mvn/java 必须 JDK 21）。设置 JAVA_HOME 指向 JDK 21 后重试。')
  }
}

function seedTrialLicense(home) {
  const dir = path.join(home, '.aiworkdeck')
  fs.mkdirSync(dir, { recursive: true })
  const now = new Date().toISOString()
  fs.writeFileSync(path.join(dir, 'license.json'), JSON.stringify({
    mode: 'trial',
    code: 'AWD-T-LAUNCH-VIDEO-PIPELINE',
    activatedAt: now,
    lastVerifiedAt: now,
  }, null, 2))
}

// 全新安装会卡在首启向导（launch → wizard，见 sidebar-shell.md 的路由术语表）：
// 没走完向导，前端连项目列表都到不了。选 OLLAMA 档是因为它不需要任何真实凭据、
// 也不触发跨境同意闸门（那条闸只挡 AWD_CLOUD 平台通道）——这条流水线的场景不需要
// AI 真的能聊天，只需要向导「已初始化」这一件事成立。
async function completeWizard(baseUrl) {
  const r = await fetch(baseUrl + '/api/admin/wizard', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ai: { activeProvider: 'OLLAMA' } }),
  })
  if (!r.ok) {
    const text = await r.text().catch(() => '')
    throw new Error(`首启向导初始化失败 HTTP ${r.status}: ${text.slice(0, 300)}`)
  }
}

async function waitReady(baseUrl, timeoutMs = 120000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    try {
      const r = await fetch(baseUrl + '/api/license/status')
      if (r.status === 200) return true
    } catch (e) { /* 未就绪，继续等 */ }
    await new Promise((r) => setTimeout(r, 1000))
  }
  return false
}

/**
 * @param {object} opts
 * @param {number} opts.port 后端监听端口（不可为 9696）
 * @param {string} [opts.tag] 隔离目录名的一部分，便于并行跑多条流水线时互不覆盖
 * @returns {Promise<{baseUrl:string, port:number, home:string, jar:string, kill:()=>void, logPath:string}>}
 */
export async function startIsolatedBackend({ port, tag = 'run' }) {
  if (port === 9696) throw new Error('拒绝起在 9696：那是维护者真实桌面后端的端口')
  const jar = process.env.LAUNCH_VIDEO_BACKEND_JAR || findJar()
  if (!jar) {
    throw new Error(
      '没找到 backend jar。先在 backend/ 下用 JDK 21 跑 `mvn -DskipTests package`，' +
      '或用 LAUNCH_VIDEO_BACKEND_JAR=<绝对路径> 指定已有 jar。'
    )
  }
  const javaBin = resolveJavaBin()

  const home = path.join(OUT_DIR, `backend-home-${tag}-${Date.now()}`)
  const cwd = path.join(home, 'cwd')
  fs.mkdirSync(cwd, { recursive: true })
  seedTrialLicense(home)

  // ai.skills.dir 相对隔离 cwd 解析不到内置 skill（同 eng-infra.md 记的坑），
  // 拷一份而不是指向原路径，防止误写污染仓库文件。
  const skillsDir = path.join(home, 'skills')
  fs.cpSync(path.join(BACKEND_DIR, 'skills'), skillsDir, { recursive: true })

  const dbUrl = `jdbc:h2:file:${path.join(home, 'db')}` +
    ';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE'

  const args = [
    `-Duser.home=${home}`,
    '-jar', jar,
    '--spring.profiles.active=desktop',
    `--server.port=${port}`,
    `--spring.datasource.url=${dbUrl}`,
    `--ai.skills.dir=${skillsDir}`,
  ]

  const logPath = path.join(home, 'backend.log')
  const logStream = fs.createWriteStream(logPath)
  const child = spawn(javaBin, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] })
  child.stdout.pipe(logStream)
  child.stderr.pipe(logStream)

  const baseUrl = `http://127.0.0.1:${port}`
  const ready = await waitReady(baseUrl)
  if (!ready) {
    try { child.kill('SIGKILL') } catch (e) { /* 已经死了 */ }
    throw new Error(`隔离后端 120s 内未就绪，日志见 ${logPath}`)
  }

  await completeWizard(baseUrl)

  // 按 pid 杀，不按 jar/进程名 pkill——并行会话可能也在跑同一个 jar。
  const kill = () => { try { child.kill('SIGTERM') } catch (e) { /* 已经死了 */ } }
  process.on('exit', kill)
  process.on('SIGINT', () => { kill(); process.exit(130) })

  return { baseUrl, port, home, jar, kill, logPath }
}
