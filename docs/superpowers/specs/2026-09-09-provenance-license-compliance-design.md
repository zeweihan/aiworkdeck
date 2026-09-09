# AI WorkDeck 可溯源性与开源合规设计规范

版本：v1，2026-09-09（dev-board#505 / #506）。由 Opus 子代理调研起草、维护者会话定稿。

**已拍板的决策（本轮落地）**
- 做：逐文件 SPDX 头 + REUSE 目录 + CI 新文件护栏；`office_thread.js` 许可头改为 `AGPL-3.0-or-later AND MIT`；界面告示（设置页 + Office/WPS 任务窗格 + 桌面 About）；`X-Source-Code` 响应头；四条既有产物签名的契约断言测试；开发规范条款并入 `.github/CONTRIBUTING.md` 与 `CLAUDE.md`。
- 不做（红线）：隐蔽回传、混淆、不参与计算的魔数水印、注释暗号、故意 bug 陷阱、「特定频率」。判据：删掉它代码照常工作，就对有意抄袭者零成本。
- 缓做（第二批，另开卡）：文档 Generator 元数据（要带用户开关）、出站 UA 统一、扫描器语料库（ClearlyDefined / 发 npm、Maven）、逐行权属映射脚本。
- 非工程（维护者/律师）：软著登记、release 可信时间戳、GPG 签名密钥备份核对。
- 私有登记簿放 `zeweihan/dev-board` 仓（公开仓只留契约测试，不出现「防抄」字样）。

配套：现有代码加固候选清单已并入本文附录（原 `hardening-candidates.md`）。开发者条款见 `.github/CONTRIBUTING.md`「Provenance and license notices」一节。

---

## 0. 先纠正一个前提

维护者的原话是「担心别人用了我们的产品却不报备」。调研之后要先把这句话拆成三种完全不同的情况，因为它们的应对手段截然不同：

| 情况 | 实际风险 | 有效手段 |
|---|---|---|
| A. 合规使用（遵守 AGPL，公开修改版源码） | **没有风险，这是我们开源的目的** | 无需应对 |
| B. 不知情的违规（工程师直接拿去用，公司法务不知道） | 中。**这是最常见的情况** | 让对方的合规扫描器自己报警（SPDX/REUSE），一封信解决 |
| C. 知情的违规（换皮、抹掉出处、当自己的产品卖） | 高，但概率低于 B | 商标 + 举证链 + 逐行权属 |

**结论一：真正的主力工作不是「让别人抄了我们能看出来」，而是「让别人抄了他自己先看出来」。**
一个抄袭者公司内部跑 Black Duck / ScanCode / FOSSA 时，如果我们的每个文件都带 SPDX 头，对方法务会先收到 AGPL 告警并主动来谈授权。这条路径比我们去打官司便宜两个数量级，而我们现在**一个 SPDX 头都没有**（实测：Java 0/1045、Vue 0/117、TS 0/89、MJS 0/157、JS 5/274，且那 5 个还是 vendored 的 MIT 头）。

**结论二：AGPL 的现实价值是「逼合规」，不是「收钱」。**
GPLv3/AGPLv3 §8 规定：违约即自动终止，但对方停止违约后若权利人 60 天内未通知则**永久恢复**；首次收到通知并在 30 天内改正的也**永久恢复**。这意味着"静静收集证据、等它做大再发难"是**反效果**的策略——发现了就要发信。
（AGPL-3.0 全文：https://www.gnu.org/licenses/agpl-3.0.txt）

**结论三：商标是最便宜的杠杆，我们这一层已经做得不错，缺的是产品内的落地。**
版权侵权要比对源码、证明实质性相似、跨境取证；商标侵权只要看对方的产品名、域名、应用商店条目。参见 Mozilla/Debian（Firefox 名称许可撤回，Debian 被迫叫 Iceweasel 十年，https://en.wikipedia.org/wiki/Debian%E2%80%93Mozilla_trademark_dispute）与 Elastic v. Amazon（和解后 AWS 全线更名 OpenSearch，https://www.elastic.co/blog/elastic-and-amazon-reach-agreement-on-trademark-infringement-lawsuit）。
我们的 `legal/TRADEMARKS.md` 与 README 的商标段落已经写得相当规范（图形商标 9/35/42 类已注册，文字商标标 ™）。**缺的不是政策，是产品界面里没有任何一处提到 AI WorkDeck 与 AGPL 的关系。**

---

## 1. 目标与明确不做什么

### 目标（按优先级）
1. **可发现**：抄袭者在自己的合规流程里就能发现用了我们的 AGPL 代码。
2. **可举证**：一旦真要维权，我们能把「具体哪几行 ↔ 谁的著作权 ↔ 何时创作」这条链条完整摆出来。
3. **可判别**：拿到一个可疑产品（安装包 / SaaS / 导出的文档），能在**不拿到对方源码**的前提下做出初步判断。
4. **不腐烂**：这些性质由 CI 守住，不靠人记得。

### 明确不做（红线，任何 PR 违反直接驳回）
- **不做隐蔽回传**。不做静默的许可探测、盗版上报、"是否官方构建"的暗中检测。理由是叠加的：与 AGPL 授予的运行自由冲突（§10 禁止 further restrictions）；PIPL 不承认 GDPR 式的 legitimate interest 基础，无单独告知同意即违法；技术上毫无用处（源码全公开，对方第一件事就是 grep 掉端点）；声誉代价灾难级（Audacity 2021 年只是**宣布**要加遥测就引发暴动并催生 Tenacity 分叉，https://www.theregister.com/2021/07/06/audacity_fork/）。**我们的用户是律师，处理的是当事人机密材料，客户端偷偷外联等同于产品死亡。**
- **不做代码混淆 / 加壳**。与 AGPL 要求提供 Corresponding Source 直接矛盾。
- **不做任何影响功能、性能、可读性的东西**。判据：一个改动如果需要在 PR 里解释"它看起来没用但请别删"，它就不该进来。
- **不做故意错误式陷阱**。地图可以印一条不存在的街（trap street），我们不能让法律文书功能"偶尔算错"。
- **不做 badgeware**。不在每个界面强制显示 logo。OSI 曾长期抵制此类 attribution 条款（SugarCRM Exhibit B、Alfresco 等最终都放弃，https://lwn.net/Articles/243841/）。我们只做 §7(b) 允许的克制版本（见 2.2）。
- **不为了品牌统一去改历史命名**（`com.checkba`、`checkba://`、`CHECKBA_*`）。详见 4.2。

### 一条贯穿全篇的判据
> **删掉它之后代码照常工作 ⇒ 它对有意的抄袭者是零成本的。**

这条判据把候选措施一刀切成两半。魔数常量水印、注释里的暗号、藏头诗式命名，全部落在无效那半边（一次 `sed` 就没）。有效的那半边只有两类：
- **功能绑定的胎记（birthmark）**：删掉就破坏兼容性（如插件协议的 `awd: 1`）；
- **法律要求的告示**：删掉本身就是违约与"明知"的证据（如 SPDX 头、AGPL §5(d) 的 Appropriate Legal Notices）。

学理依据：Collberg & Thomborson 的水印分类（POPL '99, https://dl.acm.org/doi/pdf/10.1145/292540.292569）区分静态水印与动态水印，而 Tamada 等人的 software birthmark 路线（k-gram birthmarks, Myles & Collberg SAC '05, https://collberg.cs.arizona.edu/content/research/papers/myles05k-gram.pdf）不需要预埋任何东西——对一个源码全公开的项目，birthmark 路线才是对的。动态图水印在 SandMark 的实测里体积增加 40%-75%、性能下降最多 36%、提取一次要 0.6-8.9 分钟（https://jameshamilton.eu/sites/default/files/GraphWatermarkingSurvey.pdf），对我们完全不值得。

---

## 2. 分层方案总览

