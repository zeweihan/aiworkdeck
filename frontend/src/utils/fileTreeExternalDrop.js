// 外部（Finder / 资源管理器 / 微信）文件拖进文件树的纯函数（dev-board#363）。
// 零依赖，便于 node --test 直接导入；单测见 tests/project-home/file-tree-external-drop.test.mjs。
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

function toUploadItem(file, relativePath) {
  return {
    name: file.name,
    size: file.size || 0,
    fileObject: file,
    relativePath: relativePath || file.name,
  }
}

function walkEntry(entry, prefix, out) {
  return new Promise((resolve) => {
    if (!entry) return resolve()
    if (entry.isFile) {
      entry.file((f) => { out.push(toUploadItem(f, prefix + entry.name)); resolve() }, () => resolve())
      return
    }
    if (entry.isDirectory && typeof entry.createReader === 'function') {
      const reader = entry.createReader()
      const dirPrefix = prefix + entry.name + '/'
      // readEntries 每次最多回 100 条，读到空数组才算读完
      const readBatch = () => {
        reader.readEntries(async (batch) => {
          if (!batch || batch.length === 0) return resolve()
          for (const child of batch) await walkEntry(child, dirPrefix, out)
          readBatch()
        }, () => resolve())
      }
      readBatch()
      return
    }
    resolve()
  })
}

// 把 dataTransfer 整理成 confirmUpload 吃的形状 [{ name, size, fileObject, relativePath }]。
// 目录经 webkitGetAsEntry 递归展开，relativePath 带目录前缀（confirmUpload 据此建目录）。
// webkitGetAsEntry 与 files 必须在 drop 事件同步阶段取——事件处理器一返回 items 就作废，
// 所以两份快照都在第一个 await 之前拿好。
export function collectDroppedFiles(dt) {
  if (!dt) return Promise.resolve([])
  const plainFiles = Array.from(dt.files || [])
  const items = dt.items ? Array.from(dt.items) : []
  const entries = items.map((it) => (
    it && it.kind === 'file' && typeof it.webkitGetAsEntry === 'function' ? it.webkitGetAsEntry() : null
  ))
  if (entries.some(Boolean)) {
    const out = []
    return (async () => {
      for (const entry of entries) await walkEntry(entry, '', out)
      return out
    })()
  }
  return Promise.resolve(plainFiles.map((f) => toUploadItem(f, f.name)))
}
