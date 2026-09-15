# Product Hunt 发布物料（2026-09-01，全套齐备）

国际渠道补充（对应 workdeck.ai，不在原六周矩阵内，作为国际线并行动作）。
理论底座：书《大模型时代的法律科技——从历史困局到平台跃迁》（工具孤岛→系统平台、
人肉总线、让专业回归判断）+ IDE 集成价值与行为经济学报告（摩擦成本、认知负荷、
算法厌恶、人在回路、延伸认知外骨骼）。

## Name of the launch（40 字符内）

AI WorkDeck

## Tagline（56/60 字符）

Your whole case in one window — an IDE for document work

## Description of the launch（479/500 字符）

Coders have IDEs. Document workers still juggle a dozen windows. AI WorkDeck is an open-source desktop workbench that puts the whole case in one window: an Office-grade editor where AI edits with character-level tracked changes you accept or reject, due-diligence checks, litigation timelines and relationship graphs, phone-to-desktop evidence capture, and a plugin ecosystem. Files stay local, human stays in the loop. Built by a practicing lawyer. AGPL, free community edition.

## Launch tags（三选）

Artificial Intelligence / Productivity / Open Source
（若搜索框里有 Legal 类目，可用 Legal 替换 Productivity）

## First comment（maker 首评）

Hi Product Hunt — I'm Zewei, a practicing lawyer in Beijing.

I co-authored a book on legal tech in the LLM era. Its core observation: coders got IDEs — one environment where code, tools, and now AI live together — while document workers never did. A lawyer running a case works as the human message bus: copying text between Word, a PDF viewer, twenty browser tabs, and an AI chatbot in yet another window, saving files named "final_v15". Behavioral economics calls this friction and cognitive load. We call it a normal workday.

Chatbots don't fix it. Pasting a contract into a chat and pasting the answer back is still fragmentation. And a black-box agent that rewrites documents unsupervised is nothing a professional can sign their name under — in law, the signature is the product.

So I built the tool I wanted, on the thesis of the book: AI shouldn't replace the workbench. It should be built into one.

What AI WorkDeck does:

- A real Office-grade editor (LibreOffice compiled to WebAssembly) running locally. AI edits appear as character-level tracked changes you accept or reject — like reviewing a colleague's redline.
- The whole case in one window: files, AI conversation, evidence pane, browser, all cross-linked.
- Fact checking where every claim links back to a source document.
- Litigation timelines, process flows and party-relationship graphs generated from your files.
- A phone app that captures evidence photos straight into the project folder.
- A plugin system — lawyers are already building their own.

It's desktop-first and files stay on your machine; confidentiality is non-negotiable in this profession. The community edition is free and AGPL open source.

We started with lawyers, but the ambition is broader: IDEs are for coders, AI WorkDeck is for doc-ers — anyone whose final output is a document. If you've ever felt like the human middleware between windows, I'd genuinely love your feedback.

## 图片物料（ph-assets/，已生成）

- `thumbnail-240.png`：240x240 缩略图（来源 desktop/build/icon.png）。
- `gallery-01-hero.png` 至 `gallery-07-opensource.png`：七张画廊图，2540x1520
  （= PH 推荐比例 1270x760 的 2 倍图）。顺序：主视觉（真截图内嵌）→ 修订 →
  可视化 → 手机取证 → 插件生态 → Office/WPS 插件 → 开源。第一张同时是
  社交分享预览图。
  插件卡只列真实已上架插件（尽调 v0.6.1/诉讼可视化/会议转写/脱敏），不用
  官网 plugin-marketplace.png 那张愿景图——其内容（247 skills/安装量/课程）
  是虚构的，违反「只公布真实数据」红线。
- 生成管线：`ph-cards.html`（HTML → 无头 Chrome 截图），改图只改 HTML 重渲。
- 画廊内真截图取自官网 hero-dashboard.png（中文界面）；日后可用截图流水线
  换 EN 界面版，卡片版式不变。

## Shoutouts（点名帮我们造出产品的其他产品）

机制：每条 shoutout 会变成一条创始人评价，挂在对方产品评价区最前面并回链本
launch；PH 明示带 shoutout 的 launch 更易被 featured（首页精选决定 PH 成败），
所以必填。操作：Add shoutout → 搜产品名 → 选中 → 附一句说明。

**只点真用过的**——这是以维护者名义发出的评价，虚构点名违反「只说真实」红线。

首选三条（真实技术栈 + PH 上有活跃页面）：

- **Claude**：AI WorkDeck was written with Claude Code, and Claude is one of the
  models our users run inside it. I'm a practicing lawyer, not a professional
  engineer — this is the tool that made building a real desktop product possible.
- **OpenRouter**：One key, every model. OpenRouter lets us route each task to the
  right model without shipping a dozen provider integrations, and lets our users
  pay for what they actually use.
- **LibreOffice**：We compile LibreOffice to WebAssembly so our users get a genuine
  Office-grade editor inside the workbench — tracked changes, comments, real .docx.
  Decades of work we could never have built ourselves.

备选（想多点几条时按序）：Electron（桌面外壳=文件留本机的前提）、Vue.js（前端）、
DeepSeek（模型）、Stripe（支付）、Cloudflare（Turnstile）。

## 表单其余字段

- Links：主链接 https://workdeck.ai ，附加链接 GitHub 仓库。
- Pricing：选 Free（开源，另有付费商业授权可在评论里答）。
- 视频（可选）：预告片成片后补挂 YouTube 链接。
- Makers：标记维护者本人为 maker。

## 左侧栏其余各屏口径

- **Funding information**：勾 **Bootstrapped**（未拿 VC），YC/Venture backed 不勾；
  Team size 选 1-10；**Crunchbase URL 留空**（选填，无条目不必专门去注册）。
  注：bootstrapped 在 PH 是加分项——社区偏爱独立开发者，正好接「执业律师自己
  写了个开源软件」的叙事。
- **Extras**：给 PH 用户的专属优惠码/福利。社区版本免费，跳过。
- **Connect with Investors**：把 launch 推送给平台投资人。不在融资，跳过或勾上皆可
  （勾上无害，可能带来国际投资人关注）。
- **Launch checklist**：PH 自己的完成度清单，提交前照着核一遍漏项。

## 发布操作要点

- 全表单一律英文（当前 Description 里的中文句子要替换掉）。
- 链接指 workdeck.ai（国际站），不要挂 aiworkdeck.com。
- 发布时点：太平洋时间 00:01（北京时间 15:01/16:01）上榜整天曝光最长；选周二至周四。
- PH 带来的不是中国律师用户，是国际技术圈曝光、GitHub star 与潜在报道——预期要摆对。
- 画廊图（gallery）与 logo 也要备：可用官网截图流水线出图，需要时另行生产。
- 全物料无 emoji（品牌红线，PH 惯例虽爱用，我们不用）。
