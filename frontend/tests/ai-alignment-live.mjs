// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real-provider acceptance; credentials are configured only in the isolated backend process.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'

const base = process.env.AI_ALIGNMENT_BACKEND || 'http://127.0.0.1:9797'
assert.equal(new URL(base).hostname, '127.0.0.1', 'Use the isolated local backend')
assert.equal(process.env.AI_ALIGNMENT_LIVE, '1', 'Explicit live-test opt-in is required')
const reportPath = process.env.AI_ALIGNMENT_REPORT || '/tmp/ai-alignment-live-result.json'
const report = { startedAt: new Date().toISOString(), model: 'deepseek/deepseek-v4-flash', checks: [], conversations: [] }
const connections = []
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function api(endpoint, method = 'GET', body) {
  const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const data = await response.json()
  assert.ok(response.ok, `${method} ${endpoint}: ${response.status} ${JSON.stringify(data)}`)
  return data
}
async function until(fn, timeout = 180000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const result = await fn(); if (result) return result; await sleep(350) }
  throw new Error('Timed out waiting for the live model acceptance condition')
}
let projectId
async function conversation() {
  const { conversationId: id } = await api('/api/agent/conversations', 'POST', { projectId })
  const controller = new AbortController()
  const events = []
  const response = await fetch(`${base}/api/agent/connect/${id}`, { signal: controller.signal })
  assert.ok(response.ok)
  const reader = response.body.getReader()
  const loop = (async () => {
    let pending = ''
    const decoder = new TextDecoder()
    try {
      for (;;) {
        const { value, done } = await reader.read(); if (done) break
        pending += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let boundary
        while ((boundary = pending.indexOf('\n\n')) >= 0) {
          const block = pending.slice(0, boundary); pending = pending.slice(boundary + 2)
          const type = block.match(/^event:\s*(.+)$/m)?.[1]
          const raw = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
          if (type && raw) { try { events.push({ type, data: JSON.parse(raw) }) } catch { events.push({ type, data: raw }) } }
        }
      }
    } catch (error) { if (error.name !== 'AbortError') throw error }
  })()
  connections.push({ controller, loop })
  report.conversations.push({ id, events })
  return { id, events }
}
function submit(conv, message, submissionMode = 'steer', requestId = crypto.randomUUID(), mode = 'AGENT') {
  return api('/api/agent/chat', 'POST', { projectId, conversationId: conv.id, message, model: report.model,
    mode, clientCapability: 'none', submissionMode, clientRequestId: requestId })
}
const history = conv => api(`/api/ai/history?conversationId=${conv.id}`)
const inbox = conv => api(`/api/agent/inbox/${conv.id}`)
async function finished(conv) {
  return until(async () => {
    const state = await inbox(conv)
    if (['ERROR', 'PAUSED', 'AWAITING_APPROVAL', 'AWAITING_INPUT', 'CANCELLED', 'INTERRUPTED'].includes(state.status))
      throw new Error(`Unexpected terminal state: ${JSON.stringify(state)}`)
    return state.status === 'FINISHED' && !state.items.some(item => item.state === 'pending') ? state : null
  })
}
function pass(name) { report.checks.push(name); console.log('PASS', name) }
try {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-alignment-live-project-'))
  const opened = await api('/api/projects/open-local', 'POST', { localRoot: root, createFolder: false, name: 'AI交互真实模型验收' })
  projectId = opened.data.projectId
  report.projectId = projectId
  const spaces = (await api(`/api/ai/memory/spaces?projectId=${projectId}`)).data
  const personal = spaces.find(space => space.scope === 'user')
  const marker = `青鹭-${crypto.randomBytes(3).toString('hex')}`
  const memoryPath = `live-preference-${Date.now()}.md`
  const first = await conversation()
  await submit(first, `这是隔离测试，请实际调用记忆工具，把个人偏好写入 ${memoryPath}：以后本测试项目的报告代号为“${marker}”。请用 memory_write(scope="user", path="${memoryPath}", expectedRevision=0) 创建。写入后读取核实，再简短回复。`)
  await finished(first)
  const saved = (await api(`/api/ai/memory/file?${new URLSearchParams({ spaceId: personal.id, path: memoryPath })}`)).data
  assert.ok(saved.content.includes(marker), 'Model must persist exact preference in canonical Markdown')
  pass('Real model writes and reads personal Markdown memory')

  const second = await conversation()
  await submit(second, `请查阅个人记忆 ${memoryPath}，告诉我设定的测试报告代号。只输出代号。`, 'steer', crypto.randomUUID(), 'ASK')
  await finished(second)
  assert.ok((await history(second)).some(row => row.role === 'ASSISTANT' && row.content.includes(marker)))
  pass('New ASK conversation retrieves the saved preference through read-only memory')

  const work = await conversation()
  await submit(work, '先查阅个人和项目记忆，然后列出一份详细的十项报告检查清单，每项解释检查步骤。不要写文件。')
  await until(async () => (await inbox(work)).status === 'RUNNING')
  const q1 = await submit(work, '当前任务结束后，只回复“队列原稿”。', 'queue')
  const q2 = await submit(work, '当前任务结束后，只回复“稍后删除”。', 'queue')
  assert.equal(q1.state, 'pending'); assert.equal(q2.state, 'pending')
  let state = await inbox(work)
  let item = state.items.find(row => row.id === q2.messageId)
  await api(`/api/agent/inbox/${work.id}/${item.id}`, 'PATCH', { position: 0, expectedRevision: item.revision })
  state = await inbox(work)
  assert.equal(state.items.filter(row => row.state === 'pending')[0].id, q2.messageId)
  item = state.items.find(row => row.id === q1.messageId)
  await api(`/api/agent/inbox/${work.id}/${item.id}`, 'PATCH', { message: '当前任务结束后，只回复“队列修订成功”。', expectedRevision: item.revision })
  item = (await inbox(work)).items.find(row => row.id === q2.messageId)
  await api(`/api/agent/inbox/${work.id}/${item.id}?expectedRevision=${item.revision}`, 'DELETE')
  const key = crypto.randomUUID()
  const direction = '调整方向：保留报告检查的目标，把清单缩成三项，末尾写“方向调整成功”。'
  const steer = await submit(work, direction, 'steer', key)
  const duplicate = await submit(work, direction, 'steer', key)
  assert.equal(duplicate.messageId, steer.messageId)
  await finished(work)
  const rows = await history(work)
  assert.equal(rows.filter(row => row.role === 'USER' && row.content.includes(direction)).length, 1)
  assert.ok(rows.some(row => row.role === 'ASSISTANT' && row.content.includes('方向调整成功')))
  assert.ok(rows.some(row => row.role === 'ASSISTANT' && row.content.includes('队列修订成功')))
  assert.ok(!rows.some(row => row.role === 'USER' && row.content.includes('稍后删除')))
  pass('Real run accepts steering once, preserves edited/reordered queue and drains it')

  const stopped = await conversation()
  await submit(stopped, '请写一份非常详细的二十项资料整理工作清单，每项说明具体操作。')
  await until(async () => (await inbox(stopped)).status === 'RUNNING')
  const queued = await submit(stopped, '只回复“停止后恢复成功”。', 'queue')
  await api(`/api/agent/cancel/${stopped.id}`, 'POST')
  await until(async () => ['CANCELLED', 'PAUSED'].includes((await inbox(stopped)).status))
  item = (await inbox(stopped)).items.find(row => row.id === queued.messageId)
  assert.equal(item.state, 'pending')
  await api(`/api/agent/inbox/${stopped.id}/${item.id}`, 'PATCH', { submissionMode: 'steer', expectedRevision: item.revision })
  await finished(stopped)
  assert.ok((await history(stopped)).some(row => row.role === 'ASSISTANT' && row.content.includes('停止后恢复成功')))
  pass('Stop pauses pending queue; explicit send-now starts it once')
  report.status = 'passed'
} catch (error) { report.status = 'failed'; report.error = error.stack; process.exitCode = 1; console.error(error.message) }
finally {
  for (const connection of connections) connection.controller.abort()
  await Promise.allSettled(connections.map(connection => connection.loop))
  report.finishedAt = new Date().toISOString()
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2))
  console.log('Acceptance evidence:', reportPath)
}
