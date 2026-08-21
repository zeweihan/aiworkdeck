// 把并发调用串成一条队列，保证同一时刻只有一个任务在执行——用来堵住"共享单槽状态
// 被后一次调用覆盖，前一次的 await 永远不落地"这类竞态。
//
// 典型场景：DrawioEditor.vue 的 persist()。exportSvg() 把等 SVG 回包的 resolver 存进
// 一个单槽 pendingExport；两次 persist 同时在飞时，第二次调用会直接覆盖第一次的
// resolver，draw.io 回包时只喂得到"当前槽"，第一次那条回包被丢弃、resolver 永远
// 不会被调用，那次保存的 await 因此永久挂起——不写盘、不报错、不改保存提示，
// 静默烂尾。用这条队列把 save 事件串行化后，同一时刻只会有一次 exportSvg 在飞，
// 单槽设计也就天然安全，不需要把 pendingExport 改造成能装多个的结构。

/**
 * @returns {(task: () => Promise<any>) => Promise<any>} run(task)：把 task 接到队列尾部，
 *   返回一个在"轮到它执行且执行完毕"时 settle 的 Promise（成功/失败都如实传递）。
 *   前一个任务失败不会阻塞后一个任务被执行。
 */
export function createSerialQueue() {
  let chain = Promise.resolve()
  return function run(task) {
    const result = chain.then(task, task)
    // 链本身绝不能因为某次任务失败就"卡死"后续任务——不管这次结果是成功还是失败，
    // chain 都继续往下传递；run() 的返回值仍然带着这次调用真实的成功/失败。
    chain = result.then(() => {}, () => {})
    return result
  }
}
