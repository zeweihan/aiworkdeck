// 离开工作台前把还没落盘的编辑器内容存下来。
//
// 病灶：自动保存是防抖的，用户敲完最后一个字到真正落盘之间有一段窗口。
// closeFile / evictLibreInstance 都会先 await flushSave 再拆实例，但**离开整个页面**
// 的三条路（切项目 / 返回项目列表 / 退出登录）走的是 uni.reLaunch，页面组件树直接销毁，
// 一次 flush 都没有。LibreOfficeEditor 的 beforeUnmount 自己写着「export 需要活的
// webview，从这里保存已经太晚」——所以 Office 文档那几秒的改动**静默丢失，且无任何提示**。
//
// 刻意做成零依赖的纯函数：既能被 project-overview.vue 直接用，也能在 node:test 里
// 真跑一遍（本目录其余模块都 import 了 @/ 别名，测不动）。

/**
 * 逐个 flush 还脏的编辑器实例。
 *
 * 判据与 closeFile 保持一致：
 * - Office 文档（LibreOfficeEditor）要求 ready 且非 isError——加载失败的实例画布是
 *   空白原型，保存会拿空白覆盖真文件（同 evictLibreInstance 的取舍）；
 * - 纯文本（PlainTextEditor）只要求有 file。
 *
 * 单个实例保存失败不许拖累其它实例：逐个 try/catch，全部尝试完才返回。
 *
 * @param {object} libreRefs      形如 { 'left:123': inst }，来自 project-overview 的 _libreRefs
 * @param {object} plainTextRefs  形如 { left: inst, right: inst }，来自 _plainTextRefs
 * @returns {Promise<{flushed: number, failed: number}>}
 */
export async function flushDirtyEditors(libreRefs, plainTextRefs) {
  let flushed = 0
  let failed = 0

  const attempt = async (inst) => {
    try {
      await inst.flushSave()
      flushed++
    } catch (e) {
      failed++
      console.warn('[ProjectOverview] leave flush-save failed:', e)
    }
  }

  for (const inst of Object.values(libreRefs || {})) {
    if (inst && inst.ready && !inst.isError && inst.file && (inst.dirty || inst.saving)
        && typeof inst.flushSave === 'function') {
      await attempt(inst)
    }
  }

  for (const inst of Object.values(plainTextRefs || {})) {
    if (inst && inst.file && (inst.dirty || inst.saving) && typeof inst.flushSave === 'function') {
      await attempt(inst)
    }
  }

  return { flushed, failed }
}
