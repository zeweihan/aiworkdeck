# 安装包瘦身设计（dev-board#526 总卡；实施卡 #528 / #529 / #530）

日期：2026-09-09。拍板人：韩泽伟。执行：Fable 主会话统筹，Opus 子代理实施。

## 0. 目标与拍板

- 目标：mac DMG 1486MB / win exe 1714MB（v0.37.0）→ 300–500MB 量级。
- 拍板：① pptx-service 也按需下载（做）；② LOWA 编辑器引擎与 CJK 字体保持随包（不做按需）；
  ③ Windows 保持单包双架构（ARM64 壳不拆，但其压缩方式可改）。
- 用户硬性要求：**首次安装登录后**与**前端首次触发对应模块时**都要提示是否下载；
  提示必须写明**影响范围**（下载什么、多大、解锁哪些功能、不下载哪些功能不可用）。
- 预期落点：P1 后 mac ≈1235 / win ≈1298；P2 后 mac ≈440 / win ≈396。安装后磁盘从
  「1.6GB + 2.9GB pysvc 解压树」降到约 1GB。

## 1. 体积构成（实测 v0.37.0，mac 安装后 1650MB）

| 组件 | 体积 | 根因 |
|---|---|---|
| Resources/pysvc.tar.gz | 797MB gzip（解压 2.9GB） | 四个 venv 零共享：torch×2、cv2×2、onnxruntime×3（三个版本）；mineru 带 gradio 196MB；pptx 带火山 SDK 131MB；`__pycache__` 92MB；bin/magika+ruff 76MB；torch/include 61MB×2 |
| backend/lib | 370MB | Playwright `driver-bundle-1.43.0.jar` 161MB 含五套平台驱动；`javacv-platform` 拖进 opencv/openblas/leptonica/tesseract（mac 51MB、win 101MB），全仓只有 `MeetingAudioTranscoder` 用 ffmpeg |
| Electron Framework | 222MB（压缩后 ~93） | 164 个 lproj 37MB 未裁 |
| frontend/dist/zetaoffice | 116MB | LOWA 64MB（已 brotli）+ CJK 字体 51MB —— **保持随包** |
| jre / python | 67 / 66MB | 已 jlink zip-6、已剥 stdlib test |
| win 专属 | ARM64 壳 +106MB | NSIS 默认 differential-aware（dict 1MB、非固实）；壳走 zlib |

四个 Python 服务都是活功能：pptx（AI 生成 PPT、PDF 转 Word、扫描件 OCR 路由）、mineru（扫描件解析，
经 pptx 转发，无直接前端入口）、kokoro（语音合成唯一实现）、asr（会议转写「录音不出本机」第三档，
faster-whisper）。mineru/kokoro 在模型未下时本就不启动；asr 故意常驻以区分「服务没起」与「模型没下」。

## 2. P1 构建管线纯裁剪（#528）——零产品变化

全部随大版本 0.38.0（patch-gate 拦 pom / desktop/package.json / desktop/build）。

1. **Playwright 单平台**：`prepare-backend.js` 拆包后重写 `driver-bundle-*.jar`，只保留
   `driver/<本平台>/`（mac-arm64 / win32_x64），其余四个平台目录删除。验证：`browse_url` 工具真抓一次页面。
2. **javacv 只留 ffmpeg**：`backend/pom.xml` 的 `org.bytedeco:javacv-platform` 换成
   `org.bytedeco:javacv` + `org.bytedeco:ffmpeg-platform`（或 ffmpeg + classifier），
   `-Djavacpp.platform` 继续生效。验证：`MeetingAudioTranscoder` 真转码一段 webm → mp3；`mvn -B test` 全绿。
3. **electronLanguages**：`desktop/package.json` build 加 `"electronLanguages": ["en-US", "zh-CN"]`。
4. **NSIS 压缩**：`nsis.differentialPackage: false`；`desktop/build/installer.nsh` 在 ARM64 壳
   `File /r` 之前 `SetCompressor /SOLID lzma`（本项目不用 electron-updater 差量；`build.publish` 为空）。
   验证：`installer-ui-smoke.yml` 绿；ARM64 覆盖安装路径不变。
