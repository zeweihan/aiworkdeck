// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { ref } from 'vue'
import { t } from './i18n.js'
import { listGitLinks, createGitLink, deleteGitLink } from './api.js'

/**
 * 关联 GitHub / Gitee 仓库（dev-board#720）：让不装桌面端的用户也有一个「权威源」
 * 可供 AI 参考。关联挂在**项目**上，AI 只读这些仓库里的文件，从不提交、不推送。
 *
 * 与 transfer.js / revisionLog.js 同一个模式：状态是模块级的，面板只做渲染与接线，
 * 判断都在这里（可以在 node 里直接测，不必把 Vue 组件挂起来）。
 *
 * 令牌的纪律：只在 addLink 这一刻穿过去一次，之后哪儿都不留——不进 links、
 * 不进 localStorage、不进日志。服务端加密存，回来的只有 tokenLast4。
 */

/** 当前项目的关联仓库（服务端是权威源，这里只是它的一份视图） */
export const links = ref([])
/** 清单是否正在拉取（面板据此显示「加载中」而不是一片空白） */
export const loading = ref(false)

const SUPPORTED = {
  'github.com': 'github',
  'gitee.com': 'gitee'
}

/**
 * 仓库地址校验，与后端 `GitProviderClient.parseUrl` 同规则，前端先挡一遍——
 * 少一次「填了 GitLab 地址、等一次往返、再被后端拒绝」。
 *
 * **只能比后端宽松，不能更严**：前端多拒一个后端认的地址，用户就被自己人挡在门外，
 * 而且没有任何出路（后端的报错他压根看不到）。所以这里只做两件确定的事——
 * 认出主机名，再确认路径能切出 owner/repo，其余一律交给后端定夺。
 *
 * 返回 `{ ok, provider?, error? }`。空输入返回 `{ ok:false }` 不带 error：
 * 输入框还空着的时候弹一条红字是在骂用户，按钮禁用就够了。
 */
export function validateRepoUrl(url) {
  const raw = String(url == null ? '' : url).trim()
  if (!raw) return { ok: false }

  let host = ''
  let path = ''
  const ssh = /^git@([^:]+):(.+)$/.exec(raw)
  if (ssh) {
    host = ssh[1]
    path = ssh[2]
  } else {
    const https = /^https?:\/\/([^/]+)\/(.*)$/.exec(raw)
    if (https) {
      host = https[1]
      path = https[2]
    }
  }
  // 主机名认得出、但不是这两家：说清只支持哪两家（GitLab、自建 Gitea 都走这条）
  host = host.toLowerCase().replace(/^www\./, '')
  const provider = SUPPORTED[host]
  if (host && !provider) return { ok: false, error: t('gitLinkOnlyHosts') }
  if (!provider) return { ok: false, error: t('gitLinkBadUrl') }

  // 是这两家，但路径不是一个仓库（少了仓库名、多了 /tree/main 之类）：提示地址形状。
  // 说「只支持 GitHub 与 Gitee」在这里是答非所问——用户填的正是 GitHub。
  const segments = path.replace(/\/+$/, '').replace(/\.git$/, '').split('/').filter(Boolean)
  if (segments.length !== 2) return { ok: false, error: t('gitLinkBadUrl') }
  return { ok: true, provider }
}

/** 拉当前项目的关联清单。没有项目就清空——git 关联是挂在项目上的，没项目无从谈起。 */
export async function loadLinks(settings, projectId) {
  if (!projectId) {
    links.value = []
    return
  }
  // 先清空再拉：换了项目重开面板时，上一个项目的仓库不能顶着新项目的名头显示
  links.value = []
  loading.value = true
  try {
    links.value = await listGitLinks(settings, projectId)
  } finally {
    loading.value = false
  }
}

/**
 * 新增关联。地址先本地挡一遍再出站——没过校验就把令牌发出去是白送一次凭据暴露面。
 * 服务端校验通过后返回 link（不含令牌），按 id 并进清单（撞已有关联时覆盖那一行，
 * 不留两行同一个仓库）。
 */
export async function addLink(settings, { projectId, url, branch, token }) {
  const check = validateRepoUrl(url)
  if (!check.ok) throw new Error(check.error || t('gitLinkBadUrl'))
  const link = await createGitLink(settings, { projectId, url, branch, token })
  if (link && link.id != null) {
    const at = links.value.findIndex((l) => l && l.id === link.id)
    if (at >= 0) links.value.splice(at, 1, link)
    else links.value.push(link)
  }
  return link
}

/** 解除关联。服务端删成功才动清单——界面显示的必须是服务端的真实状态。 */
export async function removeLink(settings, id) {
  await deleteGitLink(settings, id)
  links.value = links.value.filter((l) => !l || l.id !== id)
}

/** 面板里的一行展示名：provider/owner/repo@branch */
export function linkLabel(link) {
  if (!link) return ''
  const branch = link.branch ? `@${link.branch}` : ''
  return `${link.provider}/${link.owner}/${link.repo}${branch}`
}