| 层 | 做什么 | 主要收益 | 成本 |
|---|---|---|---|
| L1 法律层 | 商标、软著登记、CLA 与逐行权属、时间戳 | 权属与时间的证明 | 低（多为一次性、非工程） |
| L2 声明层 | SPDX/REUSE 逐文件头、AGPL §5(d) 界面告示、`X-Source-Code` 头 | **让对方自己发现；把删除行为变成"明知"的证据** | 中（1500+ 文件机械改） |
| L3 静态胎记层 | 登记既有天然胎记，加字面量断言防自毁 | 源码级同源性证明 | 极低（多数无需改代码） |
| L4 产物指纹层 | 构建指纹、文档 Generator 元数据、UA 统一 | **黑盒取证，无需对方源码** | 低 |
| L5 取证流程层 | 举证清单 SOP、比对工具链、DMCA/发信模板 | 真要用时能立刻动起来 | 低（写文档） |

**投入产出排序**：L2 > L4 > L1 > L3 > L5。L3 几乎不用花钱（资产已存在），但也就不是瓶颈。

---

## 2.1 L1 法律层

### 做法
1. **商标**：已完成图形商标 9/35/42 类注册。待办：
   - 记忆里「35/42 类异议期 2026-08-27 后要回查」这条需要闭环。
   - 中国是申请在先制，抢注风险最大，文字商标「AI WorkDeck」建议尽快提交申请（现在只是未注册标 ™）。
2. **软著登记**：向中国版权保护中心登记。《计算机软件保护条例》第七条：登记证明文件是登记事项的**初步证明**。实践中它近乎是准入门槛级的权属证据。交存材料按《计算机软件著作权登记办法》第十条：源程序前后各连续 30 页、每页不少于 50 行（https://www.ncac.gov.cn/xxfb/flfg/bmgz/202410/t20241015_869486.html）。与开源不冲突——代码本来全公开，登记的是权属而非保密。**建议每个大版本各登记一次，形成时间序列。**
3. **美国版权登记（若要保留法定赔偿路径）**：17 U.S.C. §411(a) 要求注册核准后才能起诉（Fourth Estate v. Wall-Street.com, 2019, https://www.supremecourt.gov/opinions/18pdf/17-571_e29f.pdf）；§412 要求在侵权开始前、或首次发表后三个月内注册，才能主张法定赔偿与律师费。对开源项目这几乎是唯一现实的经济杠杆。**注意三个月的窗口——过了就永久失去。**
4. **逐行权属映射**：这一条是 GPL 执法史上最惨痛的教训。Hellwig v. VMware（汉堡地院 2016 一审、2019 二审）两审皆败，**败因不是 VMware 没抄，而是原告无法把 VMware 产品里的具体代码行对应到自己名下**（https://sfconservancy.org/news/2019/apr/02/vmware-no-appeal/）。落地：CLA 流程无漏（`.github/workflows/cla.yml` 已有 contributor-assistant，需确认覆盖维护者直推路径）+ 定期导出 `git blame` 归属快照与 CLA 名单对账 + 对首批关键文件维护逐段作者表。
5. **时间戳**：双轨。境外用 GPG 签名（**已在做**——抽查最近 40 条提交全部带签名）+ `git tag -s` + OpenTimestamps（https://github.com/opentimestamps/opentimestamps-client/blob/master/doc/git-integration.md）；境内每个发版对产物哈希做一次可信时间戳。《最高人民法院关于互联网法院审理案件若干问题的规定》第十一条明确认可"电子签名、可信时间戳、哈希值校验、区块链"固化的电子数据（https://www.court.gov.cn/zixun/xiangqing/116981.html），杭州互联网法院 2018 年华泰一媒案是首个确认区块链存证效力的判例（https://www.court.gov.cn/zixun/xiangqing/104552.html）。
   - **风险提示**：GPG 私钥丢失 = 这条证据链断掉。需要确认密钥的备份与托管。

### 落地位置
`legal/`（已有 LICENSE / CLA.md / COMMERCIAL-LICENSE.md / TRADEMARKS.md / PRIVACY.md / MARKETPLACE-TERMS.md，结构已经很好）。新增：`legal/REGISTRATIONS.md`（登记与存证台账，非密）。

---

## 2.2 L2 声明层（最高优先级）

### 2.2.1 逐文件 SPDX 头 + REUSE 合规

**现状（实测）**：全仓 `SPDX-License-Identifier` 只出现 6 次，其中 5 次在 zetaoffice 相关文件且是 `MIT`。带 `Copyright (c)` 的文件 7 个，其中 5 个是 LICENSE 文件本身。**我们的一方源码里一个自己的版权头都没有。**

**做法**：按 FSFE REUSE Specification v3.x（https://reuse.software/spec-3.3/）：
1. 每个一方源文件顶部两行：
   ```
   SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
   SPDX-License-Identifier: AGPL-3.0-or-later
   ```
2. 根目录 `LICENSES/AGPL-3.0-or-later.txt` 放全文（文件名即 SPDX 标识符）。
3. 二进制、字体、WASM 产物等无法写注释的，用 `REUSE.toml` 覆盖（注意 `REUSE.toml` 与旧的 `.reuse/dep5` 互斥，dep5 已废弃）。
4. 第三方件保持上游原头一字不改，物理隔离进 `vendor/`。

**为什么这是第一优先级**（三条理由，缺一不可）：
- 抄袭者公司的 SCA 扫描器（Black Duck 的 codeprint snippet 匹配、SCANOSS 的 winnowing 指纹、ScanCode 的许可证文本匹配）读的就是 SPDX 表达式。没有头，我们的代码在对方合规报告里可能显示为"未知许可"甚至漏过。
- AGPL §4 要求 "keep intact all notices"。逐文件带头之后，对方**每一次删头都是一条可机器检测、可批量举证的独立违约事实**，并直接坐实"明知"（willfulness）。
- 举证颗粒度从"仓库"降到"文件"，直接回应 Hellwig 案的教训。

**副作用（要坦白）**：diff 会变吵；有些构建工具需要配置忽略；Vue SFC 的注释会保留在产物里（可接受）。这是本方案里唯一有实质工程噪音的一项，但它的收益也是最大的。

**要先解决的一个真实漏洞**：`frontend/src/zetaoffice/public/office_thread.js` 现在头是 `SPDX-License-Identifier: MIT`。它源自 #39 Phase 0 的 529 行 spike（当时照抄 allotropia/zetajs 的 MIT 示例，头是对的），此后经 63 次提交长到 **6827 行**，是整个文档编辑器最核心的单个文件。**按现在这个头，任何人拿走它就是 MIT 的。** 详见 `hardening-candidates.md` B1。

### 2.2.2 AGPL §5(d) 的界面告示（Appropriate Legal Notices）

**这是本次调研里最重要的单条发现。**

AGPL-3.0 §5(d)：`If the work has interactive user interfaces, each must display Appropriate Legal Notices; however, if the Program has interactive interfaces that do not display Appropriate Legal Notices, your work need not make them do so.`

注意后半句：**这是有条件继承的。只有当上游本身显示 ALN，下游才必须显示。**

§0 对 Appropriate Legal Notices 的定义要求界面里有 "a convenient and prominently visible feature" 显示：(1) 适当的版权声明；(2) 告知无担保、被许可人可依本许可证分发、以及如何查看许可证副本。并明确："If the interface presents a list of user commands or options, such as a menu, a prominent item in the list meets this criterion."

**实测现状**：全仓搜索 `frontend/src` 与 `desktop/main`，**产品界面里没有任何一处提到 AGPL、GNU 或开源许可**（命中的全是 package-lock 与构建脚本抓第三方件的噪音）。`frontend/src/pages/` 下也没有 About/关于 页面。

**后果**：因为我们自己的交互界面不显示 ALN，**所有下游 fork 都免于这项义务**。我们等于主动放弃了最容易取证的一个把柄——本来只要打开对方产品的关于页看一眼就能判断。

**做法**（一次性，工作量以小时计）：
- 桌面端设置菜单里加一个显眼的「关于」项，内容包含：
  - `AI WorkDeck <version> (<commit sha>)`
  - `Copyright (C) 2025-2026 北京京微资易科技有限公司 and AI WorkDeck contributors`
  - `This program comes with ABSOLUTELY NO WARRANTY.`
  - `This is free software, and you are welcome to redistribute it under the terms of the GNU Affero General Public License version 3 or later.`
  - 「查看许可证全文」入口 + 「获取源代码 https://github.com/zeweihan/aiworkdeck」入口
  - 商标行：`AI WorkDeck™ 与 K 形标识是北京京微资易科技有限公司的商标。开源许可证不授予任何商标权利。`
