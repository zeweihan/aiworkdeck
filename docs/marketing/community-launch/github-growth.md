# GitHub 涨星作战（2026-09-03，dev-board#408）

## 诊断（14 天真实数据，2026-08-20 – 09-02）

- 独立访客 **104 人**，其中 referrer=github.com 的 49 人基本是维护者自己与通知流量。
- 四个社区定投发帖（知乎/X/V2EX/掘金）合计带来 **约 4 个真人**（v2ex 2、知乎 1、t.co 1、掘金 0）。
- Product Hunt → 仓库引荐 **0**（PH 受众点官网不点仓库，符合预期；官网首屏本就有仓库链接）。
- 星史：唯一一次爆发在 2026-06-01 – 06-21（三周 40 星），此后每周 1-4 星纯自然滴灌。
- clones 3976/371u 是 CI 与镜像，不是人。

**结论：不是转化问题，是分发问题。** 每周 ~50 个陌生人根本谈不上转化率；定投发帖
不是「遇到瓶颈」，是这个渠道的产出本来就约等于零。再多发十倍也是 40 个人。

**这类项目的星从来不是滴出来的，是几次一次性爆点砸出来的**——而四个最大的爆点渠道
一个都还没打过。这反而是好消息。

## 方案：自动化「生产与监测」，不自动化「发帖频率」

### 第一层：一次性爆点（每个只做一次，我备稿，维护者按键）

| 渠道 | 预期 | 谁按键 | 状态 |
|---|---|---|---|
| **Show HN** | 上首页 = 48h 内 300-1000 星 + GitHub Trending 连带；最大杠杆 | 维护者本人（HN 封自动账号与刷票环，绝不自动化） | 稿见下 |
| **阮一峰《科技爱好者周刊》** | 中文开源项目最著名的涨星入口，常见 +100-500 | 在 ruanyf/weekly 开 issue 自荐 | 稿见下 |
| **HelloGitHub 月刊** | +50-200，长尾持续 | hellogithub.com 提交项目 | 稿见下 |
| **awesome 榜单 PR** | 每个 +10-50，但是**永久回链** | 可全自动（gh fork+PR） | 目标见下 |

### 第二层：能复利的内容（我写，发布可脚本化）

两篇有真材实料的工程文章——这类内容稀缺，HN/Reddit 会自然传播，Google 长尾能吃几年：

1. **Running LibreOffice compiled to WebAssembly inside Electron**（引擎选型、内存、字体、IME、
   保活池、`.uno:` 命令桥——doc-editor.md 里的地雷就是素材）
2. **Turning LLM edits into character-level tracked changes**（diff → 修订原语、
   为什么不整段替换、算法厌恶与人在回路）

发布面：官网 blog（待建）→ dev.to（有 API，可全自动）→ 掘金/知乎（hermes 以「真文章」
形态发，不是推广帖）。发完由维护者投 HN / r/programming / r/electronjs。

Reddit 一次性投放（维护者本人，每周最多一个 sub）：r/opensource、r/LawFirm、
r/legaltech、r/electronjs。

### 第三层：管道与监测（全自动，本次已落地）

- `.github/workflows/repo-metrics.yml` + `scripts/repo-metrics.mjs`：每周一把 views/clones/
  referrers/paths/stars 快照追加到 `repo-metrics` 分支 `metrics/traffic.jsonl`。
  读法：`git fetch origin repo-metrics && git show origin/repo-metrics:metrics/traffic.jsonl | jq`
- README「开源内核、非完整产品」的口径已改为与定价页一致的「完整工作台」（开发者读到
  「kernel」会当成阉割版）。

### hermes agent 改任务（把下面这段直接发给它）

```
任务变更。停止：按排期向知乎/X/V2EX/掘金发布推广帖——过去 14 天这些帖子合计带来
约 4 个仓库访客，继续发只会消耗账号信誉（V2EX 尤其封推广）。

新任务三项：
1. 监测应答。每日扫 HN、Reddit（r/LawFirm r/legaltech r/opensource r/electronjs
   r/selfhosted）、X、知乎、V2EX、掘金，找这些意图的真实提问：律师/法务 AI 工具推荐、
   开源法律 AI、LibreOffice WebAssembly、Office 加载项 + AI、LLM 修订/redline、
   「有没有给 X 的 VS Code」。对每条命中：起草一个 80% 回答问题、20% 顺带提及的回复，
   放入待审队列由维护者一键发出。不代发。
2. 周报。每周一读取 repo-metrics 分支 metrics/traffic.jsonl，输出：本周独立访客、
   referrer 排名、星增量、与上周对比、哪个动作带来了人。
3. 执行提交清单。维护者批准后，按 github-growth.md 的「awesome 榜单」表逐个开 PR，
   按各仓 CONTRIBUTING 的格式与门槛，被拒就记录原因不重投。

红线：不买星、不互星、不刷票、同一内容一天不跨两个以上平台、不在别人帖子下刷回复。
```

