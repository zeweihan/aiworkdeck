// 起一个带 CDP 的 dev Electron，再用 puppeteer 连上去 —— desktop / feedback / meeting
// 三套 e2e 都要这一段。以前三个文件各抄一份，于是同一个坑得踩三次：2026-08-17
// desktop-e2e「新建 Word 文档」间歇性红查了大半天，根因就在这段里（详见
// .claude/agents/eng-infra.md 的两条）。收在这里，改一次三套都受益。
//
// 这段解决四件事，缺一件就会以"点了没反应"的形态间歇性红：
//   ① 端口现挑——写死会撞上并行会话，也会撞上自己上一轮没死透的残留；
//   ② 整棵进程树一起收——spawn 的是 npx，Electron 是**孙子进程**，kill(npx) 打不到它；
//   ③ 连之前核身份——确认应答端口的就是自己刚起的那棵树，不是别人的窗口；
//   ④ 连上之后钉稳输入通道——见 INPUT_FLAGS 与 hardenPageInput 的注释。

import { spawn, execSync } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'

// 驱动一个"不在最前面"的窗口时，必须关掉 Chromium 的后台降级。被遮挡/非活动的
// 窗口一旦被降级，**跟焦点绑定的输入**（mousePressed / mouseReleased / keyDown）
// 会被静默丢弃，而按命中测试投递的 mouseMoved 不受影响——现象就是"鼠标移动送得到、
// 点击和按键送不到，页面其余一切正常"。puppeteer.launch() 自带这三个开关（所以
// app-e2e / lowa-e2e 那种自己 launch 无头 Chrome 的套件天然没这问题），而我们是手动
// spawn Electron 再 connect，不显式加就没有。
export const INPUT_FLAGS = [
  '--disable-backgrounding-occluded-windows',
  '--disable-renderer-backgrounding',
  '--disable-background-timer-throttling',
]

export const portFree = (p) => {
  try { execSync('lsof -nP -iTCP:' + p + ' -sTCP:LISTEN -t', { stdio: 'pipe' }); return false }
  catch (e) { return true } // lsof 非零退出 = 没人在听
}

// 从 from 起找第一个空闲端口。envName 给一个显式覆盖口，方便手动错开。
export const pickCdpPort = (envName, from) => {
  const forced = Number(process.env[envName])
  if (forced) return forced
  for (let p = from; p < from + 40; p++) if (portFree(p)) return p
  throw new Error('CDP 端口 ' + from + '-' + (from + 39) + ' 全被占，挑不出空闲的')
}

// detached 让它自成进程组，收尾时 kill(-pid) 能把 npx→node→Electron 整棵树带走。
// 测试进程自己被 Ctrl-C / 异常退出时也要收，否则下一轮又撞上一个占着端口的孤儿。
// 装好的 AI WorkDeck.app 开着的时候，dev 实例会被 main.js 的
// requestSingleInstanceLock 当场 app.quit()——单实例锁按 userData 目录判重，两边
// 默认是同一个。现象极不好认：Electron 起来、打了 "DevTools listening"、随即无声退出，
// 套件只看见「CDP 端点未就绪」或连 ws 时 ECONNREFUSED。给 dev 实例挑一个自己的
// profile 目录，锁就各归各的，维护者不必为跑一次门禁去关自己的应用。
// （按端口取名：同一个套件重跑复用同一份 profile，不会把 tmp 撑爆。）
export const devProfileDir = (cdpPort) =>
  path.join(os.tmpdir(), 'awd-e2e-electron-profile-' + cdpPort)

export const spawnElectron = ({ desktopDir, cdpPort, env, extraArgs = [] }) => {
  const elec = spawn('npx', ['electron', '.', '--remote-debugging-port=' + cdpPort,
    '--user-data-dir=' + devProfileDir(cdpPort), ...INPUT_FLAGS, ...extraArgs], {
    cwd: desktopDir,
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
  })
  const killTree = () => {
    for (const sig of ['SIGTERM', 'SIGKILL']) {
      try { process.kill(-elec.pid, sig) } catch (e) { /* 组没了就算了 */ }
    }
  }
  process.on('exit', killTree)
  process.on('SIGINT', () => { killTree(); process.exit(130) })
  return { elec, killTree }
}

