const test = require('node:test')
const assert = require('node:assert')
const { createBrowserViewRegistry } = require('../main/browser-views')

function harness() {
  const onWindow = new Set()
  const win = {
    addBrowserView: (v) => { if (onWindow.has(v)) throw new Error('already added'); onWindow.add(v) },
    removeBrowserView: (v) => { onWindow.delete(v) },
  }
  const made = []
  const registry = createBrowserViewRegistry({
    createView: (id) => {
      const view = {
        id,
        destroyed: false,
        bounds: null,
        setBounds: (b) => { view.bounds = b },
        setAutoResize: () => {},
        webContents: { destroy: () => { view.destroyed = true } },
      }
      made.push(view)
      return view
    },
    getWindow: () => win,
  })
  const shownIds = () => [...onWindow].map((v) => v.id).sort()
  return { registry, made, shownIds }
}

test('同一个标签第二次 ensure 复用旧 view（切回来不重新加载页面）', () => {
  const { registry, made } = harness()
  const first = registry.ensure('tab1')
  assert.strictEqual(first.created, true)
  const again = registry.ensure('tab1')
  assert.strictEqual(again.created, false)
  assert.strictEqual(again.view, first.view)
  assert.strictEqual(made.length, 1)
})

test('detach 只是从窗口摘下，view 还活着；destroy 才真的销毁', () => {
  const { registry, shownIds } = harness()
  const { view } = registry.ensure('tab1')
  registry.attach('tab1')
  assert.deepStrictEqual(shownIds(), ['tab1'])

  registry.detach('tab1')
  assert.deepStrictEqual(shownIds(), [])
  assert.strictEqual(registry.has('tab1'), true, 'detach 不该把 view 从注册表里删掉')
  assert.strictEqual(view.destroyed, false, 'detach 不该销毁 webContents——页面状态就靠它留着')
  // 切回来：复用同一个 view
  assert.strictEqual(registry.ensure('tab1').created, false)

  registry.destroy('tab1')
  assert.strictEqual(registry.has('tab1'), false)
  assert.strictEqual(view.destroyed, true)
})

test('全局隐藏再恢复：只挂回正在显示的标签，后台保活的不许冒出来', () => {
  const { registry, shownIds } = harness()
  for (const id of ['front', 'background']) registry.ensure(id)
  registry.attach('front')
  registry.attach('background')
  registry.detach('background') // 用户切到了 front 标签，background 保活但不显示
  assert.deepStrictEqual(shownIds(), ['front'])

  registry.setAllVisible(false) // 弹窗/截图框选/离开工作台
  assert.deepStrictEqual(shownIds(), [])

  registry.setAllVisible(true)
  assert.deepStrictEqual(shownIds(), ['front'], '恢复后只该有 front，background 是保活的后台标签')
})

test('隐藏期间挂载的面板：当下不上窗口，恢复时补上', () => {
  const { registry, shownIds } = harness()
  registry.ensure('tab1')
  registry.setAllVisible(false)
  registry.attach('tab1')
  assert.deepStrictEqual(shownIds(), [], '蒙层开着的时候不能把 view 挂上去')
  registry.setAllVisible(true)
  assert.deepStrictEqual(shownIds(), ['tab1'])
})

test('destroy 之后不再被恢复挂回（记账清干净，不留幽灵）', () => {
  const { registry, shownIds } = harness()
  registry.ensure('tab1')
  registry.attach('tab1')
  registry.destroy('tab1')
  registry.setAllVisible(false)
  registry.setAllVisible(true)
  assert.deepStrictEqual(shownIds(), [])
  assert.deepStrictEqual(registry._state().wanted, [])
  assert.deepStrictEqual(registry._state().attached, [])
})

test('同一个标签被左右双开：关掉一侧不该把另一侧正看的网页摘走', () => {
  const { registry, shownIds } = harness()
  registry.ensure('tab1')
  registry.attach('tab1') // 左窗格
  registry.attach('tab1') // 右窗格（跨窗格拖拽是复制，id 相同）
  registry.detach('tab1') // 关掉其中一侧
  assert.deepStrictEqual(shownIds(), ['tab1'], '另一侧还端着它，不能从窗口摘下')
  registry.detach('tab1') // 两侧都没了
  assert.deepStrictEqual(shownIds(), [])
  assert.strictEqual(registry.has('tab1'), true, '摘下不等于销毁')
})

test('bounds 只更新不重挂（避免打断导航），destroy 后清掉', () => {
  const { registry } = harness()
  const { view } = registry.ensure('tab1')
  registry.attach('tab1')
  registry.setBounds('tab1', { x: 1, y: 2, width: 3, height: 4 })
  assert.deepStrictEqual(view.bounds, { x: 1, y: 2, width: 3, height: 4 })
  assert.deepStrictEqual(registry.getBounds('tab1'), { x: 1, y: 2, width: 3, height: 4 })
  registry.destroy('tab1')
  assert.strictEqual(registry.getBounds('tab1'), null)
})