## 成稿

### Show HN（维护者本人发，周二至周四，北京时间 20:00-21:00 = 美东早 8-9 点）

标题（78 字符，HN 上限 80）：

```
Show HN: Open-source IDE for legal work, built by a lawyer (LibreOffice-in-WASM)
```

URL 填仓库地址（不是官网——HN 用户要看代码）。正文（发帖后立即作为第一条评论贴出）：

```
I'm a practicing lawyer in Beijing. I wrote this because my working day was
copying text between Word, a PDF viewer, twenty browser tabs and an AI chat
window, and saving the result as final_v15.docx. Developers got an IDE thirty
years ago; document work never did.

What it is: a desktop workbench (Electron, Spring backend bundled) where a
whole matter lives in one window — files, an agent panel, an evidence pane,
a browser, version history. AGPL, files stay local.

The two parts that were technically interesting to build:

1. The editor is LibreOffice compiled to WebAssembly (the zetaoffice/allotropia
   work), running inside the renderer. Real .docx fidelity, tracked changes,
   comments, CJK fonts and IME — and a long list of things that bite you:
   memory ceilings, a keep-alive pool so documents don't cold-start, driving
   it through .uno: commands, autosave races.

2. AI edits never replace text. The model's output is diffed against the
   document and applied as character-level tracked changes the user accepts
   or rejects one by one. This was a design decision before it was a feature:
   professionals sign their name to the output, so autonomy is exactly what
   they cannot delegate. The behavioral-econ literature on algorithm aversion
   says the same thing — people reject algorithms they can't adjust.

Also: Office and WPS add-ins that talk to the same backend, an iOS companion
that drops evidence photos into the case folder, and a plugin SDK with a
marketplace (a few plugins exist; the due-diligence one is closed-source).

Honest limitations: the UI was Chinese-first and English is newer; the
Windows build isn't code-signed yet; the official desktop build routes AI
through our platform on a usage meter, though the self-hosted stack takes
your own providers including Ollama.

I'd love feedback from anyone who has fought LibreOffice-in-the-browser, or
who does document-heavy work and has opinions about what "IDE for documents"
should mean.
```

### 阮一峰周刊自荐（在 ruanyf/weekly 当期「自荐」issue 下回复）

```
**AI WorkDeck**：一位执业律师写的开源「律师 IDE」。把整个案子装进一个窗口——
文件、AI 对话、依据窗格、浏览器、版本记录；编辑器是编译成 WebAssembly 的
LibreOffice，AI 的每处修改都以字符级修订留痕，由人逐条接受或拒绝。桌面端本地
存储，AGPL 开源，另有 Office/WPS 加载项与 iOS 取证同步。

https://github.com/zeweihan/aiworkdeck
（配图：仓库 README 首屏截图 .github/assets/workspace-ai.png）
```

### HelloGitHub 提交（hellogithub.com「提交项目」）

- 仓库：https://github.com/zeweihan/aiworkdeck
- 类别：AI / 其他（编辑器）
- 一句话（限 100 字）：

```
律师写的开源「律师 IDE」：把整个案子装进一个窗口。内嵌 WebAssembly 版
LibreOffice，AI 修改全部以字符级修订留痕、由人接受或拒绝，文件留在本机。
```

### awesome 榜单目标（按各仓 CONTRIBUTING 格式；可由 hermes 批准后自动开 PR）

| 仓库 | 星 | 位置 | 条目 |
|---|---|---|---|
| sindresorhus/awesome-electron | 27k | Apps → Open Source → Other | `- [AI WorkDeck](https://github.com/zeweihan/aiworkdeck) - AI workbench for legal and document-heavy work, with LibreOffice compiled to WebAssembly as the editor.` |
| punkpeye/awesome-mcp-clients | 6.6k | 按其表格格式 | Desktop workbench for legal/document work; MCP client for case-law and registry lookups. |
| e2b-dev/awesome-ai-agents | 30k | Open Source → 最近的垂直类目 | 同上口径 |
| Vaquill-AI/awesome-legaltech | 214 | 开源工具 | 同上口径（小但正中靶心） |
| awesome-selfhosted/awesome-selfhosted | 317k | Document Management（YAML 在 awesome-selfhosted-data 仓） | 门槛严：需自托管服务端、≥4 个月、不依赖付费第三方——自托管栈 + Ollama 满足，仍可能被拒，被拒不纠缠 |

## 度量与复盘

- 北极星改为 **周独立访客** 与 **referrer 分布**，星是滞后指标。
- 每次爆点后 48 小时看 traffic.jsonl 的 referrer，用数据决定下一次投哪。
- 对 PH 的公平判断：PH 是 09-02 15:01 上线的，判断它对仓库的影响至少等 7 天；
  但结构性结论（定投 ≈ 0）不依赖 PH。
