/**
 * Office/PowerPoint 面的形状遍历（dev-board#288）：
 *   node --test office-addin/taskpane/lib/officePptShapes.test.js
 *
 * 病灶：`loadPptTextFrames` 只对顶层形状取 `getTextFrameOrNullObject()`，
 * **组合形状（group）里的文字完全不可达**——而演示稿里图示+标注、SmartArt 转出来的
 * 内容恰恰都是组合。用户看着满屏字，AI 说这页没这段内容。
 * WPS 面（wpsWppHandlers.textBearingShapes）早就按三条路收，Office 面此前只有一条。
 *
 * 两条不变式：
 * 1. PowerPointApi 1.8 可用时，组合（含嵌套）里的文字要读得到；
 * 2. 1.8 不可用时**不许报错**，退化成「只收顶层」——与改造前逐字一致。
 *    老宿主上不该因为想多读一点就把整条命令打死。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

function textShape(text) {
  return {
    type: 'TextBox',
    getTextFrameOrNullObject() {
      return {
        isNullObject: false,
        hasText: !!text,
        textRange: { text, load() {} },
        load() {}
      }
    }
  }
}

function groupShape(children) {
  return {
    type: 'Group',
    group: {
      shapes: {
        items: children,
        load() {}
      }
    },
    getTextFrameOrNullObject() {
      // 组合壳自身没有文字
      return { isNullObject: true, hasText: false, textRange: { text: '', load() {} }, load() {} }
    }
  }
}

/** 装 PowerPoint 命名空间 + Office.context.requirements（版本门槛由它决定） */
function install({ slides, supports18 }) {
  const savedPpt = globalThis.PowerPoint
  const savedOffice = globalThis.Office
  globalThis.Office = {
    HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
    context: {
      host: 'PowerPoint',
      document: { url: 'C:/x/方案.pptx' },
      requirements: {
        isSetSupported: (name, ver) => {
          if (name !== 'PowerPointApi') return false
          if (ver === '1.4') return true
          if (ver === '1.8') return !!supports18
          return false
        }
      }
    }
  }
  globalThis.PowerPoint = {
    run: async (cb) => cb({
      presentation: {
        slides: {
          items: slides.map((shapes) => ({ shapes: { items: shapes, load() {} } })),
          load() {}
        }
      },
      sync: async () => {}
    })
  }
  return () => {
    if (savedPpt === undefined) delete globalThis.PowerPoint; else globalThis.PowerPoint = savedPpt
    if (savedOffice === undefined) delete globalThis.Office; else globalThis.Office = savedOffice
  }
}

const { executeOfficeCommand } = await import('./officeExecutor.js')

test('组合形状里的文字要读得到（含组合套组合）', async () => {
  const restore = install({
    supports18: true,
    slides: [[
      textShape('本页标题'),
      groupShape([
        textShape('图示标注甲'),
        groupShape([textShape('嵌套标注乙')])
      ])
    ]]
  })
  try {
    const out = await executeOfficeCommand('ppt_get_slides', {})
    assert.equal(out.ok, true, out.error)
    const texts = out.data.slides[0].texts
    assert.ok(texts.includes('本页标题'))
    assert.ok(texts.includes('图示标注甲'), `组合子形状文字应读到，实际 ${JSON.stringify(texts)}`)
    assert.ok(texts.includes('嵌套标注乙'), '组合套组合也要递归')
  } finally { restore() }
})

test('PowerPointApi 1.8 不可用时静默退化成只收顶层，绝不报错', async () => {
  const restore = install({
    supports18: false,
    slides: [[textShape('本页标题'), groupShape([textShape('看不到的标注')])]]
  })
  try {
    const out = await executeOfficeCommand('ppt_get_slides', {})
    assert.equal(out.ok, true, `老宿主上不该报错：${out.error}`)
    const texts = out.data.slides[0].texts
    assert.deepEqual(texts, ['本页标题'], '1.8 缺失时只收顶层，与改造前一致')
  } finally { restore() }
})
