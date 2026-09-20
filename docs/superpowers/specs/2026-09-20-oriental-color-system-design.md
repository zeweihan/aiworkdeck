# 东方清雅配色体系换代设计（bamboo-moon 2.0.0）

日期：2026-09-20
状态：已与维护者确认两项拍板，其余由本文档定案
色源：`design/tokens/awd-palette.json`

## 1. 要解决的问题

把全产品配色从 1.x「深松绿 `#1A5336` + 薄荷 `#5BD197` + Bootstrap 冷灰」换成东方清雅体系
（竹月青 `#89A8A0` / 玉脂白 `#F1EFE7` / 浅茶金 `#D7C5A1`），覆盖桌面端、官网、移动端三个仓，不遗漏。

盘点（10 个并行 agent，三仓全量）得出的真实图景，与直觉有三处不同：

1. **产品现在就已经是绿色系。** 真正要变的不是"绿"，是两件事：冷灰中性色 → 暖玉白；高饱和薄荷 → 低饱和茶金。
2. **官网已经有令牌层**（`app/globals.css` 的 `--king-forest-*` + Tailwind v4 `@theme inline`，67 文件 1176 处在用），
   不是"还没做"。要做的不是从零建，是把逃出令牌层的手写 hex 收回去。
3. **全产品有 6 处令牌定义，互相手抄，零机器对拍。** 这是"换配色必然漏改"的根因，也是本次唯一值得做的架构改动。

## 2. 拍板记录

| 决策 | 结论 | 决定人 |
|---|---|---|
| 竹月青的角色 | 氛围色 + 派生深色主色。竹月青做大面积浅底/选中/悬停/图表/装饰；主按钮与强调文字用派生的墨竹青 `#2E5A50` | 维护者 |
| logo 与应用图标 | 保形换调。形状一笔不改，深松绿→墨竹青，薄荷→竹月青；茶金不进 logo | 维护者 |
| 茶金用量 | 只做点睛（分隔金线、引用块、徽章、空状态描边），不做大面积底色 | 本文档 |
| 深色主题 | 跟着换成暖墨调。竹月青在深底上 7.0:1，正是深色模式的强调文字色 | 本文档 |
| 移动端（现为藏青 `#1E3A8A`，本来就与桌面端不同系） | 并入统一体系 | 本文档 |
| litviz 诉讼可视化引擎 | 保留其黑白灰克制美学，只把强调色并入体系（克莱因蓝 `#002FA7` → 墨竹青；深红 `#991B1B` 保留作对抗方标记）。它出的是给法庭与客户看的文书插图，不是产品界面 | 本文档 |
| 语义色（成功/警告/危险/信息） | 保留色相家族，只降饱和并压到同一明度台阶。危险色取朱砂 `#B5483C`（印泥色） | 本文档 |

### 硬约束：对比度

这是海报搬进产品界面时唯一的硬墙，也是「竹月青不能直接当主色」的全部理由：

| 颜色 | 对白底 | 能否承载正文 |
|---|---|---|
| 竹月青 `#89A8A0` | 2.6:1 | 否 |
| 浅茶金 `#D7C5A1` | 1.7:1 | 否 |
| 墨竹青 `#2E5A50`（派生） | 7.8:1 | 是 |
| 旧深松绿 `#1A5336` | 9.0:1 | 是 |

WCAG 线：正文 4.5:1，大字与控件 3:1。工作台是四列同屏、小字号为主的密集信息界面，这个差别会被放大。

## 3. 新体系

完整定义见 `design/tokens/awd-palette.json`。设计要点：

- **中性色阶浅档暖、深档收敛。** 背景/表面/边框跟玉脂白走暖调（H≈45）；正文压到 `#2B2A26`（S≈6%）。
  整条都暖会让灰字发棕，法律文书界面显旧。
