// 请求代次判定：给每次发出的异步请求打一个"代次"号，响应回来时只有代次仍等于
// "此刻最新一次"，这份响应才允许生效。用于堵住"连续点击触发多个同类请求，
// 先发出的却后回来，把新请求的结果覆盖掉"这类竞态。
//
// 典型场景：PersonalSettingsPanel.vue 的 toggleTotpPanel——反复点「绑定」会连续
// 调用 totpSetup()，后端每次都新生成一把密钥并落库（后来者覆盖数据库）。如果界面
// 不认代次、谁的响应后回来就显示谁的，可能显示的是 A 请求的密钥而数据库存的是
// B 请求的，用户扫到一把服务端已经不认的密钥，验证码永远校验不过。

/**
 * @param {number} requestSeq 发出这次请求时记录的代次
 * @param {number} currentSeq 此刻最新的代次（可能因为期间又发出了新请求而已经前进）
 * @returns {boolean} 这份响应是否仍然是"最后一次发出的"，可以采用
 */
export function shouldAcceptResponse(requestSeq, currentSeq) {
  return requestSeq === currentSeq
}
