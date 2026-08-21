# LOWA 编辑器"真人模拟"e2e 回归 / human-simulation e2e

无头启动**真实 LibreOffice WASM 引擎**，用 CDP 键盘事件 + IME 合成走**完整的
覆盖层键盘链路**（key event → IME overlay → executor → worker → UNO），逐项断言
文档 / 光标 / 剪贴板状态——和真人在编辑器里打字、删除、按快捷键是同一条链。

## 为什么

原语级测试（直接调 executor 命令）反复通过，但真人却撞上"Backspace 只能删一个"
（修订模式卡死，PR#164）、"Delete/Cmd+Z 没反应"（覆盖层吞键，PR#164/166）——这类
bug 只存在于按键事件到 UNO 之间的链路上。本套件测的就是这条链。改动
zetaOfficeImeOverlay.js / office_thread.js / libreofficeExecutorClient.js 后必跑。

## 运行

```bash
cd frontend
npm run build:zetaoffice          # 注意：会清空 dist/zetaoffice（包括引擎文件！）
node ../desktop/scripts/fetch-lowa-assets.js   # 引擎不在时重新拉取
npm run test:lowa-e2e
```

环境变量：

| 变量 | 作用 |
|---|---|
| `LOWA_ENGINE_DIR` | 从外部目录服务引擎（soffice.* + .encodings.json），躲开 build:zetaoffice 清空 dist 的坑 |
| `PUPPETEER_EXECUTABLE_PATH` | Chrome 路径（默认 mac 的 Google Chrome） |
| `LOWA_E2E_PORT` | 服务器端口（默认 8901） |

引擎启动约 90 秒；全套跑完约 3 分钟。退出码非 0 = 有断言失败。

server / puppeteer 启动件在 `_boot.mjs`（preflight、COOP/COEP 静态服务、无头 Chrome、
打开 `editor.html?verify=1`），run.mjs 与下面的大文档基线组共用。端口被别的
worktree 占着时（`EADDRINUSE`）设 `LOWA_E2E_PORT`。

## 大文档基线组（dev-board#108）

```bash
python3 -m pip install --user python-docx pillow   # 夹具生成依赖，只装一次
npm run test:lowa-big                               # 夹具不存在会自动生成到 $TMPDIR/awd-big-doc/big.docx
```

`big-doc.mjs` 加载 `fixtures/gen-big-doc.py` 生成的 150 页 / 920 段 / 30 张 12x5 表 /
20 张噪声 JPEG（6.7MB，随机种子固定，每页首段各一处「目标公司」= 150 命中）夹具，
对六项逐个计时，**每项 3 轮取中位数**（同机抖动可达 2 倍），任一项未达硬阈退出码 1。
`LOWA_BIG_RUNS` 改轮数，`LOWA_BIG_DOC` 指向别的 docx。

改造前后（本机 Apple Silicon，无头 Chrome，r4 引擎；改造前 2026-08-21、改造后 2026-08-22，
期间本机其他会话同时在跑 e2e，load avg 10-30）：

| 项 | 改造前 | 改造后（3 轮中位数） | 硬阈 |
|---|---|---|---|
| load_document 6.7MB/150 页 | 3.59s | 2.13s（3.71 / 1.43 / 2.13） | < 15s |
| get_document_text 第 2 次（同参数） | 1.99s | **21ms**（13 / 21 / 28） | < 300ms |
| get_document_text {startParagraph:800} | 2.02s | 8-64ms | （只记录） |
| find_replace 修订 150 命中 | 28.5s（命中一多就撞执行器 30s 超时，worker 仍在改） | **162ms**（157 / 162 / 254） | < 8s |
| apply_house_style 920 段 + 30 表 | 执行器 30s 超时，worker 实跑约 440-630s，期间页面主线程被冻住 | **18.4s**（18.4 / 17.9 / 19.3），truncated=false，有进度、可取消 | < 120s |
| export_document | 2.3s（被前面排队的命令顶到 180s 超时那轮不算）；全文格式化后 11.5s | 6.82s（4.56 / 7.60 / 6.82，全文格式化之后量） | < 10s |
| 导出后 30s 内 modified 次数 | 0 | 0 / 0 / 0 | = 0 |

