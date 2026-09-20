# Checkba Desktop (Electron)

## Dev

在一个终端启动前端：

```bash
cd frontend
npm run dev:h5
```

后端：桌面端启动时会自动拉起本机后端（端口 9696）。如需手动调试后端，也可以单独启动。

启动桌面端（会加载 `http://localhost:5173`）：

```bash
cd desktop
npm i
npm run dev
```

## Troubleshooting

- OCR 提示 `No handler registered for 'checkba:ocr-capture-screen'`：
  - 多半是桌面端未重启（主进程没加载新代码）。请先完全退出桌面端再重新 `npm run dev`。
  - 新版本已做 fallback（主进程 handler 缺失时会在 preload 里直接走 desktopCapturer）。

## Packaging（本地打包）

完整安装包由 CI（`.github/workflows/desktop-build.yml`）产出；本地打包需按序执行同样的四步（以 mac Apple Silicon 为例——mac 仅支持 M 芯片，Intel 已放弃）：

```bash
# 1. 前端构建（含 LibreOffice 编辑器 bundle）
cd frontend && npm run build:h5 && npm run build:zetaoffice
# 2. LOWA 运行时 + CJK 字体离线烘焙
node desktop/scripts/fetch-lowa-assets.js
# 3. 后端 jar + 裁剪 JRE（需 JDK 21）
mvn -B -q -DskipTests -Djavacpp.platform=macosx-arm64 -f backend/pom.xml package
node desktop/scripts/prepare-backend.js --jar backend/target/backend-0.0.1-SNAPSHOT.jar --out desktop/bundled/mac-arm64
# 4. CPython 运行时（只烙解释器；litviz 与四个 Python 服务的 runtime pack 共用它）
#    0.38.0 起 pptx / mineru / kokoro / asr 的依赖与源码不再进安装包，改由四个
#    native pack（<service 前缀>-runtime）按需下载，见 docs/NATIVE_PACK_DISTRIBUTION.md
node desktop/scripts/prepare-python-service.js --runtime-only 1 --out desktop/bundled/mac-arm64
# 出包（本地不签名）
cd desktop && CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder --publish never
```

打包态由 ServiceManager（`main/services/`）统一拉起本地服务：Java 后端固定 9696，pptx / mineru / kokoro / asr 动态端口（`EXTERNAL_PPTX_SERVICE_BASE_URL` 注入后端、`MINERU_LOCAL_URL` 注入 pptx、`EXTERNAL_TTS_LOCAL_BASE_URL` 注入后端、`EXTERNAL_ASR_LOCAL_BASE_URL` 注入后端）。四个服务都先判各自的 runtime pack 在不在场（`~/.aiworkdeck/packs/<service 前缀>-runtime/`，解析见 `main/services/pysvc-runtime.js` 的 `resolveServiceRoot`），没装就不启动；mineru / kokoro 还要再判模型已下载（落 `~/.aiworkdeck/models/{mineru,kokoro}/`），下完自动拉起；**asr 不设模型这道门**——「录音不出本机」开关的就绪探测必须能分清「服务没起」和「模型没下」，不起进程就只剩前一种结论。云端 MinerU 兜底默认关闭（`CHECKBA_MINERU_FORCE_CLOUD=1` 可放开）；kokoro / asr 运行时 `HF_HUB_OFFLINE=1` 零出网。数据落 `~/.aiworkdeck/`，日志落 `~/.aiworkdeck/logs/<service>.log`。

## Notes

### 文档引擎内存复测（dev-board#721）

8 GiB 及以下设备保留第一套引擎预热，但有文档实例后不再补一套空白引擎。
最近两份文档按原保存保护规则保活，分屏中的活动文档和保存失败的文档不强制卸载。
旧壳未提供物理内存时使用同一保守策略；大内存设备沿用现有预热策略。

隔离实验使用合成文档，不读用户项目，也不启动后端或上传报告。先准备本树
`frontend/dist/zetaoffice`（含 LOWA 引擎和字体），再从 `desktop/` 运行：

```bash
npx electron scripts/measure-editor-memory.js
```

也可用环境变量 `AWD_EDITOR_DIR` 指向安装版的 `resources/frontend/dist/zetaoffice`；
`AWD_MEMORY_OUTPUT` 指定 JSON 报告路径（默认系统临时目录）。Windows PowerShell 示例：

```powershell
$env:AWD_EDITOR_DIR = 'C:\\path\\to\\AI WorkDeck\\resources\\frontend\\dist\\zetaoffice'
$env:AWD_MEMORY_OUTPUT = "$env:TEMP\\awd-editor-memory.json"
npx electron scripts/measure-editor-memory.js
```

实验依次记录一套引擎、两套引擎、销毁备用引擎和全部关闭后的进程工作集；随后验证
剩余引擎的插入、DOCX 导出和重开内容。它衡量引擎固定成本，不代表整套应用占用，
也不代替实际 UI 的预热/保活测试。工作集加总含共享页，macOS 压缩内存会影响读数，
不可直接换算成 Windows 任务管理器百分比。Windows 报告另含 Electron 提供的 privateBytes。

- 开发模式下，Electron 会加载 Vite Dev Server（保留你的前端热更新体验）。
- 生产模式下，会加载 `frontend` 的构建产物。
- 单元测试：`npm test`（node:test，覆盖 ServiceManager）。