- **纸张永远是纸白。** `office_thread.js` 的 `DocColor` 刻意不动（注释明写"深色下纸仍是纸白"）。
  外壳转暖之后白纸在暖案上更像纸——这是本次换色的额外收益。同时 `--awd-surface` 从纯白改为 `#FCFBF7`，
  让面板与文档纸张天然区分开。
- **`--awd-mint` 令牌名保留，值换成竹月青。** 这个名字是插件 SDK 公开契约
  （`THEME_TOKEN_NAMES` 白名单 + 第三方插件 CSS 里的 `var(--awd-mint)`），改名会静默打破第三方插件。
  新增 `--awd-bamboo` 作同值别名，供新代码使用语义正确的名字。

## 4. 架构改动：单一色源 + 机器对拍

这是让「不遗漏」可持续的关键，也是本次唯一超出"改颜色"的改动。

```
design/tokens/awd-palette.json          ← 唯一真源（三仓各存一份逐字节相同的副本）
  │
  ├─ checkba_cloud/scripts/generate-tokens.mjs
  │     ├→ frontend/src/App.vue          :root 与 html[data-theme='dark'] 令牌块
  │     ├→ frontend/src/uni.scss         $awd-* SCSS 变量
  │     └→ office-addin/taskpane/styles.css  该面自己的 --awd-* 令牌块
  │
  ├─ aiworkdeckweb/scripts/generate-tokens.mjs
  │     ├→ app/globals.css               --king-* 变量与 @theme inline 映射
  │     └→ lib/plugin-template.ts        发给第三方插件作者的模板令牌表
  │
  └─ aiworkdeck_mobile/scripts/generate-tokens.mjs
        ├→ ios/Sources/Design/Tokens.swift
        └→ miniprogram/styles/tokens.wxss
```

每个仓加 `scripts/check-palette.mjs`，CI 里跑：重新生成一遍，与入库文件逐字节比对，不一致即红。
跨仓一致性靠 `awd-palette.json` 里的 `version` + 各仓校验自己那份副本的 sha256。

这一步顺带还掉一笔既有欠账：移动仓两份令牌文件头都写着「由 `scripts/check-tokens.mjs` 对拍」，
而那个脚本根本不存在，同步一直是纯人工纪律。

生成的令牌块用标记注释包住（`/* AWD-TOKENS:BEGIN */ … /* AWD-TOKENS:END */`），
生成器只替换标记之间的内容，不碰文件其余部分。

## 5. 三仓改造清单

### checkba_cloud（桌面端主仓）

| 面 | 内容 | 量级 |
|---|---|---|
| 令牌层 | App.vue `:root` 浅/深两套、uni.scss `$awd-*`、office-addin `styles.css` | 3 处，改生成器即可 |
| 工作台前端 | 令牌层之外的硬编码：约 425 处 hex + 273 处 rgba，约 60 文件 | 690 个决策点 |
| ↳ 其中最大一坨 | 注入 LOWA iframe 的 CSS 字符串（`zetaOfficeInlineReview.js`、`semanticWritingPanel.js`、`zetaOfficeReviewBalloons.js`、`writingAssistancePresentation.js`、`zetaOfficeImeOverlay.js`）约 163 处 | 只扫 .css/.vue 会全漏 |
| Office/WPS 插件 | 组件内联 36 处、`scripts/build-wps.mjs` 的 `BRAND_STYLE` 28 处、`installer/art` 三个 HTML 57 处、`installer/mac/main.swift` 10 个 Swift 常量 | 六个宿主面共用一份产物 |
| 桌面外壳 | `main/main.js` 的 `backgroundColor`/`titleBarOverlay`；`build/dmg-background.html` 与 `build/win/*.html` 六个源文件约 123 处 | 改 HTML 后须手动跑 `render-win-installer-art.mjs` / `render-oneclick-art.mjs` 重渲位图入库，**CI 不会自动跑** |
| 可视化 | litviz vendor（走 `PATCHES.md` 的 `[AWD-PATCH]` 机制，不直接改源码）、标签调色板、日历分类色、版本时间轴强调色 | 195 个点 |
| 内部工具 | `backend/src/main/resources/static/feedback-console/index.html` 反馈看板管理台 | 照改（它是产品化页面） |

