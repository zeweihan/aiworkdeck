# Product Hunt「Connect with Investors」问卷答稿（2026-09-01）

五问各限 5000 字符，实际控制在 1200-1900。口径：不夸大、不虚构数字；
唯一待填的真实数据用 [FILL] 标出，发布前必须换成真数或整句删掉。

底稿来源：书《大模型时代的法律科技》的诊断（工具孤岛/人肉总线/平台跃迁）、
IDE 行为经济学报告（摩擦成本、算法厌恶、人在回路）、docs/PLUGIN_API_ROADMAP.md
的生态战略、官网 lib/pricing.ts 的公示价、legal/CLA.md 的权利集中安排。

---

## 1. Why are you the right founder/team to work on this?

I'm a practicing lawyer, and I wrote the software myself.

Legal technology is usually built by engineers who have never had to file a case, or by lawyers who need engineers to translate for them. I sit on both sides of that gap. I practice law in China, and I co-authored the industry's reference book on legal technology in the era of large models — published with King & Wood Mallesons, with a foreword by its Global Chairman and endorsements from the Chief Legal Officer of Alibaba, the Dean of Renmin University Law School, and the Chairman of Qichacha. A second book is in progress; AI WorkDeck is the argument it makes, written in software instead of prose.

That book is also why the diagnosis behind this product isn't a guess. It's a systematic account of why four decades of legal technology kept failing to reach the lawyer's actual desk — and the failure was never model quality. It was that the tools never integrated into how the work is really done.

And I shipped it. AI WorkDeck today is not a prototype: a signed desktop application for Windows and macOS, iOS and WeChat mini-program companions, add-ins for Microsoft Office and WPS Office, an open plugin SDK with a marketplace, incremental updates, telemetry, billing and licensing. Built by one practitioner with AI as the engineering team.

That last part is itself the thesis. The product argues that professionals should keep judgment and delegate mechanism to AI inside an integrated workbench. That is exactly how it was built.

---

## 2. Why did you pick this idea to work on?

Because I am the user, and the pain is my own working day.

A lawyer running a case is the human message bus of their own workflow: carrying text between a word processor, a PDF reader, twenty browser tabs, a folder of client files, and now an AI chat window in a twenty-first. The output of all that context switching is a document named final_v15.

Software developers solved this thirty years ago and called it the IDE — one environment where the files, the tools, the version history and now the AI all live together. Every other profession whose final output is a document — law, accounting, finance, consulting — never got one. They got a suite of disconnected applications, and lately a chatbot in a browser tab.

Behavioral economics explains why that gap costs so much and why nobody feels it as a crisis: friction and switching costs are paid a few minutes at a time, so they never become the thing you set out to fix. They just quietly cap what a professional can produce, and how much of the day is spent on judgment rather than logistics.

Two things changed at once. Large models made an integrated document workbench technically possible for the first time. And AI-assisted engineering made it possible for one practitioner who actually understands the work to build it, instead of a fifty-person team that doesn't. The gap has been visible for years. The window to close it just opened.

---

## 3. Who are your competitors, and what do you understand about this idea that they don't?

Three groups. Vertical legal AI built for large firms — Harvey, Legora, Spellbook, Robin AI, CoCounsel. Horizontal assistants — Copilot and general chatbots that any professional can open. And in China, Alibaba's Tongyi Farui plus a set of domestic vendors selling into courts and large firms. Several are excellent and far better funded than we are.

Three things we believe that they don't act on:

**The unit of work is the matter, not the prompt.** Chat is a fine interface for one question. It is a bad interface for work that spans thirty documents, six weeks and four revisions, because the human is left holding all the context. What professionals need is what an IDE gives a developer: everything in one window, cross-linked, with state. Competitors add AI to a document. We rebuilt the environment the document lives in.

**Trust is the binding constraint, not capability.** Research on algorithm aversion is consistent: people reject autonomous algorithms but readily use ones they can adjust. In law this is not a preference, it's the job — you sign your name to the output, so you must be able to see and reverse every change. So our atomic primitive is the tracked change: AI proposes character-level redlines inside a real Office-grade editor, and the professional accepts or rejects each one. Others demo autonomy. Autonomy is precisely what a signature cannot delegate.

**The market is bottom-up, and the endgame is a platform.** Incumbents sell top-down: six-figure contracts to the largest firms. The overwhelming majority of the world's lawyers — and nearly all of China's — work solo or in small firms and will never see that sales motion. Reaching them requires a free open-source client, files that stay on their own machine, and pay-per-use AI. And what finally wins is not the best feature set: VS Code didn't win on editing features, it won by becoming the thing everyone else built on. IDEs are for coders; we intend AI WorkDeck to be that for doc-ers — lawyers first, then everyone whose final output is a document.

---

## 4. What's your revenue and/or growth rate?

Pre-revenue, and honest about it: the community edition launches this month, with commercial licensing opening alongside it.

What exists is not a plan but a shipped commercial stack:

- Community edition — free, AGPL-licensed, full workbench. Monetized through metered AI usage sold above cost, so every active user carries positive unit economics without a subscription.
- Commercial licensing for firms that need relief from AGPL obligations — published, non-negotiated pricing, from RMB 39,800/year and tiered by headcount to RMB 398,000/year. OEM redistribution from RMB 300,000/year plus revenue share.
- Plugin marketplace — authors keep their copyright, the platform takes 15%.
- A founding-customer program is open: the first three firms get half off year one in exchange for named case studies.

To date: [FILL — 真实安装/注册数，没有把握就整句删掉] from a closed beta with zero marketing spend. A six-week launch plan is written and now running.

I would rather show an honest zero behind a finished product and published prices than a curve built on a waitlist. The interesting question at this stage isn't the revenue number, it's whether a free, local-first, open-source workbench can convert professional users bottom-up. We are about to find out in public.

---

## 5. Anything else you would like investors to know?

Four things.

**Legal is the wedge, not the market.** The workbench is document-shaped, not law-shaped. Accountants, financial analysts, consultants and in-house teams have the same fragmented day. We are starting where I have standing and distribution, and the plugin architecture is what lets the rest arrive without us building each vertical.

**The open-source structure is deliberate and diligence-ready.** AGPL for the community edition is what makes commercial licensing a product rather than a favor. Contributor IP is centralized under a signed CLA, the trademark is registered, and the entities are set up on both sides — a domestic company for China and an overseas company operating workdeck.ai. Investors who look at open-source companies always ask who owns the code; the answer here is already documented rather than to be arranged later.

**Distribution is not a hypothesis.** The book put this thesis in front of the profession with the endorsement of the country's leading firm and its most-cited legal academics. The second book is being written with this product as its case study. Bar associations, law schools and firm training programs are channels I can actually walk into.

**On capital.** I'm bootstrapped and shipping, and not running a raise off this form. The conversations worth having are with people who know professional-services software and platform ecosystems — where the leverage is distribution and patience, not headcount. If that's you, I'd like to talk.
