const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
let yaml
try {
  yaml = require('js-yaml')
} catch (e) {
  // 明确报出来而不是让它变成一句看不懂的 MODULE_NOT_FOUND；也绝不改成静默跳过——
  // 跳过等于把这道门禁变成摆设。js-yaml 是 electron-builder 的传递依赖，
  // package-lock.json 里有顶层条目，npm ci 之后必然在位。
  throw new Error('缺少 js-yaml：先在 desktop/ 下跑 npm ci')
}

// dev-board#74 稳定性审计：desktop-build.yml 的 build job 曾经在 matrix（每个
// 平台一台独立 runner）里直接发布 GitHub Release。strategy.fail-fast:false 下，
// 一条腿失败完全不妨碍另一条腿继续跑完，会独立调用 softprops/action-gh-release
// 把 release 发出去——mac 腿在公证抖动处失败时，windows 腿会把只有 .exe、
// 缺 .dmg 的半成品版本发布给用户。修复把发布步骤挪到一个 needs:[build] 的
// 独立 job（不加 if:always()，天然要求 build 的所有矩阵腿都成功才跑），
// 同构于 pack-release.yml 里 needs:[mac,win] 的 release job。
//
// 这里是纯静态检查：解析仓库里全部 workflow YAML，断言任何带 strategy.matrix
// 的 job 都不能再直接包含 action-gh-release 步骤——防止以后哪次改动又把发布
// 步骤挪回矩阵里而没人注意到。js-yaml 是 electron-builder 的既有传递依赖
// （已经在 desktop/package-lock.json 里锁定），这里直接 require，没有为此
// 新增任何 package.json 依赖。

const workflowsDir = path.join(__dirname, '../../.github/workflows')

function workflowFiles() {
  return fs.readdirSync(workflowsDir).filter((f) => f.endsWith('.yml') || f.endsWith('.yaml'))
}

function loadWorkflow(file) {
  return yaml.load(fs.readFileSync(path.join(workflowsDir, file), 'utf8'))
}

function stepsUseGhRelease(steps) {
  return (steps || []).some((s) => typeof s.uses === 'string' && s.uses.startsWith('softprops/action-gh-release'))
}

test('前提：workflows 目录真的存在且非空（防止路径写错导致下面的用例全部空跑通过）', () => {
  const files = workflowFiles()
  assert.ok(files.includes('desktop-build.yml'), 'desktop-build.yml 应该在 .github/workflows 下')
  assert.ok(files.length > 0)
})

test('任何带 strategy.matrix 的 job 都不能直接发布 GitHub Release', () => {
  const offenders = []
  for (const file of workflowFiles()) {
    const doc = loadWorkflow(file)
    const jobs = (doc && doc.jobs) || {}
    for (const [jobId, job] of Object.entries(jobs)) {
      const hasMatrix = Boolean(job && job.strategy && job.strategy.matrix)
      if (hasMatrix && stepsUseGhRelease(job.steps)) {
        offenders.push(`${file}:${jobId}`)
      }
    }
  }
  assert.deepStrictEqual(offenders, [], '这些 job 是 matrix 且直接发布 release，会重犯半成品发布的问题: ' + offenders.join(', '))
})

test('desktop-build.yml：发布已收口到独立 release job，且被 needs 正确门控', () => {
  const doc = loadWorkflow('desktop-build.yml')
  const buildJob = doc.jobs.build
  assert.ok(Boolean(buildJob.strategy && buildJob.strategy.matrix), 'build 应该仍然是 matrix job（本用例的前提）')
  assert.ok(!stepsUseGhRelease(buildJob.steps), 'build（matrix job）不应该再包含 action-gh-release 步骤')

  const releaseJob = doc.jobs.release
  assert.ok(releaseJob, '应该存在一个独立的 release job')
  assert.ok(!(releaseJob.strategy && releaseJob.strategy.matrix), 'release job 本身不应该是 matrix')
  assert.ok(stepsUseGhRelease(releaseJob.steps), 'release job 应该包含 action-gh-release 步骤')

  const needs = Array.isArray(releaseJob.needs) ? releaseJob.needs : [releaseJob.needs]
  assert.ok(needs.includes('build'), 'release job 必须 needs: build——默认语义下 build 任一矩阵腿失败它就不会跑')

  // 不能用 if: always() 之类的条件放行失败腿——否则又把「必须全部成功」这道
  // 门禁架空，等于走了一遍手续但没有实际效果。
  const ifCond = String(releaseJob.if || '')
  assert.ok(!/always\s*\(\s*\)/.test(ifCond), 'release job 的 if 条件不应该用 always() 绕开 needs 的全部成功前提')
})

test('desktop-build.yml：镜像同步必须排在 release 之后（它是从 GitHub Release 拉资产的）', () => {
  const doc = loadWorkflow('desktop-build.yml')
  const sync = doc.jobs['sync-mirror']
  assert.ok(sync, '应该存在 sync-mirror job')
  const needs = Array.isArray(sync.needs) ? sync.needs : [sync.needs]
  // 发布从 matrix 里挪走之后，sync-mirror 若仍只 needs:[build] 就会与 release
  // 并行：服务器脚本从 GitHub Release 拉资产，Release 还没建出来就拉空，
  // latest.json 停在上一版，本 job 末尾的校验必挂。
  assert.ok(needs.includes('release'),
    'sync-mirror 必须 needs: release，否则会和发布并行、拉不到 Release 资产')
})