5. **prune 扩表**：`prepare-python-service.js` 的 `prune()` 追加删除：`torch/include`、`torch/test`、
   `torch/share`、`lib/bin/magika`、`lib/bin/ruff`、`speech_recognition/pocketsphinx-data`、
   各包 `tests/`、`testing/` 目录、`gradio/**/*.js.map`、`*.dist-info/RECORD` 不删。
   验证：`desktop/tests/prepare-python-service.test.js` 扩用例；四个服务 CI 冒烟不变。
6. **jlink** `--compress zip-9`。
7. 不做：DMG 换 UDBZ（实测仅 4 个百分点）；字体子集化；zstd `--long=31`。

## 3. P2 四个 Python 服务搬 native pack（#529）

### 3.1 pack 划分与命名
- 一服务一 pack，id：`pptx-runtime`、`mineru-runtime`、`kokoro-runtime`、`asr-runtime`。
  独立 semver 从 `1.0.0` 起；`minAppVersion: 0.38.0`；`engineApi: 1`。
- 每个 pack 的 components：
  - `lib`：`platforms: [mac-arm64]` / `[win-x64]` 各一个压缩包，内容 = `prepare-python-service.js` 产出的
    `pysvc/<service>/lib`（已 prune），`unpackDir: lib`。
  - `app`：`platforms: ["*"]`，内容 = `pysvc/<service>/app`（mineru 无此组件），`unpackDir: app`。
  - 落盘 `~/.aiworkdeck/packs/<id>/<version>/{lib,app}`，与现有 `pysvc/<service>/{lib,app}` 布局同构，
    服务 js 只换根目录。
- 压缩格式沿用 tar.gz（Java 侧解包代码不动、不引新依赖）；zstd 留作后续。
- `NativePackService` 的单包上限从 5000 条目 / 500MB 抬到 **150000 条目 / 2.5GB**
  （manifest 有 Ed25519 签名，zip-bomb 只能来自我们自己的构建；torch 一个包就超旧上限）。
- 模型（`mineru-models` 3GB / `kokoro-models` 300MB / `asr-models` 1.5GB）仍走 `model-manager.js`，
  不并入 pack；「组件」在 UI 上呈现为「运行时 + 模型」一次下载，底层两条通道顺序执行。
- CPython 运行时（`Resources/python` 66MB）继续随包（四个服务与 litviz 共用）。

### 3.2 桌面壳与后端接线
- `desktop/main/services/pysvc-runtime.js`：新增 `resolveServiceRoot(ctx, service)`：
  打包态 → `~/.aiworkdeck/packs/<service>-runtime/current.json` 指向且带 `.pack-complete` 的目录；
  开发态 → 现有 `desktop/bundled/<os>-<arch>/pysvc/<service>`。首启解压 `pysvc.tar.gz` 的逻辑整段删除
  （`main.js` `ensurePysvcReady` 与 splash「约一分钟」文案）。
- 四个 `*-service.js`：`enabled` 一律先判 runtime pack 在场；mineru/kokoro 再判模型；asr 改为
  pack 在场即启动（`/health` 仍回 `modelReady`）。`LocalAsrClient.probe()` 扩成四态：
  `RUNTIME_MISSING / SERVICE_DOWN / MODEL_MISSING / READY`。
- 后端 `NativePackService` 已装/在场判定复用；`PackAutoInstaller` 不对这四个 pack 自动补下
  （用户要求提示后再下）。新增只读接口 `GET /api/packs/optional-components`：返回四个组件的
  id、版本、压缩体积、解压体积、是否已装、模型是否已装、功能清单键（供前端文案）。
- `PptxTools` / `PdfTools`：服务不可达且 pack 未装 → 通过 `EditorBridgeService` 发
  `component_required` 动作（`{ packId, modelId?, sizeMb, features: [...] }`），工具返回给模型的文本
  说明「已请用户确认下载」，不再报「稍后重试」。

### 3.3 打包与发布
- `desktop/scripts/build-pack.js` 增加四个 pack 的组件定义（源目录 = `desktop/bundled/<os>-<arch>/pysvc/<service>`）。
- `.github/workflows/pack-release.yml`：tag `pack-<id>-v<ver>` 触发，矩阵 mac-arm64 + win-x64 各跑
  `prepare-python-service` → `build-pack`，汇总 job merge manifest；`prerelease: true` 保持。
  冒烟：从 pack 布局启动服务打 `/health`（与 desktop-build.yml 现有四个冒烟同款）。
