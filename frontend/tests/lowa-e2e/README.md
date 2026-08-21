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
    书签随之消失、旧式 filelink 超链接收编、docx 往返存活（27 步）

步数基线：2026-08-21 组 27 落地后 417 步全绿（此前 390）。

## 机制说明

- 测试专用的 worker 动作（`debug_set_record_changes`、`debug_char_prop`）由测试
  服务器**在内存中**注入到所服务的 office_thread.js / editor 资产里——源码与
  dist 均不被改动。锚点是 `const EXEC = {` 与白名单里的
  `'get_hyperlink_at_cursor'`，若重构了这两处需同步本套件。
- 服务器按引擎目录的 `.encodings.json` 回放 Content-Encoding（CDN 引擎是 brotli
  原样存储）。
- 新增快捷键后：在 run.mjs 加一个场景断言 + README 本表补一行。