- Office / WPS 插件任务窗格与 H5 各自的设置里放同样的入口（每个 interactive interface 都要）。
- FSF 确认下游可以改变展示位置（例如从页脚挪到 About 页），但不能删除（https://www.fsf.org/blogs/community/gpl-compliant-legal-notices-author-attributions）。

### 2.2.3 §7(b) 附加条款：克制版

GPLv3/AGPLv3 §7(b) 允许附加 "Requiring preservation of specified reasonable legal notices or author attributions"。FSF 明确划线：**链接不算，logo 一般也不算**（"logos are neither 'legal notices' nor 'author attributions' as normally understood"）。

**我们的做法**：把诉求拆两层——
- 法律层走 §7(b)：只要求保留 About 页里的版权行、作者归属与许可证声明。文字克制。
- 品牌层走 §7(e)（允许拒绝授予商标权）：logo 与「AI WorkDeck」字样不走版权走商标，由 `legal/TRADEMARKS.md` 管。**这正是 FSF 推荐的路径**——商标权人可以阻止有损声誉的使用，同时用户保留移除商标的选项。

我们现有的 README 商标段与 TRADEMARKS.md 已经是这个形态，不需要改，只需要在产品界面落地（2.2.2）。

### 2.2.4 `X-Source-Code` 响应头

后端所有 HTTP 响应加一个 `X-Source-Code: https://github.com/zeweihan/aiworkdeck` 头（一个 `OncePerRequestFilter`，半小时的活）。
- 服务 AGPL §13：任何人把 AI WorkDeck 改造成对外 SaaS，这个头跟着走。
- 取证价值极高：**不需要对方源码，不需要装对方桌面端，打一次对方的公网服务就能取证。**
- 对方要么保留（我们取证），要么删掉（willful）。
- 现状：backend 里只有 `FeedbackConsoleConfig` 一处用到过 `WebMvcConfigurer`/`OncePerRequestFilter`，加一个新 filter 无冲突风险。

### 2.2.5 进扫描器语料库

SCA 扫描器只认识它索引过的代码。当前 `com.checkba:plugin-api` 是"不在任何远端仓库"的本地件——**没有任何扫描器认得它**。待办：
- ClearlyDefined 上为 `github/zeweihan/aiworkdeck` 补 curation；
- plugin-sdk 发 npm、plugin-api 发 Maven Central（这本来对插件生态也有好处）；
- 发布 SBOM（SPDX 或 CycloneDX），供应链客户尽调会用。

---

## 2.3 L3 静态胎记层

**核心认识：我们已经有一批很强的天然胎记，缺的是登记与保护，而不是新增。**

天然胎记的证据效力**高于**人为植入的水印，因为它们功能必需、无法在不破坏兼容性的前提下删除。完整清单见 `hardening-candidates.md` A 组（13 条），这里只讲分类与规则。

### 三个强度档

| 档 | 定义 | 例子 | 剥离成本 |
|---|---|---|---|
| 一档：契约绑定 | 第三方生态依赖它，改了就破坏兼容性 | 插件 SDK 的 `{awd: 1}`；74 个 `--awd-*` 语义令牌集合 | 极高 |
| 二档：产物签名 | 会写进用户文件 / 用户 Git 仓库 / 用户目录 | `X-AWD-Kind:` 提交尾注、`__ai_anchor_N` 书签、`.awd/tree.json`、`checkba://filelink` 超链接 | 中（要迁移存量数据） |
| 三档：结构与语料 | 非显然的设计决策与独特文本 | 十一个协议标签 + 窄化转义规则；`.claude/agents/*.md` 的 5979 行中文技术散文 | 高（等于重写） |

**三档里 `.claude/agents/*.md` 这份语料被严重低估。** 代码相似度需要专家论证；一段一模一样的中文注释不需要。Cadence v. Avant! 案的黄金证据就是"同一条注释，同一个单词拼错"（https://spectrum.ieee.org/software-forensics-tools-enter-the-courtroom）；Google v. Oracle 里陪审团认定复制的全部依据是 9 行的 `rangeCheck`。**注释与文档是资产，这是"注释写为什么不写是什么"这条既有习惯的意外红利。**

### 规则
- **不新增人为水印**。理由见 1 节的判据。唯一允许的"新增"是把散落的字面量收敛成具名常量（既是工程整洁，也让胎记可校验）。
- **既有胎记必须加字面量断言测试**（`assertEquals("__ai_anchor_", ANCHOR_PREFIX)`），防止我们自己在某次重构里抹掉。这是最容易被忽略的失败模式。
- **不许为品牌统一改历史命名**。`checkba` 是旧代号（核查宝），与对外品牌 AI WorkDeck 不一致——**这个不一致是资产**：一个从零写的竞品绝无可能选中 `checkba` 这个词。改名对用户零收益（包名用户看不见），对我们是自毁最大的一块证据。这条要写进 `CLAUDE.md`（已写入 `CLAUDE.md` 全局约定末段）。

### 关于 trap street 的法律真相（务必记准，很多人搞反）

**陷阱本身几乎肯定不受版权保护；它的价值是"抄袭的证据"，不是"另一项侵权"。**
- Nester's Map & Guide Corp. v. Hagstrom Map Co., 796 F. Supp. 729 (E.D.N.Y. 1992)：明确认定 copyright traps 本身不受保护——地名不受版权保护，虚构地名同样不受（https://law.justia.com/cases/federal/district-courts/FSupp/796/729/1559434/）。
- Feist Publications v. Rural Telephone, 499 U.S. 340 (1991)：Rural 插的 4 条虚构条目全部出现在 Feist 的电话簿里，抄袭事实被钉死，但最高法院仍判**不侵权**，因为事实性名录缺乏独创性（https://supreme.justia.com/cases/federal/us/499/340/）。

**推论**：胎记要埋在**有版权的表达内部**（源码、注释、结构），而不是埋在事实数据里。我们 L3 的全部条目都符合这一点。

---

## 2.4 L4 产物指纹层

黑盒取证路径——**不需要拿到对方源码**，这是它的独特价值。

1. **构建指纹**：桌面端打包时把 version + git commit sha + 构建时间写进 `build-info.json`，并在 2.2.2 的「关于」窗口展示。合法、无隐私问题、正是取证需要的。**注意这是溯源标记，不是探测器**——它不外联。
2. **文档 Generator 元数据**：保存 docx/pptx 时写标准 core properties 的 `Application` = `AI WorkDeck <version>`。现状是**一个字都不写**（`office_thread.js` 里搜不到 `DocumentProperties`，后端 POI 路径也没写 core properties），这在办公软件里反而不寻常——Word / WPS / LibreOffice 都写。取证成本最低：拿到一份对方产品导出的文档即可。要给用户一个关闭开关（律所交付前会 scrub 元数据），且**只写产品名与版本，不带任何用户身份信息**。
3. **出站 UA 统一**：现在有 `checkba-browser/1.0`、`AI-WorkDeck`、`AI-WorkDeck-Capability-I…`、`aiworkdeck-build` 四种写法散在四处。收敛成一个 `ProductIdentity` 常量，格式 `AIWorkDeck/<version> (<component>)`。**服务端可观测**：对方产品用我们的浏览器面板打外部站点时，我们自己的服务器日志能看到。改 UA 前必须实测外部站点（企查查/法宝）没有 UA 白名单。
4. **服务端日志**：官网、云网关、更新检查、插件市场 registry 的访问日志本就存在。竞品若复用我们的 registry / 更新源 / 字体 / 模型网关，服务端日志就是最干净的证据链，且不需要在用户机器上加任何探针。**这条最应该加固**：给 registry 与更新接口留存 build identifier 与 UA 并归档。
5. **端口链与握手形状**：桌面后端 5269 → 5369 → 5169，探到占用先打 `/api/admin/wizard` 验明再比对 jar 指纹。纯黑盒可取证——装上对方产品看它监听哪个端口、`/api/admin/wizard` 返回什么形状即可。已存在，只需登记。

