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
# 4. pptx-service（Python 运行时 + 依赖 + 源码）
node desktop/scripts/prepare-python-service.js \
  --service pptx-service --src pptx-service/backend \
  --requirements pptx-service/requirements.lock --out desktop/bundled/mac-arm64
# 5. mineru-service（纯 pip 包，无 --src；模型不进包，首启在「系统管理 → 组件管理」下载）
node desktop/scripts/prepare-python-service.js \
  --service mineru-service \
  --requirements mineru-service/requirements.lock --out desktop/bundled/mac-arm64
# 6. kokoro-service（本地 TTS 包装层；Kokoro 模型约 300MB 同样走组件管理下载）
node desktop/scripts/prepare-python-service.js \
  --service kokoro-service --src kokoro-service \
  --requirements kokoro-service/requirements.lock --out desktop/bundled/mac-arm64
# 7. pysvc 打成单个 tar.gz（上万个小文件不直接进 .app——逐文件 codesign 的 Apple
#    时间戳请求会抖动；首次启动由主进程解压到用户数据目录，见 main/services/pysvc-runtime.js）
node desktop/scripts/pack-pysvc.js --bundle desktop/bundled/mac-arm64
# 出包（本地不签名）
cd desktop && CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder --publish never
```

打包态由 ServiceManager（`main/services/`）统一拉起本地服务：Java 后端固定 9696，pptx / mineru / kokoro 动态端口（`EXTERNAL_PPTX_SERVICE_BASE_URL` 注入后端、`MINERU_LOCAL_URL` 注入 pptx、`EXTERNAL_TTS_LOCAL_BASE_URL` 注入后端）。mineru / kokoro 为条件启动：模型未下载则跳过，在「系统管理 → 组件管理」下载（落 `~/.aiworkdeck/models/{mineru,kokoro}/`）后自动拉起；云端 MinerU 兜底默认关闭（`CHECKBA_MINERU_FORCE_CLOUD=1` 可放开）；kokoro 运行时 `HF_HUB_OFFLINE=1` 零出网。数据落 `~/.aiworkdeck/`，日志落 `~/.aiworkdeck/logs/<service>.log`。

## Notes

- 开发模式下，Electron 会加载 Vite Dev Server（保留你的前端热更新体验）。
- 生产模式下，会加载 `frontend` 的构建产物。
- 单元测试：`npm test`（node:test，覆盖 ServiceManager）。


