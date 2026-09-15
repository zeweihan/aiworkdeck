# 0.38.0 发布顺序（四个 Python 运行时 pack 先行）

**硬约束：四个 pack 必须先于 0.38.0 安装包上两站镜像并 verify 通过。** 顺序反了，
新装用户点「下载组件」会得到 404，而这四个组件覆盖了 PPT 生成、PDF 版式级转换、
语音合成与本机转写四条功能线。

规范见 `docs/NATIVE_PACK_DISTRIBUTION.md` §7.5；设计见
`docs/superpowers/specs/2026-09-09-installer-slimming-design.md` §3。

## 0. 前置

- `desktop/package.json` 的版本已经是 `0.38.0`（本仓惯例：单独一个
  `chore(release): bump desktop version to 0.38.0` PR，与实施 PR 分开）。
  四个 pack 的 `minAppVersion` 写死 `0.38.0`（`build-pack.js` 的
  `MIN_APP_VERSION_BY_ID`），装到更早的壳上会被拒——两边必须对得上。
- 签名私钥只在北京官网机上（`AWD_PLUGIN_SIGNING_KEY`），不进 CI、不落本机。

## 1. 出包（每个 pack 一次）

对每个 id ∈ {`pptx-runtime`, `mineru-runtime`, `kokoro-runtime`, `asr-runtime`}：

Actions → Pack Release → `pack_id=<id>`，`version=1.0.0` → 等 `runtime (mac-arm64)`
与 `runtime (win-x64)` 两腿的「Smoke test from pack layout」都绿 + `release` job
出 prerelease。

两腿各产什么：

| 腿 | 组件 | 说明 |
|---|---|---|
| mac-arm64 | `lib` + `app` | `app` 平台无关，只在这一腿产一次 |
| win-x64 | `lib` | 冒烟用本腿刚烙好的 `desktop/bundled/win-x64/pysvc/<svc>/app` 顶班 |

`mineru-runtime` 两腿都只产 `lib`（纯 pip 包服务，没有 `app/`）。

## 2. 签名与上架（每个 pack 一次）

下载该 release 的全部资产到本机同一目录，逐个跑：

```
bash deploy/publish-pack.sh check   ~/Downloads/pack-<id>-v1.0.0
bash deploy/publish-pack.sh sign    ~/Downloads/pack-<id>-v1.0.0
bash deploy/publish-pack.sh publish ~/Downloads/pack-<id>-v1.0.0 <id> 1.0.0
bash deploy/publish-pack.sh verify  <id> 1.0.0
```

`verify` 要求北京（www.aiworkdeck.com）与新加坡（workdeck.ai）两站的 manifest 与
每个组件的 sha256/size 全部一致；**任一站不过就不许继续**（lowa r4 只传北京、CI 从
新加坡拉到 404 的教训）。

## 3. 发应用

四个 id 全部 `verify` 通过后，再打应用 tag `v0.38.0`，走 `desktop-build.yml`。

## 4. 真机验收（新装 DMG）

- 安装包体积：mac ≤ 700MB（Phase 2 目标口径），`Contents/Resources` 下**没有**
  `pysvc.tar.gz`；
- 首启没有「正在准备本地组件…约一分钟」的解压窗；
- 登录后出现「可选组件」面板（#530），四张卡片体积数字非 0；
- 「稍后再说」→ AI 对话让它生成 PPT → 弹下载提示 → 下载完自动重发原消息并成功出 PPT；
- 语音面板下载「语音合成」→ 运行时 + 模型两段进度 → 合成成功；
- 会议面板打开「录音不出本机」→ 提示 `RUNTIME_MISSING` → 装完组件与模型 → 转写成功。

## 5. 回滚

- **pack 侧**：按镜像上旧版本的 manifest 快照重装（旧版本常年在架，`publish` 只切
  `<id>/manifest.json` 这个指针，历史版本目录不删）。
- **应用侧**：官网发 0.37.x——它自带 pysvc，与 pack 互不影响。