---

## 2.5 L5 取证流程层

见第 7 节的 SOP。

---

## 3. 开发者怎么遵守

见 `.github/CONTRIBUTING.md`「Provenance and license notices」一节（正向条款 + 明确无效的做法）与 `CLAUDE.md` 全局约定末段。

---

## 4. 自动化校验

全部放在 `scripts/`，接进 `.github/workflows/ci.yml`（现有 CI 已有 `check:emits` / `check:locales` / `check:nav:full` 三条前端契约检查，形态可直接照抄 `frontend/scripts/check-*.mjs`）。

| 脚本 | 检查 | 阻塞 | 说明 |
|---|---|---|---|
| `check-spdx.mjs` | 本 PR **新增**的一方源文件带 SPDX 双行头 | 是 | 只看 `git diff --name-only origin/master...` 的新增文件，存量不阻塞，避免一上来全红 |
| `check-fingerprints.mjs` | 登记在册的胎记常量仍在其登记位置且字面量未变 | 是 | 见第 6 节的登记簿形态 |
| `check-vendor-headers.mjs` | `vendor/` 下文件头未被改动（比对上游哈希） | 是 | |
| `reuse lint` | 全仓 REUSE 合规 | 先只报告 | 覆盖率上来后转红 |

**关于 `check-fingerprints.mjs` 的设计要点**：脚本本身在公开仓库里，不能把胎记清单直接写进去（否则等于把陷阱清单公开给对手，Nester's 案里陷阱之所以有效正因为对方不知道）。三种形态见第 6 节。

---

## 5. 胎记登记簿的形态：建议与理由

三个候选：

**方案 A：清单在私有仓，公开仓只放脚本**
脚本从环境变量或 CI secret 拉一份清单 URL，本地开发时清单缺失则脚本 skip（打印一行提示）。
- 优点：清单不公开。
- 缺点：本地开发不校验，等于只有 CI 兜底；fork 者跑 CI 会全部 skip，看起来像坏掉的脚本。

**方案 B：清单公开，形态是"常量必须存在"的断言**
把胎记校验拆成**普通的单元测试**，散在各自模块里（`assertEquals("__ai_anchor_", ANCHOR_PREFIX)`），不集中成一份清单，也不叫"胎记测试"，就叫"契约测试"。
- 优点：本地与 CI 都跑；不暴露"我们在收集陷阱"这件事；对外看起来就是正常的契约锁定测试（**它本来也确实是**——`awd: 1` 与 `--awd-*` 令牌集合就是插件生态的公开契约）。
- 缺点：分散，没有一个总览。

**方案 C：两者都要（推荐）**
- **公开仓**里只有分散的契约测试（方案 B），不出现"防抄""水印""胎记"任何字样。这些测试的公开理由是真实的：它们锁定的是第三方插件依赖的公开契约。
- **私有仓**（`zeweihan/dev-board` 或一个新的 `zeweihan/awd-legal`）里放一份 `FINGERPRINT-REGISTRY.md`，每条记：编号、位置（文件:行）、形态（字面量）、加入日期与 commit、强度档（一/二/三）、能证明什么、剥离成本、对应的公开契约测试在哪。
- **私有仓**里另放 `check-fingerprints.mjs`，只在维护者本地或私有 CI 跑，用来定期核对登记簿与代码是否还对得上。

**理由**：登记簿的价值有两个——取证时的索引，和防止我们自己抹掉。第二个价值由公开的契约测试完成（且不需要保密）；第一个价值需要保密（一份公开的"我们的独特特征清单"就是给抄袭者的剥离指南）。方案 C 把两个价值分开承载，各自用最合适的载体。

**登记簿也要做时间戳。** 它本身就是证据的一部分（"我们在 2026-09 就登记了这条特征"），私有仓的 commit 加 GPG 签名，重要版本做一次可信时间戳。

---

## 6. 举证流程 SOP

### 阶段 0：怀疑（还没确认）
1. **先核实，别声张。** SFC《Principles of Community-Oriented GPL Enforcement》第一条就是"必须先仔细核实再指控"（https://sfconservancy.org/copyleft-compliance/principles.html）。
2. 黑盒取证（不需要对方源码）：
   - 装对方桌面端：看监听端口（5269 链？）、`/api/admin/wizard` 返回形状、关于页有无我们的告示、安装包里的 `build-info.json`。
   - 打对方 SaaS：抓响应头（`X-Source-Code`？）、看 SSE 事件名与协议标签形状。
   - 拿对方产品导出的一份 docx：`unzip -p x.docx docProps/app.xml` 看 Generator；`word/document.xml` 里搜 `__ai_anchor_`。
   - 拿对方产品建的一个项目目录：看有无 `.awd/tree.json`，`git log` 里有无 `X-AWD-Kind:` 尾注。
   - 如果对方有插件生态：看插件协议信封是不是 `{awd: 1}`。
3. **全程存证**：每一步用可信时间戳平台或司法链做自动抓取存证（对齐《最高法互联网法院规定》第十一条的可验证性要求）。截图不够，要能重现的抓取记录。

### 阶段 1：确认（拿到源码或二进制之后）
4. 源码比对：SCANOSS（开源、winnowing、保留原始行号，可自证方法论 https://github.com/scanoss/scanoss.py/blob/main/WINNOWING.md）+ JPlag（token 化 + Greedy String Tiling，对改名重排稳健）。**用开源工具而非闭源工具，可复现性在法庭上是加分项。**
5. 排除良性同源：把我们自己依赖的第三方件、自动生成代码、教科书算法先剔掉，否则相似度报告不可信。
6. **做逐行 ↔ 著作权人映射**（Hellwig 案的教训）：对每一处命中，给出我们这边的 `git blame` 作者、commit sha、日期，以及该作者的 CLA 签署记录。
7. 生成《特征命中清单》：每条一行——特征编号（对应登记簿）、我们的位置、对方的位置、形态、独立发明的可能性评估。

### 阶段 2：接触
8. **先发信要求合规，不要先起诉。** SFC 原则：首要目标是促成合规而非惩罚；诉讼是最后手段；保密可以提高对方配合度（公开指控往往直接升级为诉讼）；绝不收钱换取放过违约。
9. **注意 §8 的时限**：一旦发现，不要"等它做大"。对方停止违约满 60 天而我们未通知 = 许可永久恢复。首次通知后 30 天内改正的也永久恢复。**所以第一封信的现实目标是让对方合规或买商业授权，不是索赔。**
10. 信里要写清：具体文件与行号、AGPL 的具体条款（§4 keep intact notices / §5(a) modification notice / §5(d) ALN / §13 网络源码提供 / §10 further restrictions）、30 天治愈期、以及一条出路（`legal/COMMERCIAL-LICENSE.md`）。

### 阶段 3：升级
11. **商标路线优先于版权路线**（更快更便宜）：如果对方用了「AI WorkDeck」名称或 K 形标识，直接走商标投诉/行政查处/应用商店下架，不用碰源码比对。
    - **反面教材**：WP Engine v. Automattic，Automattic 用平台封禁做报复性打击，2024-12 被加州北区法院下初步禁令（https://techcrunch.com/2024/12/10/court-orders-mullenweg-and-automattic-to-restore-wp-engines-access-to-wordpress-org）。**商标执法必须走商标程序，不能变成对生态参与者的断供报复。**
12. **GitHub DMCA**（若对方把代码传上 GitHub）：
    - 必须给出**涉嫌侵权材料的 URL**；只有部分文件侵权时要**指明具体文件或行号**。GitHub 无法停用单个文件，只能停整个仓库或 package。
    - **fork 不自动处理**，必须在通知里明示包含 forks。
    - GitHub 会先给用户**约 1 个工作日**修改窗口。
    - 反通知后权利人须在 10-14 天内起诉，否则内容恢复。
    - **关键坑**：GitHub 明确提示"代码带版权声明不等于侵权，它可能是依据开源许可证在使用"——**纯粹的 AGPL 条款违反 GitHub 不会当作自动的 DMCA 事由**。主张必须构造成："超出许可证范围使用 ⇒ AGPL §8 许可自动终止 ⇒ 未经许可的复制"，并附行号级比对。
    - 所有 DMCA 通知都会公开在 https://github.com/github/dmca ——双刃剑，既是公开施压，也会暴露我们的比对方法。
    - 政策原文：https://docs.github.com/en/site-policy/content-removal-policies/dmca-takedown-policy

