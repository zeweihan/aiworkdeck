// 静态护栏自身的回归测试：属性里出现裸 '>' 时，护栏不许把后面的 @event 漏掉。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const SCRIPT = resolve(dirname(fileURLToPath(import.meta.url)), '../../scripts/check-emit-bindings.mjs')

// 造一棵最小 src/：一个不 emit 'some-event' 的子组件 + 一个绑了它的父组件
function makeFixture(parentTemplate) {
  const root = mkdtempSync(join(tmpdir(), 'check-emits-'))
  mkdirSync(join(root, 'components'), { recursive: true })
  // 脚本尾部的反馈浮窗护栏要求这个文件存在
  writeFileSync(join(root, 'components/FeedbackWidget.vue'), '<template><div class="awdfb-mask"></div></template>\n')
  writeFileSync(join(root, 'components/ChildComp.vue'), '<script setup>\nconst emit = defineEmits([\'other-event\'])\n</script>\n')
  writeFileSync(
    join(root, 'components/ParentComp.vue'),
    `<template>\n${parentTemplate}\n</template>\n<script setup>\nimport ChildComp from './ChildComp.vue'\n</script>\n`,
  )
  return root
}

function run(root) {
  return spawnSync(process.execPath, [SCRIPT], {
    encoding: 'utf8',
    env: { ...process.env, CHECK_EMITS_SRC: root },
  })
}

test('属性值里含裸 > 时，后面的死绑定仍要被抓出来', () => {
  const root = makeFixture('  <ChildComp :disabled="count > 5" @some-event="handler" />')
  try {
    const r = run(root)
    assert.equal(r.status, 1, `期望检查失败，实际 stdout=${r.stdout} stderr=${r.stderr}`)
    assert.match(r.stderr, /@some-event/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('没有裸 > 的死绑定照旧能被抓出来', () => {
  const root = makeFixture('  <ChildComp @some-event="handler" />')
  try {
    const r = run(root)
    assert.equal(r.status, 1, `期望检查失败，实际 stdout=${r.stdout} stderr=${r.stderr}`)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('子组件确实 emit 的绑定不报错', () => {
  const root = makeFixture('  <ChildComp :disabled="count > 5" @other-event="handler" />')
  try {
    const r = run(root)
    assert.equal(r.status, 0, `期望检查通过，实际 stderr=${r.stderr}`)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
