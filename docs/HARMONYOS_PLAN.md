# 鸿蒙（HarmonyOS PC）版本规划

> 2026-07-13 定稿。目标：release 三件套 —— Windows exe、mac Apple Silicon dmg、鸿蒙可安装包（HAP/App）。

## 一、结论先行

鸿蒙 NEXT（鸿蒙电脑 = HarmonyOS 5/6，麒麟 ARM）**没有 Linux 兼容层、没有 JVM、没有 Python/PyTorch 生态**。
现有桌面版三层架构在鸿蒙上的命运：

| 现有组件 | 鸿蒙上的命运 |
|---|---|
| Electron 前端壳（含 LOWA 编辑器） | ✅ 可跑 —— OpenHarmony SIG 官方移植（[openharmony-sig/electron](https://gitcode.com/openharmony-sig/electron)，Electron 34，预编译 `libelectron.so`）。WPS 鸿蒙 PC 版即此路线 |
| Spring Boot 后端 + 裁剪 JRE | ❌ 无任何 JVM 发行版支持鸿蒙 NEXT |
| Python 三服务（MinerU / pptx / Kokoro） | ❌ 无鸿蒙 Python + torch 轮子 |

**因此鸿蒙版必然是瘦客户端**：界面在本机，后端在远端 ——
用户自己的 ECS，或局域网里一台跑桌面版的 Windows/mac 主机（数据不出内网）。

## 二、路线：A → B 分步走

### Phase A：Web 服务器版（鸿蒙浏览器可用，也是 B 的技术前提）

前端已是 uni-app H5 + `VITE_API_BASE_URL`（默认同源），后端全部路由在 `/api/` 下，
`application-prod.yml` / docker-compose 已备 —— 服务器化地基现成。缺两块：

1. **A1 · H5 编辑器通道**（核心工程点）：编辑器目前是 Electron `<webview>` 专属
   （`LibreOfficeEditor.vue` 依赖 `window.checkbaDesktop.zetaoffice`）。
   纯浏览器需要 iframe + MessageChannel 走同一套 editor bridge/executor 协议。
   LOWA 在普通浏览器里跑的可行性早已由 `experiments/zetaoffice-spike/`（serve.mjs + COOP/COEP）证明，
   剩下的是产品化。
2. **A2 · 部署配置**（本次已落）：`deploy/web/` —— nginx 配置
   （全站 COOP/COEP、`/lowa/*` brotli 预压缩回放、`/api` 反代 + SSE 不缓冲）+ 部署 README
   + 鸿蒙浏览器能力探针页（`deploy/web/probe/`，验证 crossOriginIsolated / SharedArrayBuffer / WASM threads）。

**验证**：探针页在鸿蒙电脑浏览器全绿 → LOWA 必然能跑（同为 Chromium 内核 ArkWeb）。
无真机时可去华为体验店 10 分钟测完，或先在 DevEco 鸿蒙 PC 模拟器里测。

### Phase B：鸿蒙 Electron 壳（正式可安装形态）

用 openharmony-sig/electron 打包现有前端 + 主进程（`zetaoffice-server.js` 等大部分可复用），
砍掉 JVM/Python 拉起逻辑，改为「连接远端后端」设置页（复用向导）。产出 HAP/App。

前置条件（用户侧）：
- 华为开发者账号（个人实名免费；上架 AppGallery 建议企业认证 + 软著）
- 鸿蒙电脑真机（MateBook Pro 约 ¥7,999 起）或先用 DevEco 模拟器
- 侧载调试：普通账号调试证书 14 天有效期，适合开发期；正式分发走 AppGallery

### Phase C（backlog）：uni-app harmony 原生编译

`package.json` 已带 `@dcloudio/uni-app-harmony` / `build:mp-harmony`。可编译 ArkTS 原生应用，
顺带覆盖鸿蒙手机/平板；但 PC 窗口形态与 LOWA 嵌 ArkWeb 的跨域隔离风险最高，不作为 PC 首选。

## 三、风险表

| 风险 | 等级 | 缓解 |
|---|---|---|
| 华为浏览器/ArkWeb 对 SharedArrayBuffer 的真机支持 | 中 | 探针页 10 分钟验证；ohos Electron 自带 Chromium，不受影响 |
| ohos Electron 对 Node API（如 child_process）的裁剪边界 | 低 | 鸿蒙壳不需要拉子进程（后端在远端） |
| 签名/上架资质（软著、企业认证）周期 | 中 | 开发期侧载 + 模拟器并行，资质提前申请 |
| uni-app H5 编辑器 bridge 协议在 iframe 下的差异 | 中 | A1 单独 issue，spike 先行 |

## 四、预算（截至 2026-07，下单前请核对）

| 项目 | 费用 |
|---|---|
| 华为开发者账号（个人/企业认证） | 免费 |
| 软著（上架用） | 自办免费（2-3 个月）/ 代办加急约 ¥300-800 |
| 鸿蒙电脑真机 | 约 ¥7,999（MateBook Pro 低配）；验证阶段可先模拟器/体验店，¥0 |