---

## 7. 成本与副作用评估

| 项 | 一次性成本 | 持续成本 | 功能影响 | 可读性影响 | 风险 |
|---|---|---|---|---|---|
| SPDX 头（L2.1） | 1500+ 文件机械改，一个 sonnet 子代理一天 | CI 一条检查 | 无 | diff 变吵，每文件多 2 行 | 低。唯一风险是漏掉 vendored 目录，误把第三方件标成 AGPL |
| 修 `office_thread.js` 的 MIT 头 | 半天 | 无 | 无（改注释） | 无 | **要维护者拍板**，涉及"能否改一个已按 MIT 发布过的文件的许可" |
| AGPL §5(d) 界面告示（L2.2） | 桌面 + H5 + Office/WPS 插件共约一天 | 版本号跟随 | 无（新增一个菜单项） | 无 | 低。要注意每个 interactive interface 都要有 |
| `X-Source-Code` 头（L2.4） | 半小时 | 无 | 无 | 无 | 极低 |
| 胎记登记 + 契约测试（L3） | 两小时 | 每次改契约要同步 | 无 | 无 | 低 |
| 文档 Generator（L4.2） | 半天 | 无 | 无（标准 OOXML 字段） | 无 | 中。**必须给用户关闭开关**，律所交付前会 scrub 元数据；且不许带用户身份信息 |
| UA 统一（L4.3） | 半天 | 无 | **需实测**外部站点无 UA 白名单 | 改善 | 中 |
| 软著登记 / 时间戳（L1） | 非工程，维护者或律师 | 每大版本一次 | 无 | 无 | 低 |
| 逐行权属映射（L1.4） | 一个脚本 + 一次人工核对 | 季度对账 | 无 | 无 | 低 |

**总体**：主力工作（L2）是机械的、零功能影响的，风险集中在"别把第三方件误标成 AGPL"这一点，靠 `vendor/` 物理隔离 + `check-vendor-headers.mjs` 兜住。

**最大的副作用不是技术性的**：如果我们把这套东西的**动机**对外表述成"防止别人抄"，会伤害开源形象（我们的 README 明确写着欢迎 fork、有 CLA、有 RFC、有社区例会）。对外的表述应该是**开源合规与可追溯性**——这也确实是事实：L2 的每一项都是 AGPL 本来就要求或推荐的合规动作，我们只是把它做完整。**"我们不做隐蔽遥测"这条公开承诺本身就是品牌资产。**

---

## 8. 建议的落地顺序

**第一批（本周可做，全部零功能影响）**
1. 修 `office_thread.js` 的 MIT 头，`zeta.js` 挪 `vendor/`（要维护者先拍板）
2. `X-Source-Code` 响应头（半小时）
3. AGPL §5(d)「关于」界面告示（桌面端先做，插件跟上）
4. 全仓 SPDX + REUSE + `check-spdx.mjs` CI
5. 三条最强天然胎记（`awd: 1`、`X-AWD-Kind:`、`__ai_anchor_`）加契约断言测试
6. 往 `CLAUDE.md` 写一条"不许把 `com.checkba` 改名"

**第二批**
7. 文档 Generator 元数据（带开关）
8. UA 统一 + registry/更新接口日志留存
9. 私有胎记登记簿建册 + 逐行权属映射脚本
10. ClearlyDefined curation + plugin-sdk 发 npm

**第三批（非工程，交维护者/律师）**
11. 文字商标「AI WorkDeck」提交注册；35/42 类异议期回查闭环
12. 软著登记（每大版本一次）
13. 每个 release 的可信时间戳
14. 确认 GPG 签名密钥的备份与托管

---

# 附录：现有关键代码加固候选清单

范围：针对维护者提出的 (b)「现有关键部分补充防抄措施，千万不要影响功能」。
本清单只列**候选**，不代表已决定要做。每条给：文件 / 形态 / 为什么零功能影响 / 怎么验证没影响 / 能证明什么 / 抄袭者剥离成本。

排序按性价比（能证明的强度 ÷ 落地与维护成本），前 5 条标「建议首批」。

---

## 先说结论：我们已经有一批很强的天然胎记，缺的是登记与保护

调研仓库后最重要的发现是：**AI WorkDeck 的防抄资产已经存在，只是没人把它们当资产管理**。
下面 A 组是既有天然胎记（不用改代码，只要登记 + 加护栏），B 组才是需要新增的。
天然胎记的证据效力普遍**高于**人为植入的水印，因为它们是功能必需的、无法在不破坏兼容性的前提下删除。

---

## A 组：既有天然胎记（零改动，只需登记 + CI 护栏）

### A1（建议首批）插件 SDK 线协议魔数 `awd: 1`
- **文件**：`sdk/plugin-sdk/awd-plugin-sdk.js`（`PROTOCOL` 常量、`if (!msg || msg.awd !== PROTOCOL) return`），宿主侧配对实现。
- **形态**：宿主与插件 iframe 之间每一条 postMessage 的信封字段名就是 `awd`，值恒为 `1`。握手 / call / result / theme / event 五类消息全部带。
- **为什么零影响**：本来就在跑，不动一行。
- **能证明什么**：**这是全清单里最强的一条。** 任何人 fork 我们的插件生态，只要想让既有插件能装上去，就必须原样保留 `awd: 1` 这个字段名与值——改成 `xyz: 1` 会让所有第三方插件握手失败。这是 Collberg 意义上的"功能绑定型胎记"（birthmark），不是可选水印。
- **剥离成本**：极高。剥离 = 放弃插件生态兼容性。
- **建议动作**：写进胎记登记簿；在 `sdk` 的测试里加一条断言 `PROTOCOL === 1 && 字段名 === 'awd'`，防止我们自己哪天重构掉。

### A2（建议首批）提交消息尾注 `X-AWD-Kind:` / `X-AWD-Note:` / `X-AWD-Skipped-Large-Files:`
- **文件**：`backend/src/main/java/com/checkba/version/ProjectRepoService.java:259-262`（三个常量），`VersionEntry.java`，解析在 `extractTrailer()`。
- **形态**：我们写进**用户项目 Git 仓库**每一条自动提交的尾注。
- **为什么零影响**：已在跑。
- **能证明什么**：这是**会向外扩散的胎记**——它不留在我们的代码里，而是留在用户的项目仓库里。任何人拿走我们的版本记录模块，用户机器上产生的 Git 历史就带 `X-AWD-Kind:`。取证时不需要拿到对方源码，拿到对方产品生成的一个项目目录即可。等价于地图行业的 trap street：不是水印，是**产物签名**。
- **剥离成本**：中。改字符串很容易，但改了之后读不了用户既有的历史（`extractTrailer` 按前缀匹配），要么做双前缀兼容（那就等于保留了证据），要么放弃老数据。
- **建议动作**：登记；在 `ProjectRepoServiceTest` 加断言常量字面量不变。

### A3（建议首批）文档锚点书签命名 `__ai_anchor_N` 与形状命名 `__awd_shape_N`
- **文件**：`frontend/src/zetaoffice/public/office_thread.js:90`（`const ANCHOR_PREFIX = '__ai_anchor_'`）、`:1510`（`'__awd_shape_' + (counter++)`）。
- **形态**：编辑器把隐藏书签写进正在编辑的文档，作为稳定定位句柄；未命名形状补一个稳定名。这些名字**会随文档一起被保存进 .docx / .pptx**。
- **为什么零影响**：已在跑，是 §0.2 锚点机制的实现细节。
- **能证明什么**：又一条产物签名，而且是**留在最终交付物里**的。拿到对方产品生成的一份 docx，解开 `word/document.xml`，若出现 `w:bookmarkStart w:name="__ai_anchor_3"`，配合我们的公开源码就是一条极强的间接证据。
- **剥离成本**：低（改字符串即可）。但抄袭者通常不知道要改。
- **建议动作**：登记；`office_thread.js` 内加一条 e2e 断言前缀字面量；**不要**把这两个前缀写进公开的 README / 领域文档里去宣传（登记簿是私有的，见本文第 5 节）。