- `desktop-build.yml`：摘掉四步 `prepare-python-service`、pysvc 冒烟、`pack-pysvc`；
  `desktop/package.json` extraResources 摘掉 `pysvc.tar.gz` / `pysvc.meta.json`。
- `build-patch-assets.js` 的 `pysvc-src` 组件废除：服务源码修复走 pack 新版本（24h 自动追新）。
  `patch-gate.sh` 中 requirements.lock 那条改为「lock 改动走 pack 发版」。
- 发布走 `deploy/publish-pack.sh`（签名私钥只在官网机），两站镜像对账。

### 3.4 升级与兼容
- 老用户升级到 0.38.0：随包 pysvc 不再存在，四个服务在 pack 未装时不启动；首次登录后弹「可选组件」面板
  （§4），模型已装的组件默认勾选（用户此前已选择过该功能）。不做静默下载。
- 离线部署：`ai.packs.enabled=false` 已有旁路；另出「离线整包」变体属于后续，不在本轮。

## 4. 两处提示与影响范围（#530）

### 4.1 首次安装登录后：「可选组件」面板
- 触发：桌面端，登录成功后（或本机免登身份解析完成后），`optional-components` 里存在未装组件且
  本机未标记 `optionalComponentsPrompted`（存 `~/.aiworkdeck/prefs` 或 electron-store，不是 localStorage，
  重装才重置）。每次大版本升级后若出现新组件再提示一次。
- 内容：四张卡片各写 名称 / 下载体积（运行时 + 模型分开列）/ 解锁功能 / 不装时哪些功能不可用；
  复选框；「立即下载所选」（逐个顺序下载，带总进度）与「稍后再说」（可从 设置→组件管理 再来）。
- 同一组件卡片组件复用到 设置→组件管理（替换现有只列模型的三行）。

### 4.2 首次触发模块
- AI 对话：收到 `component_required` → 弹窗（复用 DrawioEditor `installPack*` 文案与交互）→
  确认后 `POST /api/packs/{id}/install` 带进度 → 若有 `modelId` 接着走 model-manager 下载 →
  `host.services.ensure(service)` → 自动把原消息重发一次。
- 语音合成面板：现有 `EasyVoicePane` 下载状态机从「只下模型」扩成「运行时 + 模型」。
- 录音「不出本机」开关：打开时探测四态；`RUNTIME_MISSING` 弹同款提示。

### 4.3 文案模板（zh-CN / en 双语，落 `frontend/src/locales/*/components.js`）
> 需要下载〈组件名〉（约 N MB；含模型约 M MB）。落盘于 ~/.aiworkdeck，可在「设置→组件管理」卸载释放。
> 它用于：……。不下载则：……不可用；其余功能不受影响。

| 组件 | 运行时 | 模型 | 解锁功能 | 不装的影响 |
|---|---|---|---|---|
| PPT 与 PDF 组件（pptx-runtime） | ≈165MB | 无 | AI 生成 PPT、PPTX 读/改格式、PDF 转 Word（版式级）、扫描件 OCR 入口 | 上述功能不可用；PDF 仍可预览与结构级转换 |
| 文档解析引擎（mineru-runtime） | ≈250MB | 3GB | 扫描件 PDF 转 Word / OCR 解析 | 扫描件转换退回云端 MinerU 或不可用 |
| 语音合成（kokoro-runtime） | ≈200MB | 300MB | 语音面板「语音合成」 | 语音合成不可用 |
| 本机语音识别（asr-runtime） | ≈40MB | 1.5GB | 录音转写「录音不出本机」档 | 只能用云端听悟两档 |

（体积以 pack 构建实测为准，UI 从 manifest `size` 读，不写死。）

## 5. 验证口径
- P1：mac 本地 `electron-builder --dir` 量 Resources 与 Frameworks；CI Windows 腿产物体积；
  `mvn -B test`；会议录音真转码；`browse_url` 真抓取；`installer-ui-smoke.yml`。
- P2：四个 pack 在两平台从 pack 布局启动过 `/health`；桌面端 `desktop/tests` 新增
  `pysvc-runtime.pack-resolve.test.js`；后端 `PackController` 与 `PptxTools` 单测；
  前端 `components` 面板单测；真机走查：新装 → 首次登录面板 → 稍后再说 → AI 生成 PPT 触发提示 → 下载 → 自动重试成功。
- 发版：0.38.0 大版本；四个 pack 先于 0.38.0 发布到两站镜像（否则新装用户无处可下）。
