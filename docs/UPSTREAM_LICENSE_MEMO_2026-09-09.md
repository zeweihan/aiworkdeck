# 上游许可隐患决策备忘：pptx-service（CC BY-NC-SA）与 easyvoice（无 LICENSE）

- 日期：2026-09-09
- 状态：待维护者决策
- 起因：SPDX/REUSE 盘点（PR #760，REUSE.toml）把两个 vendored 目录标成占位许可，需要拍板
- 关联：dev-board#510；`docs/superpowers/specs/2026-09-09-provenance-license-compliance-design.md`
- 范围：只出备忘，不改服务代码。所有事实均在本工作树与上游仓库克隆上核实，未核实项单列在第 4 节

## 0. 一页结论

**pptx-service**：违规是真实的、不是灰区，但出路比预想便宜得多。

1. 我们打进付费桌面安装包分发的是上游 banana-slides v0.4.0（2026-02-09 发布），该版本许可是 CC BY-NC-SA 4.0。NC 条款禁止的正是「装进收费产品分发」这种用法；SA 条款没有任何可兼容的非 CC 许可（AGPL 不在名单上）；BY 条款要求的署名在面向用户的分发物里也没做。三条全中。
2. **上游作者已于 2026-02-17 把许可改为 AGPL-3.0**（提交 a9a5c36，配套 PR #225 重写了未签 CLA 的贡献者代码），我们 vendor 的 v0.4.0 恰好打在改证前 8 天。也就是说 NC 问题在上游侧已经消失，我们只是卡在旧快照上。
3. 推荐路径：**立即 re-vendor 到改证提交 a9a5c36**（与 v0.4.0 只差 32 个提交，我们最重的三个补丁文件上游没动过，估 1 人周内），随下一个桌面版本发出，把 pptx-service 变成 AGPL-3.0 组件。这一步与我们社区版许可完全一致，商业版按「第三方 AGPL 组件、独立进程、随附源码」处理即可，不必向上游买商业授权。买授权、换实现、剥离成可选件三条路都不是必需，只作备选。
4. 顺带发现一个独立问题：我们自己为 `pdf_to_word` 引入的 pdf2docx 拉进了 PyMuPDF（AGPL-3.0 / Artifex 商业双许可）。这与 banana-slides 无关，升级也不会消失，商业闭源版需要单独决策。

**easyvoice**：上游 cosin2077/easyVoice 根本没有任何许可声明（无 LICENSE、README 与 package.json 均无），法律上等于保留全部权利，因此「补 UPSTREAM.md + 正确许可标注」这条路不成立，没有许可可以标。本地拷贝未改一行、运行时零调用、CI 与打包均不涉及。**建议直接删除目录**，连带清掉 docker-compose 里的死配置段。

## 1. pptx-service

### 1.1 它在产品里做什么

- 调用链：Java 后端 `backend/src/main/java/com/checkba/service/ai/PptxServiceClient.java` 通过 HTTP 调本机 Flask 进程（默认 `http://localhost:5001`，打包态动态回环端口）。生产调用方只有两个 AI 工具类：`PptxTools.java`（`pptx_*` 九个工具）与 `PdfTools.java`（`pdf_to_word`）。
- 没有独立的 UI 按钮入口，全部经 AI 对话的工具调用触发；工具无能力槽或插件门控，对所有用户始终注册。
- 用户可见功能与代码归属：