### A4 AI 面板伪 XML 协议标签集与转义规则
- **文件**：`frontend/src/composables/agentTagProtocol.mjs`（`PROTOCOL_TAGS` 十一项：thinking / title / process / step / tool_code / tool_output / walkthrough / final / question / option / artifact），后端配对 `backend/src/main/java/com/checkba/service/ai/AgentTagProtocol.java`，双端由 `AgentTagProtocolTest` 对拍。
- **形态**：标签集合本身 + 「只把已知标签形状的起始 `<` 转成 `&lt;`，不全量转义」这条**非显然的设计决策**（为了让合同正文里的 `<甲方>` 原样呈现）。
- **能证明什么**：标签名单独看不算独特，但**这十一个标签的确切集合 + 这条窄化转义规则 + 双端对拍测试**三者同时出现，几乎不可能独立发明。属于 Collberg-Thomborson 分类里的"结构性静态胎记"。
- **剥离成本**：中高。改标签名要同时改前后端、改测试、改模型 system prompt，且会影响历史消息回灌。
- **建议动作**：登记为「结构胎记」，无需改代码。

### A5 桌面端端口链 5269 → 5369 → 5169 与 build 指纹握手
- **文件**：`desktop/main/services/backend-service.js:11`（`DESKTOP_PORT_CHAIN = [5269, 5369, 5169]`），`desktop/main/main.js:352`，`backend/.../WizardController.java:66-70`（回显 `AWD_BACKEND_BUILD`）。
- **形态**：选 5269 是有故事的（IANA 注册给 XMPP server-to-server 的端口，日常桌面机上基本不会被占），降级链的具体三个数字、以及"探到占用先打 `/api/admin/wizard` 验明是不是自家后端、再比对 jar 的 size-mtime 指纹"这套握手都是我们特有的。
- **能证明什么**：黑盒可观测。不需要对方源码——装上对方产品，看它监听哪个端口、打 `/api/admin/wizard` 看返回体形状，就能取证。**这是本清单里唯一一条纯黑盒可取证的**，价值特殊。
- **剥离成本**：低（改数字）。但改了就要重做端口复用逻辑的全部测试。
- **建议动作**：登记；`desktop/tests/service-manager.test.js` 已有一条测试提到端口链，把它升级成对字面量的断言。

### A6 自定义 URI scheme `checkba://` 家族
- **文件**：`backend/.../model/entity/DocFileLink.java:12`（`checkba://filelink?k=&projectId=`）、`service/ai/evidence/MemoryEvidenceRetriever.java:88-94`（`checkba://file/`、`checkba://conversation/`、`checkba://memory/`）、`DocumentEditTools.java:1516`（包装形式 `https://checkba-internal.local/open?u=checkba://filelink?...`）。
- **形态**：`checkba` 是项目旧代号（核查宝），产品对外早已叫 AI WorkDeck。这个**历史遗留的不一致本身**就是最好的胎记——一个从零写的竞品绝无可能选中 `checkba` 这个词。同理还有 Java 包根 `com.checkba.*`（1045 个 .java 文件）、环境变量 `CHECKBA_BACKEND_PORT`、preload 注入的 `window.checkbaDesktop`。
- **能证明什么**：极强。而且 `checkba://filelink` 会被写进用户文档的超链接地址里，又是一条产物签名。
- **剥离成本**：对包根来说是高的（1045 个文件的 rename，能做但会在对方的 git 历史里留一笔巨大的机械提交）；对 URI scheme 来说是中（要迁移用户文档里的既有链接）。
- **建议动作**：登记。**并且明确记一条决策：不要为了"品牌统一"去把 `com.checkba` 改名成 `com.aiworkdeck`。** 这个改名的品牌收益接近零（用户看不见包名），却会亲手销毁我们最大的一块胎记。这条要写进 CLAUDE.md。

### A7 授权 Key 前缀 `awdk_` 与试用码前缀 `AWD-T-`
- **文件**：`backend/.../controller/LicenseController.java:24,147`、`service/LicenseService.java:214`、`AccountController.java`、`PlatformAiKeyController.java`。
- **能证明什么**：中等。黑盒可观测（对方产品的授权输入框校验什么前缀）。
- **剥离成本**：低。
- **建议动作**：登记，不改代码。

### A8 CSS 语义令牌前缀 `--awd-`（74 个唯一令牌，127 个文件）
- **文件**：全前端 + `sdk/plugin-sdk/awd-plugin-sdk.js` 的主题推送（`tokens: { "--awd-*": ... }`）。
- **能证明什么**：中等偏强。**74 个令牌的确切命名集合**（`--awd-text-2` / `--awd-text-3` / `--awd-accent-soft` / `--awd-text-on-accent` / `--awd-border-subtle` / `--awd-border-strong` / `--awd-mint` 这种三层文字色 + 三层边框 + `mint` 这个非通用色名的组合）比前缀本身更有说服力。而且它经 SDK 推给第三方插件，等于**公开契约**——改名会让所有插件的 CSS 失效。
- **剥离成本**：改前缀很容易（sed），但改**令牌集合**会破坏插件兼容性。
- **建议动作**：登记「令牌集合」而不是「前缀」。

### A9 HTTP User-Agent 与产品标识字符串
- **文件**：`checkba-browser/1.0`、`AI-WorkDeck`、`AI-WorkDeck-Capability-I...`、`aiworkdeck-build`（散在 backend 与 desktop 的出站请求处）。
- **能证明什么**：中。**服务端可观测**——如果对方产品用我们的浏览器面板打外部站点，我们自己的服务器日志里能看到。
- **剥离成本**：低。
- **建议动作**：登记；顺手统一成一个常量（现在是散落的字面量，`checkba-browser/1.0` 和 `AI-WorkDeck` 混用），既是工程整洁也让胎记更可校验。

### A10 `.awd/tree.json` 文件树清单格式
- **文件**：`backend/.../version/ProjectTreeManifestService.java:35`（`MANIFEST_PATH = ".awd/tree.json"`）、`TreeManifest.java`。
- **能证明什么**：强的产物签名。用户项目目录里出现 `.awd/tree.json`，且其 JSON 结构（uid 字段、relPath 语义）与我们的 `TreeManifest` 一致。
- **剥离成本**：中（要迁移用户既有项目）。
- **建议动作**：登记。

### A11 Office 插件 manifest GUID `5d9024e8-b355-46a1-ad19-e47aa9f12f65`
- **文件**：`office-addin/manifest.xml`。
- **能证明什么**：中。GUID 是全局唯一的，对方若直接复用 manifest 会在 Office 侧与我们冲突（所以有动机改），但如果没改就是铁证。
- **剥离成本**：极低。
- **建议动作**：登记；价值主要在"顺手看一眼"。

### A12 `.claude/agents/*.md` 领域文档语料（5979 行）
- **形态**：中文技术散文，带大量"病灶 / 地雷 / 铁律"式的独特措辞与具体日期、PR 号、dev-board 卡号。
- **能证明什么**：**极强，且是最容易向非技术裁判解释的一类证据。** 代码相似度需要专家论证，一段一模一样的中文注释不需要。这类文本几乎不可能"独立创作雷同"。
- **剥离成本**：高（要真的重写，而重写就等于放弃了这份文档的价值）。抄袭者最常见的行为恰恰是把这类文档整个搬走。
- **建议动作**：登记；不改。这也是"注释即资产"这条开发规范的现实依据。

### A13 全量提交 GPG 签名
- **现状**：抽查最近 40 条提交，`%G?` 全部返回 `E`（有签名、本机缺公钥），即**历史是签名的**。仓库 893 条提交，首条 2025-12-08。
- **能证明什么**：创作时间与作者身份的技术证明。配合 GitHub 的公开时间线，构成"我们在前"的时间证据链。
- **建议动作**：登记为既有资产；确认签名密钥的托管与备份（密钥丢了这条证据链就断了）。