### aiworkdeckweb（官网）

- `app/globals.css` 的 `--king-*` 令牌改由生成器产出
- 收回逃出令牌层的手写值：`bg-[#16452D]`、`text-[#0b2418]`、`rgba(18,58,38,…)` 等（Hero/Pricing/skills/u 等页面）
- `admin` 自绘 SVG 图表（`telemetry/Charts.tsx`、`TelemetryDashboard.tsx`）`fill` 里写死的 `#1A5336`/`#5BD197`/`#123A26`
- `lib/plugin-template.ts` 内嵌的 `--awd-*` 表改由生成器产出，与桌面端同源
- 裸 `neutral-*` 类的使用违反了 DESIGN.md 自己写的规则，顺手收进令牌
- 官网明确不做深色模式（globals.css 有注释说明），本次不引入
- 16 张产品截图/海报画面里是旧配色 UI，需换色后重新出图
- 同步更新 `DESIGN.md` 的色彩章节

### aiworkdeck_mobile（移动端）

- `ios/Sources/Design/Tokens.swift` 与 `miniprogram/styles/tokens.wxss` 改由生成器产出，并真正补上对拍脚本
- iOS `Design/Components.swift` 缩略图占位渐变 6 处
- 小程序 `glass.wxss`、6 个页面/组件 wxss、`utils/icons.ts`（SVG 图标用 JS 常量拼 data URI）、
  `app.json` 的 `window.backgroundColor`、`project.ts` 的 `wx.showModal` `confirmColor`
- 图标全是内联 SVG，换色只改 hex 常量，不需重新出图
- iOS AppIcon、小程序图标需重出

## 6. 绝对不动

这条线画错会污染用户产物或打破第三方识别。

**用户文档产物**
- `office_thread.js` 的 `HIGHLIGHT_COLORS`（Word 标准高亮色板，写进用户 docx 的 `CharHighlight`）
- `doc_*`/`table_*` 原语的 `p.color`/`p.background`/`p.borderColor` 运行时透传参数
- `DocColor` 纸张白
- `house-default.js` / `house-default.json` 的 `#000000`（律所标准格式模板）
- Excel 内置三色阶 `#F8696B`/`#FFEB84`/`#63BE7B`、PDF 标准荧光黄
- `DocxStyleHelper.java` 引用段落默认字色 `#666666`
- 用户已保存的标签颜色数据（调色板可选项可改，用户历史选择的结果不能重刷）

**第三方与公开契约**
- 文件类型色 Word `#185ABD` / PPT `#C43E1C` / Excel `#1D6F42` / PDF `#D93025`
- 插件 SDK 主题通道的字段名与 `THEME_TOKEN_NAMES` 令牌名清单（只改值，不改名）
- `office-addin/wps/vendor/publish-template.html`（逐字 vendor 自上游，许可头一字不改）
- `pptx-service/assets/logo_aihubmix.png`（合作方 AIHubMix 品牌标识）

**历史存档**
- `aiworkdeck_mobile/docs/design/variants.html`（已否决方案的评审存档，被规格文档引为历史证据）

## 7. 会挂的断言

| 文件 | 断言 | 处理 |
|---|---|---|
| `frontend/tests/project-home/page.test.mjs` | 源码含 `#1A5336` | 改断言目标色值 |
| `frontend/scripts/check-navigation-contract.mjs` | 同上 | 改断言目标色值 |
| `scripts/star-history.mjs` | 用 `#5BD197`/`#1A5336`/`#2D7A52` 画 SVG 徽章 | 换新值 |
| `litviz/.../lint.py` | 颜色白名单 | 必须与令牌改动同步改，否则渲染产出会被判"未授权颜色"假红 |
| `README.md` / `README.zh-CN.md` | shields.io 徽章色 | 换新值 |