改造前只拿到第 1 轮（旧代码的 find_replace / apply_house_style 超时后 worker 继续跑，
后续命令全部排队，第 2 轮 load_document 都等不到）。改造后三轮完整跑完、六项全过硬阈。

**两个真根因**（2026-08-22 探针实证，与「引擎记修订贵」的猜测相反）：

1. 只要装着 JS 实现的 `XModifyListener`，引擎每条文档写入（一条 `setPropertyValue` /
   `setString`）都回调进 JS 一次，一次约 35ms，与回调体做什么无关（空函数体同样 35ms），
   与 RecordChanges / `lockControllers` / undo 无关；摘掉监听器后同一条写入 0.1ms。
   全文格式化 920 段 x 2 次批写 + 30 表 x 60 格 x 4 次写 ≈ 9000 次回调就是几百秒的来源。
   现在 `lockModel()` 期间把监听器摘下，结束装回并补发一次 `modified`。
2. 逐命中 `setString` 每处 90-130ms（JS 往返 + 上面的回调），而引擎原生
   `XReplaceable.replaceAll` 一次调用 150 命中 160ms，RecordChanges 开着时同样按命中
   各记一条删除 + 一条插入修订。用正则 `(?<=前缀)中段(?=后缀)` 只替差异中段，字符级
   颗粒度（PR#188）保持不变；纯插入型（零宽匹配引擎不认）回退逐命中分批路径。

剩下的 18s 里约 15s 是 30 张表（每格约 8ms，5 次写 + 一次取文本），段落只占约 1s。

改 `office_thread.js` 里的全文路径（段落枚举 / 修订 / 全文格式化 / 导出）后必跑。

## 覆盖场景

1. 修订模式下退格删除文档原文——光标逐字越过（PR#164 卡死回归）
2. Delete 键前删
3. IME 中文合成上屏 + 退格真删（本人插入）
4. Cmd+Z / Cmd+Shift+Z 撤销重做
5. Cmd+A 全选、Cmd+B 加粗 toggle
6. Cmd+C/V/X 系统剪贴板复制、多段粘贴覆盖选区、剪切
7. Home / Shift+End 选择 / Option+← 跳词 / Cmd+← 行首 / Cmd+↓ 文尾
8. Tab 制表符、Shift+Enter 软回车、Esc 取消选区、PageUp/Down
9-26. 见 run.mjs 各组标题（条款识别 / 修订署名与颗粒度 / 批注 / 版本对比 / 表格 / Calc / Impress / 工具栏与查找 / chrome 退场）
27. EvidenceLink 证据锚点（dev-board#103）：bookmark_selection / get_bookmark_context /
    check_link_anchors / adopt_legacy_links / goto_bookmark，含书签内插字扩张、整段删除
    书签随之消失、旧式 filelink 超链接收编（含生产 URL 形态与非法 key 跳过）、
    docx 往返存活（31 步）

步数基线：2026-08-21 组 27 落地后 421 步全绿（此前 390）。

## 机制说明

- 测试专用的 worker 动作（`debug_set_record_changes`、`debug_char_prop`）由测试
  服务器**在内存中**注入到所服务的 office_thread.js / editor 资产里——源码与
  dist 均不被改动。锚点是 `const EXEC = {` 与白名单里的
  `'get_hyperlink_at_cursor'`，若重构了这两处需同步本套件。
- 服务器按引擎目录的 `.encodings.json` 回放 Content-Encoding（CDN 引擎是 brotli
  原样存储）。
- 新增快捷键后：在 run.mjs 加一个场景断言 + README 本表补一行。
