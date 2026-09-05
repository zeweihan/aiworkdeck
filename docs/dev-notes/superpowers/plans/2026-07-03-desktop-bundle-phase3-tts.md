# 桌面版三服务打包 Phase 3：本地 TTS（Kokoro 直连，跳过 easyvoice）— 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 语音合成本地化：新增极薄 kokoro-service（OpenAI 兼容 `/v1/audio/speech`）进包，Kokoro v1.1-zh 模型（约 300MB，中英双语）经 ModelManager 首启下载，Java `TtsService` 增加 local provider 分支（桌面默认 local，ElevenLabs 转显式可选）。

**Architecture 关键修订（勘察后偏离设计稿 §2.1/§3，理由充分）：**
- **跳过 easyvoice**：生产已停用（restart-all.sh 注释、无任何调用方），真实链路是 前端 → Java `/api/tts` → ElevenLabs 直连；easyvoice 连 OpenAI 兼容端点都不暴露，捆绑它 = 白养一个 Node 服务。
- **跳过 ffmpeg**：只为 easyvoice 音频拼接而存在；Kokoro 包装层直接出 WAV（soundfile），无转码需求。
- 新增 `kokoro-service/`（仓库新目录，约百行 FastAPI）：`GET /health`、`GET /v1/audio/voices`、`POST /v1/audio/speech`；模型懒加载（/health 不依赖模型，CI 冒烟可用）；按 voice 前缀选 pipeline（`zf_/zm_`→中文 `z`，`af_/bf_`→英文 `a`）；运行时 `HF_HUB_OFFLINE=1` 强制零出网。
- License 全链路干净：kokoro / misaki 均 Apache 2.0。

## Global Constraints

- 沿用 Phase 1/2 全部约束与基建（python 3.11 共享运行时、prepare-python-service.js、ModelManager、组件管理页数据驱动、mac arm64-only、docs 强制 add）。
- **git 推拉一律走 SSH**（`ssh://git@github.com/...`，本机代理 fake-IP 卡死 HTTPS git 通道）。
- ElevenLabs 云端/自部署行为不变：`external.tts.provider` 默认 `elevenlabs`，仅桌面 spawn env 注入 `local`。
- Kokoro 模型不进包不进 CI；CI 冒烟 = 无模型拉起 + `/health` + `/v1/audio/voices`。
- 本期收尾即发 **v0.6.0**：desktop/package.json 版本随 PR 提到 0.6.0，合并后打 tag 触发发布。

## Tasks

### Task 1: kokoro-service 源码 + requirements.lock + 本地验证
- Create `kokoro-service/app.py`（FastAPI：三端点；KPipeline 按 lang 缓存懒加载；voice 表：`zf_001`（女·中文，默认）、`zm_010`（男·中文）、`af_maple`（女·英文）、`bf_vale`（女·英文）——均为 v1.1-zh 仓库自带；`speed` 参数透传；输出 WAV 24kHz）
- Create `kokoro-service/requirements.in`（kokoro、misaki[en,zh]、fastapi、uvicorn、soundfile、espeakng-loader）→ `uv pip compile --universal` 出 `requirements.lock`
- 本地烙制（复用 prepare-python-service.js，--src kokoro-service）→ 无模型拉起验 /health、/v1/audio/voices
- **本地全链路验证一次**（下载 v1.1-zh 模型到临时 HF_HOME → synth 一句中文出 wav 非空）——CI 不做，这里必须做

### Task 2: Java TtsService local provider 分支
- `application.yml` 加 `external.tts.provider: elevenlabs` / `external.tts.local-base-url:`（空）
- TtsService：`resolveProvider()`（settings 覆盖 env 覆盖 yml）；local 分支 getVoices → GET local `/v1/audio/voices`；generateAudio → POST `/v1/audio/speech`（input/voice/speed 映射，rate "1.2x"→speed 1.2 容错解析），出 .wav；本地服务连接失败 → `FeatureNotConfiguredException("tts", "本地语音组件未就绪：请在系统管理 → 组件管理 下载语音组件")`（前端既有引导机制直接吃住）
- AdminConfigController/向导不加新 UI（provider 切换走 system_setting，后续需要再说）；backend `mvn test` 过

### Task 3: 桌面接线
- ModelManager COMPONENTS 加 `kokoro-models`（约 300MB；spawnSpec：kokoro lib 的 python `-c` snapshot_download('hexgrad/Kokoro-82M-v1.1-zh')，env `HF_HOME=<models>/kokoro` + `HF_ENDPOINT`（默认 hf-mirror.com 国内镜像，env 可覆盖））
- Create `desktop/main/services/kokoro-service.js` 描述符（条件 eager=模型已装；dev 复用 8880；打包态动态端口；env：PYTHONPATH、HF_HOME 同下载侧、`HF_HUB_OFFLINE=1`、PORT）
- backend-service.js spawnEnv 注入 `EXTERNAL_TTS_PROVIDER=local` + `EXTERNAL_TTS_LOCAL_BASE_URL`（仅 ctx.ports['kokoro-service'] 存在时）
- main.js：注册描述符；onProgress done 的自动拉起与 admin.vue 的「启用」按钮改为 **组件→服务名映射表**（{mineru-models: mineru-service, kokoro-models: kokoro-service}）
- desktop 单测：ModelManager 多组件 status 用例补一条

### Task 4: 文案/文档/版本
- wizard.vue：合规文案补语音合成本地化；「语音合成（ElevenLabs）」组标题改「语音合成（可选云端 ElevenLabs——默认使用本地引擎，无需配置）」
- 设计文档 §0 决策记录追加「跳过 easyvoice/ffmpeg」及理由；§2.1 表格、§3 Phase 3、验收标准同步；desktop/README 打包步骤补 kokoro
- desktop/package.json version → **0.6.0**

### Task 5: CI + PR + 发布
- desktop-build.yml：双平台 Bundle kokoro-service + 冒烟（/health + voices）
- push（SSH）→ PR → CI 全绿 → 合并 → 打 tag v0.6.0（触发 desktop-build 的 release 附件与说明）→ 核对 Release 页

## 风险
- kokoro 依赖树含 torch——与 mineru 共享同一运行时但独立 site-packages，安装包再 +约 1.5GB 未压缩：**接受**（若 dmg 超 3GB 在 PR 记录实际值；后续可做 lib 去重优化，不阻塞本期）
- espeakng-loader 若缺 win wheel：从 requirements 移除（仅影响英文 OOD 词发音质量）
- v1.1-zh 仓库文件名/voice 名以 Task 1 实测为准（计划中的 voice 表允许微调）
