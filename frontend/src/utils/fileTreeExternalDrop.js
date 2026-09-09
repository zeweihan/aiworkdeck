// 外部（Finder / 资源管理器 / 微信）文件拖进文件树的纯函数（dev-board#363）。
// 零依赖，便于 node --test 直接导入；单测见 tests/project-home/file-tree-external-drop.test.mjs。
//
// 只剩「识别这是不是一次外部文件拖拽」与「同一个原生事件只认领一次」两件事：
// 落点定了之后走的是 import-local（顶层条目的本机绝对路径直接交给后端，目录由后端
// 递归展开），前端不再用 webkitGetAsEntry 展开目录、也不再整理上传队列（dev-board#513）。
//
// 地雷：uni-h5 把 <view> 上的事件重建成普通对象（$nne → createNativeEvent），只补
// click / mouse / touch 三类字段，drag 系事件的 dataTransfer / relatedTarget 全丢。
// 要读它们必须回到正在派发的原生事件 window.event 上（同 fileOpenTabs.js 的 mouseButtonOf）。

export function nativeEvent(e) {
  if (e && e.dataTransfer) return e
  const native = typeof window !== 'undefined' ? window.event : null
  return native || e || null
}

export function nativeDataTransfer(e) {
  if (e && e.dataTransfer) return e.dataTransfer
  const native = typeof window !== 'undefined' ? window.event : null
  return native && native.dataTransfer ? native.dataTransfer : null
}

// dragover / dragenter 阶段 files 恒为空（浏览器只在 drop 时才给内容），只能看 types
// 里有没有 'Files'；应用内拖拽（text/plain + application/x-checkba-file）永远没有它。
export function isExternalFileDrag(dt) {
  if (!dt) return false
  if (dt.files && dt.files.length > 0) return true
  const types = dt.types
  if (!types) return false
  return Array.from(types).indexOf('Files') !== -1
}

// 同一个原生 drop 事件会先后到达节点与容器两个监听器（uni 的 stopPropagation 只是转发，
// 不依赖它）；在原生事件对象上打一个认领标记，第二次到达直接跳过。
export function claimExternalDrop(native) {
  if (!native || typeof native !== 'object') return true
  if (native.__awdExternalDropClaimed) return false
  try { native.__awdExternalDropClaimed = true } catch (e) { /* ignore */ }
  return true
}