**保留不动的断言**（换色不影响，删了反而丢护栏）：
- `office-addin` 的 `wpsWppHandlers.test.js`/`wpsEtHandlers.test.js`（测 hex↔COM RGB↔BGR 编码算法，用任意示例色）
- `frontend/tests/ocr-overlay/ocr-overlay-style.test.mjs`（断言选区背景必须半透明，是不透明度契约不是色值）
- `frontend/tests/project-home/*.test.mjs` 的 `!SRC.includes('#212629')`（配色红线：外壳保持浅色。新体系仍是浅色系，这条继续生效）
- `plugin-sdk/theme-channel.test.mjs`（测通道结构行为，不测具体色值）

## 8. 需要重出的图形资产

logo 与应用图标是**位图 PNG，三仓都没有找到矢量源文件**。保形换调需要重新出图：

- 产品 logo（`logo_full_v2.png`、`new_full_logo.png`、`iconmark_v2.png` 等）
- 三端应用图标：`desktop/build/icon.png`（派生 .icns/.ico）、iOS `AppIcon.appiconset`、小程序图标、favicon
- Office 插件 `assets/icon-{16,32,64,80}.png`、AppSource `store-logo-300.png`
- 安装器美术：DMG 背景、Windows NSIS `installerHeader.bmp`/`installerSidebar.bmp`、一键安装 hero/mini
  （从 HTML 渲染，改 HTML 后须手动跑渲染脚本入库）
- 官网 OG 图、16 张产品截图/海报（画面里是旧配色 UI，须换色上线后重新截图）

发版影响：App Store 与微信小程序改图标要随版本提审；AppSource 商店图需更新。

## 9. 验证

1. `scripts/check-palette.mjs` 三仓各跑一遍：生成物与入库文件逐字节一致
2. 对比度复算：`check-palette.mjs` 按 WCAG 2.1 重算 `contrast` 块里的每个数，与声明值不符即红
3. 全仓扫残留：三个旧品牌色字面量 `#1A5336`/`#5BD197`/`#1E3A8A` 在改造范围内应为零命中
   （白名单：第 6 节列出的不动项、历史注释、CHANGELOG）
4. UI 真渲染走查：工作台四列、AI 对话、文档编辑器、插件面板、设置、项目列表、登录页，浅/深两套各一轮截图
5. Office/WPS 六个宿主面各一轮真机走查
6. 官网双站、移动端两端各一轮
7. 安装器：重渲位图后本地打包，看安装向导实际画面（`makensis` 本地复现配方见项目记忆）

## 10. 本次刻意不做（如实记录，不是遗漏）

- **Office 插件不读宿主主题。** 现状任务窗格固定浅色，与 Word/Excel/PPT 的深色模式无联动
  （全文搜索 `officeTheme`/`prefers-color-scheme` 零命中）。玉脂白进 Office 深色宿主会和现在的冷白一样突兀，
  换色不会让情况更糟。做宿主主题联动是独立一张卡的事。
- **`uni.scss` 的孤儿层。** 里面还留着 uni-app 脚手架自带的约 50 个 `$brand-color-gold`/`$uni-*`（金色+深蓝），
  全仓只有 `variable-library.vue` 一处引用。如实报告，本次不删（CLAUDE.md：注意到无关死代码要提出、不要删）。
- **`frontend/static/logo.png`**（金色抽象图形）全仓搜不到引用，疑似废弃资产。如实报告，本次不动。
- **`pptx-service`** 有独立 LICENSE（CC BY-NC-SA）与自己一套橙金配色，按第三方受限服务处理，不在本次范围。
- **`TagManager.vue` 与 `TagSelector.vue`** 各维护一份不一致的标签色预设（14 个 vs 18 个）。
  本次只换色不统一列表——统一是架构问题，超出换色范围。
- **AI 修订作者色**由 LibreOffice 引擎按作者名哈希自动分配，代码里没有可改的色值。
  要让"AI 作者的修订"固定为竹月青需新增显式 `RedlineColor` 机制，是独立需求。