| 功能 | 依赖模块 | 归属 |
|---|---|---|
| AI 从想法一键生成整篇 PPT（大纲、逐页描述、逐页生图、导出） | project_controller / task_manager / ai_providers / image_service / pptx_builder | 上游为主，我们只加了 model_config 下发与落字排版修补 |
| 仅生成大纲、口头改大纲、单页改图 | 同上 | 上游 |
| 可编辑 pptx 导出（整页图反拆成元素） | image_editability/* | 上游，未改 |
| 存量 pptx 格式全览与六种批量格式操作 | pptx_format_service / pptx_format_utils / text_sanitizer / pptx_edit_controller | 全部我们自写，只 import python-pptx（MIT），零上游模块 |
| PDF 转 Word、扫描件 OCR 转 markdown | pdf_convert_service / pdf_convert_controller，复用 file_parser_service | 我们自写；OCR 分支复用上游 file_parser_service（我们改过） |

结论：「AI 生成 PPT」主线几乎完全建立在上游代码上；「存量 pptx 格式读写」与「PDF 转换」是我们自己的代码，只是恰好挂在同一个 Flask 进程里，随时可搬。

### 1.2 分发面

- 桌面安装包内置，mac 与 win 都装，所有用户一视同仁。`desktop/scripts/prepare-python-service.js` 把 `pptx-service/backend` 源码原样拷入产物，`desktop/package.json` 的 extraResources 携带独立 Python 与 pysvc 包；`.github/workflows/desktop-build.yml` 两平台各打一份并冒烟。`desktop/main/services/pptx-service.js` 设 `eager: true`，随桌面壳启动即拉起。
- 只有一套构建。付费是 entitlement 解锁，不是另出安装包。**付费商业用户拿到的 .dmg/.exe 里同样带着这份 CC BY-NC-SA 源码。**
- 云端不跑：`deploy/cloud/README.md:14` 明确「不带 Python 附属服务（pptx/mineru/kokoro 云端不可用）」。根目录 `docker-compose.yml:40-65` 的 pptx-service 段只用于本地开发。
- 上游 React 前端（约 18866 行）我们既不改也不打包。

### 1.3 我们的改动量

对照方法：本仓 `git ls-files pptx-service`（256 个文件）与上游 v0.4.0 树（244 个文件）逐文件 diff。

| 口径 | 数值 |
|---|---|
| 我们新增的文件 | 14（两个控制器、两个服务、两个工具模块、五个测试、UPGRADE_CHECKBA.md、compat_smoke_test.sh、requirements.lock） |
| 我们修改的上游文件 | 16 |
| 删除的上游文件 | 2（上游误入库的 server.log） |
| 修改上游文件的行数 | +614 / -113 |
| 我们新增的 Python 行数（含测试） | 1340 |
| 上游 Python 总行数 | 23020 |
| 我们的 delta 占比 | 约 8% |

`UPGRADE_CHECKBA.md` 的定制清单漏了四项：`settings_controller.py`（PPTX_SETTINGS_TOKEN 强制校验，PR#241）、`task_manager.py`（启动对账 `reconcile_orphaned_tasks`，PR#526）、两个测试文件 `test_task_reconcile.py` 与 `test_pdf_convert.py`、以及 `pyproject.toml` 的 pdf2docx 一行。下次 re-vendor 前必须先补进清单，否则会像 0.1.0 到 0.4.0 那次一样被静默丢掉。

### 1.4 许可文本与时间线

`pptx-service/LICENSE` 与上游 v0.4.0 逐字节相同，要点原文：

> Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International Public License
> Copyright (c) 2025 Anionex
> NonCommercial — You may not use the material for commercial purposes
> ShareAlike — If you remix, transform, or build upon the material, you must distribute your contributions under the same license
> 商业授权 (Commercial License) 如需将本项目用于商业目的，请联系作者获取商业授权: GitHub: https://github.com/Anionex/banana-slides

v0.4.0 README 的「商业使用」定义列了三个分类（企业内部使用、对外服务、其他营利目的），冒号后面上游自己就是空的。

本仓时间线：

| 日期 | 事件 |
|---|---|
| 2026-01-05 | 首次 vendor（aaffd1e9），LICENSE 从第一次提交起就在 |
| 2026-01-22 | 建立 AGPL + 商业双授权体系（89107868），同一提交触及 pptx-service，但未做任何切分或例外声明 |
| 2026-02-09 | 上游发布 v0.4.0 |
| 2026-02-17 | 上游改证为 AGPL-3.0（a9a5c36），配套 PR #225 重写未签 CLA 贡献者的代码 |
| 2026-07-03 | pptx-service 进桌面安装包（746b38c3，PR#93）。**分发义务从这里开始** |
| 2026-07-09 | re-vendor 到 v0.4.0（PR#129/#132），此时上游已是 AGPL 但我们按 tag 取了改证前的版本 |
| 2026-09-05 | 上游最新 tag v0.9.0-rc.7；15568 star；最后 push 2026-09-08 |

声明情况：仓根没有 NOTICE、THIRD-PARTY、LICENSES 目录；`legal/COMMERCIAL-LICENSE.md` 完全没提任何第三方组件例外；根 README 只有一行「pptx-service/ | AI-native PPT generation service」，无上游归属、无许可提示。唯一带署名性质的提及在 `docs/pptx_generation.md`，而 docs/ 在 .gitignore 里。

### 1.5 冲突分析

**NonCommercial**。CC BY-NC-SA 4.0 法律文本第 1(k) 条：

> NonCommercial means not primarily intended for or directed towards commercial advantage or monetary compensation.

CC 的 NC 解释页说判断看使用的主要目的，不看使用者是谁。AI WorkDeck 是面向法律行业的收费产品，带 Credits 计价与商业授权线，把 pptx-service 打进付费桌面端分发，主要目的显然是商业收益。这不是灰区。

**ShareAlike**。我们对上游做了实质修改（16 个文件、600 多行，改造了 pptx_builder、ai_service_manager、config、app、prompts），构成 Adapted Material，SA 要求改编作品只能以相同许可分发。CC 兼容许可页明确写「Currently, no non-CC licenses have been designated as compatible with BY-NC-SA 4.0」，AGPL-3.0 不在名单上，无任何兼容通道。

**Attribution**。分发物里没有署名，README 也没有。

**「独立进程」抗辩救不了 NC**。pptx-service 是 Electron spawn 的本机子进程加 localhost HTTP，按 FSF 判准偏向「分别的程序」，这可以在 SA 上争一争（若不算改编就不触发 SA）。但 NC 限制的是使用，不是衍生：哪怕它百分之百独立，装进收费产品分发仍是商业性使用。

**CC 自己不建议用于软件**。CC FAQ 原文：「We recommend against using Creative Commons licenses for software」；CC BY-NC-SA 既非 OSI 批准也非 FSF 认可的自由软件许可。

**已发生的暴露**：2026-07-03（PR#93）之后发布的所有桌面版本都随安装包分发了这份代码，都在 NC 之下。升级只能停止继续违规，不能抹掉这段历史。上游作者的姿态（主动改 AGPL、README 仍留商业授权联系方式、无公开索赔先例）说明实际风险偏低，但作为律师维护者应知悉。

### 1.6 上游改证的核实

在上游全量克隆上验证：

```
git log -- LICENSE
a9a5c36 2026-02-17 chore: change license to AGPL-3.0
3048484 2025-12-13 docs: update README
50d79ea 2025-11-30 add LICENSE
git merge-base --is-ancestor a9a5c36 v0.4.0   ->  否（v0.4.0 早于改证）
```

- a9a5c36 的 diff 是把 CC 文本整体替换为 AGPL-3.0 全文（+645 / -36）。GitHub API 当前 `license.spdx_id = AGPL-3.0`。
- 配套 PR #225「rewrite select contributed code for license compatibility」：作者自述改证需全体贡献者同意，少数作者失联，故删除受影响代码并重写。上游 CLA 第 2 条授予维护者「Sublicense and relicense your Contributions under any license, including for commercial purposes」。改证的法律基础比一般单方面改证扎实。
- 上游现行 README 许可段：「本项目采用 GNU Affero General Public License v3.0（AGPL-3.0）开源，可自由用于个人学习、研究、试验、教育或非营利科研活动等非商业用途；闭源商业用途需获取授权。如有疑问或合作意向、获取多租户商业版本，可联系: davidyang042@gmail.com」。「闭源商业用途需获取授权」在 AGPL 下是正确表述：开源的商业使用 AGPL 本身就允许。
- 有意义的 fork 不存在（最大的 27 star）。个别 1 star fork 把 license 字段改成 MIT，fork 无权对上游作者的代码重新授权，不可作为依据。

### 1.7 出路对比

用户要求的三条之外，上游改证带来了成本最低的第零条。

| 方案 | 许可结果 | 工程量 | 说明 |
|---|---|---|---|
| **A0 re-vendor 到改证提交 a9a5c36** | AGPL-3.0 | 约 1 人周 | v0.4.0 到 a9a5c36 只有 32 个提交；后端总漂移 +1855 / -334；我们打补丁的 12 个文件里 9 个有变动（+952 / -34，几乎纯新增），**export_controller、ai_service_manager、pptx_builder 三个最重的补丁文件上游没动**。a9a5c36 把 lazyllm、volcengine-python-sdk、fastapi 放进了核心依赖（执行时实测 lock 从 105 到 123 个 pin，无 torch / opencv 之类重包）。有 compat_smoke_test.sh 与四个单元测试兜底 |
| A1 升级到 v0.9.0-rc.x | AGPL-3.0 | 2 到 4 人周 | 729 个提交；后端 +17759 / -1997；我们的 12 个文件漂移 +5735 / -1268。我们用的 7 个端点全部还在。但依赖大增（onnxruntime、opencv、PyMuPDF、多家模型 SDK），桌面包体积明显上涨，需裁剪 |
| B 向 Anionex 买商业授权 | 合同定 | 接近 0，加谈判 | 邮箱 davidyang042@gmail.com；无公开报价，issues 里无先例。只有想在商业闭源版里保留对 pptx-service 的私有修改、或想给客户提供高于 AGPL 的保证时才需要。合同须写清再分发、OEM、转授权范围与对已重写贡献者代码的权利保证 |
| C1 换 Presenton | Apache-2.0 | 6 到 10 人周 | 10126 star，FastAPI + Next.js。产品形态不同：出原生可编辑 pptx，不是整页 AI 生图。契约全量重写，双栈打包，CJK 渲染未核实，按请求下发 model_config 要重做 |
| C2 自建（python-pptx MIT + 我们已有编排层） | MIT | 4 到 6 人周，含可编辑导出再加 6 到 10 | 大纲、描述、生图三步在 Java 编排层基本是 prompt 工作；整页图铺满 pptx 约 200 行。难点只有「可编辑导出」，而它在上游本来就是 experimental。换来 100% 干净依赖与瘦身的包体，但要自己长期维护 |
| C3 LandPPT | NOASSERTION | 5 到 8 人周 | LICENSE 是手工拼接的 Apache/MIT 混合体且无版权人，法务尽调会挂红；可编辑导出依赖付费 Apryse SDK。不建议 |
| D 剥离为可选独立部署件、商业版不带 | 不变 | 中 | 目前只有一套构建、无版本分叉，要新建构建分叉与安装期开关；且它并不解决社区版本身的 NC 问题（社区版也是收费产品的一部分）。上游已改 AGPL 之后此路失去意义 |

已核实但排除的其他候选：PPTAgent/DeepPresenter（MIT，但 Docker 沙箱硬依赖且不支持 Windows）、slide-deck-ai（MIT，Streamlit 应用、固定供应商白名单）、marp-cli 与 slidev（MIT，无 AI 管线、非 pptx 生成器）、ai-to-pptx（GPL-3.0）、PPTist（AGPL，浏览器内编辑器）、deepseek-design（source-available）、若干无 LICENSE 的 skill 仓。

### 1.8 推荐

**做 A0，随下一个桌面版本发出；B、C、D 都不做，只留记录。**

理由：

1. A0 是唯一同时满足「立刻停止违规」「工程量最小」「不改产品形态」三条的路。上游改证后 pptx-service 变成 AGPL-3.0，与我们社区版许可完全一致。
2. 商业版不需要向上游买授权。pptx-service 是独立进程、HTTP 通信、源码随安装包原样分发，是 AGPL 意义上的聚合而非衍生。商业授权客户只需遵守 pptx-service 自身的 AGPL（源码可得，我们的修改在公开仓里），不改它就没有额外义务。要做的是在 `legal/COMMERCIAL-LICENSE.md` 加一段「第三方 copyleft 组件」条款，明确商业授权的 copyleft 豁免只覆盖 AI WorkDeck 自有代码，随附的 pptx-service、PyMuPDF 等第三方组件按各自许可。
3. 换实现（C）的 4 到 10 人周投入不值得：这个功能没有 UI 入口、只在 AI 工具层，且上游活跃（15568 star、九月仍在发 rc）。除非未来上游依赖膨胀到桌面包扛不住，再考虑 C2 自建。
4. A1 不急。A0 先脱离 NC，v0.9 的功能与依赖膨胀另开卡评估。

执行顺序（每步可独立开卡）：

1. 补全 `UPGRADE_CHECKBA.md` 缺的四项定制。
2. re-vendor 到 a9a5c36：按定制清单逐项套用，跑 `compat_smoke_test.sh` 与四个单元测试，桌面两平台冒烟。`pptx-service/LICENSE` 随之变为 AGPL-3.0 全文；在 `UPGRADE_CHECKBA.md` 顶部记录上游改证事实与所取提交号，避免下次 re-vendor 的人按 tag 又取回旧版。
3. 署名与声明：根 README 的目录表加上游归属与许可；PR #760 的 REUSE.toml 把 `pptx-service/**` 从占位改为 `AGPL-3.0-only`、版权人 Anionex 与 banana-slides contributors，我们新增的文件另标 checkba 版权；新增仓根 THIRD-PARTY 或 NOTICE 汇总 vendored 组件。
4. `legal/COMMERCIAL-LICENSE.md` 加第三方 copyleft 组件条款。
5. 移除 `pptx-service/CLA.md` 与 `pptx-service/CONTRIBUTING.md`（上游原文逐字节 vendored，与我们的 `legal/CLA.md` 并存会让贡献者混淆），或在文件头注明是上游文件。
6. 历史暴露的处理由维护者决定：可选择向作者致信说明已切到 AGPL 版本并致谢，顺带问商业授权条件（B 作为备选）。这一步会让对方知悉历史使用，利弊自判。

### 1.9 附带发现：PyMuPDF 是独立的商业版阻断点

`pptx-service/requirements.lock` 106 个 pin 里，`pymupdf 1.28.0` 的 PyPI 许可字段是「Dual Licensed - GNU AFFERO GPL 3.0 or Artifex Commercial License」。它由我们自己为 `pdf_to_word` 引入的 pdf2docx（MIT）传递带入，`pdf_convert_service.py:16` 还直接 `import fitz`。上游 v0.9 也直接依赖 PyMuPDF。

- 社区版：AGPL 相容，无问题。
- 商业闭源版：分发含 PyMuPDF 的桌面包，按同样的「独立进程聚合」逻辑处理即可，但 Artifex 对 AGPL 的执行姿态比个人作者强硬得多，且 `pdf_to_word` 是我们自己的代码直接 import 它（进程内链接，不是 HTTP 隔离）。**若商业版客户要求无 AGPL 依赖，只能买 Artifex 商业许可或砍掉 pdf2docx 链路。** 建议单独开卡评估。

其他依赖：img2pdf（LGPLv3，上游原有，动态使用可接受）、pikepdf（MPL-2.0，文件级 copyleft）、其余 MIT/BSD/Apache。

## 2. easyvoice

### 2.1 事实

- 上游 https://github.com/cosin2077/easyVoice：2309 star，最后 push 2026-01-26，未归档。**无 LICENSE 文件**（全树 find 无匹配，GitHub API `license: None`），README 无许可章节，package.json 无 license 字段。
- 本地 `easyvoice/`：141 个文件，2.1 MB，2026-01-05 一次性 vendor（eec413ff），此后无独立提交。与上游 main 全目录 diff 只有 3 个文件不同，全部是上游之后的版本号漂移（Chromium UA 版本、build-multi.sh），**不含任何我们的修改**。`grep -i "checkba|workdeck"` 零命中。本地 package.json 与上游逐字节一致。
- 运行时零调用：`TtsService.java` 只有 Kokoro 一档（注释明确「只有本机一档：桌面包内置的 Kokoro 服务」），无 9549 端口或 easyvoice HTTP 调用；`desktop/` 零引用；`.github/workflows/` 零引用；`desktop/package.json` 的 extraResources 不含它。
- 残留引用：`docker-compose.yml:72-80` 一段死配置（`restart-all.sh` 已不启动）；`frontend/src/components/EasyVoicePane.vue` 与 `leftSidebarPlugins.js` 的 `easyvoice` 路由键兼容层，只是历史命名，打的是我们自己的 `/api/tts/*`；`README.md:283,296`、`README.zh-CN.md:281,294`、`CLAUDE.md:3`、`.claude/agents/eng-infra.md:226` 的文档提及。
- PR #760 的 REUSE.toml 已把它标为 `LicenseRef-Vendored` 占位并注明「上游许可待人工核实」。本次核实的结论是：上游没有可引用的许可。

### 2.2 分析

无许可声明的公开仓库，默认保留全部权利。GitHub 服务条款只授予他人在 GitHub 平台上查看与 fork 的权利，不授予复制到自己产品仓库并分发的权利。因此：

- 「补 UPSTREAM.md + 正确许可标注」不成立：没有许可可以标，标了反而是虚假声明。
- 继续留在仓里是无授权的复制品，虽然运行时不用，但仓库本身是公开的 AGPL 社区版，任何 clone 都在再分发它。
- 删除后对产品零影响：TTS 已完全在 Kokoro 上。

### 2.3 建议

**直接删除 `easyvoice/` 目录**，同一个 PR 里连带：

1. 删除 `docker-compose.yml:72-80` 的 easyvoice 服务段。
2. 更新 `README.md`、`README.zh-CN.md`、`CLAUDE.md:3`、`.claude/agents/eng-infra.md:226` 的提及（改成「语音合成走桌面捆绑 Kokoro」）。
3. PR #760 的 REUSE.toml 删掉 easyvoice 那条标注。
4. `EasyVoicePane.vue` 与路由键 `easyvoice` 是我们自己写的代码，只是沿用了名字，无许可问题，可留；若嫌名字误导，另开卡改名，不与本次删除混在一起。

Git 历史里仍然存在那份拷贝。这是公开仓的常态，不建议改写历史（会破坏所有 fork 与 PR 引用）；若维护者认为有必要，可在 THIRD-PARTY 里注明「2026-01-05 至删除日曾含 easyVoice 拷贝，已移除」。

## 3. 需要维护者拍板的问题

1. pptx-service 是否按 A0（re-vendor 到 a9a5c36）执行，随下一个桌面版本发出。
2. 是否向 Anionex 致信（致谢、说明已切 AGPL、询问商业授权条件）。这会让对方知悉 2026-07-03 以来的历史使用。
3. `legal/COMMERCIAL-LICENSE.md` 是否加「第三方 copyleft 组件不在豁免范围」条款。
4. PyMuPDF 在商业闭源版里怎么处理：接受 AGPL 聚合、买 Artifex 商业许可、还是砍掉 pdf2docx 链路。
5. easyvoice 是否直接删除。

## 4. 未核实项

- Anionex 商业授权的报价、是否含再分发与 OEM 权、是否接受买断。
- 上游作者对 v0.4.0 时期历史使用的态度。
- a9a5c36 与 v0.4.0 之间 file_parser_service（上游 +104，我们 +238）、task_manager（上游 +253，我们 +32）两处的合并冲突实际大小，要动手才知道。
- v0.9.x 桌面打包体积的实际增量。
- Presenton 的中文字体渲染质量。
- v0.4.0 README 三个「商业使用」分类的确切外延，上游自己没写。

## 5. 核实方法与来源

- 本仓：`git log --follow`、`git ls-files`、与上游树逐文件 `diff -ru`；调用链靠 grep `PptxServiceClient`、`PptxTools`、`PdfTools`、`pptx-service.js`、`prepare-python-service.js`、`desktop-build.yml`、`deploy/cloud/README.md`。
- 上游 banana-slides：全量克隆，`git log -- LICENSE`、`git merge-base --is-ancestor`、`git diff --stat v0.4.0 a9a5c36` 与 `v0.4.0 v0.9.0-rc.7`、`git rev-list --count`；GitHub API 取 star、license、PR #225。
- 上游 easyVoice：浅克隆全目录 diff；GitHub API `license` 字段；raw README 与 package.json。
- 许可文本：CC BY-NC-SA 4.0 legalcode、CC FAQ、CC NonCommercial interpretation wiki、CC compatible licenses 页；PyPI 元数据取各包 license 字段。
