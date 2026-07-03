# 桌面版三服务完整打包方案（pptx / MinerU / TTS 本地化）

> 2026-07-03 设计定稿。目标：兑现桌面版「除大模型调用外，文件、运算、服务全部本地」的产品承诺。
> 现状差距由本次勘察确认：安装包已捆绑 Java 后端 + 精简 JRE + H2 本地库 + LOWA 离线编辑器，
> 但 **pptx-service（AI PPT）、mineru-service（文档解析）、easyvoice（语音合成）三个服务不在包内**，
> 装机用户这三项功能不可用或需自行部署 Docker——与「双击即用」的定位相悖。

---

## 0. 决策记录（维护者已确认）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 体积策略 | **运行时进包，模型首启下载**。安装包约 300MB → **约 2GB**；MinerU 模型（约 3GB）与 Kokoro 权重（约 300MB）首次使用时下载到 `~/.aiworkdeck/models/`，之后纯离线 | 全塞进包则安装包 5GB+ 且每次发版全量重下；类比 Ollama 拉模型的体验，用户已接受该心智模型（向导本就引导 `ollama pull`） |
| TTS 引擎 | **easyvoice（编排层）+ Kokoro（本地引擎）**。ElevenLabs 降级为可选云端高音质 | easyvoice 本身不是引擎，默认合成走云端接口；Kokoro 82M 参数、Apache 2.0、CPU/Apple Silicon 实时、中英双语，且 easyvoice 已预留 `localhost:8880/v1` 对接口 |
| ViiTor Voice | **不用于本地版，记入云端版候选** | 硬依赖 NVIDIA CUDA（无 CPU/Apple Silicon 路径，目标用户多为 MacBook/办公本）；仓库无 License（分发即侵权风险）；维护弱（新仓 5 commits）。待云端版立项且其挂出可商用 License 再评估 |
| 打包机制 | **内嵌运行时 + 统一 ServiceManager**（沿用 Java 后端已验证模式），不用 PyInstaller 冻结（torch/MinerU 冻结脆弱）也不内嵌容器运行时（体积/权限/虚拟机体验差） | CI 预烙每平台运行时 → extraResources 进包 → Electron spawn 管理，`prepare-backend.js` + jlink 这条链路已在生产验证 |
| mac 架构 | **仅支持 Apple Silicon，放弃 Intel Mac**（2026-07-03 Phase 1 实施中确认） | onnxruntime 1.20+/pikepdf 9+ 等依赖已停发 macOS x86_64 wheel，交叉烙制不可持续；macos-13 Intel runner 已退役；Phase 2 的 torch 2.3+ 同样无 Intel mac 包 |

## 1. 目标与非目标

**目标**

1. macOS（arm64/x64）与 Windows（x64）安装包内含三个服务的全部代码与运行时，用户无需 Docker / Python / Node / Java；
2. 除以下两类流量外，桌面版默认零出网：① 用户选定的大模型 API（或本地 Ollama）；② 首次使用组件时的一次性模型下载；
3. 每个捆绑服务在 CI 有真实拉起的冒烟测试（对标现有后端 smoke test）。

**非目标**

- 不做云端版（本地成熟后另行立项，届时评估 ViiTor 等 GPU 方案）；
- 不重写三个服务的业务逻辑，只动打包、启动、端口与出网开关；
- 不承诺低配机（<8GB 内存）上 MinerU 解析的速度，只保证可用性提示。

## 2. 架构

### 2.1 ServiceManager（desktop/main/services/）

把 `desktop/main/backend.js` 的单服务逻辑泛化为通用服务管理器。每个服务一份描述符：

```js
{
  name: 'pptx-service',
  runtime: 'python',        // 'jre' | 'python' | 'node-utility'
  entry: 'backend/app.py',
  startMode: 'lazy',         // 'eager' | 'lazy'（首次功能调用时拉起）
  healthPath: '/api/health',
  env: (ports) => ({ ... }), // 端口等注入
}
```

