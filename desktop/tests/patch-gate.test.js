// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const gateScript = path.resolve(__dirname, '../scripts/patch-gate.sh').replace(/\\/g, '/')

function fixture(t) {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-patch-gate-'))
  t.after(() => fs.rmSync(cwd, { recursive: true, force: true }))
  const git = (...args) => {
    const result = spawnSync('git', ['-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', ...args], { cwd, encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr || result.error?.message)
  }
  const write = (file, content) => {
    const target = path.join(cwd, file)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, content)
  }
  const commit = (tag) => {
    git('add', '.')
    git('commit', '-qm', tag)
    git('-c', 'tag.gpgsign=false', 'tag', tag)
  }
  git('init', '-q')
  git('config', 'user.name', 'Patch Gate Test')
  git('config', 'user.email', 'patch-gate@example.invalid')
  write('backend/skills/legal-opinion-review/prompt.md', '原规则\n')
  write('backend/skills/legal-opinion-review/prompt.en.md', 'Original rule\n')
  write('backend/src/main/java/example/OpinionCompletionCheck.java', 'class OpinionCompletionCheck {}\n')
  commit('v0.51.3')
  return {
    write,
    commit,
    run: (tag) => spawnSync('bash', [gateScript, tag], { cwd, encoding: 'utf8' })
  }
}

for (const prompt of ['prompt.md', 'prompt.en.md']) {
  test(`补丁版拒绝内置 skill 的 ${prompt} 变更`, (t) => {
    const repo = fixture(t)
    repo.write(`backend/skills/legal-opinion-review/${prompt}`, 'Updated evidence rule\n')
    repo.commit('v0.51.4')
    const result = repo.run('v0.51.4')
    assert.equal(result.status, 1, result.stdout + result.stderr)
    assert.match(result.stdout, /backend\/skills/)
    assert.match(result.stdout, /0\.52\.0/)
  })
}

test('全量版允许内置 skill 变更', (t) => {
  const repo = fixture(t)
  repo.write('backend/skills/legal-opinion-review/prompt.md', '新规则\n')
  repo.commit('v0.52.0')
  const result = repo.run('v0.52.0')
  assert.equal(result.status, 0, result.stdout + result.stderr)
})

test('补丁版仍允许业务 Java 代码变更', (t) => {
  const repo = fixture(t)
  repo.write('backend/src/main/java/example/OpinionCompletionCheck.java', 'class OpinionCompletionCheck { boolean bounded = true; }\n')
  repo.commit('v0.51.4')
  const result = repo.run('v0.51.4')
  assert.equal(result.status, 0, result.stdout + result.stderr)
  assert.match(result.stdout, /通过/)
})