---

## B 组：需要新增的加固（按性价比排序）

### B1（建议首批，最高优先级）修掉 `office_thread.js` 的 MIT 头 —— 这是一个真实的授权漏洞，不只是防抄问题
- **文件**：`frontend/src/zetaoffice/public/office_thread.js:1`，现为 `/* SPDX-License-Identifier: MIT`。
- **事实**：这个文件源自 #39 Phase 0 的 529 行 spike（当时确实照抄了 allotropia/zetajs 的 MIT 示例，头是对的），此后经 **63 次提交长到 6827 行**，绝大部分是我们自己的 UNO 原语实现——它是整个文档编辑器最核心、最值钱的单个文件。头没跟着改。
- **后果**：**任何人现在拿走这个文件，按文件头它就是 MIT 的，我们几乎无法主张 AGPL。** 这不是"防抄措施不足"，这是我们自己把最值钱的资产按 MIT 发出去了。
- **正确做法**：改成双段头 —— 保留对 allotropia/zetajs 原始 MIT 部分的署名，主体声明为 `AGPL-3.0-or-later`：
  ```
  /* SPDX-FileCopyrightText: 2025-2026 北京京微资易科技有限公司
   * SPDX-FileCopyrightText: 2023 allotropia software GmbH (portions, see below)
   * SPDX-License-Identifier: AGPL-3.0-or-later AND MIT
   * ...
   */
  ```
  同时把 `frontend/src/zetaoffice/public/zeta.js`（1115 行，**是** allotropia 上游原文）明确标为纯 MIT 第三方件，最好挪进一个 `vendor/` 目录物理隔离。`experiments/zetaoffice-spike/` 那份 529 行的原始 spike 保持 MIT 不动（它确实基本是示例代码）。
- **零功能影响验证**：改的是注释。跑 `npm run build:zetaoffice` 产物字节数变化仅为注释长度；lowa-e2e 全绿。
- **法律动作**：这一步涉及"我们能不能单方面改一个已经以 MIT 发布过的文件的许可"。因为主体是我们自己写的、且我们持有 CLA，答案是可以（MIT 部分继续以 MIT 提供，不撤回）。但**建议让维护者本人过一眼**，不要子代理自作主张改。

### B2（建议首批）为全部一方源码补 SPDX 头 + REUSE 合规
- **现状（实测）**：`SPDX-License-Identifier` 在全仓只出现 6 次，其中 5 次是 zetaoffice 相关文件（且是 MIT），1 次是 spike 的 html。分语言覆盖率：**Java 0/1045、Vue 0/117、TS 0/89、MJS 0/157、JS 5/274**。带 `Copyright (c)` 的文件 7 个，其中 5 个是 LICENSE 本身。
  换句话说：**我们的源码里一个自己的版权头都没有。**
- **做法**：每个一方源文件加两行头：
  ```
  // SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
  // SPDX-License-Identifier: AGPL-3.0-or-later
  ```
  并按 FSFE REUSE 规范补 `LICENSES/` 目录与 `REUSE.toml`。
- **能证明什么**：这是**整份清单里性价比最高的一条**，理由不是"抄了能看出来"，而是三条：
  1. 抄袭者公司内部跑 Black Duck / ScanCode / FOSSA 时，扫描器读的就是 SPDX 头。没有头，我们的代码在对方的合规报告里可能显示为"未知许可"甚至被漏过；有头，对方法务会主动收到 AGPL 告警——**很多"抄袭"其实是对方工程师不知情，一个头就能在纠纷发生前解决**。
  2. 剥离 SPDX 头是**故意行为**，在侵权认定里直接构成"明知"（willfulness），影响赔偿倍数；而且 AGPL/GPLv3 §7(b) 与《著作权法》都明确保护权利管理信息，删除版权管理信息本身另有可诉性。
  3. 每个文件独立带头之后，**举证颗粒度从"仓库"降到"文件"**——只要证明对方某一个文件与我们某一个文件实质相似，就有一份自带权属声明的原件可比对。
- **零功能影响验证**：纯注释。Java 头要放在 `package` 语句**之前**；`.vue` 放在 `<template>` 之前会被编译器保留在产物里（可接受）或用 `<!-- -->`；`.mjs`/`.js` 顶部。跑一次全量 `mvn test` + `npm run build:h5` + `npm run build:zetaoffice` 对比产物功能，不要求字节相同。
- **成本**：1500+ 文件，机械改，一个 sonnet 子代理 + 一个 CI 护栏脚本即可。要注意排除 vendored 目录（`litviz/` 的上游件、`zeta.js`、`node_modules`、`data/`）。

### B3（建议首批）统一出站 User-Agent 常量 + 产物内嵌构建指纹
- **做法**：
  - 后端加一个 `ProductIdentity` 常量类，把散落的 `checkba-browser/1.0` / `AI-WorkDeck` / `AI-WorkDeck-Capability-I…` 收进来，格式固定为 `AIWorkDeck/<version> (<component>)`。
  - 桌面端打包时把 git commit sha 与构建时间写进 `app.asar` 内一个 `build-info.json`，并在「关于」窗口展示。
- **零功能影响验证**：UA 变化不影响任何我们控制的服务端；对外部站点（企查查 / 法宝）要确认没有 UA 白名单——**这条必须实测**，不能想当然。
- **能证明什么**：黑盒 + 服务端双向可观测。对方产品发出的请求带我们的 UA 形状 = 强证据。而且「关于」里的构建指纹是**透明的**，符合 AGPL §5 "prominent notices stating that you modified it" 的精神。

### B4（建议首批）在编辑器保存路径写入文档 Generator 元数据
- **现状**：`office_thread.js` 里搜不到任何 `DocumentProperties` / `UserDefinedProperties` 的写入；后端 POI 路径（`SensitiveService`、`LitigationTimelineTools`、`MeetingRecordingService`）也没有写 core properties。也就是说我们**当前保存的文档不带任何产品标识**——这在办公软件里反而是不寻常的（Word / WPS / LibreOffice 都写）。
- **做法**：保存时设置标准 core properties 的 `Application` / `AppVersion`（POI: `POIXMLProperties.getExtendedProperties()`；LOWA 侧: `DocumentProperties.setGenerator()`），值形如 `AI WorkDeck 0.36.0`。
- **为什么零功能影响**：这是 OOXML 标准字段，Word / WPS / Pages 全都读得懂并原样保留；不改一个字节的正文。
- **验证**：生成一份 docx，`unzip -p out.docx docProps/app.xml` 看到 `<Application>AI WorkDeck 0.36.0</Application>`；Word / WPS 各开一次确认无警告；lowa-e2e 的 docx 组全绿。
- **能证明什么**：**取证成本最低的一条**。不需要对方源码、不需要装对方产品，只要拿到一份对方产品导出的文档。同时对用户是完全透明的（右键属性即可见），没有任何隐蔽性问题。
- **注意**：要在设置里给用户一个关闭开关（有些律所对文档元数据敏感，交付前要 scrub），且**默认值不应包含任何用户身份信息**——只写产品名与版本。

### B8（建议首批，与 B2 并列）AGPL §13 的「源码入口」内建告示
- **做法**：两处，都是标准 AGPL 合规动作，不是水印：
  1. 桌面端「关于」窗口与设置页固定一行不可关闭的告示：`AI WorkDeck <version> · AGPL-3.0-or-later · 源代码：https://github.com/zeweihan/aiworkdeck`，配 commit sha。这就是 AGPL §0 定义的 Appropriate Legal Notices。
  2. 后端所有 HTTP 响应加一个 `X-Source-Code: https://github.com/zeweihan/aiworkdeck` 响应头（一行 Servlet Filter / `WebMvcConfigurer`）。
