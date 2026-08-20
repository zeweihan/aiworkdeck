# 发布视频图卡动效（dev-board #59）

三段图卡动效，内容对照 `../script-v2.md`：

| 文件 | 时长 | 内容 |
|---|---|---|
| `card-open.html` → `out/card-open.mp4` | 14.0s | 冷开场：黑场浮现「一个律师的一个案子，要开多少个窗口？」 |
| `card-reveal.html` → `out/card-reveal.mp4` | 26.0s | 三合一揭晓：三行字逐行浮现 → 淡出 → 「AI WorkDeck」落定 + 副标题 |
| `card-close.html` → `out/card-close.mp4` | 12.0s | 收束：「把整个案子，装进一个窗口。」→「让专业回归判断。」+ 角标版本号 |

## 视觉规范

衬线中文（Songti SC，回退 Noto Serif SC / STSong）；品牌名/版本号等纯拉丁文字用 Georgia 衬线（回退 Times New Roman）。纸白（`#f7f3e8`）/ 墨黑（`#201d18` 或黑场 `#050504`）双色，无渐变、无纹理装饰、无 emoji。动效只用透明度 + 轻微位移（12–22px），三段缓入缓出（`easeInOutCubic`），克制、慢。

## 实现方式：确定性逐帧渲染

每个 HTML 是自包含页面（1920×1080），不使用 CSS `animation`/`transition`。页面在 `window.renderFrame(t)` 里根据「相对片段起点的秒数 t」直接计算每个元素的 `opacity`/`transform`，纯函数、无副作用。`render.mjs` 按 30fps 从 `t=0` 精确步进到片段时长，每步调用一次 `renderFrame(t)` 再截图——不依赖无头浏览器里 CSS 动画的真实播放时钟，因此不会有丢帧/时间漂移问题，每一帧对应的画面状态是可复现的。

截完全部帧后用 ffmpeg 按恒定 30fps 编码为 h264 mp4（`yuv420p` + `faststart`）。

## 用法

```bash
cd docs/marketing/launch-video/cards
node render.mjs              # 渲染三段
node render.mjs card-open    # 只渲染一段
```

依赖直接复用 `../pipeline/node_modules` 里已装好的 `puppeteer-core`（相对路径 import，`cards/` 下不另装 `node_modules`）。无头浏览器用本机 Google Chrome.app，路径写死在 `render.mjs` 里，可用 `CHROME_PATH` 环境变量覆盖。

产物落在 `out/`（不入库）：

- `out/<name>.mp4` —— 最终成片
- `out/<name>-check/{first,mid,last}.png` —— 每段的首/中/尾帧，渲染后人工核对用

## 自查结论（渲染于本次改动时）

- 三段分辨率均为 1920×1080，帧率 30fps，时长与设计值精确一致：14.000s / 26.000s / 12.000s（`ffprobe` 核对）。
- 首/中/尾帧目视核对：字体确实是衬线（中文 Songti SC、品牌名 Georgia），无截断、无溢出；`card-reveal` 额外在 t=2.0/6.0/9.5/14.3/20.0s 抽帧核对了三行逐次浮现、淡出、品牌名与副标题落定的节奏，均符合预期。
- 全程无 emoji、无渐变背景、无花哨装饰，动效仅透明度 + 轻微位移。
