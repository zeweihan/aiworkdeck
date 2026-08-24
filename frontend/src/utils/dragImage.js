// 文件拖拽的 ghost 小徽标（FileTree 与文件暂存区共用，dev-board#139）。
//
// 两个坑，都在这里一并解决：
// 1) dragstart 里现场 `new Image()` 再 setDragImage：同步执行时图片还没加载完，
//    Chromium 规定此时回退**拖拽源元素快照**——于是整行文件卡片（含悬停时显示的
//    下载/复制/删除操作图标）浮在正文上。必须预加载，dragstart 只引用现成的。
// 2) /static/Drag.png 原图 200x200：setDragImage 按图片固有尺寸渲染，直接用
//    还是一个巨大 ghost。加载后经 canvas 缩成 32px 小徽标再用。
let readyImage = null
let warming = false

export function warmDragImage() {
  if (readyImage || warming) return
  if (typeof Image === 'undefined' || typeof document === 'undefined') return
  warming = true
  const src = new Image()
  src.onload = () => {
    try {
      const c = document.createElement('canvas')
      c.width = 32
      c.height = 32
      c.getContext('2d').drawImage(src, 0, 0, 32, 32)
      const small = new Image()
      small.onload = () => { readyImage = small }
      small.src = c.toDataURL('image/png')
    } catch (e) {
      // canvas 不可用（罕见）：退回原图，大总比整行快照好认
      readyImage = src
    }
  }
  src.src = '/static/Drag.png'
}

/** dragstart 里调用：徽标已就绪则设为拖拽影像，未就绪保持默认（下次就有了） */
export function applyDragImage(e) {
  if (!readyImage || !e || !e.dataTransfer) return
  try {
    e.dataTransfer.setDragImage(readyImage, 16, 16)
  } catch (err) {
    // 非 H5 环境无此 API
  }
}
