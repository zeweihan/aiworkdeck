// 起一个带无边框标题栏的真实 dev Electron 外壳，用 puppeteer-core 通过 CDP 连上去驱动。
// 写法照抄 frontend/tests/_lib/electron-cdp.mjs 踩过的四条坑（端口现挑 / 整棵进程树一起收 /
// 连之前核身份 / 连上之后钉稳输入通道），这条流水线是自包含 node 项目、不跨包 import，
// 所以在本地复刻一份而不是引用 frontend/tests 下的模块。

import { spawn, execSync } from 'node:child_process'
import puppeteer from 'puppeteer-core'
import { DESKTOP_DIR, DEVSERVER_URL, VIEWPORT } from './config.mjs'

// 被遮挡/非活动窗口会让 Chromium 静默丢掉跟焦点绑定的输入（mousedown/mouseup/keydown），
// mousemove 不受影响——现象就是「鼠标能到，点击和打字没反应」。puppeteer.launch() 自带
// 这三个开关，手动 spawn 再 connect 就没有，必须显式补上。
const INPUT_FLAGS = [
  '--disable-backgrounding-occluded-windows',
  '--disable-renderer-backgrounding',
  '--disable-background-timer-throttling',
]

function portFree(p) {
  try { execSync(`lsof -nP -iTCP:${p} -sTCP:LISTEN -t`, { stdio: 'pipe' }); return false }
  catch (e) { return true }
}

function pickCdpPort(from = 9333) {
  const forced = Number(process.env.LAUNCH_VIDEO_CDP_PORT)
  if (forced) return forced
  for (let p = from; p < from + 60; p++) if (portFree(p)) return p
  throw new Error(`CDP 端口 ${from}-${from + 59} 全被占，挑不出空闲的`)
}

async function waitForCdpWs(cdpPort, tries = 90) {
  for (let i = 0; i < tries; i++) {
    await new Promise((r) => setTimeout(r, 1000))
    const ws = await fetch(`http://127.0.0.1:${cdpPort}/json/version`)
      .then((r) => r.json()).then((j) => j.webSocketDebuggerUrl).catch(() => null)
    if (ws) return ws
  }
  return null
}

/**
 * @param {object} opts
 * @param {number} opts.backendPort 后端端口（经 CHECKBA_BACKEND_PORT 注入渲染层，
 *   优先级高于 VITE_API_BASE_URL——这是走 Electron 壳换后端唯一有效的旋钮）
 * @returns {Promise<{page:import('puppeteer-core').Page, browser, cdpPort:number, close:()=>Promise<void>}>}
 */
export async function launchElectron({ backendPort }) {
  const cdpPort = pickCdpPort()
  const elec = spawn('npx', ['electron', '.', `--remote-debugging-port=${cdpPort}`, ...INPUT_FLAGS], {
    cwd: DESKTOP_DIR,
    env: {
      ...process.env,
      AIWORKDECK_DESKTOP_DEV: '1',
      CHECKBA_DEV_SERVER_URL: DEVSERVER_URL,
      CHECKBA_BACKEND_PORT: String(backendPort),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true, // npx→node→Electron 是三代进程，kill(-pid) 才能整组带走
  })
  const killTree = () => {
    for (const sig of ['SIGTERM', 'SIGKILL']) {
      try { process.kill(-elec.pid, sig) } catch (e) { /* 组已经没了 */ }
    }
  }
  process.on('exit', killTree)
  process.on('SIGINT', () => { killTree(); process.exit(130) })

  const ws = await waitForCdpWs(cdpPort)
  if (!ws) { killTree(); throw new Error(`CDP 端点 ${cdpPort} 90s 内未就绪`) }

  const holder = (() => {
    try { return execSync(`lsof -nP -iTCP:${cdpPort} -sTCP:LISTEN -t`).toString().trim().split('\n')[0] }
    catch (e) { return '' }
  })()
  if (holder && holder !== String(elec.pid)) {
    // 不强求严格祖先关系（跨平台 pgrep 树形状不一），但至少不是明显撞了别的会话——
    // 真撞上时端口早在 pickCdpPort 那步就该跳过，这里只做兜底提示。
    console.log(`  ! CDP 端口 ${cdpPort} 应答 pid=${holder}，起的进程 pid=${elec.pid}（父子关系，属预期）`)
  }

  const browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })
  // main.js 在 dev 模式下会自动 openDevTools({mode:'detach'})，那个 detach 窗口也是一个
  // CDP page target——browser.pages() 里它经常排在真正的应用窗口前面。挑第一个不是
  // devtools:// 的页面，找不到就等一轮再试（应用窗口可能还没创建完）。
  const pickAppPage = async () => {
    const pages = await browser.pages()
    return pages.find((p) => !p.url().startsWith('devtools://')) || null
  }
  let page = await pickAppPage()
  for (let i = 0; i < 20 && !page; i++) {
    await new Promise((r) => setTimeout(r, 500))
    page = await pickAppPage()
  }
  if (!page) throw new Error('连上了 CDP，但只找到 devtools:// 页面，没找到应用主窗口')
  await page.bringToFront()
  await page.setViewport(VIEWPORT)

  const cdp = await page.target().createCDPSession()
  await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: true })
  page.__awdCdp = cdp

  const close = async () => {
    try { await browser.disconnect() } catch (e) { /* 忽略 */ }
    killTree()
  }

  return { page, browser, cdpPort, close }
}