// elec 传进来时顺带看着它：进程自己先退了就不必再干等满 60 秒，
// 而且能把退出码报出来（"起不来"和"起来了但没听端口"是两种病）。
export const waitForCdpWs = async (cdpPort, tries = 60, elec = null) => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  for (let i = 0; i < tries; i++) {
    await sleep(1000)
    const ws = await fetch('http://127.0.0.1:' + cdpPort + '/json/version')
      .then((r) => r.json()).then((j) => j.webSocketDebuggerUrl).catch(() => null)
    if (ws) return ws
    if (elec && elec.exitCode !== null) {
      console.error('Electron 起来后自己退了（exitCode=' + elec.exitCode
        + '）。最常见的是装好的 AI WorkDeck 正开着、单实例锁把 dev 实例顶掉了，'
        + '或者 dev server 地址不对。日志见 ' + path.join(os.tmpdir(), '*-electron.log'))
      return null
    }
  }
  return null
}

// 应答这个端口的，必须是我们刚起的那棵进程树。撞上别人就当场报死——总比默默驱动
// 别人的窗口、最后以"点了没反应"的形态红在某一步强。
export const cdpOwnershipError = (cdpPort, elec) => {
  const kids = (root) => {
    const out = []
    const walk = (p) => {
      let cs = ''
      try { cs = execSync('pgrep -P ' + p + ' 2>/dev/null || true').toString() } catch (e) { cs = '' }
      for (const c of cs.split('\n').map((s) => s.trim()).filter(Boolean)) { out.push(c); walk(c) }
    }
    walk(root)
    return out
  }
  let holder = ''
  try {
    holder = execSync('lsof -nP -iTCP:' + cdpPort + ' -sTCP:LISTEN -t 2>/dev/null || true')
      .toString().trim().split('\n')[0]
  } catch (e) { holder = '' }
  const ours = [String(elec.pid), ...kids(elec.pid)]
  if (holder && !ours.includes(holder)) {
    return 'CDP 端口 ' + cdpPort + ' 上应答的是 pid=' + holder + '，不是本轮起的 Electron('
      + ours.join(',') + ')——多半有别的会话在跑同一套 e2e'
  }
  return null
}

// 光有命令行开关还不够：窗口不是"活动窗口"时，渲染器会把这一页当成非活动页，于是
// **跟焦点绑定的输入被丢**（mousePressed / mouseReleased / keyDown），而按命中测试
// 投递的 mouseMoved 照送。CDP 专门为"自动化一个不在前台的页面"留了这个开关。
//
// 2026-08-18 实测坐实（desktop-e2e 去掉加固后跑出来的现场，同一轮三次一致）：
//   掉事件时 press=false → 开 setFocusEmulationEnabled → press=true
// 所以这不是"可能有用的加固"，是这一档故障的对症解。**同一现场还证明整页重载
// 救不回来**（重载三次仍然是死的），所以恢复路径要先补这一刀，别指望重载。
//
// 会话要拿住：emulation 是挂在这条 CDP 会话上的，会话没了设置也就没了。
export const hardenPageInput = async (page) => {
  try {
    const cdp = page.__awdCdp || (page.__awdCdp = await page.target().createCDPSession())
    await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: true })
    return true
  } catch (e) {
    console.log('  ! 焦点仿真没开成（' + String(e.message || e).slice(0, 60) + '），输入通道少一层保险')
    return false
  }
}

// 掉事件之后就地补刀：重新压一次焦点仿真。导航/会话重建都可能把它丢掉，
// 而它恰恰是唯一验证过管用的解药。
export const reassertFocusEmulation = async (page) => {
  try {
    const cdp = await page.target().createCDPSession()
    await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: true })
    return true
  } catch (e) { return false }
}
