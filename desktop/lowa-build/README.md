# 自建 zh-CN LibreOffice WASM (LOWA) 引擎 / Build a Chinese-enabled LOWA engine

> Issue #66 续。**为什么需要这个**：上游 `cdn.zetaoffice.net/zetaoffice_latest/` 的
> LOWA 是 **`--with-lang=en-US` 单语编译**（ZetaOffice 24 / LibreOffice 24.2.8.0）。
> UI 语言在 LibreOffice **编译期**就烧死了——**运行期任何注入都无效**（已三方实测证实：
> 注入新 `.xcd`、注入用户 `registrymodifications.xcu`、覆盖已有 `.xcd` / 焙进 `soffice.data`
> 全部失败，`ooLocale` 始终 `en-US`，详见记忆 `lowa-build-and-localization`）。
> 唯一出路＝**用 `--with-lang` 含 `zh-CN` 重新编译 LOWA**，自托管产物替换 CDN。

## ⚠️ 硬性前提（先确认再开工）

- **内存**：链接阶段 emscripten WASM finalize **可能需要 ~64 GB RAM**（LO 官方 README.wasm.md
  原话）。**普通笔记本和标准 CI runner（7 GB）跑不动。** 需要一台 ≥64 GB RAM、多核、~100 GB
  磁盘的构建机（自有大机或云上临时开一台高内存 VM 几小时）。
- **时间**：Qt5 + LibreOffice 全量构建 **数小时**（首次，含 ccache 冷启）。
- **专业度**：LO WASM 构建以"难"著称，本目录是**经研究的脚手架 + runbook**，**几乎一定需要在
  构建机上迭代调试**（沙箱无法编译/验证）。把它当强起点，不是一键保证。

## 工具链（务必版本对齐 allotropia ZetaOffice 24.2，否则 zetajs/UNO 接口对不上）

| 组件 | 源 | 分支/版本 |
|---|---|---|
| LibreOffice core | https://git.libreoffice.org/core | `distro/allotropia/zeta-24-2` |
| emscripten | https://github.com/allotropia/emscripten | `fixed-3.1.65` |
| qt5 (qtbase) | https://github.com/allotropia/qt5 | `5.15.2+wasm` |
| zetajs（参考，已 vendored 在 repo） | https://github.com/allotropia/zetajs | 对应 24.2 |

> 上游来源出处：allotropia zetajs README 声明 cdn.zetaoffice.net 的 WASM 即由上述分支构建。
> 通用构建文档：LibreOffice core `static/README.wasm.md`。

## 构建步骤（GUI/Qt5 构建，产 `soffice.{js,wasm,data}`）

见 `Dockerfile` + `build.sh`（封装下列步骤），核心：

1. **emscripten**：clone allotropia/emscripten，`./emsdk install/activate`（用 `fixed-3.1.65`
   对应的 SDK；该 fork 是 LO 24.2 WASM 必需的补丁版）。`source emsdk_env.sh`。
2. **Qt5**：clone allotropia/qt5 `5.15.2+wasm` → `init-repository --module-subset=qtbase` →
   `./configure -opensource -confirm-license -xplatform wasm-emscripten -feature-thread
   -prefix <QT5DIR> QMAKE_CFLAGS+=-sSUPPORT_LONGJMP=wasm QMAKE_CXXFLAGS+=-sSUPPORT_LONGJMP=wasm`
   → `make -j<N> module-qtbase && make -j<N> install`。
3. **LibreOffice**：clone core `distro/allotropia/zeta-24-2` → 放入本目录的 `autogen.input`
   （**关键：`--with-lang=en-US zh-CN`**，比上游多焙中文）→ `./autogen.sh`（已 patch 成走
   emconfigure）→ `make -j<N>`。
4. **产物**：`workdir/installation/LibreOffice/emscripten/` 下的
   `soffice.js` / `soffice.wasm` / `soffice.data`（+ `soffice.data.js.metadata`）。

### `--with-lang` 与默认 UI 语言

- `--with-lang="en-US zh-CN"` 让 `soffice.data` **原生包含** zh-CN 的 `.mo` + `Langpack-zh-CN.xcd`
  + `res/registry_zh-CN.xcd` 等（即 PR #67 我们手动焙的那批，这次由构建系统正确生成）。
- **默认显示中文**还需让 `ooLocale=zh-CN`。三选一，按可行性在构建机上验：
  1. **zh-CN 成为内建 InstalledLocale 后，运行期 `registrymodifications.xcu` 设 `ooLocale=zh-CN`
     可能就生效了**（之前失败疑因 zh-CN 未注册为合法 locale；现已内建，值合法）。
     最省事，**优先重试**（用 `desktop/.../zetaOfficeBoot.js` 里已写过的 user-profile 注入思路）。
  2. 留 `ooLocale` 为空 → LO 跟随系统/浏览器 locale（`navigator.language`）→ 中文环境自动中文。
  3. 实在不行＝在源码树 patch `officecfg` 里 `Setup/L10N/ooLocale` 默认值为 `zh-CN` 后再编译。
- **多语种/分语种**：一次 `--with-lang="en-US zh-CN ..."` 出多语种引擎，运行期切 `ooLocale`；
  或分别构建中文版/英文版引擎，桌面按产品语种焙不同 `soffice.data`。

## 集成进安装包

构建产物替换当前从 CDN 拉取的 LOWA：

- 现状：`desktop/scripts/fetch-lowa-assets.js` 从 `cdn.zetaoffice.net/zetaoffice_latest/` 下载
  `soffice.{js,wasm,data}` + metadata 焙进 `frontend/dist/zetaoffice/lowa/`。
- 改法：把自建的 4 个产物放到一个我们控制的位置（release asset / 对象存储 / 直接入库 LFS），
  把 `fetch-lowa-assets.js` 的 `LOWA_CDN` 指过去（或改成从本地 `desktop/lowa-build/out/` 拷贝）。
  其余焙入逻辑（br 编码 sidecar、字体）不变。
- **PR #67 的运行期 langpack 注入随之作废**：自建引擎原生含 zh-CN，不需要 `langpackUrl` 注入。
  vendored 的 `frontend/src/zetaoffice/public/lang/zh-CN/`（33 .mo + 5 .xcd）可作为构建对照/
  fallback 参考保留或删除（运行期注入已证无效）。

## 验证（构建机 + 真机）

1. 构建机本地起一个 COOP/COEP 静态服指向产物，浏览器开 `editor.html`，确认原生菜单中文。
   （可复用本 session 的 headless 诊断：`get_ui_lang` 读 `ooLocale`，应为 `zh-CN`。）
2. 焙进安装包 → 装 → 开 .docx → 原生菜单/工具栏/右键/对话框中文 + 文档中文 + AI + IME 正常。

## 不在本目录范围

实际编译（需 64 GB 机）。本目录只交付 runbook + 脚手架；编译与迭代在构建机进行。
