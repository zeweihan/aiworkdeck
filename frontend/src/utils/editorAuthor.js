// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// editorAuthor.js — 修订（redline）署名要用的「当前用户展示名」取数层。
//
// WHY 这一层存在：引擎对空作者有自己的兜底，zh-CN 语言包里就是**「未知作者」**
// （真机实证 24.2.8-zhcn-r5：load_document 带 authorName:'' 之后，手打与查找替换
// 产生的修订作者一律是这四个字；带上名字则两者都正确署名）。而宿主原来只读
// uni 本地缓存里的登录用户（utils/auth.js 的 getCurrentUser），桌面端 local-mode
// 免登**从不写那个键**（saveSession 只在登录页调用，launch 直达工作区），所以整机
// 没登录过的安装上，用户自己的每一条修订都署「未知作者」。
//
// 判定与取数分开：这里只有纯函数与一个依赖可注入的取数器，才跑得了 node --test
// （tests/lowa-unit/editorAuthor.test.mjs）。

// 展示名只认展示字段。**不许回落 username**（Spec §6：手机号注册的用户名是
// `u`+随机串，落进修订与批注就是永久的，历史条目不回填）。拿不到名字宁可给空串，
// 让引擎用它自己的默认作者。
const NAME_KEYS = ['displayName', 'nickname', 'name']

/** 按来源顺序取第一个非空展示名；都没有回空串。 */
export function pickAuthorName(...sources) {
  for (const s of sources) {
    if (!s || typeof s !== 'object') continue
    for (const k of NAME_KEYS) {
      const v = String(s[k] == null ? '' : s[k]).trim()
      if (v) return v
    }
  }
  return ''
}

/**
 * 展示名取数器：先用本地缓存（同步、零开销），缓存里没有才问一次后端
 * （/api/auth/me 在 local-mode 免登下同样会回 displayName）。
 *
 * 一个进程内只问一次，失败也不再重试——署名拿不到就退回引擎兜底，不值得为它
 * 反复打网络。
 *
 * @param deps.readCached  () => object|null      同步读本地缓存的用户对象
 * @param deps.fetchRemote () => Promise<object|null>  拉一次后端的用户对象
 */
export function createAuthorNameResolver(deps) {
  const readCached = deps.readCached
  const fetchRemote = deps.fetchRemote
  let remote = null
  let inflight = null
  let tried = false
  const current = () => pickAuthorName(readCached(), remote)
  return {
    /** 同步取：只看已经有的东西，不发请求。 */
    current,
    /** 异步取：已经有名字就直接兑现；没有就问一次后端（单飞）。永不抛。 */
    resolve() {
      const now = current()
      if (now) return Promise.resolve(now)
      if (inflight) return inflight
      if (tried) return Promise.resolve('')
      tried = true
      inflight = Promise.resolve()
        .then(() => fetchRemote())
        .then((r) => { remote = r || null; return current() })
        .catch(() => '')
        .then((name) => { inflight = null; return name })
      return inflight
    },
  }
}
