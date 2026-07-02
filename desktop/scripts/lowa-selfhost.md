# 自托管 LOWA 引擎（替换 CDN）/ Self-hosting the LOWA engine

> 中文在前，English below.

嵌入式 LibreOffice 编辑器（LOWA / ZetaOffice WASM）默认从
`https://cdn.zetaoffice.net/zetaoffice_latest/` 下载运行时，焙进安装包离线使用。
上游 CDN 的引擎是 `--with-lang=en-US` 单语编译，**UI 全英文且运行期改不动**
（详见 issue #66）。要得到中文原生 UI，需要**自建一个含 zh-CN 的 LOWA**，然后让构建
脚本从你自己的来源拉取，而不是 CDN。

`desktop/scripts/fetch-lowa-assets.js` 为此加了一个环境变量 **`LOWA_BASE_URL`**：

- **不设**（默认）：从 ZetaOffice CDN 拉取，行为与历史**逐字节一致**。
- **设了**：从该 base 拉取 4 个 LOWA 运行时文件
  （`soffice.js` / `soffice.wasm` / `soffice.data` / `soffice.data.js.metadata`）。
  base 可以是 **https/http URL** 或 **`file://` 本地目录**，**必须**能直接拼接文件名
  （脚本自动补尾部 `/`）。CJK 字体 `cjk.ttc` 不受影响，始终从 `FONT_URL` 拉取。

> 自建产物（`desktop/lowa-build/`，由另一条工作线在 ≥64GB 机器/云 VM 上构建）多半是
> **裸字节（identity，未 brotli 压缩）**。脚本按**实际拿到的 content-encoding** 写
> `lowa/.encodings.json`——identity 文件不写编码，`zetaoffice-server.js` 就直接当裸
> wasm/data 发，不做 brotli 回放。无需手工改任何编码配置。

## 怎么把自建产物接上来 / How to wire up a self-built engine

构建机产出 4 个文件后，有两条路径：

### 优先方案：阿里云 OSS（自托管 https）

1. 在阿里云 OSS 建一个 bucket（或复用现有），开公共读，传入 4 个文件，例如放在
   `lowa/` 前缀下：
   ```
   lowa/soffice.js
   lowa/soffice.wasm
   lowa/soffice.data
   lowa/soffice.data.js.metadata
   ```
   裸字节直接上传即可（不要让 OSS 给它们设 `Content-Encoding: br`，除非文件确实是
   brotli——脚本会按实际编码处理，但 identity 最简单）。
2. 构建时把 `LOWA_BASE_URL` 指向该前缀（**注意尾部斜杠**）：
   ```sh
   export LOWA_BASE_URL="https://<bucket>.oss-<region>.aliyuncs.com/lowa/"
   cd /绝对路径/aiworkdeck && node desktop/scripts/fetch-lowa-assets.js
   # 然后照常 cd frontend && npm run build:zetaoffice && npm run build:h5
   # 再 electron-builder 打包
   ```
3. CI 里同理：把 `LOWA_BASE_URL` 设为仓库/环境变量（或 secret），在
   `fetch-lowa-assets.js` 步骤之前导出。不设则继续走 CDN。

### 先本地验证：`file://` 目录

接 OSS 之前，先用本地目录验证 4 个文件能被脚本接受、能打进 bundle：

```sh
export LOWA_BASE_URL="file:///绝对路径/to/lowa-build-output/"   # 目录里直接放 4 个文件
cd /绝对路径/aiworkdeck && node desktop/scripts/fetch-lowa-assets.js
cat frontend/dist/zetaoffice/lowa/.encodings.json   # 裸字节应为 {} ；含 br 的才会列出
```

`file://` 一律按 identity 处理（本地文件没有 content-encoding 概念）。

## 校验与排错 / Validation

- 脚本对每个文件做 **magic / shape 校验**（wasm 头 `\0asm`、JSON 以 `{` 开头、字体头、
  非空大小），解压后再校验——截断、HTML 错误页、坏 brotli 都会让构建**直接失败**，
  不会悄悄发坏包。
- 支持的编码：`identity`（裸字节）、`br`、`gzip`；其它编码会报错中止。
- `static` 资产（字体）必须是 identity。
- 默认 CDN 路径仍**断言**期望编码（wasm/data=br），CDN 变更会让构建失败；自定义
  `LOWA_BASE_URL` 时编码未知，接受任意可解码/可回放的编码并按实际记录。

**真机验收**：沙箱/CI 起不了 LOWA（无显示器、office 线程 ~2-3 分钟/次），中文 UI
是否生效**只能在装包后真机确认**——这一步留给打包验收。

---

## English summary

The embedded LibreOffice editor (LOWA / ZetaOffice WASM) is baked into the desktop
app at build time by `desktop/scripts/fetch-lowa-assets.js`, defaulting to the
ZetaOffice CDN. The CDN engine is English-only (`--with-lang=en-US`, baked at
compile time — see issue #66), so a Chinese UI requires a **self-built LOWA** that
you host yourself.

Set **`LOWA_BASE_URL`** to override the source of the 4 runtime files
(`soffice.js`, `soffice.wasm`, `soffice.data`, `soffice.data.js.metadata`):

- **Unset (default):** fetch from the CDN — byte-for-byte unchanged.
- **Set:** fetch from that base — an `https`/`http` URL (e.g. an Aliyun OSS bucket)
  or a `file://` directory. A trailing `/` is added automatically. The CJK font is
  unaffected (always from `FONT_URL`).

A self-built `soffice.data`/`.wasm` is usually **raw bytes (identity)**. The script
records the **actual** content-encoding per file into `lowa/.encodings.json`, so
identity files get no encoding and `zetaoffice-server.js` serves them raw — no
brotli replay, no manual config.

**Preferred:** upload the 4 files to an Aliyun OSS bucket (public read) and point
`LOWA_BASE_URL` at the prefix (with trailing slash). **Verify locally first** with a
`file://` directory. Magic/shape checks fail the build on truncation or wrong
encoding. Chinese UI can only be confirmed on a real packaged build (LOWA can't boot
in CI/sandbox).