| 服务 | 运行时 | 启动时机 | 说明 |
|---|---|---|---|
| Java 后端 | 捆绑 JRE（现状不变） | eager | 迁移为 ServiceManager 首个描述符，行为不变 |
| pptx-service | 捆绑 Python | eager（轻量 Flask；触发方在 Java agent 工具循环内、渲染层无 ensure 拦截点，lazy 机制随 Phase 2 mineru 落地） | Flask + SQLite，秒级启动 |
| mineru-service | 捆绑 Python | lazy（首次文档解析） | 内存大户；退出应用即停 |
| easyvoice | **Electron utilityProcess**（复用自带 Node） | lazy | 无需捆绑 Node 运行时，省 50MB+ |
| kokoro-tts | 捆绑 Python | 随 easyvoice 拉起 | 暴露 OpenAI 兼容 `/v1`，easyvoice 指向它 |

**端口策略**：不再写死 5001/8001/9549/8880。ServiceManager 逐服务挑选空闲端口，经环境变量注入消费方（Java 后端读 `PPTX_SERVICE_URL`、easyvoice 读 `TTS_BASE_URL` 等，Spring 侧用 relaxed binding 已支持环境变量覆写 `application.yml`）。

**进程治理**：统一日志到 `~/.aiworkdeck/logs/<service>.log`；应用退出时逐个优雅停止（沿用 backend.js 的信号处理）；崩溃自动重启一次，再失败则在前端标记该功能不可用并给出日志路径。

### 2.2 Python 运行时打包（CI 侧）

新增 `desktop/scripts/prepare-python-service.js`（对标 `prepare-backend.js`）：