- **为什么这一条在取证上格外强**：它不是我们偷偷埋的东西，而是**许可证要求的告示**。抄袭者必须主动删掉它才能隐藏来源，而删除权利管理信息本身即是独立的可诉行为，并在侵权认定里直接坐实"明知"（willfulness）。同时如果对方没删，我们打一次对方的公网服务就能取证——**不需要拿到对方源码，不需要装对方的桌面端**。
- **对 AGPL §13 的意义**：§13 要求把 Corresponding Source 提供给通过网络与程序交互的用户。任何人把 AI WorkDeck 改造成 SaaS 对外提供，这个头会跟着走；他要么保留（我们取证）、要么删掉（willful）。
- **零功能影响验证**：一个响应头 + 一段静态文案。跑 `mvn test` 与 desktop e2e；确认没有对响应头做严格白名单的客户端（我们自己的前端不会）。
- **成本**：半小时。**这是全清单里成本最低、收益最高的一条，与 B2 并列建议首批。**

### B9 让扫描器语料库能索引到我们
- **做法**：
  1. 把 `LICENSES/AGPL-3.0-or-later.txt` 全文放进仓库（ScanCode / Black Duck 对完整许可证正文的匹配置信度最高）。
  2. 在 ClearlyDefined（clearlydefined.io）上为 `github/zeweihan/aiworkdeck` 补齐 curation。
  3. 若日后发 npm 包（plugin-sdk）或 Maven 包（plugin-api），发到公共仓库而不是只在本地 install——`com.checkba:plugin-api` 现在是"不在任何远端仓库"的本地件，这意味着**没有任何 SCA 扫描器认得它**。
- **能证明什么**：不直接证明。它改变的是**发现机制**：抄袭者公司内部跑 Black Duck / SCANOSS 时会自己弹红，法务主动来找我们谈授权，而不是等我们去起诉。**从"我们要去打官司"变成"对方先来谈"，这是整份文档里最重要的一次范式转换。**
- **前提**：B2（SPDX 头）必须先做，否则扫描器读不到许可信息。

### B10 建立「逐行 ↔ 著作权人」映射（Hellwig v. VMware 的血泪教训）
- **背景**：Christoph Hellwig 诉 VMware（汉堡地方法院 2016 一审、2019 二审）两审皆败，**败因不是 VMware 没抄，而是原告无法把 VMware 产品里的具体代码行对应到自己名下的著作权**。这是 GPL 执法史上代价最惨痛的举证教训。
- **做法**：
  1. 保证 CLA 流程无漏（`.github/workflows/cla.yml` 已有，确认它覆盖所有合并路径，尤其是维护者自己的直推）。
  2. 定期导出一份 `git log --numstat` + `git blame` 的作者归属快照，与 CLA 签署名单对账，存档。
  3. 对首批要保护的关键文件（`office_thread.js`、`AgentOrchestrator.java`、`EditorBridgeService.java`、`ProjectRepoService.java`、`awd-plugin-sdk.js`）单独维护一份逐段作者表。
- **成本**：一个脚本 + 一次人工核对。**没有这一步，前面所有胎记都可能像 Hellwig 案一样在权属这一关就被挡掉。**

### B5 私有胎记登记簿 + CI 护栏脚本
- **做法**：见本文第 5 节。仓库里只放 `scripts/check-fingerprints.mjs`（读取一份**只在私有仓**的清单，或退化为读环境变量注入的清单），公开仓库里的脚本本身不暴露胎记内容。
- **能证明什么**：不直接证明什么，但它保证前面 A/B 组的胎记**不会在某次重构里被我们自己抹掉**——这是清单里最容易被忽略的失败模式。

### B6 中国软件著作权登记（软著）
- **做法**：向中国版权保护中心登记 AI WorkDeck 的源代码。登记时提交鉴别材料（源码前 30 页 + 后 30 页，每页 50 行）。
- **为什么值得**：在中国的诉讼与行政投诉实践里，**软著登记证书几乎是准入门槛级的权属证据**，没有它法院会先花大量精力在权属举证上。费用与周期都很低。
- **与开源冲突吗**：不冲突。登记的是著作权归属，与以 AGPL 许可他人使用是两回事。
- **注意**：登记的版本是快照，产品迭代后要考虑续登关键版本。这件事应由维护者本人或律师办，不是工程动作。

### B7 时间戳存证
- **做法**：对每个 release tag 的源码包做一次可信时间戳（国内可用保全网 / 至信链等区块链存证，杭州互联网法院 2018 年华泰一媒案后此类证据在互联网法院被广泛接受）。
- **价值**：与 GPG 签名 + GitHub 公开时间线互补，成本极低。
- **优先级**：不高（GitHub 公开仓库本身已是很强的时间证据），但发大版本时顺手做一次不亏。

---

## C 组：明确不建议做

### C1 不做隐蔽回传（phone-home / 静默激活探测）
- 与 AGPL 的用户自由精神直接冲突，与 PIPL / GDPR 的告知同意要求冲突，且一旦被发现对一个法律行业产品是毁灭性的声誉打击。
- 我们已有的 `TelemetryController` 是正确形态：只放行三个前端事件名（`editor.action` / `ui.nav` / `app.start`）、服务端事件不走 HTTP、有 `TelemetrySettings` 开关。**保持它，不要往里塞"检测是否为官方构建"这类逻辑。**

### C2 不做代码混淆 / 加壳
- 与开源直接矛盾（AGPL 要求提供 Corresponding Source，混淆后的源码不算 source）。
- 而且我们发的就是源码，混淆无从谈起。

### C3 不做"故意留 bug"式的陷阱
- 地图行业的 trap street 之所以可行，是因为一条假街不影响地图的可用性。软件里的等价物（故意的错误行为）**一定会影响功能**，直接违背维护者"千万不要影响功能"的要求。
- 更要命的是我们的用户是律师，一个"无害的小错"在法律文书语境下可能不无害。

### C4 不做单纯的魔数常量水印
- 例如往代码里塞一个 `const MAGIC = 0x41574431;`。它不参与任何计算，一次 `grep` + `sed` 就没了，且死代码检查器会报警。
- 唯一的例外是**参与计算、去掉就出错**的常量（如 A1 的 `awd: 1`）——那已经不是水印，是胎记。
- 判据：**如果一个"防抄措施"删掉之后代码照常工作，它对有意的抄袭者就是零成本的。** 只对无意的、直接复制粘贴的人有效——而那类人本来就会被 A12（中文注释）和 B2（SPDX 头）抓到。

### C5 不做"特定频率"（维护者原话里提到的）
- 理解为在网络请求或定时任务里埋一个特征周期（比如心跳恰好 47 秒）。
- 不建议：性能与可靠性上是纯负担，取证上不如 UA 与端口链直接，且很容易被"我们也随手选了这个值"解释掉。要做特征周期不如做 A5（端口链）——同样黑盒可观测，但有工程理由撑着。

---

## 建议首批（5 条）

| # | 动作 | 谁做 | 成本 |
|---|---|---|---|
| B1 | 修 `office_thread.js` 的 MIT 头，隔离 `zeta.js` 到 vendor | 维护者拍板 + 一个 opus 子代理 | 半天 |
| B2 | 全仓 SPDX + REUSE + CI 护栏 | sonnet 子代理批量改 + opus 写护栏 | 一天 |
| A1/A2/A3 | 三条最强天然胎记登记 + 加字面量断言测试 | sonnet | 两小时 |
| B4 | 文档 Generator 元数据 | opus（碰编辑器保存路径） | 半天 |
| B8 | AGPL §13 源码入口告示（关于窗口 + `X-Source-Code` 响应头） | opus | 半小时 |
| A6 决策 | 写一条"不许把 `com.checkba` 改名"进 CLAUDE.md | 维护者 | 十分钟 |

（B1 与 B8 是两件不同性质的事：B1 是补上我们自己捅的授权漏洞，B8 是把 AGPL 本来就要求的告示落地。两条都不是"防抄措施"，但对防抄的实际作用超过任何水印。）

第二批：B3（UA 统一）、B9（进扫描器语料库，依赖 B2）、B10（逐行权属映射）、B5（登记簿与 CI 护栏）。
第三批（非工程，交维护者/律师）：B6（软著登记）、B7（时间戳存证）。
