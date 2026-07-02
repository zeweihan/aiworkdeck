# 自建 zh-CN LibreOffice WASM (LOWA) 引擎 / Self-built Chinese LOWA engine

> Issue #66。**已两次全程验证**（2026-06-26 阿里云杭州、2026-07-02 阿里云新加坡）：
> 真机中文 UI ✅、tooltip 中文 ✅。本目录是**实战验证过的构建系统**，不再是脚手架。
>
> Proven twice end-to-end. Native Chinese UI incl. Qt tooltips, verified in real Chrome.

## 为什么要自建 / Why

上游 `cdn.zetaoffice.net` 的 LOWA 是 `--with-lang=en-US` 单语编译，UI 语言**编译期烧死、
运行期任何注入都无效**（三方实测证伪，详见项目记忆 `lowa-build-and-localization`）。
且 stock emscripten 3.1.65 构建出的 soffice.js 不导出 `FS/callMain/specialHTMLTargets`
（gbuild 里被 QT6 门控），与我们的引导器不兼容——必须用 allotropia fork + 本目录补丁。

## 一条龙构建 / One-shot build

```bash
# 全新 Ubuntu 22.04 VM（≥32 核、≥64GB RAM、~40GB 盘；国际直连，境内到 GitHub 极慢）
scp mega-build.sh patches/apply-source-patches.py patches/fs-image-patch.py \
    patches/ZZZ-aiworkdeck-locale-zh-CN.xcd root@<vm>:/root/
ssh root@<vm> 'nohup bash /root/mega-build.sh > /root/mega.log 2>&1 &'
# 轮询：grep -E "PHASE_._DONE|MEGA_" /root/mega.log
# 实测 32C/123G 新加坡 VM：裸机 → 产物全程 40 分钟，零人工干预
# 产物：/root/out/soffice.{js,wasm,data} + soffice.data.js.metadata（+ .gz 便于传输）
```

`mega-build.sh` 编码了全部踩坑成果（阶段标记 `PHASE_1..7_DONE` / `MEGA_FAILED` 便于监控）：

1. apt 依赖 + gcc-12（LO 需 ≥12，Ubuntu 22.04 默认 11）+ **假 `systemd-detect-virt` 绕过
   root 构建检查**（LO 的检查只认容器，KVM 虚机会被拒）
2. **GitHub 镜像**：`git.libreoffice.org`/`gerrit` 在部分云区域不可达 → 全局
   `url.insteadOf` 重定向到 `github.com/LibreOffice/`（core + translations 子模块都吃到）
3. **fork emscripten 安装**（最容易错的一步）：emsdk 装 stock 3.1.65 取 llvm/binaryen/node
   → clone `allotropia/emscripten` `fixed-3.1.65` → 拷 stock 的 node_modules → 版本文件对齐
   `3.1.65` → 手建 `out/*.stamp`（future-dated）绕 bootstrap → 符号链接为 emsdk 活动前端
   → **必须 `export EM_CONFIG=<emsdk>/.emscripten`**（emsdk_env.sh 不导出它，emcc 会找不到
   LLVM_ROOT）→ `emcc --clear-cache` + hello-world 自检
4. qt5（allotropia `5.15.2+wasm`，qtbase）后台编译，与 translations 子模块/源码补丁并行
5. autogen（`--with-lang=en-US zh-CN`，见 `autogen.input`）+ 全量 `make -j`
6. **两阶段 zh-CN 焙入**：先全量 build（instdir 生成 zh-CN 资源但 fs_image 只打包 en-US
   硬编码清单）→ 打 `fs-image-patch.py` + 拷 ZZZ 进 instdir → 删 fs_image 产物重跑 make
   （`$(wildcard)` 解析期求值，instdir 必须已存在，所以必须两阶段）
7. 产物校验（FS 导出、metadata 含 zh_CN .mo×25 + ZZZ）+ sha256 + gzip

## 源码补丁 / Patches（`patches/`，apply-source-patches.py 幂等应用）

| 补丁 | 作用 |
|---|---|
| `EMSCRIPTEN_INTEL_GCC.mk` | **无条件**导出 `FS/callMain/specialHTMLTargets`（上游 QT6 门控，QT5 构建缺失 → 引导器崩） |
| `vcl/qt5/QtInstance.cxx` | **tooltip 中文修复**：`AfterAppInit()` 里 `QFontDatabase::addApplicationFont()` 注册运行期注入的 CJK 字体（`/instdir/share/fonts/truetype/AAA-CJK.ttc`），把其 family 追加为 QApplication + `QTipLabel` 字体回退。Qt 原生 QToolTip 绕过 VCL/fontconfig，不打此补丁 tooltip 中文全是方框 |
| `CustomTarget_emscripten_fs_image.mk` | 把 zh-CN（25 .mo + 4 registry xcd + ZZZ）打进 soffice.data |
| `ZZZ-aiworkdeck-locale-zh-CN.xcd` | 设默认 `ooLocale=zh-CN`（语言包只注册不设默认） |

完整增量记录见 `RECIPE.md`（含产物 sha256、工具链精确版本、每个坑的因果）。

## 消费产物 / Consume

```bash
# PR #68 的 LOWA_BASE_URL 机制（file:// 或 https://）：
cd frontend && npm run build:zetaoffice   # 注意会清空 dist/zetaoffice/lowa
cd ../desktop && LOWA_BASE_URL=file:///path/to/out/ node scripts/fetch-lowa-assets.js
# 验证（真 Chrome，headless 不渲染 canvas）：COOP/COEP 服务 dist/zetaoffice，
# 开 editor.html?verify=1&lowa=<同源>/lowa/ → 菜单/对话框中文 + 悬浮按钮 tooltip 中文
```

长期方案：产物传 OSS/静态服务器，CI 以 `LOWA_BASE_URL=https://...` 焙进安装包。

## 工具链版本（务必对齐，改版本≈重新踩坑）

| 组件 | 源 | 版本 |
|---|---|---|
| LibreOffice core | github.com/LibreOffice/core（镜像） | `distro/allotropia/zeta-24-2` |
| emscripten | github.com/allotropia/emscripten | `fixed-3.1.65`（**fork，非 stock**） |
| qt5 | github.com/allotropia/qt5 | `5.15.2+wasm` |
| gcc（宿主） | Ubuntu 22.04 apt | gcc-12 |