1. 下载对应平台 [python-build-standalone](https://github.com/astral-sh/python-build-standalone)（统一 **3.11**——MinerU 与 pptx-service 均兼容；mac arm64 + win x64 各一份，**同一安装包内多个 Python 服务共享同一运行时**）；
2. 按各服务锁定的 `requirements.lock`（新增，pip-compile 生成）安装到独立 site-packages：`desktop/bundled/${os}-${arch}/pysvc/<service>/`；torch 用 **CPU 版 wheel**（mac 走默认含 MPS 的 wheel）；
3. 裁剪：`__pycache__`、tests、`*.dist-info` 冗余、opencv 不用的模块。

（原方案第 4 步「mac x64 交叉取 wheel」已随「放弃 Intel Mac」决策取消——Phase 1 验证时确认 onnxruntime/pikepdf 无 x86_64 wheel，该风险以缩小支持面方式消解。）

easyvoice：CI 里 `pnpm build` 出 `dist/`，连同生产 `node_modules`（pruned）进 extraResources；另捆绑静态 ffmpeg（约 25MB/平台，BtbN/evermeet 构建，License 合规确认 GPL 版可随 AGPL 主体分发）。

### 2.3 ModelManager（模型首启下载）

- 下载目录统一 `~/.aiworkdeck/models/{mineru,kokoro}/`；MinerU 经环境变量（`MINERU_MODEL_SOURCE=modelscope` + cache dir 重定向）走 ModelScope 国内源；Kokoro 权重优先 ModelScope 镜像、回退 HuggingFace；
- 断点续传 + sha256 校验；下载进度经 Electron IPC 推给前端；
- 前端首次触发相关功能：确认框（「此功能需一次性下载约 xGB 组件」）→ 进度条 → 完成后自动拉起服务；
- 系统管理新增「组件管理」页：各组件状态（未下载/已就绪/损坏）、占用空间、删除与重下。

### 2.4 出网收口（合规）

| 出网点 | 现状 | 桌面版处置 |
|---|---|---|
| MinerU 云端兜底（mineru.net） | pptx-service 在本地 MinerU 不可达时回落云端 | **默认关闭**，系统管理可显式开启并二次确认 |
| easyvoice 云端 TTS（OpenRouter/OpenAI 接口） | 默认路径 | 默认指向本地 Kokoro；ElevenLabs 作为显式可选项保留 |
| LOWA CDN 兜底（cdn.zetaoffice.net） | 缺文件才触发 | 保持现状（正常打包不触发） |
| 企查查/Tushare/北大法宝/阿里云 OCR | 用户填 key 才启用 | 保持现状 |
| 向导数据流向文案 | 只写了对话内容 | 同步更新：语音合成、文档解析均不出本机；组件下载为一次性 |

## 3. 分期交付（每期一个 PR 合入 master，CI 全绿）

### Phase 1 — 打包骨架 + pptx-service（先走通 Python 链路）

- ServiceManager 重构（Java 后端迁入，行为回归验证）；
- `prepare-python-service.js` + pptx-service `requirements.lock`；
- pptx-service 进 extraResources，lazy 启动、动态端口注入 Java 后端；
- `desktop-build.yml`：三平台烙制 Python 运行时 + pptx venv；冒烟测试（拉起捆绑 pptx-service → curl 健康检查 → 生成一页最小 PPT 走通）；
- mac x64 交叉 wheel 验证（风险点前置）。

### Phase 2 — mineru-service + ModelManager

- mineru venv 进包（torch CPU/MPS）；ModelManager（下载/续传/校验/进度 IPC）；
- 前端确认框 + 进度条 + 组件管理页；
- MinerU 云端兜底默认关闭；
- 冒烟测试：拉起 mineru-service（跳过模型下载，用最小 stub 或缓存层 mock）→ 健康检查。

### Phase 3 — TTS 本地化（easyvoice + Kokoro + ffmpeg）

- easyvoice 以 utilityProcess 进包；kokoro-tts venv 进包；静态 ffmpeg 进包；
- easyvoice 默认指向本地 Kokoro；ElevenLabs 转显式可选；
- 向导与合规文案更新；
- 冒烟测试：Kokoro 合成一句短文本出 wav → easyvoice 编排链路走通。

## 4. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| ~~mac x64 交叉 wheel~~ | ~~高~~ | **已消解**：放弃 Intel Mac（见 §0 决策记录），mac 仅出 arm64 包 |
| MinerU 内存要求（8GB+） | 中 | 启动前 `os.totalmem()` 检查，低配机提示「解析会较慢/建议关闭其他应用」，不阻断 |
| 安装包 2GB 的下载/公证时长 | 中 | dmg 压缩已含 brotli 思路（LOWA 先例）；公证时间可接受（LOWA +60MB 先例）；发版说明标注体积变化 |
| Windows 未签名 + 大量新二进制 → SmartScreen/杀软误报 | 中 | 已知现状（issue #12），签名另行推进；Python 运行时用官方 release 原样分发降低误报 |
| ModelScope 源稳定性 | 低 | 双源（ModelScope 主、HF 备）+ 断点续传 + 校验 |
| ffmpeg GPL 与 AGPL 主体共分发 | 低 | AGPL 兼容 GPL 二进制并置分发；保留 LGPL 构建选项备用 |

## 5. 验收标准（整体）

1. 全新 mac（无 Docker/Java/Python）装 dmg：AI PPT、文档解析、语音合成三项功能均可在确认组件下载后正常使用；
2. 断网状态（组件已下载、选本地 Ollama）：三项功能全部可用，抓包无出网流量；
3. 每平台 CI 冒烟测试覆盖四个捆绑服务（Java/pptx/mineru/kokoro+easyvoice）的真实拉起与健康检查；
4. 卸载后 `~/.aiworkdeck` 保留（用户数据与模型），文档说明手动清理方式。

## 6. 云端版备忘（不在本次范围）

- 本地版成熟后另立项：本地只留界面，服务全部上云；
- TTS 候选：ViiTor Voice（音色克隆能力强，需其提供可商用 License；GPU 服务器环境下 CUDA 依赖不再是障碍）；
- 届时 ServiceManager 的服务描述符可平滑替换为远端 URL——本方案的解耦是云端版的地基。
