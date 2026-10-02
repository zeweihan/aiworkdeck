// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const H5_DIR = path.resolve(__dirname, '../dist/build/h5')
const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const SCREENSHOT_DIR = path.resolve(__dirname, '../../docs/screenshots')
const ARTIFACT_DIR = '/Users/zewei/.gemini/antigravity/brain/f3a2659b-3f03-43e3-ba06-862e3c91bfc4'

fs.mkdirSync(SCREENSHOT_DIR, { recursive: true })
fs.mkdirSync(path.join(ARTIFACT_DIR, 'screenshots'), { recursive: true })

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
}

// 启动本地静态服务器
const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0]
  if (reqPath === '/' || reqPath === '') reqPath = '/index.html'
  const filePath = path.join(H5_DIR, reqPath)
  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const ext = path.extname(filePath).toLowerCase()
    res.writeHead(200, {
      'Content-Type': MIME_TYPES[ext] || 'application/octet-stream',
      'Access-Control-Allow-Origin': '*'
    })
    fs.createReadStream(filePath).pipe(res)
  } else {
    // 单页应用回落 index.html
    const indexPath = path.join(H5_DIR, 'index.html')
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    fs.createReadStream(indexPath).pipe(res)
  }
})

const PORT = 8991
server.listen(PORT, async () => {
  console.log(`Preview server listening at http://127.0.0.1:${PORT}`)

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
    defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 2 }
  })

  try {
    const page = await browser.newPage()
    await page.setRequestInterception(true)

    const meetingDetail = {
      id: 1,
      projectId: 1,
      subject: 'AI WorkDeck 产品与技术架构研讨会',
      meetingCode: '888-999-111',
      startTime: '2026-10-02T14:30:00',
      duration: '45分钟',
      status: 'SYNCED',
      speakersJson: '["韩泽伟", "张工", "李工"]',
      smartMinutesText: `### 核心议题
- 腾讯会议官方 CLI 插件架构与接入落地
- 逐字稿时间轴交互与 AI ToDo 提取方案

### 决议事项
1. 采用本地 tmeet CLI 封装，确保用户数据安全且无需自备腾讯云开发者凭证
2. AI 编排集成支持一键提炼专业五段式会议纪要与自动落入项目日程

### 行动项 (ToDo)
- [ ] 韩泽伟：完成插件前端验收与 PR 合并
- [ ] 张工：跟进长录音分片同步与异常重试优化
- [ ] 李工：丰富会议日程多维度标签`,
      transcriptJson: JSON.stringify([
        { startTime: '00:05', speakerName: '韩泽伟', text: '各位下午好，今天我们讨论 AI WorkDeck 的腾讯会议官方插件研发进展。目前 tmeet CLI 的封装已经就绪。' },
        { startTime: '00:35', speakerName: '张工', text: '是的，CLI 的 auth status、sync、transcript-get 接口已经完成后端 Service 和 Controller 封装，单测已全部通过。' },
        { startTime: '01:10', speakerName: '李工', text: '前端部分在左侧边栏提供了独立的腾讯会议面板，支持查看当前账号连接状态、配置自动同步频率，以及一键立即同步。' },
        { startTime: '02:05', speakerName: '韩泽伟', text: '非常棒。在中栏工作台打开逐字稿标签后，用户可以直接查看说话人分离的时间轴，并且可以通过系统自带的 AI 一键生成会议纪要和提取 ToDo List 到日程。' },
        { startTime: '02:45', speakerName: '张工', text: '这个商业化体验非常流畅，律师和企业团队开完会几秒钟就能把待办落进系统日程，价值非常直接。' }
      ])
    }

    const meetingList = [
      {
        id: 1,
        projectId: 1,
        subject: 'AI WorkDeck 产品与技术架构研讨会',
        meetingCode: '888-999-111',
        startTime: '2026-10-02T14:30:00',
        duration: '45分钟',
        status: 'SYNCED',
        speakersJson: '["韩泽伟", "张工", "李工"]'
      },
      {
        id: 2,
        projectId: 1,
        subject: '法律大模型合规与数据安全评审会',
        meetingCode: '222-333-444',
        startTime: '2026-10-01T10:00:00',
        duration: '60分钟',
        status: 'SYNCED',
        speakersJson: '["韩泽伟", "王律师"]'
      }
    ]

    page.on('console', msg => console.log('PAGE LOG:', msg.text()))
    page.on('pageerror', err => console.log('PAGE ERROR:', err.message))

    await page.evaluateOnNewDocument(() => {
      localStorage.setItem('checkba_session_id', 'mock-session-123')
      localStorage.setItem('checkba_user', JSON.stringify({ id: 1, username: 'zewei', role: 'ADMIN', name: '韩泽伟' }))
    })

    page.on('request', (req) => {
      const url = req.url()
      const method = req.method()
      console.log(`REQ: ${method} ${url}`)

      if (method === 'OPTIONS') {
        req.respond({
          status: 204,
          headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS, HEAD',
            'Access-Control-Allow-Headers': '*',
            'Access-Control-Max-Age': '86400'
          }
        })
        return
      }

      if (url.includes('/api/')) {
        const respond = (data) => {
          req.respond({
            status: 200,
            contentType: 'application/json; charset=utf-8',
            headers: {
              'Access-Control-Allow-Origin': '*',
              'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS, HEAD',
              'Access-Control-Allow-Headers': '*'
            },
            body: JSON.stringify({ code: 0, data })
          })
        }

        if (url.includes('/api/auth/me')) {
          respond({ id: 1, username: 'zewei', role: 'ADMIN', name: '韩泽伟' })
        } else if (url.includes('/api/skills/list')) {
          respond([
            { id: 'tencent-meeting', name: '腾讯会议', enabled: true },
            { id: 'voice', name: '语音', enabled: true }
          ])
        } else if (url.includes('/api/tmeet/auth/status')) {
          respond({ authorized: true, accountName: '韩泽伟@晴天' })
        } else if (url.includes('/api/tmeet/config')) {
          respond({ autoSync: true, syncIntervalMinutes: 30, excludeKeywords: '' })
        } else if (url.includes('/api/tmeet/meetings/1')) {
          respond(meetingDetail)
        } else if (url.includes('/api/tmeet/meetings')) {
          respond(meetingList)
        } else if (url.includes('/api/projects/1/files')) {
          respond([])
        } else if (url.includes('/api/projects/1')) {
          respond({ id: 1, name: 'AI WorkDeck 研发项目', status: 'ACTIVE' })
        } else if (url.includes('/api/projects')) {
          respond([{ id: 1, name: 'AI WorkDeck 研发项目' }])
        } else {
          respond([])
        }
      } else {
        req.continue()
      }
    })

    console.log('Navigating to project overview page...')
    await page.goto(`http://127.0.0.1:${PORT}/#/pages/project-overview/project-overview?id=1`, {
      waitUntil: 'networkidle0',
      timeout: 30000
    })

    // 等待页面挂载完成
    await new Promise(r => setTimeout(r, 2000))

    // 1. 点击左栏腾讯会议图标打开面板
    console.log('Opening Tencent Meeting sidebar pane...')
    // 查找包含 title="腾讯会议" 的元素或 rail item
    const railItems = await page.$$('.rail-item, .left-rail-btn, [title="腾讯会议"], .rail-button')
    let clickedRail = false
    for (const item of railItems) {
      const title = await item.evaluate(el => el.getAttribute('title') || el.innerText || '')
      if (title.includes('腾讯会议')) {
        await item.click()
        clickedRail = true
        break
      }
    }

    if (!clickedRail) {
      // 备选方案：通过 evaluate 直接调用 vm 的 toggleLeftPane('tmeet')
      await page.evaluate(() => {
        const app = document.querySelector('#app')
        if (app && app.__vue_app__) {
          // vue3 root
        }
        // 直接寻找含 svg 或 text 的按钮
        const buttons = Array.from(document.querySelectorAll('*'))
        const btn = buttons.find(b => b.getAttribute && b.getAttribute('title') === '腾讯会议')
        if (btn) btn.click()
      })
    }

    await new Promise(r => setTimeout(r, 1500))

    // 截图 1: 左栏腾讯会议面板
    const sidebarScreenshotPath = path.join(SCREENSHOT_DIR, '01-tmeet-sidebar-panel.png')
    const sidebarArtifactPath = path.join(ARTIFACT_DIR, 'screenshots/01-tmeet-sidebar-panel.png')
    await page.screenshot({ path: sidebarScreenshotPath, fullPage: false })
    fs.copyFileSync(sidebarScreenshotPath, sidebarArtifactPath)
    console.log(`Saved screenshot 1: ${sidebarScreenshotPath}`)

    // 2. 点击「查看逐字稿」打开中栏标签
    console.log('Clicking "查看逐字稿"...')
    const clickResult = await page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('*'))
      const btn = all.find(el => (el.tagName === 'UNI-BUTTON' || el.tagName === 'BUTTON' || (typeof el.className === 'string' && el.className.includes('tmeet-action-btn'))) && el.textContent && el.textContent.includes('查看逐字稿'))
      if (btn) {
        btn.click()
        btn.dispatchEvent(new CustomEvent('tap', { bubbles: true, cancelable: true }))
        return { success: true, tag: btn.tagName, class: btn.className }
      }
      return { success: false, foundCount: all.length }
    })
    console.log('Click result:', clickResult)

    await new Promise(r => setTimeout(r, 1500))

    // 截图 2: 逐字稿时间轴标签页
    const transcriptScreenshotPath = path.join(SCREENSHOT_DIR, '02-tmeet-transcript-timeline.png')
    const transcriptArtifactPath = path.join(ARTIFACT_DIR, 'screenshots/02-tmeet-transcript-timeline.png')
    await page.screenshot({ path: transcriptScreenshotPath, fullPage: false })
    fs.copyFileSync(transcriptScreenshotPath, transcriptArtifactPath)
    console.log(`Saved screenshot 2: ${transcriptScreenshotPath}`)

    // 3. 点击「智能纪要」分段
    console.log('Clicking "智能纪要" section tab...')
    await page.evaluate(() => {
      const tabs = Array.from(document.querySelectorAll('.tmeet-tab-item, text, view'))
      const smartTab = tabs.find(el => el.textContent && el.textContent.includes('智能纪要'))
      if (smartTab) smartTab.click()
    })

    await new Promise(r => setTimeout(r, 1500))

    // 截图 3: 智能纪要视图
    const smartMinutesScreenshotPath = path.join(SCREENSHOT_DIR, '03-tmeet-smart-minutes.png')
    const smartMinutesArtifactPath = path.join(ARTIFACT_DIR, 'screenshots/03-tmeet-smart-minutes.png')
    await page.screenshot({ path: smartMinutesScreenshotPath, fullPage: false })
    fs.copyFileSync(smartMinutesScreenshotPath, smartMinutesArtifactPath)
    console.log(`Saved screenshot 3: ${smartMinutesScreenshotPath}`)

  } catch (err) {
    console.error('Screenshot error:', err)
  } finally {
    await browser.close()
    server.close()
    console.log('Preview server closed.')
  }
})
