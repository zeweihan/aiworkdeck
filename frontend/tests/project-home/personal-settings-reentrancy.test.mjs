// 审计（dev-board#74）：PersonalSettingsPanel 的四个「确认绑定/解绑」handler 没有
// 重入闸，连点两次会并发发两次请求——第一次成功、第二次因验证码一次性失效而 reject，
// 于是成功 toast 后面又叠一个失败 toast，用户以为绑定失败。
//
// 这份用例不做源码文本断言，直接把组件的 <script> 抽出来跑：把 import 换成桩，
// export default 换成 return，再用普通对象当 this 调 methods。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/userprofile/PersonalSettingsPanel.vue', import.meta.url), 'utf8')

function makeVm(deps, uniStub) {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  const importRe = /import\s*\{([\s\S]*?)\}\s*from\s*'[^']+'\s*;?/g
  const locals = []
  let m
  while ((m = importRe.exec(script)) !== null) {
    for (const part of m[1].split(',')) {
      const t = part.trim()
      if (!t) continue
      locals.push(/\sas\s/.test(t) ? t.split(/\s+as\s+/)[1].trim() : t)
    }
  }
  const preamble = locals
    .map((n) => `const ${n} = deps[${JSON.stringify(n)}] || (() => { throw new Error('未打桩: ${n}') });`)
    .join('\n')
  const body = script.replace(importRe, '').replace('export default', 'return')
  // eslint-disable-next-line no-new-func
  const component = new Function('deps', 'uni', preamble + '\n' + body)(deps, uniStub)
  const base = { $t: (k) => k }
  return Object.assign(base, component.data.call(base), component.methods)
}

// 一次性验证码：第一次成功，第二次必然失败
function onceOnly(okValue) {
  const state = { calls: 0 }
  state.fn = () => {
    state.calls++
    return state.calls === 1
      ? Promise.resolve(okValue)
      : Promise.reject(new Error('验证码已失效'))
  }
  return state
}

function setup(depsOverride) {
  const toasts = []
  const deps = Object.assign({ setSessionUser: () => {}, getAppLanguage: () => 'zh-CN' }, depsOverride)
  const vm = makeVm(deps, { showToast: (o) => toasts.push(o.title) })
  return { vm, toasts }
}

test('手机号：确认绑定连点两次只发一次请求，成功后不再弹失败', async () => {
  const api = onceOnly({ data: { phoneMasked: '138****0000' } })
  const { vm, toasts } = setup({ bindPhone: api.fn })
  vm.bindPhoneInput = '13800000000'
  vm.bindCodeInput = '123456'
  await Promise.all([vm.confirmBindPhone(), vm.confirmBindPhone()])
  assert.equal(api.calls, 1, '第二次点击必须被重入闸挡住')
  assert.deepEqual(toasts, ['account.bindSuccessToast'], '不该在成功 toast 之后再叠一个失败 toast')
})

test('邮箱：确认绑定连点两次只发一次请求，成功后不再弹失败', async () => {
  const api = onceOnly({ data: { emailMasked: 'a***@b.com' } })
  const { vm, toasts } = setup({ bindEmail: api.fn })
  vm.bindEmailInput = 'a@b.com'
  vm.bindEmailCodeInput = '123456'
  await Promise.all([vm.confirmBindEmail(), vm.confirmBindEmail()])
  assert.equal(api.calls, 1, '第二次点击必须被重入闸挡住')
  assert.deepEqual(toasts, ['account.bindSuccessToast'])
})

test('认证器：完成绑定连点两次只发一次请求', async () => {
  const api = onceOnly({ data: {} })
  const { vm, toasts } = setup({ totpActivate: api.fn })
  vm.totpCodeInput = '123456'
  await Promise.all([vm.confirmTotpBind(), vm.confirmTotpBind()])
  assert.equal(api.calls, 1, '第二次点击必须被重入闸挡住')
  assert.deepEqual(toasts, ['account.totpBoundSuccess'])
})

test('认证器：确认解绑连点两次只发一次请求', async () => {
  const api = onceOnly({ data: {} })
  const { vm, toasts } = setup({ totpDisable: api.fn })
  vm.totpCodeInput = '123456'
  await Promise.all([vm.confirmTotpDisable(), vm.confirmTotpDisable()])
  assert.equal(api.calls, 1, '第二次点击必须被重入闸挡住')
  assert.deepEqual(toasts, ['account.totpUnboundSuccess'])
})

test('请求失败后闸要放开，允许用户重试', async () => {
  let calls = 0
  const { vm } = setup({ bindPhone: () => { calls++; return Promise.reject(new Error('验证码错误')) } })
  vm.bindPhoneInput = '13800000000'
  vm.bindCodeInput = '123456'
  await vm.confirmBindPhone()
  await vm.confirmBindPhone()
  assert.equal(calls, 2, '失败后不放开闸，用户就再也点不动了')
})
