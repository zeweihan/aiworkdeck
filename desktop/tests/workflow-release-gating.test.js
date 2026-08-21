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

// dev-board#74 稳定性审计：pysvc 缓存的 key 只由 requirements.lock 与打包脚本
// 组成，唯独漏了各服务自己的源码（prepare-python-service.js 的 --src 会把它
// cpSync 进被缓存的 pysvc/ 目录）。只改 pptx-service/backend、kokoro-service、
// asr-service 的源码而不动 lock 时 key 不变，缓存命中就把「Bundle X-service」
// 整步跳过，装机包里带的还是上一次构建的旧源码——构建全绿、冒烟也过（跑的是
// 旧代码），tag 构建会把缺了刚合并那笔改动的安装包发出去且毫无告警。
test('desktop-build.yml：pysvc 缓存 key 必须覆盖各服务源码，否则改源码会命中旧缓存发出旧代码', () => {
  const doc = loadWorkflow('desktop-build.yml')
  const steps = doc.jobs.build.steps
  const cacheSteps = steps.filter((s) =>
    typeof s.uses === 'string' && s.uses.startsWith('actions/cache@') &&
    String((s.with && s.with.path) || '').includes('/pysvc'))
  assert.ok(cacheSteps.length > 0, '应该存在缓存 pysvc 目录的步骤（本用例的前提）')

  for (const cache of cacheSteps) {
    const key = String(cache.with.key)
    // 被这条缓存的 cache-hit 门控、且带 --src（有自有源码）的打包步骤
    const gated = steps.filter((s) =>
      String(s.if || '').includes(`steps.${cache.id}.outputs.cache-hit`) &&
      /--src\s/.test(String(s.run || '')))
    assert.ok(gated.length > 0, `${cache.id} 应该门控着若干带 --src 的打包步骤（本用例的前提）`)
    for (const s of gated) {
      const src = String(s.run).match(/--src\s+(\S+)/)[1]
      assert.ok(key.includes(`'${src}/**'`),
        `缓存 ${cache.id} 的 key 没覆盖 ${src}：只改这个服务的源码 key 不变，` +
        `缓存命中会把打包步骤整步跳过，装机包里还是旧源码。key=${key}`)
    }
  }
})

// v0.23.0 发版实测：sync-mirror job 转红、官网下载页停在 0.22.0 直到人工介入。
// 时间线——CI 16:15 开始推安装包，服务器 cron 16:17 起来（那一刻 exe 还没推完，
// 它的 skip-exists 判据当时确实成立不了），抢到 flock 后从 GitHub 慢拉 1.59GB；
// CI 推完回头等锁，1200s 超时，update-mirror-sync.sh 从没跑过，latest.json 停在
// 上一版，最后那条校验 exit 1。v0.17.0 已经踩过一次同款（当时的处置只是加了
// CI 侧的 flock 等待，没挡住「推送途中新起的 cron」）。
//
// 治法是让路发生在**抢锁之前**：推送前落 marker，cron 见到新鲜 marker 就退出。
// 下面三条各钉住这条链的一环，任何一环被摘掉都会让那个窗口重新打开。
test('desktop-build.yml：推安装包之前必须落 release-push marker，否则途中起来的 cron 会抢锁慢拉', () => {
  const doc = loadWorkflow('desktop-build.yml')
  const step = doc.jobs['sync-mirror'].steps.find((s) => /rsync/.test(String(s.run || '')))
  assert.ok(step, 'sync-mirror 里应该有那个 rsync 推送步骤（本用例的前提）')
  const run = String(step.run)
  const markerAt = run.indexOf('touch /var/lock/awd-release-push-in-progress')
  const rsyncAt = run.indexOf('rsync ')
  assert.ok(markerAt !== -1, '推送步骤里没有落 marker：cron 会在推送途中抢锁重拉同一批包')
  assert.ok(markerAt < rsyncAt,
    'marker 必须落在 rsync 之前——落在后面等于把那 20 多分钟的窗口原样留着')
})

test('desktop-build.yml：CI 回头跑同步脚本必须带 FORCE=1，否则被自己刚落的 marker 拦下', () => {
  const doc = loadWorkflow('desktop-build.yml')
  const step = doc.jobs['sync-mirror'].steps.find((s) => /update-mirror-sync\.sh/.test(String(s.run || '')))
  assert.ok(step, '应该有调用 update-mirror-sync.sh 的步骤（本用例的前提）')
  assert.match(String(step.run), /FORCE=1\s+bash update-mirror-sync\.sh/,
    'CI 自己调用同步脚本时没带 FORCE=1：会被自己刚落下的 marker 挡住，latest.json 永远不翻')
})

test('desktop-build.yml：marker 必须有 always() 的清理步骤，否则失败一次会让 cron 白让路', () => {
  const doc = loadWorkflow('desktop-build.yml')
  const cleanup = doc.jobs['sync-mirror'].steps.find((s) =>
    /rm -f \/var\/lock\/awd-release-push-in-progress/.test(String(s.run || '')))
  assert.ok(cleanup, '没有清理 marker 的步骤：推送失败后 marker 残留，接下来的 cron 全部让路')
  assert.equal(String(cleanup.if || '').trim(), 'always()',
    '清理步骤必须 if: always()——只在成功时清等于「失败那次留下的残留最坏」')
})
