// 后端复用版本闸（dev-board#139 根因之一）：更新后的应用把 8-21 残留的陈旧后端
// 当自家后端直接复用，新前端打旧后端 404 且毫无提示。闸的裁决必须满足：
//   陌生进程 skip / 同指纹自家后端 reuse / 打包态指纹不一致 replace / dev 态不看指纹。
// 把 decideReuse 退回「只认 initialized 就复用」（旧行为）时，第 3、4 条会转红。
const test = require('node:test')
const assert = require('node:assert/strict')
const http = require('http')

const { decideReuse, probeBackend } = require('../main/services/backend-service')

test('decideReuse：陌生进程 skip（打包/dev 一致）', () => {
  assert.equal(decideReuse({ ours: false, build: '' }, { packaged: true, expectedBuild: 'a' }), 'skip')
  assert.equal(decideReuse({ ours: false, build: 'a' }, { packaged: false, expectedBuild: '' }), 'skip')
})

test('decideReuse：打包态同指纹复用，指纹不一致要求替换', () => {
  assert.equal(decideReuse({ ours: true, build: '100-1' }, { packaged: true, expectedBuild: '100-1' }), 'reuse')
  // 陈旧残留后端（更新前的 jar 指纹）——不许再被静默复用
  assert.equal(decideReuse({ ours: true, build: '100-1' }, { packaged: true, expectedBuild: '200-2' }), 'replace')
  // 旧版后端连 build 字段都没有（空串）——同样视为陈旧
  assert.equal(decideReuse({ ours: true, build: '' }, { packaged: true, expectedBuild: '200-2' }), 'replace')
})

test('decideReuse：算不出期望指纹时不拦（新装/异常兜底），dev 态不看指纹', () => {
  assert.equal(decideReuse({ ours: true, build: 'anything' }, { packaged: true, expectedBuild: '' }), 'reuse')
  // dev 态后端由 restart-backend.sh 等外部流程拉起，没有注入指纹，不能被闸误杀
  assert.equal(decideReuse({ ours: true, build: '' }, { packaged: false, expectedBuild: '999-9' }), 'reuse')
})

test('probeBackend：解析 wizard 响应的 initialized 特征与 build 指纹', async () => {
  const srv = http.createServer((req, res) => {
    if (req.url === '/api/admin/wizard') {
      res.setHeader('content-type', 'application/json')
      res.end(JSON.stringify({ code: 0, initialized: true, build: '4242-1724480000000' }))
    } else {
      res.statusCode = 404
      res.end()
    }
  })
  await new Promise((r) => srv.listen(0, '127.0.0.1', r))
  const port = srv.address().port
  try {
    const probe = await probeBackend(port)
    assert.deepEqual(probe, { ours: true, build: '4242-1724480000000' })
  } finally {
    srv.close()
  }
})

test('probeBackend：旧后端无 build 字段 → 空串；陌生响应 → ours=false', async () => {
  const srv = http.createServer((req, res) => {
    if (req.url === '/api/admin/wizard') {
      res.end(JSON.stringify({ code: 0, initialized: false }))
    } else {
      res.end('hello')
    }
  })
  await new Promise((r) => srv.listen(0, '127.0.0.1', r))
  const port = srv.address().port
  try {
    const probe = await probeBackend(port)
    assert.equal(probe.ours, true)
    assert.equal(probe.build, '')
  } finally {
    srv.close()
  }

  const stranger = http.createServer((req, res) => { res.end('not a backend') })
  await new Promise((r) => stranger.listen(0, '127.0.0.1', r))
  try {
    const probe = await probeBackend(stranger.address().port)
    assert.equal(probe.ours, false)
  } finally {
    stranger.close()
  }
})
