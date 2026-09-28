# Store screenshots — spec and shot list

Screenshots are produced by the walkthrough card **dev-board#697**, not by this card. This file is
the spec they have to hit; the PNGs land in this directory.

## Hard requirements (Partner Center / AppSource)

| Item | Value | Source |
|---|---|---|
| Count | at least 1, at most 5 | [Checklist](https://learn.microsoft.com/partner-center/marketplace-offers/checklist) — "One screenshot is required" |
| Dimensions | **1366 w x 768 h pixels**, exactly | [Craft effective store images](https://learn.microsoft.com/partner-center/marketplace-offers/craft-effective-appsource-store-images) — "Provide at least one image that is 1366w x 768h pixels and no greater than 1024 KB" |
| File size | **≤ 1024 KB** each | same |
| Format | PNG | same |
| Aspect ratio | do not stretch or pinch — capture or pad to 1366x768, never scale non-uniformly | same |

A Retina capture is 2x; downscale to exactly 1366x768 with a proportional resize, or capture at a
window size that already gives that ratio and crop. Verify every file before uploading:

```bash
sips -g pixelWidth -g pixelHeight office-addin/appsource/screenshots/*.png
ls -l office-addin/appsource/screenshots/*.png     # every file under 1024 KB
```

## Content rules (these are what reviewers actually reject on)

- **No personal or client information.** Use a made-up matter — invented company names, invented
  people, invented dates. Nothing from a real file, no real firm name in the window chrome.
- **Show real content, not an empty document.** An empty Word page with an empty task pane says
  nothing.
- **Trim the unrelated UI.** Crop away the taskbar/dock, the file path, other applications. The
  docs say to use the Office UI sparingly — keep enough ribbon to make it obvious this is Word,
  and no more.
- **One point per image.** Each shot makes exactly one claim; put a short caption on it if the
  claim is not self-evident.
- **Legible at a glance.** Zoom the document to a size where body text is readable in the 1366px
  frame; magnify a crop rather than shrinking content.
- **Brand, lightly.** One AI WorkDeck mark is enough; do not paste the logo over content.

## Shot list

Five slots, one platform-coverage shot each for the three platforms plus two feature shots.
Policy 1120.3 requires the add-in to work on Office on the web and on Mac, so the reviewer should
be able to see all three platforms in the listing.

| # | Platform | Scene | The one claim it makes |
|---|---|---|---|
| 1 | **Word, Windows** | Signed-in task pane mid-conversation, document showing coloured tracked-change insertions and strikethrough deletions from the request in the pane | AI edits arrive as a redline you accept or reject — this is the hero shot, make it slot 1 |
| 2 | **Word, Mac** | Same conversation shape, plus a Word comment anchored to a sentence with the assistant's reply in the comment thread | It works comment-by-comment, and it works on Mac |
| 3 | **Word or Excel, web** | Office on the web in a browser, pane signed in, a visible edit in the sheet or document | It works in the browser too |
| 4 | **Excel** | A small table with a total row and bold header the assistant just wrote, pane showing the request | Spreadsheets are a first-class host, not a stub |
| 5 | **PowerPoint** | A deck with a slide the assistant just added, pane showing the request | Decks too |

If only three can be produced, keep 1, 3 and 4 — hero, platform breadth, second host.

## Naming

`NN-host-platform-scene.png`, e.g.

```
01-word-windows-tracked-changes.png
02-word-mac-comment-reply.png
03-word-web-edit.png
04-excel-total-row.png
05-powerpoint-new-slide.png
```

Upload order in Partner Center is the display order, so the numbers are the order.

---

## 当前已有的镜头（2026-09-16 走查，dev-board#697）

| 文件 | 画面 | 可否直接上架 |
|---|---|---|
| `01-word-mac-signed-in.png` | Mac Word，任务窗格已登录，文档是三段英文谅解备忘录，窗格显示三个快捷动作 | 可用（英文界面） |
| `01-word-mac-tracked-changes.png` | Mac Word，AI 把第一段口语化表述改成正式措辞并加结束句，正文里是修订，窗格英文回复（2026-09-21 维护者重拍） | 可用；气泡里有维护者姓名 |
| `02-word-mac-comment.png` | Mac Word，AI 在第一句上加批注询问日期是否正确（2026-09-21） | 可用 |
| `04-powerpoint-mac-new-slide.png` | Mac PowerPoint，AI 在末尾新增「Key Takeaways」三点总结页（2026-09-21；原图非 16:9，补了白边；画面里有一枚「已添加到剪贴板」提示） | 可用，最好重拍去掉提示 |
| `06-word-web.png` | Word 网页版（word.cloud.microsoft），旁加载清单后 AI 生成一份带修订的英文备忘录，窗格英文回复（2026-09-21；原图非 16:9，补白边；带浏览器外框） | 可用 |
| `02-excel-mac-signed-in.png` | Mac Excel，窗格已登录，工作表里是小表格 | 可用 |
| `03-excel-mac-total-row.png` | Mac Excel，AI 加合计行并把表头加粗，chip「Write range / Format cells」，页脚已是一行（2026-09-21，#764 修后） | 可用 |
| `05-word-windows-tracked-changes.png` | Windows Word（Parallels，英文界面），第一段改正式并加结束句，词级修订，chip「Current Word document」（2026-09-21，#768 修后；右下角有输入法悬浮条） | 可用（备用第六张） |

六张齐了（Partner Center 只收 5 张，选法见 ../SUBMIT.md §4.4）。历史遗留：


重拍时注意：修订气泡会打印 Word 用户的真实姓名，截图前把该窗口切成行内修订
（`osascript -e 'tell application "Microsoft Word" to set revisions mode of view of active window to in line revisions'`），
截完再切回去。

## 2026-09-28 按认证驳回 100.3.2.2 修图（dev-board#1007）

审核意见：`Please try to hide other addins Icons from the ribbon`。上架的五张（01 / 02 / 03 / 04 / 06）
没有重拍，用 ImageMagick 在像素层处理，尺寸与文件大小仍满足 1366x768 / ≤1024 KB：

- Mac 三宿主四张：把「打开 AI WorkDeck」按钮块（含左侧分隔线）整体左移到最后一个 Office 原生按钮
  （编辑器 / 加载项 / 设计器）之后，再用 ribbon 底色（Word 浅色 245 / Excel 40 / PowerPoint 28）
  填平原来 Ghostwriter、GPT for Excel Word、Claude 所在区域。
- `06-word-web.png`：抹掉浏览器工具栏的扩展图标（用同一行干净底色横向拉伸覆盖），关掉第二个标签页
  并把「+」挪回第一个标签页旁；Word 网页版自身的功能区没有第三方加载项，未动。
- `05-word-windows-tracked-changes.png`（备用第六张）**没有处理**：Windows 功能区同样有第三方加载项，
  Windows 任务栏也带一堆图标，要用它必须先重拍或照上面的办法修。

维护者在 Partner Center 两种语言的 Marketplace listings 里把旧的五张全部删除后按 ../SUBMIT.md §4.4 的顺序重传。
