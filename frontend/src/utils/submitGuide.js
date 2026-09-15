// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「交稿引导」的状态 → 步骤映射（dev-board#645）——**纯函数，不许 import**。
 *
 * 病灶：律师点「交稿」时手头这段活还没结束、或者案件库已经被同事推进过，后端就回一句
 * REMOTE_AHEAD/没东西可交的 toast 打发他。那句话说不清「下一步该干什么」，他只会反复点
 * 那个按钮。这里把「能不能交」拆成三步，按顺序解锁，每一步都给一个能点的动作。
 *
 * 三步的顺序不是排版偏好，是硬依赖：手头的活没收成一版就没有东西可交；案件库领先时
 * 交稿必被拒（见 CloudSyncService 的 REMOTE_AHEAD），必须先取回。
 *
 * 判据只有四个本机已有的信号，一个新接口都不加：
 *   working        /version/status 的 working（手头这段活还没收尾）
 *   remoteAhead    cloudStatus 的 remoteAhead（同事/自己在另一台电脑交了新稿）
 *   offline        cloudStatus 的 offline（连不上案件库）
 *   remoteAheadCount / remoteAheadBySelf  只为第 ② 步那句话带上版数与「是不是我自己」
 *
 * 离线是一条独立的出路而不是第四步：连不上时三步一步都做不了（取回要联网、交稿也要），
 * 摆一张全是灰按钮的清单只会让人以为软件卡住了。
 */

/** 步骤 id，顺序即依赖顺序。 */
export const SUBMIT_GUIDE_STEP_IDS = ['end-session', 'pull-latest', 'submit']

/**
 * @param {Object} s
 * @param {boolean} [s.working]            手头还有没收尾的工作段
 * @param {boolean} [s.remoteAhead]        案件库比本机新
 * @param {number}  [s.remoteAheadCount]   领先几版（老服务端不回，为 0）
 * @param {boolean} [s.remoteAheadBySelf]  领先的那几版全是本人在另一台电脑交的
 * @param {boolean} [s.offline]            连不上案件库
 * @returns {{title: string, offline: boolean, pending: number,
 *            steps: Array<{id: string, state: 'todo'|'active'|'done', count?: number, bySelf?: boolean}>,
 *            canSubmit: boolean}}
 */
export function submitGuideSteps(s = {}) {
  const working = !!(s && s.working)
  const remoteAhead = !!(s && s.remoteAhead)
  const offline = !!(s && s.offline)

  if (offline) {
    return { title: 'version.submitGuideOfflineTitle', offline: true, pending: 0, steps: [], canSubmit: false }
  }

  const pending = (working ? 1 : 0) + (remoteAhead ? 1 : 0)
  const doneOf = { 'end-session': !working, 'pull-latest': !remoteAhead, submit: false }

  // 顺序解锁：第一个没做完的是「进行中」，它后面的都还是「待做」。
  // 「交稿」这一步永远不会是 done——它做完弹窗就关了。
  let seenActive = false
  const steps = SUBMIT_GUIDE_STEP_IDS.map((id) => {
    const step = { id, state: 'done' }
    if (!doneOf[id]) {
      step.state = seenActive ? 'todo' : 'active'
      seenActive = true
    }
    if (id === 'pull-latest') {
      step.count = Number(s && s.remoteAheadCount) || 0
      step.bySelf = !!(s && s.remoteAheadBySelf)
    }
    return step
  })

  let title = 'version.submitGuideReady'
  if (pending === 2) title = 'version.submitGuideTwoLeft'
  else if (pending === 1) title = 'version.submitGuideOneLeft'

  return { title, offline: false, pending, steps, canSubmit: pending === 0 }
}
