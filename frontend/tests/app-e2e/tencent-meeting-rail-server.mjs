#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real workbench + in-memory API fixture. No real backend, database, account or CLI.
// Run: node frontend/tests/app-e2e/tencent-meeting-rail-server.mjs
// UI must be exercised through normal user interactions; this fixture never emits
// market events or invokes Vue instance methods. See the companion README.
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(here, '../..')
const repo = path.dirname(frontend)
const pluginWeb = path.join(repo, 'official-plugins/tencent-meeting/web')
if (!fs.existsSync(path.join(pluginWeb, 'index.html'))) throw new Error('Tencent Meeting plugin web source is missing from this checkout. Update the checkout before starting this fixture.')
const port = Number(process.env.TMEET_RAIL_PORT || 19142)
const vitePort = Number(process.env.TMEET_RAIL_VITE_PORT || 19143)
const origin = `http://127.0.0.1:${port}`
const project = { id: 1140, name: '合成测试 · 腾讯会议侧栏回归', projectType: 'BLANK', ownerId: 1140, status: 'IN_PROGRESS', permission: 'OWNER' }
const user = { id: 1140, username: 'synthetic_rail_user', displayName: '合成测试用户', role: 'ADMIN' }
const plugin = {
  id: 'tencent-meeting', name: '腾讯会议', description: '仅供合成 UI 验收：会议列表、逐字稿与智能纪要。',
  version: '1.0.0', author: 'AI WorkDeck synthetic fixture', frontendEntry: 'web/index.html',
  permissions: ['file_read', 'file_write', 'network'], tools: ['tencent_meeting_action'], toolCount: 1,
  marketInstalled: true, installedFromMarket: true, languages: ['zh-CN', 'en-US'], priceCents: 0
}
let state = { installed: true, enabled: false, revokedReason: null, incompatibleReason: null }
let folderCreated = false
const requests = []
const unhandled = new Set()
function reset(mode) {
  state = { installed: mode !== 'uninstalled', enabled: mode === 'enabled' || mode === 'revoked' || mode === 'incompatible', revokedReason: mode === 'revoked' ? 'Synthetic revoked fixture' : null, incompatibleReason: mode === 'incompatible' ? 'Synthetic incompatible fixture' : null }
  folderCreated = false; requests.length = 0; unhandled.clear()
}
const json = (res, body, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(body)) }
const ok = (data = {}) => ({ code: 0, data })
async function body(req) {
  let text = ''; for await (const part of req) text += part
  return text ? JSON.parse(text) : {}
}
function skillList() {
  return state.installed && state.enabled ? [{ id: 'tencent-meeting-market', name: '腾讯会议下载版技能', sourcePluginId: 'tencent-meeting', enabled: true, available: true, activationMode: 'auto', languages: ['zh-CN', 'en-US'] }] : []
}
async function api(req, res, url) {
  const route = url.pathname, method = req.method
  requests.push({ sequence: requests.length + 1, method, path: route, installed: state.installed, enabled: state.enabled })
  if (route === '/api/plugins/list') return json(res, state.installed ? [{ ...plugin, ...state }] : [])
  if (route === '/api/plugins/market/list') return json(res, { code: 0, plugins: [{ ...plugin, installed: state.installed }], accountConnected: false })
  if (route === '/api/plugins/market/install' && method === 'POST') {
    const data = await body(req); if (data.id !== plugin.id) return json(res, { code: 1, message: 'Unknown fixture plugin' })
    state.installed = true; state.enabled = false; return json(res, { code: 0, pluginId: plugin.id, enabled: false })
  }
  if (route === '/api/plugins/market/uninstall' && method === 'POST') { state.installed = false; state.enabled = false; return json(res, ok()) }
  if (/^\/api\/plugins\/tencent-meeting\/(enable|disable)$/.test(route) && method === 'POST') {
    state.enabled = route.endsWith('/enable'); return json(res, { code: 0, enabled: state.enabled })
  }
  if (route === '/api/plugins/rescan') return json(res, { code: 0, pluginCount: state.installed ? 1 : 0 })
  if (route === '/api/skills/rescan') return json(res, { code: 0, skillCount: skillList().length })
  if (route === '/api/skills/list') return json(res, skillList())
  if (route === '/api/skills/market/list') return json(res, { code: 0, skills: [], accountConnected: false })
  if (route === '/api/plugins/tencent-meeting/settings') return json(res, { code: 0, settings: [] })
  if (route === '/api/plugins/contributed/style-profiles') return json(res, { code: 0, profiles: [] })
  if (route === '/api/plugins/contributed/templates') return json(res, { code: 0, templates: [] })
  if (route.startsWith('/api/plugin-web/tencent-meeting/')) {
    if (!state.installed || !state.enabled) return json(res, { code: 1, message: 'Fixture plugin disabled' }, 404)
    const name = path.basename(route)
    if (!['index.html', 'panel.js', 'panel.css', 'awd-plugin-sdk.js'].includes(name)) return json(res, {}, 404)
    const file = path.join(pluginWeb, name)
    if (!fs.existsSync(file)) return json(res, { message: 'Plugin fixture resource missing: ' + name }, 404)
    const type = name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html'
    const bytes = fs.readFileSync(file)
    res.writeHead(200, { 'Content-Type': type + '; charset=utf-8' }); return res.end(bytes)
  }
  if (route === '/api/plugins/tencent-meeting/tools/tencent_meeting_action') {
    const input = await body(req)
    const fixture = { status: { cliAvailable: true, loggedIn: false }, config: { lookbackDays: 30, excludeKeywords: [] }, list: { meetings: [] } }
    const data = fixture[input.args?.action]
    return json(res, { code: 0, output: JSON.stringify(data ? { success: true, data } : { success: false, error: 'This rail fixture does not run the CLI' }) })
  }
  if (route === '/api/auth/me') return json(res, ok(user))
  if (route === '/api/projects/my') return json(res, [project])
  if (route === '/api/projects/1140') return json(res, project)
  if (route === '/api/projects/1140/members') return json(res, ok([{ ...user, userId: user.id, projectRole: 'OWNER' }]))
  const folder = { id: 94000, name: '暂存区', isFolder: true, parentId: null, projectId: 1140 }
  if (route === '/api/projects/1140/files') return json(res, folderCreated && !url.searchParams.get('parentId') ? [folder] : [])
  if (route === '/api/projects/1140/files/folder' && method === 'POST') { folderCreated = true; return json(res, folder) }
  if (route === '/api/license/status') return json(res, { mode: 'none', accountConnected: false, unlocked: false, localMode: true })
  if (route === '/api/account/status') return json(res, { connected: false })
  if (route === '/api/account/balance') return json(res, { balance: 0, connected: false })
  if (route === '/api/entitlements') return json(res, { features: [] })
  if (route === '/api/local-identity/status') return json(res, { needsSelection: false })
  if (route === '/api/ai/config') return json(res, { code: 0, activeProvider: 'LOCAL', data: { activeProvider: 'LOCAL', models: [] }, models: [] })
  if (route === '/api/platform/services') return json(res, { services: [] })
  if (route === '/api/site') return json(res, { current: 'synthetic', sites: [] })
  if (route === '/api/app/language') return json(res, { code: 0, language: 'zh-CN' })
  if (route === '/api/cloud/projects/1140/status') return json(res, ok({ linked: false }))
  if (route.includes('/version/status')) return json(res, ok({ enabled: false, initialized: false }))
  if (route.endsWith('/adopt-conflict')) return json(res, ok({ conflict: false }))
  if (route === '/api/calendar') return json(res, ok({ tasks: [] }))
  if (route === '/api/calendar/summary') return json(res, ok({ overdue: 0, today: 0, week: 0, nextDue: null }))
  if (route.endsWith('/overview-stats')) return json(res, { fileCount: 0, taskCount: 0, conversationCount: 0 })
  if (route.endsWith('/task-summary')) return json(res, { total: 0, overdue: 0, pending: 0 })
  if (method === 'GET' && /(?:files|tasks|conversations|favorites|variables|tags|members|history|list|packs|spaces|candidates)(?:\/my)?$/.test(route)) return json(res, [])
  if (route.includes('/telemetry') || route.includes('/activity') || route.includes('/preferences')) return json(res, ok())
  unhandled.add(method + ' ' + route)
  // Nonessential read-only surfaces are empty fixtures; unknown writes fail closed.
  return json(res, method === 'GET' ? ok([]) : { code: 1, message: 'Unsupported synthetic fixture mutation' })
}
const bootstrap = `<script>window.checkbaDesktop={apiBaseUrl:${JSON.stringify(origin)},shell:{openExternal:async()=>({ok:false})}};localStorage.setItem('awd_app_language','zh-CN');</script>`
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, origin)
  try {
    if (url.pathname === '/__fixture/state') return json(res, { ...state, requests, unhandled: [...unhandled] })
    if (url.pathname === '/__fixture/reset' && req.method === 'POST') { reset(url.searchParams.get('mode') || 'disabled'); return json(res, state) }
    if (url.pathname.startsWith('/api/')) return await api(req, res, url)
    if (url.pathname === '/' && url.searchParams.has('fixture')) reset(url.searchParams.get('fixture'))
    const upstream = http.request({ hostname: '127.0.0.1', port: vitePort, path: req.url, method: req.method, headers: { ...req.headers, host: `127.0.0.1:${vitePort}`, 'accept-encoding': 'identity' } }, (response) => {
      const headers = { ...response.headers }
      delete headers['content-length']; delete headers['content-encoding']
      headers['content-security-policy'] = `default-src 'self' blob: data:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws://127.0.0.1:${vitePort} http://127.0.0.1:${vitePort}; frame-src 'self'`
      if ((headers['content-type'] || '').includes('text/html')) {
        let content = ''; response.setEncoding('utf8'); response.on('data', (part) => { content += part })
        response.on('end', () => { res.writeHead(response.statusCode, headers); res.end(content.replace('<head>', '<head>' + bootstrap)) })
      } else { res.writeHead(response.statusCode, headers); response.pipe(res) }
    })
    upstream.on('error', () => json(res, { message: 'Waiting for isolated Vite server' }, 503)); req.pipe(upstream)
  } catch (error) { json(res, { message: String(error.message) }, 500) }
})
const uni = path.join(frontend, 'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js')
if (!fs.existsSync(uni)) throw new Error('Frontend dependencies missing. Reuse the existing frontend node_modules before running this fixture.')
server.listen(port, '127.0.0.1', () => {
  console.log(`Workbench: ${origin}/?fixture=disabled#/pages/project-overview/project-overview?id=1140`)
  console.log(`API evidence: ${origin}/__fixture/state`)
})
const vite = spawn(process.execPath, [uni, '--host', '127.0.0.1', '--port', String(vitePort)], { cwd: frontend, env: { ...process.env, VITE_API_BASE_URL: origin }, stdio: 'inherit' })
function close() { vite.kill('SIGTERM'); server.close(); }
process.on('SIGINT', close); process.on('SIGTERM', close)
vite.on('exit', () => server.close())
