// uni-h5 的 scroll-view 在挂载后用 nextTick 补写一次 scrollTop/scrollLeft，但那个回调
// 对元素 ref 没有任何空判：组件在同一批 flush 里被卸载（v-if 分支翻转、路由离开）时，
// main.value 已经是 null，于是产线控制台常驻两条——
//   TypeError: Cannot set properties of null (setting 'scrollTop')
//   未处理的 Promise rejection（nextTick 的 promise 链把上面那条包了一层）
// 复现路径：项目列表页 → 统一设置页（AdminPane 里 PersonalWorkLogPanel 的
// `<scroll-view v-else scroll-y>` 随 loading/空/有数据三分支挂载又卸载）。dev-board#349。
//
// 上游没有空判，我们这边**无法**取消它已经排好的 nextTick，只能补这道守卫。
// 语义上也正是对的：元素都没了，本来就没有什么可滚的。
// 三处替换都只在原表达式前加一个 main.value 真值判断，行为对正常路径逐字节不变。
//
// 若将来升级 @dcloudio/uni-h5 后此处报「结构已变」：先确认新版是否已自带空判
//（搜 _scrollTopChanged），是就删掉本补丁与 package.json 的 postinstall 钩子。
// 写法与 desktop/scripts/patch-dmg-builder.js 同一套路。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const distDir = path.join(here, '..', 'node_modules', '@dcloudio', 'uni-h5', 'dist')

// [坏形态, 好形态]。锚点必须在目标文件里唯一，否则宁可报错也不乱改。
const EDITS = [
  ['main.value.scrollTop = val;', 'main.value && (main.value.scrollTop = val);'],
  ['main.value.scrollLeft = val;', 'main.value && (main.value.scrollLeft = val);'],
  // 带 scroll-with-animation 的那条分支走 scrollTo，同一个卸载竞态会崩在
  // container.scrollWidth 上（聊天区自动滚动就在这条路上）。一并守住。
  ['const container = main.value;', 'const container = main.value;\n    if (!container) return;'],
]

const targets = ['uni-h5.es.js', 'uni-h5.cjs.js']
let patched = 0
let already = 0

for (const name of targets) {
  const file = path.join(distDir, name)
  if (!fs.existsSync(file)) {
    // 依赖没装齐（如只跑了后端的 CI 腿）时不拦安装：真要用到时构建会自己暴露
    console.log(`[patch-uni-h5] 未找到 ${name}，跳过`)
    continue
  }
  let src = fs.readFileSync(file, 'utf8')
  let changed = false
  for (const [bad, good] of EDITS) {
    if (src.includes(good)) { continue }               // 已打过
    const n = src.split(bad).length - 1
    if (n !== 1) {
      console.error(`[patch-uni-h5] ${name} 里锚点 ${JSON.stringify(bad)} 出现 ${n} 次（期望 1 次）。`)
      console.error('[patch-uni-h5] uni-h5 结构已变：先确认新版是否已自带空判，再决定改锚点还是删掉本补丁（dev-board#349）。')
      process.exit(1)
    }
    src = src.replace(bad, good)
    changed = true
  }
  if (changed) { fs.writeFileSync(file, src); patched++ } else { already++ }
}

if (patched) console.log(`[patch-uni-h5] 已补 scroll-view 卸载后写 scrollTop 的空判（${patched} 个产物，dev-board#349）`)
else if (already) console.log('[patch-uni-h5] 已打过补丁')
