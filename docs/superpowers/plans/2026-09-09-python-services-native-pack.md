# 四个 Python 服务搬 native pack（dev-board#529）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 pptx / mineru / kokoro / asr 四个 Python 服务的 venv 与源码从安装包里摘出来，改由四个独立 native pack 按需下载，安装包减 ~630MB、装机磁盘再减一份解压树。

**Architecture:** 一服务一 pack（`<service 前缀>-runtime`），组件 `lib`（平台相关）+ `app`（平台无关，mineru 无）。`build-pack.js` 出包、`pack-release.yml` 双平台矩阵构建 + 汇总 merge manifest、`publish-pack.sh` 签名推两站镜像。桌面壳侧 `pysvc-runtime.js` 的 `pysvcPath()` 换成 `resolveServiceRoot()`（env 覆盖 → pack current 目录 → dev bundled），首启解压整段删除；四个服务 descriptor 的 `enabled` 一律先判 runtime pack 在场。后端 `NativePackService` 抬高解压上限、`PackAutoInstaller` 显式跳过这四个 pack，新增只读接口 `GET /api/packs/optional-components`，`PptxTools`/`PdfTools` 在服务不可达且 pack 未装时发 `component_required`。

**Tech Stack:** Node 20（desktop/scripts、desktop/tests，`node --test`）、Electron 30 主进程、Java 17 + Spring Boot（backend，JUnit 5 + Mockito）、GitHub Actions、bash。

**Spec:** `docs/superpowers/specs/2026-09-09-installer-slimming-design.md` §3 / §5；规范 `docs/NATIVE_PACK_DISTRIBUTION.md`（§2 manifest、§4 安装事务、§5 解析优先级、§7.2 v0.21.0 摘 litviz 清单）。

## Global Constraints

- pack id 固定四个：`pptx-runtime`、`mineru-runtime`、`kokoro-runtime`、`asr-runtime`。独立 semver 从 `1.0.0` 起。
- 四个 pack 的 `minAppVersion` = `0.38.0`；`engineApi` = `1`；`schema` = `1`。
- 组件命名固定：`lib`（`platforms: [mac-arm64]` / `[win-x64]`，`unpackDir: "lib"`）、`app`（`platforms: ["*"]`，`unpackDir: "app"`）。**mineru-runtime 没有 app 组件**（`prepare-python-service.js` 不带 `--src`，不产 `app/`）。
- 压缩格式沿用 tar.gz；不引 zstd、不引新 Java 依赖。
- 包内条目是裸相对路径，不套顶层目录；`contents.sha256` 必须在压缩包根（规范 §2，dev-board#65）。
- `NativePackService` 单包解压上限抬到 **150000 条目 / 2.5GB**，且必须可配置（否则单测要造 15 万个 tar 条目）。
- 模型仍走 `model-manager.js`（`mineru-models` 3GB / `kokoro-models` 300MB / `asr-models` 1.5GB），**不并入 pack**。
- CPython 运行时（`Resources/python`）继续随包，四个服务与 litviz 共用。
- `PackAutoInstaller` **绝不**对这四个 pack 自动补下（用户要求提示后再下）；`PackUpdater` 的 24h 追新照常生效（没有 skill 声明它们 = 按启用处理）。
- 本项全部落 **v0.38.0 大版本**：改了 `desktop/main`、`desktop/package.json`、`.github/workflows`，`patch-gate.sh` 会拦小版本。
- 发布顺序硬约束：四个 pack 必须**先于** 0.38.0 安装包发布到两站镜像并 `verify` 通过，否则新装用户无处可下。
- mac 侧不需要为 pack 里的 `.so` 做公证：`desktop/build/entitlements.mac.plist` 已带 `com.apple.security.cs.disable-library-validation`，且 Java HTTP 下载不写 quarantine xattr（规范 §2）。
- 跨任务共享的接口契约（#530 计划逐字相同）：
  - SSE `client_action` 的 `component_required` payload：
    `{"action":"component_required","packId":string,"service":string,"modelId":string|null,"sizeMb":number,"features":string[],"trigger":string}`
  - `GET /api/packs/optional-components` 响应见 Task 8。
  - `LocalAsrClient.Status` 四态：`RUNTIME_MISSING` / `SERVICE_DOWN` / `MODEL_MISSING` / `READY`。
  - `host.services.ensure(name)` 的服务名：`pptx-service` / `mineru-service` / `kokoro-service` / `asr-service`。

## 文件结构

| 文件 | 职责 |
|---|---|
| `desktop/scripts/build-pack.js` | 新增四个 runtime pack 的 `lib` / `app` 组件构建器、per-id `minAppVersion`、组件级 `unpackedSize` |
| `.github/workflows/pack-release.yml` | 新增 `pack_id` 输入与 runtime 矩阵腿（mac-arm64 / win-x64），含从 pack 布局起服务的冒烟 |
| `desktop/main/services/pysvc-runtime.js` | 只剩 `resolveServiceRoot()` / `libDirFor()` / `appDirFor()` / `extractTar()`；删首启解压与 pysvc-src 补丁 |
| `desktop/main/main.js` | 删 `resolvePysvcRoot` / `ensurePysvcReady` / splash 解压文案 / `syncSrcPatch` 调用 |
| `desktop/main/services/{pptx,mineru,kokoro,asr}-service.js` | 路径改走 `resolveServiceRoot`；`enabled` 先判 pack 在场 |
| `desktop/main/services/model-manager.js` | 模型下载子进程的 `PYTHONPATH` 改走 pack；runtime 缺失时快速失败 |
| `backend/.../service/pack/PackProperties.java` | 新增 `maxArchiveEntries` / `maxUnpackedBytes` |
| `backend/.../service/pack/NativePackService.java` | 上限走配置；`Component.unpackedSize`；装完落 `sizes.json`；`knownSizes()` |
| `backend/.../service/pack/OptionalComponents.java`（新） | 四个可选组件的唯一注册表（packId / service / modelId / modelBytes / featureKeys） |
| `backend/.../service/pack/ModelPresence.java`（新） | 只读判定「模型是否已下载」（`~/.aiworkdeck/models/<name>/.aiworkdeck-complete`） |
| `backend/.../service/pack/PackAutoInstaller.java` | 显式跳过四个 runtime pack |
| `backend/.../controller/PackController.java` | 新增 `GET /api/packs/optional-components` |
| `backend/.../service/ai/EditorBridgeService.java` | 新增 `sendComponentRequiredAction(...)` |
| `backend/.../service/ai/tools/{PptxTools,PdfTools}.java` | 服务不可达且 pack 未装 → 发 `component_required` |
| `backend/.../service/meeting/LocalAsrClient.java` | 探测四态 |
| `.github/workflows/desktop-build.yml`、`desktop/package.json`、`desktop/scripts/build-patch-assets.js`、`desktop/scripts/patch-gate.sh` | 摘除 pysvc 与 pysvc-src |

---

### Task 1: build-pack.js 支持四个 runtime pack 的 lib / app 组件

**Files:**
- Modify: `desktop/scripts/build-pack.js:32-33`（`MIN_APP_VERSION`）、`:126-153`（`packComponent`）、`:207`（`COMPONENT_BUILDERS`）、`:264-272`（manifest 组装）
- Test: `desktop/tests/build-pack.test.js`

**Interfaces:**
- Consumes: 无（本任务是链条起点）。
- Produces:
  - `RUNTIME_PACKS: Record<packId, {service: string, hasApp: boolean}>`，导出供测试断言。
  - `minAppVersionFor(id: string) => string`。
  - `packComponent(ctx, opts)` 返回值新增 `unpackedSize: number`（解压后字节数）。
  - CLI：`node desktop/scripts/build-pack.js --id <packId> --version <v> --out <dir> --components lib[,app]`。
  - 归档命名：`<packId>-lib-<version>-<plat>.tar.gz`、`<packId>-app-<version>.tar.gz`。

- [ ] **Step 1: 写失败测试**

在 `desktop/tests/build-pack.test.js` 末尾追加：

```js
const { RUNTIME_PACKS, minAppVersionFor } = require('../scripts/build-pack')

test('四个 runtime pack 的注册表与 minAppVersion 钉死（mineru 无 app 组件）', () => {
  assert.deepStrictEqual(Object.keys(RUNTIME_PACKS).sort(), [
    'asr-runtime', 'kokoro-runtime', 'mineru-runtime', 'pptx-runtime'
  ])
  assert.strictEqual(RUNTIME_PACKS['pptx-runtime'].service, 'pptx-service')
  assert.strictEqual(RUNTIME_PACKS['mineru-runtime'].hasApp, false,
    'mineru 是纯 pip 包服务（prepare-python-service.js 不带 --src），没有 app/')
  assert.strictEqual(RUNTIME_PACKS['kokoro-runtime'].hasApp, true)
  for (const id of Object.keys(RUNTIME_PACKS)) {
    assert.strictEqual(minAppVersionFor(id), '0.38.0', id + ' 只对 0.38.0 及以上的宿主开放')
  }
  assert.strictEqual(minAppVersionFor('litigation-visual'), '0.21.0', '老 pack 的下限不许被改动波及')
})

test('packComponent 报出解压后体积（optional-components 面板要显示「解压多大」）', (t) => {
  const workRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'build-pack-size-'))
  t.after(() => fs.rmSync(workRoot, { recursive: true, force: true }))
  const srcDir = path.join(workRoot, 'lib')
  fs.mkdirSync(srcDir, { recursive: true })
  fs.writeFileSync(path.join(srcDir, 'a.bin'), Buffer.alloc(4096, 1))
  fs.writeFileSync(path.join(srcDir, 'b.bin'), Buffer.alloc(2048, 2))
  const outDir = path.join(workRoot, 'out')
  fs.mkdirSync(outDir, { recursive: true })

  const comp = packComponent(
    { id: 'asr-runtime', version: '1.0.0', outDir },
    { name: 'lib', srcDir, exclude: [], archive: 'lib.tar.gz', platforms: ['mac-arm64'], unpackDir: 'lib' }
  )

  assert.strictEqual(comp.unpackDir, 'lib')
  assert.strictEqual(comp.unpackedSize, 4096 + 2048)
  assert.ok(comp.size > 0 && comp.size < comp.unpackedSize, '压缩包应当比解压后小')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/build-pack.test.js`
Expected: FAIL —「RUNTIME_PACKS is not defined」/「minAppVersionFor is not a function」。

- [ ] **Step 3: 最小实现**

`desktop/scripts/build-pack.js:32-33` 之后插入：

```js
const MIN_APP_VERSION = '0.21.0'
const ENGINE_API = 1

// 四个 Python 服务运行时 pack（设计 §3.1）。一服务一 pack：
//   lib  = prepare-python-service.js 产出的 pysvc/<service>/lib（已 prune），平台相关（wheel 里有 .so/.pyd）
//   app  = pysvc/<service>/app（服务源码），平台无关
// mineru 是纯 pip 包服务（desktop-build.yml 调它时不带 --src），没有 app/。
const RUNTIME_PACKS = {
  'pptx-runtime': { service: 'pptx-service', hasApp: true },
  'mineru-runtime': { service: 'mineru-service', hasApp: false },
  'kokoro-runtime': { service: 'kokoro-service', hasApp: true },
  'asr-runtime': { service: 'asr-service', hasApp: true },
}

// minAppVersion 按 pack 分：litigation-visual 是 v0.21.0 的老约定，四个 runtime pack
// 依赖 0.38.0 才有的 resolveServiceRoot（更早的壳只会去 Resources/pysvc.tar.gz 找）。
const MIN_APP_VERSION_BY_ID = {
  'pptx-runtime': '0.38.0',
  'mineru-runtime': '0.38.0',
  'kokoro-runtime': '0.38.0',
  'asr-runtime': '0.38.0',
}

function minAppVersionFor(id) {
  return MIN_APP_VERSION_BY_ID[id] || MIN_APP_VERSION
}
```

`packComponent`（`:133-148`）改成边算哈希边累加体积：

```js
  const files = listFiles(srcDir, exclude)
  let unpackedSize = 0
  const lines = files.map((rel) => {
    const abs = path.join(srcDir, rel)
    unpackedSize += fs.statSync(abs).size
    return `${sha256File(abs)}  ${rel}`
  })
  fs.writeFileSync(contentsPath, lines.join('\n') + '\n')
  try {
    const archivePath = path.join(ctx.outDir, archive)
    console.log(`打包 ${name} -> ${archivePath}（${files.length} 个文件，解压后 ${(unpackedSize / 1024 / 1024).toFixed(1)} MB）`)
    tarPack(srcDir, [...files, contentsRel], archivePath)
    const st = fs.statSync(archivePath)
    return {
      name,
      platforms,
      archive,
      size: st.size,
      // 解压后字节数：面板要同时告诉用户「下载多大」与「占盘多大」（设计 §4.3）。
      // 老 manifest 没有这个字段，Java 侧按 0 = 未知处理。
      unpackedSize,
      sha256: sha256File(archivePath),
      unpackDir: topName,
    }
  } finally {
    fs.rmSync(contentsPath, { force: true })
  }
```

`buildGraphviz` 之后追加两个构建器与注册：

```js
function runtimeSpec(ctx) {
  const spec = RUNTIME_PACKS[ctx.id]
  if (!spec) {
    throw new Error(
      `--components lib/app 只用于运行时 pack（${Object.keys(RUNTIME_PACKS).join(' / ')}），当前 --id=${ctx.id}`
    )
  }
  return spec
}

function runtimeSrcDir(ctx, sub) {
  const plat = platformTag()
  const { service } = runtimeSpec(ctx)
  const srcDir = path.join(DESKTOP_ROOT, 'bundled', plat, 'pysvc', service, sub)
  if (!fs.existsSync(srcDir)) {
    throw new Error(
      `${service} 的 ${sub}/ 未就位（${srcDir}）。先跑：node desktop/scripts/prepare-python-service.js ` +
      `--service ${service} --requirements ${service}/requirements.lock --out desktop/bundled/${plat}`
    )
  }
  return srcDir
}

const PY_EXCLUDE = [
  (relPath, name, st) => st.isDirectory() && name === '__pycache__',
  (relPath, name, st) => st.isFile() && name.endsWith('.pyc'),
]

function buildRuntimeLib(ctx) {
  const plat = platformTag()
  return packComponent(ctx, {
    name: 'lib',
    srcDir: runtimeSrcDir(ctx, 'lib'),
    exclude: PY_EXCLUDE,
    archive: `${ctx.id}-lib-${ctx.version}-${plat}.tar.gz`,
    platforms: [plat],
    unpackDir: 'lib',
  })
}

function buildRuntimeApp(ctx) {
  if (!runtimeSpec(ctx).hasApp) {
    throw new Error(`${ctx.id} 没有 app 组件（该服务是纯 pip 包，无源码目录），不要传 --components app`)
  }
  return packComponent(ctx, {
    name: 'app',
    srcDir: runtimeSrcDir(ctx, 'app'),
    exclude: PY_EXCLUDE,
    archive: `${ctx.id}-app-${ctx.version}.tar.gz`,
    platforms: ['*'],
    unpackDir: 'app',
  })
}

const COMPONENT_BUILDERS = {
  litviz: buildLitviz,
  drawio: buildDrawio,
  graphviz: buildGraphviz,
  lib: buildRuntimeLib,
  app: buildRuntimeApp,
}
```

manifest 组装处（`:269`）把 `minAppVersion: MIN_APP_VERSION` 换成 `minAppVersion: minAppVersionFor(ctx.id)`；文件末尾导出补上：

```js
module.exports = {
  packComponent, sha256File, listFiles, mergeManifest, componentKey, platformTag,
  RUNTIME_PACKS, minAppVersionFor,
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && node --test tests/build-pack.test.js`
Expected: PASS（含原有「包内无顶层前缀」「源目录不留 contents.sha256」用例）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add desktop/scripts/build-pack.js desktop/tests/build-pack.test.js
git commit -m "feat(pack): build-pack 支持四个 Python 运行时 pack 的 lib/app 组件"
```

---

### Task 1b: mineru-runtime 去 gradio（规格 §3.3；主会话审计划时补入）

**Files:**
- Modify: `desktop/scripts/prepare-python-service.js`（`prune(libDir, service)` 增加按服务的裁剪表）
- Modify: `desktop/tests/prepare-python-service.test.js`

**Interfaces:**
- Consumes: Task 1 的 mineru-runtime 组件构建（`lib` 来自 `prepare-python-service.js` 产出）。
- Produces: `PRUNE_BY_SERVICE = { 'mineru-service': ['gradio', 'gradio_client', 'gradio_pdf'] }`，只对 mineru 生效；`prune()` 第二个参数 `service` 为空时不走该表（保持 P1 行为）。

背景：mineru 的 `requirements.in` 是 `mineru[core]`，把 Gradio Web UI（P1 prune 完 `*.js.map` 后仍约 80MB 未压缩）拖进 lock；服务只跑 `-m mineru.cli.fast_api`，Web UI 从不启动。P1 已删 sourcemap，这里把整个包拿掉。

- [ ] **Step 1: 先证明 fast_api 路径不 import gradio**

在一份已安装的 mineru lib（本机 `~/Library/Application Support/aiworkdeck-desktop/pysvc-0.37.0/pysvc/mineru-service/lib` 或 CI 产物）上跑：

```bash
PYTHONPATH=<lib> python3 -c "import sys; import mineru.cli.fast_api; print([m for m in sys.modules if m.startswith('gradio')])"
```

Expected: 打印 `[]`。若非空，**停下汇报**：说明 fast_api 路径有 gradio 依赖，这一任务改为「不做」，规格 §3.3 该条作废并在卡上说明。

- [ ] **Step 2: 写失败测试**

`desktop/tests/prepare-python-service.test.js` 追加：

```js
test('prune(libDir, "mineru-service") 删掉 gradio 三件套；其它服务不动 gradio', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prune-gradio-'))
  for (const svc of ['mineru-service', 'pptx-service']) {
    const lib = path.join(root, svc)
    for (const d of ['gradio', 'gradio_client', 'gradio_pdf', 'mineru']) {
      fs.mkdirSync(path.join(lib, d), { recursive: true })
      fs.writeFileSync(path.join(lib, d, '__init__.py'), '')
    }
    prune(lib, svc)
  }
  for (const d of ['gradio', 'gradio_client', 'gradio_pdf']) {
    assert.ok(!fs.existsSync(path.join(root, 'mineru-service', d)), `mineru 仍有 ${d}`)
    assert.ok(fs.existsSync(path.join(root, 'pptx-service', d)), `pptx 的 ${d} 不该被动`)
  }
  assert.ok(fs.existsSync(path.join(root, 'mineru-service', 'mineru')))
})
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd desktop && node --test tests/prepare-python-service.test.js`
Expected: FAIL —— mineru-service 下 `gradio` 仍在。

- [ ] **Step 4: 最小实现**

`prepare-python-service.js`：

```js
// 按服务的裁剪表（Task 1b）：mineru 的 requirements.in 是 mineru[core]，把 Gradio Web UI
// 一并拖进来；服务只跑 -m mineru.cli.fast_api，Web UI 从不启动（Step 1 已证明 fast_api
// 不 import gradio）。只对 mineru 生效，别的服务不碰。
const PRUNE_BY_SERVICE = {
  'mineru-service': ['gradio', 'gradio_client', 'gradio_pdf'],
}

function prune(libDir, service) {
  // ...原有逻辑不变...
  for (const rel of (PRUNE_BY_SERVICE[service] || [])) rmIfExists(path.join(libDir, rel))
}
```

`main()` 里调用处传 `prune(libDir, serviceName)`（现有变量名以文件为准）。`.dist-info` 照旧不动。

- [ ] **Step 5: 跑测试确认通过；真起服务冒烟**

Run: `cd desktop && node --test tests/prepare-python-service.test.js`；再在 Step 1 那份 lib 上手工删掉三个目录后 `PYTHONPATH=<lib> python3 -m mineru.cli.fast_api --help`（或按 `mineru-service.js` 的启动参数起服务打 `/docs`）。
Expected: PASS；服务起得来。

- [ ] **Step 6: 提交（由主会话执行）**

```bash
git add desktop/scripts/prepare-python-service.js desktop/tests/prepare-python-service.test.js
git commit -m "build(pysvc): mineru-runtime 裁掉 gradio 三件套"
```

---

### Task 2: NativePackService 解压上限抬到 150000 条目 / 2.5GB（可配置）

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/pack/PackProperties.java:53-64`
- Modify: `backend/src/main/java/com/checkba/service/pack/NativePackService.java:93-94`（常量）、`:213-215`（`Component` record）、`:833-841`（解析）、`:895-945`（`extract`）、`:340-348`（`info`）
- Modify: `backend/src/main/java/com/checkba/controller/PackController.java:91-99`
- Test: `backend/src/test/java/com/checkba/service/pack/NativePackServiceTest.java:254-263`

**Interfaces:**
- Consumes: Task 1 产出的 manifest 里的 `components[].unpackedSize`。
- Produces:
  - `PackProperties.getMaxArchiveEntries(): int`（默认 150000）、`getMaxUnpackedBytes(): long`（默认 `2_684_354_560L` = 2.5GB）。
  - `NativePackService.Component(name, platforms, archive, size, sha256, unpackDir, urls, unpackedSize)`。
  - `NativePackService.PackInfo(String latestVersion, long totalSize, long unpackedSize)`。

- [ ] **Step 1: 写失败测试**

`NativePackServiceTest` 里把 `rejectsTooManyEntries` 改掉并补两条（`props(...)` 已是测试内构造 `PackProperties` 的辅助方法）：

```java
    @Test
    @DisplayName("默认上限是 150000 条目 / 2.5GB —— 一个 torch venv 就超旧上限（5000/500MB）")
    void defaultLimitsFitAPythonVenv() {
        PackProperties defaults = new PackProperties();
        assertEquals(150_000, defaults.getMaxArchiveEntries());
        assertEquals(2_684_354_560L, defaults.getMaxUnpackedBytes());
    }

    @Test
    @DisplayName("解压仍然拒绝超过配置条目数的压缩包（用小上限跑，不造 15 万个条目）")
    void rejectsTooManyEntries() throws Exception {
        byte[] bad = tarGz(tar -> {
            for (int i = 0; i <= 11; i++) {
                tar.file("f" + i, new byte[]{1});
            }
        });
        PackProperties p = props("https://example.invalid/plugin-packs");
        p.setMaxArchiveEntries(10);
        NativePackService svc = new TestPackService(p, publicKeyPem, "0.21.0");
        Path file = tempDir.resolve("too-many.tar.gz");
        Files.write(file, bad);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.extract(file, tempDir.resolve("out-entries")));
        assertTrue(e.getMessage().contains("条目数"), e.getMessage());
    }

    @Test
    @DisplayName("解压仍然拒绝超过配置总体积的压缩包，且错误里报的是配置值不是写死的 500 MB")
    void rejectsTooLargeUnpacked() throws Exception {
        byte[] bad = tarGz(tar -> tar.file("big.bin", new byte[4096]));
        PackProperties p = props("https://example.invalid/plugin-packs");
        p.setMaxUnpackedBytes(1024);
        NativePackService svc = new TestPackService(p, publicKeyPem, "0.21.0");
        Path file = tempDir.resolve("too-big.tar.gz");
        Files.write(file, bad);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.extract(file, tempDir.resolve("out-bytes")));
        assertTrue(e.getMessage().contains("1024"), "错误文案要报真实上限：" + e.getMessage());
    }

    @Test
    @DisplayName("manifest 的 unpackedSize 被解析出来（老 manifest 没有这个字段则为 0）")
    void parsesUnpackedSize() {
        byte[] raw = ("{\"schema\":1,\"id\":\"p\",\"version\":\"1.0.0\",\"minAppVersion\":\"0.1.0\",\"engineApi\":1,"
                + "\"components\":[{\"name\":\"lib\",\"platforms\":[\"*\"],\"archive\":\"a.tar.gz\",\"size\":10,"
                + "\"sha256\":\"x\",\"unpackDir\":\"lib\",\"unpackedSize\":4096},"
                + "{\"name\":\"app\",\"platforms\":[\"*\"],\"archive\":\"b.tar.gz\",\"size\":5,"
                + "\"sha256\":\"y\",\"unpackDir\":\"app\"}]}").getBytes(StandardCharsets.UTF_8);
        NativePackService.Manifest m = NativePackService.parseManifest(raw);
        assertEquals(4096L, m.components().get(0).unpackedSize());
        assertEquals(0L, m.components().get(1).unpackedSize(), "老 manifest 无此字段 = 未知，按 0");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn -B -Dtest=NativePackServiceTest test`
Expected: FAIL — 编译错（`getMaxArchiveEntries` / `setMaxUnpackedBytes` / `unpackedSize()` 不存在）。

- [ ] **Step 3: 最小实现**

`PackProperties.java` 在 `revokedUrl` 之后加：

```java
    /**
     * 单个压缩包的条目数上限。5000 是 litviz/drawio 时代的值——一个带 torch 的 Python venv
     * 轻松上万文件，四个运行时 pack 会当场被拦下。抬到 150000 的安全前提是 manifest 有
     * Ed25519 签名：能走到解压这一步的字节只可能来自我们自己的构建（规范 §2/§10）。
     */
    private int maxArchiveEntries = 150_000;

    /** 单个压缩包解压后的总体积上限（字节）。默认 2.5 GB：mineru 的 lib 解压后 1.2 GB 量级。 */
    private long maxUnpackedBytes = 2_684_354_560L;

    public int getMaxArchiveEntries() { return maxArchiveEntries; }
    public void setMaxArchiveEntries(int maxArchiveEntries) { this.maxArchiveEntries = maxArchiveEntries; }
    public long getMaxUnpackedBytes() { return maxUnpackedBytes; }
    public void setMaxUnpackedBytes(long maxUnpackedBytes) { this.maxUnpackedBytes = maxUnpackedBytes; }
```

`NativePackService.java`：删掉 `:93-94` 两个常量，`extract` 里改成读配置并把真实上限写进文案：

```java
                    if (++entries > props.getMaxArchiveEntries()) {
                        throw new IllegalStateException(LangText.of(
                                "压缩包条目数超过上限（" + props.getMaxArchiveEntries() + "）",
                                "Archive exceeds the entry limit (" + props.getMaxArchiveEntries() + ")"));
                    }
```

```java
                    unpacked += Math.max(e.getSize(), 0);
                    if (unpacked > props.getMaxUnpackedBytes()) {
                        throw new IllegalStateException(LangText.of(
                                "解压后体积超过上限（" + props.getMaxUnpackedBytes() + " 字节）",
                                "Unpacked size exceeds the limit (" + props.getMaxUnpackedBytes() + " bytes)"));
                    }
```

`Component` record 与解析：

```java
    public record Component(String name, List<String> platforms, String archive,
                            long size, String sha256, String unpackDir, List<String> urls,
                            long unpackedSize) {}
```

```java
                components.add(new Component(
                        c.getStr("name"),
                        strList(c.getJSONArray("platforms")),
                        c.getStr("archive"),
                        c.getLong("size", 0L),
                        c.getStr("sha256"),
                        c.getStr("unpackDir"),
                        strList(c.getJSONArray("urls")),
                        c.getLong("unpackedSize", 0L)));
```

`totalUnpacked` 辅助 + `info` 与 `PackInfo`：

```java
    private static long totalUnpacked(List<Component> components) {
        long total = 0;
        for (Component c : components) total += Math.max(c.unpackedSize(), 0);
        return total;
    }
```

```java
    public record PackInfo(String latestVersion, long totalSize, long unpackedSize) {}
```

```java
    public PackInfo info(String packId) {
        requireValidId(packId);
        Manifest m = cachedManifest(packId);
        List<Component> cs = componentsForPlatform(m);
        return new PackInfo(m.version(), totalSize(cs), totalUnpacked(cs));
    }
```

`PackController.info`（`:94-95`）补一行：

```java
            result.put("latestVersion", info.latestVersion());
            result.put("totalSize", info.totalSize());
            result.put("unpackedSize", info.unpackedSize());
```

`application.yml` 的 `ai.packs` 段不用改（默认值在类里）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn -B -Dtest=NativePackServiceTest test`
Expected: PASS（38 个用例全绿，包括原有的 zip-slip / 软链 / 断点续传各条）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add backend/src/main/java/com/checkba/service/pack/PackProperties.java \
        backend/src/main/java/com/checkba/service/pack/NativePackService.java \
        backend/src/main/java/com/checkba/controller/PackController.java \
        backend/src/test/java/com/checkba/service/pack/NativePackServiceTest.java
git commit -m "feat(pack): 解压上限抬到 150000 条目/2.5GB 并可配置，manifest 带 unpackedSize"
```

---

### Task 3: pack-release.yml 增加四个 runtime pack 的双平台矩阵与冒烟

**Files:**
- Modify: `.github/workflows/pack-release.yml:11-23`（触发与 env）、`:105-129`（release job 的 needs 与下载）
- Modify: `desktop/tests/workflow-release-gating.test.js`

**Interfaces:**
- Consumes: Task 1 的 CLI（`--id <packId> --components lib[,app]`）。
- Produces: GitHub Release，tag `pack-<packId>-v<version>`，`prerelease: true`，资产 = `manifest.json` + 全部 `*.tar.gz`（**不含 `.sig`**，签名只在官网机）。

- [ ] **Step 1: 写失败测试**

`desktop/tests/workflow-release-gating.test.js` 末尾追加：

```js
const PACK_RELEASE = fs.readFileSync(path.join(WORKFLOW_DIR, 'pack-release.yml'), 'utf8')

test('pack-release.yml：pack_id 是显式枚举，四个 runtime pack 都在其中', () => {
  for (const id of ['litigation-visual', 'pptx-runtime', 'mineru-runtime', 'kokoro-runtime', 'asr-runtime']) {
    assert.ok(PACK_RELEASE.includes(id), `pack_id 选项缺 ${id}`)
  }
})

test('pack-release.yml：app 组件只在 mac 腿产一次（两台机各产一份同名 tar.gz 会让 sha256 对不上）', () => {
  assert.match(PACK_RELEASE, /COMPONENTS=lib,app/)
  assert.match(PACK_RELEASE, /matrix\.plat[^\n]*mac-arm64/)
})

test('pack-release.yml：release 必须标 prerelease（否则顶掉仓库级 releases/latest，污染镜像同步）', () => {
  assert.match(PACK_RELEASE, /prerelease:\s*true/)
})

test('pack-release.yml：runtime 腿必须真起一次服务打 /health，不能只打包不验', () => {
  assert.match(PACK_RELEASE, /Smoke test from pack layout/)
  assert.match(PACK_RELEASE, /\/health|\/docs/)
})

test('pack-release.yml：不缓存 pysvc（pack 产物必须每次从 requirements.lock 真装一遍）', () => {
  assert.doesNotMatch(PACK_RELEASE, /actions\/cache@[^\n]*\n[\s\S]{0,400}?pysvc/)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/workflow-release-gating.test.js`
Expected: FAIL —「pack_id 选项缺 pptx-runtime」。

- [ ] **Step 3: 最小实现**

`.github/workflows/pack-release.yml`：`on.workflow_dispatch.inputs` 换成：

```yaml
on:
  workflow_dispatch:
    inputs:
      pack_id:
        description: '要构建的 pack'
        required: true
        type: choice
        default: litigation-visual
        options:
          - litigation-visual
          - pptx-runtime
          - mineru-runtime
          - kokoro-runtime
          - asr-runtime
      version:
        description: 'pack 版本号（语义化版本，如 1.0.0；与应用版本号 desktop/package.json 无关）'
        required: true
        type: string

permissions:
  contents: write
```

删掉顶层 `env: PACK_ID: litigation-visual`，把现有 `mac` / `win` 两个 job 的 `PACK_ID` 引用全部换成 `${{ inputs.pack_id }}`，并各加一行门控：

```yaml
  mac:
    name: Build pack components (macOS)
    if: inputs.pack_id == 'litigation-visual'
    runs-on: macos-latest
```

```yaml
  win:
    name: Build pack components (Windows)
    if: inputs.pack_id == 'litigation-visual'
    runs-on: windows-latest
```

在 `win` job 之后插入 runtime 矩阵 job：

```yaml
  # 四个 Python 服务运行时 pack（设计 §3.3）。两条腿各自跑一遍
  # prepare-python-service（真装 pip 依赖）→ build-pack → 从 pack 布局起一次服务。
  # 刻意不加 actions/cache：pack 是要发给用户的字节，宁可多花 2 分钟也不冒
  # 「缓存命中发出旧依赖」的险（desktop-build.yml 上曾出过 dev-board#74）。
  runtime:
    name: Build runtime pack (${{ matrix.plat }})
    if: inputs.pack_id != 'litigation-visual'
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: macos-latest
            plat: mac-arm64
          - os: windows-latest
            plat: win-x64
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - name: Resolve service name
        id: svc
        shell: bash
        run: echo "name=${{ inputs.pack_id }}" | sed 's/-runtime$/-service/' >> "$GITHUB_OUTPUT"
      - name: Bundle python service
        shell: bash
        run: |
          set -euo pipefail
          SVC="${{ steps.svc.outputs.name }}"
          # mineru 是纯 pip 包服务，没有 --src（与 desktop-build.yml 现有四步逐字同源）
          SRC_ARG=""
          case "$SVC" in
            pptx-service)   SRC_ARG="--src pptx-service/backend" ;;
            kokoro-service) SRC_ARG="--src kokoro-service" ;;
            asr-service)    SRC_ARG="--src asr-service" ;;
            mineru-service) SRC_ARG="" ;;
          esac
          node desktop/scripts/prepare-python-service.js \
            --service "$SVC" $SRC_ARG \
            --requirements "$SVC/requirements.lock" \
            --out "desktop/bundled/${{ matrix.plat }}"
          du -sh "desktop/bundled/${{ matrix.plat }}/pysvc/$SVC/lib"
      - name: Build pack components
        shell: bash
        run: |
          set -euo pipefail
          COMPONENTS=lib
          # app 组件平台无关，只在 mac 腿产一次：两台机各产一份同名 tar.gz，
          # 汇总 job 的 cp 会用后到的覆盖先到的，而 manifest 里记的是先到那份的 sha256。
          if [ "${{ matrix.plat }}" = "mac-arm64" ] && [ "${{ inputs.pack_id }}" != "mineru-runtime" ]; then
            COMPONENTS=lib,app
          fi
          node desktop/scripts/build-pack.js \
            --id "${{ inputs.pack_id }}" \
            --version "${{ inputs.version }}" \
            --out "pack-out/${{ matrix.plat }}" \
            --components "$COMPONENTS"
          ls -la "pack-out/${{ matrix.plat }}"
      - name: Smoke test from pack layout
        shell: bash
        run: |
          set -euo pipefail
          ROOT="$RUNNER_TEMP/packroot"
          rm -rf "$ROOT"; mkdir -p "$ROOT"
          # 按桌面端 resolveServiceRoot 的落盘布局解开：<root>/lib 与 <root>/app
          for f in pack-out/${{ matrix.plat }}/*-lib-*.tar.gz; do mkdir -p "$ROOT/lib"; tar -xzf "$f" -C "$ROOT/lib"; done
          for f in pack-out/${{ matrix.plat }}/*-app-*.tar.gz; do mkdir -p "$ROOT/app"; tar -xzf "$f" -C "$ROOT/app"; done
          if [ "${{ matrix.plat }}" = "mac-arm64" ]; then
            PY="$PWD/desktop/bundled/mac-arm64/python/bin/python3.11"
          else
            PY="$PWD/desktop/bundled/win-x64/python/python.exe"
          fi
          export PYTHONPATH="$ROOT/lib"
          case "${{ inputs.pack_id }}" in
            pptx-runtime)
              export PPTX_DATA_DIR="$RUNNER_TEMP/pptx-data"; mkdir -p "$PPTX_DATA_DIR"
              (cd "$ROOT/app" && "$PY" -m alembic -c alembic.ini upgrade head)
              PORT=5099 BACKEND_PORT=5099 FLASK_ENV=production "$PY" "$ROOT/app/app.py" > "$RUNNER_TEMP/smoke.log" 2>&1 &
              URL=http://127.0.0.1:5099/health ;;
            mineru-runtime)
              MD="$RUNNER_TEMP/mineru-data"; mkdir -p "$MD"
              MINERU_DEVICE_MODE=cpu MINERU_MODEL_SOURCE=modelscope MODELSCOPE_CACHE="$MD" \
                HF_HOME="$MD/hf" MINERU_TOOLS_CONFIG_JSON="$MD/mineru.json" \
                "$PY" -m mineru.cli.fast_api --host 127.0.0.1 --port 8098 > "$RUNNER_TEMP/smoke.log" 2>&1 &
              URL=http://127.0.0.1:8098/docs ;;
            kokoro-runtime)
              PORT=8881 "$PY" "$ROOT/app/app.py" > "$RUNNER_TEMP/smoke.log" 2>&1 &
              URL=http://127.0.0.1:8881/health ;;
            asr-runtime)
              export HF_HOME="$RUNNER_TEMP/asr-empty-home"
              PORT=8891 "$PY" "$ROOT/app/app.py" > "$RUNNER_TEMP/smoke.log" 2>&1 &
              URL=http://127.0.0.1:8891/health ;;
          esac
          PID=$!
          for i in $(seq 1 45); do
            sleep 2
            if curl -sf -o /dev/null "$URL"; then echo "up after ~$((i*2))s"; kill $PID; exit 0; fi
          done
          echo "service failed to start within 90s"; tail -80 "$RUNNER_TEMP/smoke.log"; kill $PID 2>/dev/null || true; exit 1
      - uses: actions/upload-artifact@v4
        with:
          name: pack-runtime-${{ matrix.plat }}
          path: pack-out/${{ matrix.plat }}/*
          if-no-files-found: error
```

`release` job 改成兼容两条路径：

```yaml
  release:
    name: Merge manifest and publish GitHub Release
    needs: [mac, win, runtime]
    if: ${{ !cancelled() && !contains(needs.*.result, 'failure') }}
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - uses: actions/download-artifact@v4
        with:
          pattern: pack-*
          path: pack-in
      - name: Merge manifest + collect archives
        shell: bash
        run: |
          set -euo pipefail
          mkdir -p pack-out/final
          MERGE=$(ls -d pack-in/*/manifest.json | paste -sd, -)
          [ -n "$MERGE" ] || { echo "没有任何 manifest 可合并"; exit 1; }
          node desktop/scripts/build-pack.js \
            --id "${{ inputs.pack_id }}" \
            --version "${{ inputs.version }}" \
            --out pack-out/final \
            --merge "$MERGE"
          cp pack-in/*/*.tar.gz pack-out/final/
          echo "=== 最终产物 ==="; ls -la pack-out/final
          echo "=== manifest.json ==="; cat pack-out/final/manifest.json
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: pack-${{ inputs.pack_id }}-v${{ inputs.version }}
          name: 'native pack: ${{ inputs.pack_id }} v${{ inputs.version }}'
          prerelease: true
          body: |
            native pack `${{ inputs.pack_id }}` v${{ inputs.version }}（未签名产物）。

            发布到镜像前必须先跑：
            ```
            bash deploy/publish-pack.sh check   <本地下载目录>
            bash deploy/publish-pack.sh sign    <本地下载目录>
            bash deploy/publish-pack.sh publish <本地下载目录> ${{ inputs.pack_id }} ${{ inputs.version }}
            ```
          files: |
            pack-out/final/*.tar.gz
            pack-out/final/manifest.json
          generate_release_notes: false
```

> 说明：规范里「tag `pack-<id>-v<ver>`」指的是 release 的 tag 命名（本 workflow 自己打），触发方式保持 `workflow_dispatch` ——若同时加 `push: tags` 触发，本 job 打完 tag 会把自己再触发一遍。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && node --test tests/workflow-release-gating.test.js`
Expected: PASS。另外本地跑一次语法检查：`python3 -c "import yaml,sys;yaml.safe_load(open('.github/workflows/pack-release.yml'))"`。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add .github/workflows/pack-release.yml desktop/tests/workflow-release-gating.test.js
git commit -m "ci(pack): pack-release 增加四个 Python 运行时 pack 的双平台矩阵与冒烟"
```

---

### Task 4: pysvc-runtime.js 换成 resolveServiceRoot（pack 优先），删首启解压

**Files:**
- Modify: `desktop/main/services/pysvc-runtime.js`（整文件重写：删 `pysvcPath` / `ensurePysvcExtracted` / `dirSize` / `cleanupOldVersions` / `syncSrcPatch` / `restoreSrcPatch` / `walkFiles` / `MARKER`，保留 `extractTar`）
- Create: `desktop/tests/pysvc-runtime.pack-resolve.test.js`
- Delete: `desktop/tests/pysvc-runtime.test.js`（其三个用例测的都是被删的函数）
- Modify: `desktop/package.json:12`（test script 换文件名）

**Interfaces:**
- Consumes: Task 1 定下的落盘布局 `~/.aiworkdeck/packs/<packId>/<version>/{lib,app}`。
- Produces（`module.exports`）：
  - `PACK_ID_BY_SERVICE: Record<'pptx-service'|'mineru-service'|'kokoro-service'|'asr-service', string>`
  - `resolveServiceRoot(ctx, service): string|null` — ctx 需要 `{dataDir, projectRoot, packaged}`
  - `libDirFor(ctx, service): string|null`、`appDirFor(ctx, service): string|null`
  - `extractTar(archive, destDir): Promise<void>`（原样保留，`update-service.js` 在用）

- [ ] **Step 1: 写失败测试**

新建 `desktop/tests/pysvc-runtime.pack-resolve.test.js`：

```js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 四个 Python 服务的根目录解析（设计 §3.2）。优先级与 LitigationVisualService.resolveRuntime
// 同构：显式 env 覆盖 → pack current 目录（必须带 .pack-complete）→ dev 态 bundled。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const {
  PACK_ID_BY_SERVICE, resolveServiceRoot, libDirFor, appDirFor,
} = require('../main/services/pysvc-runtime')

function makeCtx(root) {
  return { dataDir: path.join(root, '.aiworkdeck'), projectRoot: path.join(root, 'repo'), packaged: true }
}

function installPack(ctx, packId, version, { complete = true, revoked = false } = {}) {
  const dir = path.join(ctx.dataDir, 'packs', packId, version)
  fs.mkdirSync(path.join(dir, 'lib'), { recursive: true })
  fs.mkdirSync(path.join(dir, 'app'), { recursive: true })
  if (complete) fs.writeFileSync(path.join(dir, '.pack-complete'), version)
  fs.writeFileSync(
    path.join(ctx.dataDir, 'packs', packId, 'current.json'),
    JSON.stringify({ version, activatedAt: new Date().toISOString(), revoked })
  )
  return dir
}

test('四个服务与 pack id 的映射是唯一事实来源', () => {
  assert.deepStrictEqual(PACK_ID_BY_SERVICE, {
    'pptx-service': 'pptx-runtime',
    'mineru-service': 'mineru-runtime',
    'kokoro-service': 'kokoro-runtime',
    'asr-service': 'asr-runtime',
  })
})

test('装好的 pack 命中：返回 current.json 指向且带 .pack-complete 的版本目录', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  const dir = installPack(ctx, 'kokoro-runtime', '1.0.0')

  assert.strictEqual(resolveServiceRoot(ctx, 'kokoro-service'), dir)
  assert.strictEqual(libDirFor(ctx, 'kokoro-service'), path.join(dir, 'lib'))
  assert.strictEqual(appDirFor(ctx, 'kokoro-service'), path.join(dir, 'app'))
})

test('没有 .pack-complete 的半成品不算数（安装中途被打断）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  installPack(ctx, 'asr-runtime', '1.0.0', { complete: false })

  assert.strictEqual(resolveServiceRoot(ctx, 'asr-service'), null)
  assert.strictEqual(libDirFor(ctx, 'asr-service'), null)
})

test('被平台封禁的 pack 视而不见（规范 §8.4）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  installPack(ctx, 'pptx-runtime', '1.0.0', { revoked: true })

  assert.strictEqual(resolveServiceRoot(ctx, 'pptx-service'), null)
})

test('没装 pack 时返回 null（调用方据此判「组件未安装」，不 spawn）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  assert.strictEqual(resolveServiceRoot(makeCtx(root), 'mineru-service'), null)
})

test('dev 态回落 desktop/bundled/<plat>/pysvc/<service>；env 覆盖压过 pack', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  const plat = process.platform === 'win32' ? 'win-x64' : 'mac-arm64'
  const dev = path.join(ctx.projectRoot, 'desktop', 'bundled', plat, 'pysvc', 'pptx-service')
  fs.mkdirSync(dev, { recursive: true })
  assert.strictEqual(resolveServiceRoot({ ...ctx, packaged: false }, 'pptx-service'), dev)

  const packDir = installPack(ctx, 'pptx-runtime', '1.0.0')
  assert.strictEqual(resolveServiceRoot(ctx, 'pptx-service'), packDir, '打包态 pack 优先')

  const override = path.join(root, 'override')
  fs.mkdirSync(override, { recursive: true })
  process.env.AIWORKDECK_PYSVC_PPTX_DIR = override
  t.after(() => { delete process.env.AIWORKDECK_PYSVC_PPTX_DIR })
  assert.strictEqual(resolveServiceRoot(ctx, 'pptx-service'), override, 'env 覆盖是最高优先级（排障用）')
})

test('未知服务名不猜路径，直接 null', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  assert.strictEqual(resolveServiceRoot(makeCtx(root), 'litviz-service'), null)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/pysvc-runtime.pack-resolve.test.js`
Expected: FAIL —「resolveServiceRoot is not a function」。

- [ ] **Step 3: 最小实现**

把 `desktop/main/services/pysvc-runtime.js` 整体替换为：

```js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const path = require('path')
const fs = require('fs')
const { spawn } = require('child_process')

/**
 * 四个 Python 服务的运行时定位。
 *
 * 0.38.0 起这四个服务不再随安装包分发（设计 §3）：每个服务一个 native pack
 * （<service 前缀>-runtime），装到 ~/.aiworkdeck/packs/<id>/<version>/{lib,app}。
 * 首启解压 pysvc.tar.gz 那一整套（含 splash 与 pysvc-src 补丁）已随本次改动删除——
 * 安装包里不再有 pysvc.tar.gz，没有什么可解压的。
 *
 * 解析优先级与 LitigationVisualService.resolveRuntime / 规范 §5 同构：
 *   1. 显式 env 覆盖 AIWORKDECK_PYSVC_<SVC>_DIR（dev 与排障；最高优先级）
 *   2. pack current 目录（current.json 指向、带 .pack-complete、未被封禁）
 *   3. dev 态随仓库的 desktop/bundled/<os>-<arch>/pysvc/<service>
 * 一档都不命中就返回 null：调用方据此把服务判为「组件未安装」，不 spawn、不报错。
 */

const PACK_ID_BY_SERVICE = {
  'pptx-service': 'pptx-runtime',
  'mineru-service': 'mineru-runtime',
  'kokoro-service': 'kokoro-runtime',
  'asr-service': 'asr-runtime',
}

const COMPLETE_MARKER = '.pack-complete'

function envOverride(service) {
  const key = 'AIWORKDECK_PYSVC_' + service.replace(/-service$/, '').toUpperCase() + '_DIR'
  const v = process.env[key]
  return v && fs.existsSync(v) ? v : null
}

function packRoot(ctx, service) {
  const id = PACK_ID_BY_SERVICE[service]
  if (!id || !ctx || !ctx.dataDir) return null
  const base = path.join(ctx.dataDir, 'packs', id)
  let cur
  try {
    cur = JSON.parse(fs.readFileSync(path.join(base, 'current.json'), 'utf8'))
  } catch (e) {
    return null // 从没装过
  }
  if (!cur || !cur.version || cur.revoked) return null // 封禁的包资源解析要视而不见
  const dir = path.join(base, String(cur.version))
  return fs.existsSync(path.join(dir, COMPLETE_MARKER)) ? dir : null // 半成品不算数
}

function bundledRoot(ctx, service) {
  if (!ctx || !ctx.projectRoot) return null
  const plat = process.platform === 'win32' ? 'win-x64' : 'mac-arm64'
  const dir = path.join(ctx.projectRoot, 'desktop', 'bundled', plat, 'pysvc', service)
  return fs.existsSync(dir) ? dir : null
}

/** 服务根目录（其下是 lib/ 与 app/）；未安装返回 null。 */
function resolveServiceRoot(ctx, service) {
  if (!PACK_ID_BY_SERVICE[service]) return null
  return envOverride(service) || packRoot(ctx, service) || bundledRoot(ctx, service) || null
}

function libDirFor(ctx, service) {
  const root = resolveServiceRoot(ctx, service)
  return root ? path.join(root, 'lib') : null
}

function appDirFor(ctx, service) {
  const root = resolveServiceRoot(ctx, service)
  return root ? path.join(root, 'app') : null
}

// Windows 优先 System32 的 bsdtar（处理盘符冒号无坑）；PATH 里的 GNU tar 会把
// "D:\..." 的冒号当远程主机（见 prepare-python-service.js 同款地雷）
function tarCandidates() {
  if (process.platform === 'win32') {
    const sys = process.env.SystemRoot || 'C:\\Windows'
    return [path.join(sys, 'System32', 'tar.exe'), 'tar.exe']
  }
  return ['/usr/bin/tar', 'tar']
}

function extractTarOnce(cmd, archive, destDir) {
  return new Promise((resolve, reject) => {
    const proc = spawn(cmd, ['-xzf', archive, '-C', destDir], { stdio: ['ignore', 'ignore', 'pipe'] })
    let stderr = ''
    proc.stderr.on('data', (d) => { stderr = (stderr + d.toString()).slice(-2000) })
    proc.once('error', (e) => reject(Object.assign(e, { _spawnError: true })))
    proc.once('exit', (code) => {
      if (code === 0) resolve()
      else reject(new Error(`tar exited ${code}: ${stderr}`))
    })
  })
}

/** 增量更新（update-service.js）解补丁包用；pack 的解压在 Java 侧，不走这里。 */
async function extractTar(archive, destDir) {
  let lastErr = null
  for (const cmd of tarCandidates()) {
    try {
      await extractTarOnce(cmd, archive, destDir)
      return
    } catch (e) {
      lastErr = e
      if (!e._spawnError) throw e // tar 存在但解压失败：不是换候选能解决的
    }
  }
  throw lastErr || new Error('no tar available')
}

module.exports = { PACK_ID_BY_SERVICE, resolveServiceRoot, libDirFor, appDirFor, extractTar }
```

删除 `desktop/tests/pysvc-runtime.test.js`，并把 `desktop/package.json` 的 test 脚本里 `tests/pysvc-runtime.test.js` 换成 `tests/pysvc-runtime.pack-resolve.test.js`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && node --test tests/pysvc-runtime.pack-resolve.test.js`
Expected: PASS（7 个用例）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add desktop/main/services/pysvc-runtime.js desktop/tests/pysvc-runtime.pack-resolve.test.js desktop/package.json
git rm desktop/tests/pysvc-runtime.test.js
git commit -m "feat(desktop): pysvc 运行时改走 pack 解析，删除首启解压"
```

---

### Task 5: main.js 删除首启解压链路与 splash 文案

**Files:**
- Modify: `desktop/main/main.js:61-64`（`firstLaunchSplash` 注释）、`:1359-1467`（`resolvePysvcRoot` + `ensurePysvcReady` 整段删除）、`:1470-1483`（`retireFirstLaunchSplash` 保留但改注释）、`:1485-1521`（`createServices` 去 `pysvcRoot`）、`:1692-1709`（启动链）
- Create: `desktop/tests/pysvc-extract-removed.test.js`

**Interfaces:**
- Consumes: Task 4 的 `resolveServiceRoot`（main.js 不直接调，由 descriptor 调）。
- Produces: `createServices()` 传给 `createServiceManager` / `createModelManager` 的 ctx 不再含 `pysvcRoot`，新增 `projectRoot`（model-manager 侧解析 pack 需要）。

- [ ] **Step 1: 写失败测试**

新建 `desktop/tests/pysvc-extract-removed.test.js`（源码级断言，口径同 `main-window-bounds.test.js`：main.js 起手就 new BrowserWindow，node 直接 require 不进来）：

```js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 首启解压链路必须整段消失（设计 §3.2）。留着它的代价不是多几行死代码：
// 那段逻辑会在没有 pysvc.tar.gz 的 0.38.0 上给用户弹一个「本地组件解压失败」的错误框。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const MAIN = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const PKG = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'))

test('main.js 不再有 pysvc 解压相关的任何符号', () => {
  for (const sym of ['resolvePysvcRoot', 'ensurePysvcReady', 'ensurePysvcExtracted', 'pysvc.tar.gz', 'pysvc.meta.json', 'syncSrcPatch']) {
    assert.ok(!MAIN.includes(sym), `main.js 仍引用 ${sym}`)
  }
})

test('splash 不再承诺「约一分钟」的解压——0.38.0 起首启没有解压这一步', () => {
  assert.ok(!MAIN.includes('约一分钟'), 'splash 文案仍写着解压耗时')
  assert.ok(!MAIN.includes('本地组件解压失败'), '解压失败弹框仍在')
})

test('createServices 传下去的 ctx 带 projectRoot、不带 pysvcRoot', () => {
  const start = MAIN.indexOf('function createServices()')
  const body = MAIN.slice(start, MAIN.indexOf('\n}', start))
  assert.ok(!/pysvcRoot/.test(body), 'ctx 仍在传 pysvcRoot')
  assert.match(body, /projectRoot/, 'model-manager 与 descriptor 解析 pack 需要 projectRoot')
})

test('extraResources 不再打包 pysvc.tar.gz / pysvc.meta.json', () => {
  const froms = PKG.build.extraResources.map((r) => r.from)
  assert.ok(!froms.some((f) => String(f).includes('pysvc')), 'extraResources 仍带 pysvc：' + froms.join(' '))
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/pysvc-extract-removed.test.js`
Expected: FAIL —「main.js 仍引用 resolvePysvcRoot」。

- [ ] **Step 3: 最小实现**

1. 删除 `desktop/main/main.js:1359-1467` 整段（从注释「打包态 pysvc 不再随 .app 携带目录」到 `ensurePysvcReady` 函数末尾 `firstLaunchSplash = splash` 的闭合括号）。
2. `firstLaunchSplash` 变量与 `retireFirstLaunchSplash` **保留**（`createMainWindow` 之后仍在收尾），把 `:61-63` 的注释改为：

```js
// 首启进度窗的引用。0.38.0 起首启不再解压 pysvc（四个 Python 服务改走 native pack），
// 这里只剩「等本机服务起来」这一段的兜底；当前没有创建它的路径，保留是为了
// retireFirstLaunchSplash 的调用点不必跟着分支。
```

3. `createServices()` 改成：

```js
function createServices() {
  // 打包模式下 jar/JRE/python 从 resourcesPath 解析（Epic #18 T2），数据落 ~/.aiworkdeck；
  // 四个 Python 服务的 lib/app 落 ~/.aiworkdeck/packs/<service>-runtime/<version>/（设计 §3.2）
  const dataDir = path.join(app.getPath('home'), '.aiworkdeck')
  const projectRoot = path.join(__dirname, '..', '..')
  if (!modelManager) {
    modelManager = createModelManager({
      dataDir,
      resourcesPath: process.resourcesPath,
      projectRoot,
      packaged: app.isPackaged,
      onProgress: (evt) => {
        try {
          if (mainWindow) mainWindow.webContents.send('checkba:model-progress', evt)
        } catch (e) { /* ignore */ }
        const svc = COMPONENT_SERVICE[evt.id]
        if (evt.phase === 'done' && svc && services) {
          services.start(svc).catch((e) => console.error(`[${svc}]`, e))
        }
      }
    })
  }
  const mgr = createServiceManager({
    projectRoot,
    packaged: app.isPackaged,
    resourcesPath: process.resourcesPath,
    dataDir,
    winEmulated: require('./services/win-arch').isWinArmEmulated()
  })
  mgr.register(createBackendDescriptor())
  mgr.register(createPptxDescriptor())
  mgr.register(createMineruDescriptor(modelManager))
  mgr.register(createKokoroDescriptor(modelManager))
  mgr.register(createAsrDescriptor())
  return mgr
}
```

4. 启动链（`:1692-1709`）把 `ensurePysvcReady()` 与 pysvc-src 补丁块整段换成直接起服务：

```js
  // 桌面端启动时自动拉起本机服务（Java 后端 9696 + 已装 runtime pack 的 Python 服务）。
  // 0.38.0 起没有 pysvc 解压这一步：四个 Python 服务的 descriptor 各自判 pack 在不在场，
  // 不在场就不启动（用户在「可选组件」面板下载后 host.services.ensure 拉起）。
  Promise.resolve()
    .then(() => {
      services = createServices()
      return services.allocatePorts()
    })
    .then(() => services.startEager())
```

5. `desktop/package.json` 的 `build.extraResources` 删掉 `pysvc.tar.gz` 与 `pysvc.meta.json` 两项（`python` 与 `skills` 保留）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && node --test tests/pysvc-extract-removed.test.js && node --test tests/main-window-lifecycle.test.js tests/main-window-bounds.test.js`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add desktop/main/main.js desktop/package.json desktop/tests/pysvc-extract-removed.test.js
git commit -m "feat(desktop): 删除 pysvc 首启解压链路与 extraResources 里的 pysvc.tar.gz"
```

---

### Task 6: 四个 service descriptor 与 model-manager 改走 pack

**Files:**
- Modify: `desktop/main/services/pptx-service.js:15-21,71-82`
- Modify: `desktop/main/services/mineru-service.js:14-16,40-52`
- Modify: `desktop/main/services/kokoro-service.js:14-20,42-52`
- Modify: `desktop/main/services/asr-service.js:14-20,42-62`
- Modify: `desktop/main/services/model-manager.js:5-7,58-129,192-200`
- Create: `desktop/tests/service-pack-gate.test.js`
- Modify: `desktop/package.json:12`（test script 加新文件）

**Interfaces:**
- Consumes: Task 4 的 `resolveServiceRoot(ctx, service)` / `libDirFor` / `appDirFor`。
- Produces:
  - 四个 descriptor 的 `enabled(ctx)` 语义：
    - `pptx-service`：`!ctx.packaged || !!resolveServiceRoot(ctx, 'pptx-service')`
    - `asr-service`：`!ctx.packaged || !!resolveServiceRoot(ctx, 'asr-service')`（**pack 在场即启动**，模型未下也起，`/health` 仍回 `modelReady:false`）
    - `mineru-service` / `kokoro-service`：pack 在场 **且** 对应模型已装
  - `model-manager` 的 `download(id)` 在 runtime pack 缺失时抛
    `Error('<packId> 未安装：模型下载要用该组件的 Python 运行时')`。

- [ ] **Step 1: 写失败测试**

新建 `desktop/tests/service-pack-gate.test.js`：

```js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 四个服务的启动门（设计 §3.2）。pptx/asr 只判 pack 在场；mineru/kokoro 还要判模型。
// asr 刻意保持「pack 在场就起、模型没下也起」：就绪探测必须能分清
// RUNTIME_MISSING / SERVICE_DOWN / MODEL_MISSING 三件事（见 LocalAsrClient 四态）。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')

const { createPptxDescriptor } = require('../main/services/pptx-service')
const { createMineruDescriptor } = require('../main/services/mineru-service')
const { createKokoroDescriptor } = require('../main/services/kokoro-service')
const { createAsrDescriptor } = require('../main/services/asr-service')

function ctxWith(root, packs) {
  const dataDir = path.join(root, '.aiworkdeck')
  for (const id of packs) {
    const dir = path.join(dataDir, 'packs', id, '1.0.0')
    fs.mkdirSync(path.join(dir, 'lib'), { recursive: true })
    fs.mkdirSync(path.join(dir, 'app'), { recursive: true })
    fs.writeFileSync(path.join(dir, '.pack-complete'), '1.0.0')
    fs.writeFileSync(path.join(dataDir, 'packs', id, 'current.json'), JSON.stringify({ version: '1.0.0' }))
  }
  return { dataDir, projectRoot: path.join(root, 'repo'), packaged: true, resourcesPath: path.join(root, 'res'), ports: {} }
}

const models = (installed) => ({ isInstalled: (id) => installed.includes(id) })

test('pack 未装：四个服务全部不启动', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = ctxWith(root, [])
  assert.strictEqual(createPptxDescriptor().enabled(ctx), false)
  assert.strictEqual(createAsrDescriptor().enabled(ctx), false)
  assert.strictEqual(createMineruDescriptor(models(['mineru-models'])).enabled(ctx), false,
    '模型下了但运行时没装，起不来——不能因为模型在就 spawn 一个找不到 lib 的进程')
  assert.strictEqual(createKokoroDescriptor(models(['kokoro-models'])).enabled(ctx), false)
})

test('pack 装了：pptx 与 asr 直接启动（asr 不等模型）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = ctxWith(root, ['pptx-runtime', 'asr-runtime'])
  assert.strictEqual(createPptxDescriptor().enabled(ctx), true)
  assert.strictEqual(createAsrDescriptor().enabled(ctx), true)
})

test('pack 装了但模型没下：mineru / kokoro 仍不启动（模型加载是它们的启动前提）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = ctxWith(root, ['mineru-runtime', 'kokoro-runtime'])
  assert.strictEqual(createMineruDescriptor(models([])).enabled(ctx), false)
  assert.strictEqual(createKokoroDescriptor(models([])).enabled(ctx), false)
  assert.strictEqual(createMineruDescriptor(models(['mineru-models'])).enabled(ctx), true)
  assert.strictEqual(createKokoroDescriptor(models(['kokoro-models'])).enabled(ctx), true)
})

test('dev 态（未打包）恒启用，不被 pack 判定挡住', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { ...ctxWith(root, []), packaged: false }
  assert.strictEqual(createPptxDescriptor().enabled(ctx), true)
  assert.strictEqual(createAsrDescriptor().enabled(ctx), true)
})
```

在 `desktop/tests/model-manager.test.js` 末尾追加：

```js
test('runtime pack 未装时，模型下载当场失败并说清要先装哪个组件', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mm-nopack-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const mgr = createModelManager({
    dataDir: path.join(root, '.aiworkdeck'),
    resourcesPath: path.join(root, 'res'),
    projectRoot: path.join(root, 'repo'),
    packaged: true,
    onProgress: () => {}
  })
  await assert.rejects(() => mgr.download('kokoro-models'), /kokoro-runtime/)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/service-pack-gate.test.js tests/model-manager.test.js`
Expected: FAIL —「createPptxDescriptor().enabled is not a function」与 `mgr.download` 未按预期抛错。

- [ ] **Step 3: 最小实现**

`pptx-service.js`（顶部 require 与路径函数）：

```js
const { resolveServiceRoot, libDirFor, appDirFor } = require('./pysvc-runtime')

function appDir(ctx) {
  return appDirFor(ctx, 'pptx-service')
}

function libDir(ctx) {
  return libDirFor(ctx, 'pptx-service')
}
```

descriptor 加门（`:73` 之后）：

```js
    name: 'pptx-service',
    // 0.38.0 起 pptx 也是可选组件（设计 §3）：runtime pack 没装就不启动，
    // AI 侧的 pptx_* 工具会发 component_required 引导下载，而不是报「稍后重试」。
    eager: true,
    logName: 'pptx-service',
    enabled: (ctx) => !ctx.packaged || !!resolveServiceRoot(ctx, 'pptx-service'),
```

`mineru-service.js`：

```js
const { resolveServiceRoot, libDirFor } = require('./pysvc-runtime')

function libDir(ctx) {
  return libDirFor(ctx, 'mineru-service')
}
```

```js
    // 运行时 pack 与模型两个门都要过：模型在但 lib 不在，spawn 出去只会 ModuleNotFoundError
    enabled: (ctx) => !ctx.packaged
      || (!!resolveServiceRoot(ctx, 'mineru-service') && modelManager.isInstalled('mineru-models')),
```

`kokoro-service.js` 同构（`kokoro-service` / `kokoro-models`），`libDir`/`appDir` 换成 `libDirFor`/`appDirFor`。

`asr-service.js`：把类注释里「模型没下载时照样启动」那段补一句，并加门：

```js
/**
 * 本地 ASR（faster-whisper）。
 *
 * 两个门是分开的，别合并：
 * - **运行时 pack 没装** → 不启动（进程根本没有 lib 可加载）。探测报 RUNTIME_MISSING，
 *   下一步是「下载本机语音识别组件」。
 * - **pack 装了、模型没下** → **照常启动**。就绪探测必须能分清「服务没起」与「模型没下」，
 *   两者的下一步完全不同（重启应用 vs 下 1.5GB 模型）。模型是懒加载的，
 *   空跑一个 FastAPI 进程只有几十 MB 常驻内存。
 */
```

```js
    name: 'asr-service',
    eager: true,
    logName: 'asr-service',
    enabled: (ctx) => !ctx.packaged || !!resolveServiceRoot(ctx, 'asr-service'),
```

`model-manager.js`：`require` 换成 `const { libDirFor, PACK_ID_BY_SERVICE } = require('./pysvc-runtime')`，三处 `PYTHONPATH: pysvcPath(ctx, 'x-service', 'lib')` 换成 `PYTHONPATH: libDirFor(ctx, 'x-service')`；组件表每项加 `runtimeService`：

```js
  {
    id: 'mineru-models',
    runtimeService: 'mineru-service',
    ...
```

`download(id)` 开头加守卫：

```js
  async download(id) {
    const c = this.component(id)
    // 模型下载器本身就跑在这个服务的 venv 里（modelscope / huggingface_hub 都在 lib/ 下）：
    // 运行时 pack 没装的话，spawn 出去只会 ModuleNotFoundError，用户看到的是一句看不懂的
    // 报错而不是「先装组件」。这里当场说清楚（面板据此先装 pack 再下模型）。
    if (this.ctx.packaged && c.runtimeService && !libDirFor(this.ctx, c.runtimeService)) {
      throw new Error(`${PACK_ID_BY_SERVICE[c.runtimeService]} 未安装：模型下载要用该组件的 Python 运行时`)
    }
    if (this.active.has(id)) throw new Error(`${id} already downloading`)
```

`desktop/package.json` 的 test 脚本加上 `tests/service-pack-gate.test.js`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && npm test`
Expected: PASS（全部 18 个测试文件，含改名后的 pack-resolve 与新增两个）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add desktop/main/services/ desktop/tests/service-pack-gate.test.js desktop/tests/model-manager.test.js desktop/package.json
git commit -m "feat(desktop): 四个 Python 服务的启动门改判 runtime pack 在场"
```

---

### Task 7: LocalAsrClient 探测扩成四态

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/meeting/LocalAsrClient.java:60-68`（枚举）、`:100-104`（构造）、`:117-150`（`probe`）
- Modify: `backend/src/test/java/com/checkba/service/meeting/LocalAsrClientTest.java:29-38`（工厂）
- Modify: `backend/src/test/java/com/checkba/service/meeting/MeetingTranscriptionLocalPathTest.java`（构造签名跟随）

**Interfaces:**
- Consumes: Task 2 之后的 `NativePackService.isReady(String packId)`。
- Produces:
  - `LocalAsrClient.Status` 四态：`RUNTIME_MISSING` / `SERVICE_DOWN` / `MODEL_MISSING` / `READY`。
  - `GET /api/asr/local/probe` 的 `status` 字段可能返回 `RUNTIME_MISSING`（前端 #530 Task 9 消费）。
  - 构造签名：`LocalAsrClient(SystemSettingService, NativePackService, @Value("${external.asr.local-base-url:}") String)`。

- [ ] **Step 1: 写失败测试**

`LocalAsrClientTest` 的工厂改成可注入 pack 状态，并补两条用例：

```java
    private static LocalAsrClient client(String healthBody) {
        return client(healthBody, true);
    }

    private static LocalAsrClient client(String healthBody, boolean packReady) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(eq(LocalAsrClient.SETTING_BASE_URL), anyString())).thenReturn("http://127.0.0.1:8890");
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("asr-runtime")).thenReturn(packReady);
        return new LocalAsrClient(settings, packs, "http://127.0.0.1:8890") {
            @Override
            String getHealth(String base) {
                return healthBody;
            }
        };
    }

    @Test
    @DisplayName("连不上且 asr-runtime 没装 → RUNTIME_MISSING，下一步是下载组件而不是重启应用")
    void runtimeMissing() {
        LocalAsrClient.ProbeResult r = client(null, false).probe();

        assertEquals(LocalAsrClient.Status.RUNTIME_MISSING, r.status());
        assertFalse(r.ready());
        assertTrue(r.nextStep().contains("下载"), "下一步要指向下载运行时组件：" + r.nextStep());
        assertFalse(r.nextStep().contains("重启"), "组件没装时让用户重启应用是错的指路：" + r.nextStep());
        assertNotMistakenForLogout(r);
    }

    @Test
    @DisplayName("服务能应答就不是 RUNTIME_MISSING（dev 态从仓库跑，本机没有 pack 也算就绪）")
    void respondingServiceIsNeverRuntimeMissing() {
        LocalAsrClient.ProbeResult r = client(
                "{\"status\":\"ok\",\"model\":\"m\",\"modelReady\":true}", false).probe();

        assertEquals(LocalAsrClient.Status.READY, r.status());
    }
```

原 `serviceDown()` 用例改用 `client(null, true)`。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn -B -Dtest=LocalAsrClientTest test`
Expected: FAIL — 编译错（构造函数三参不存在、`Status.RUNTIME_MISSING` 不存在）。

- [ ] **Step 3: 最小实现**

`LocalAsrClient.java`：

```java
    /** 探测结论四态，前端据此渲染「下一步该做什么」。 */
    public enum Status {
        /** 服务在跑且模型已下载 */
        READY,
        /** 服务在跑，但模型还没下载 */
        MODEL_MISSING,
        /** 连不上，且本机根本没装 asr-runtime 这个可选组件（0.38.0 起是常态） */
        RUNTIME_MISSING,
        /** 组件装了但连不上 / 响应异常（一律归到这一档） */
        SERVICE_DOWN
    }
```

```java
    /** 本机语音识别运行时组件的 pack id（设计 §3.1）。 */
    public static final String RUNTIME_PACK_ID = "asr-runtime";

    private final SystemSettingService systemSettingService;
    private final NativePackService packService;
    private final String defaultBaseUrl;

    public LocalAsrClient(SystemSettingService systemSettingService,
                          NativePackService packService,
                          @Value("${external.asr.local-base-url:}") String defaultBaseUrl) {
        this.systemSettingService = systemSettingService;
        this.packService = packService;
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
    }
```

`probe()` 的 `body == null` 分支改成先分运行时：

```java
        if (body == null) {
            // 判定顺序刻意是「先问服务、再问 pack」：dev 态从仓库直接跑 asr-service 时
            // 本机没有 pack，但服务确实活着——先问 pack 会把它误报成「组件没装」。
            if (!packService.isReady(RUNTIME_PACK_ID)) {
                return new ProbeResult(Status.RUNTIME_MISSING, base, "", false,
                        LangText.of("本机语音识别组件还没安装。",
                                "The on-device speech recognition component is not installed."),
                        LangText.of("下载「本机语音识别」组件（运行时约 40MB，模型 1.5GB）后，录音可以完全不出本机。",
                                "Download the on-device speech recognition component (about 40 MB runtime plus a 1.5 GB model) "
                                        + "to keep recordings entirely on this computer."));
            }
            return new ProbeResult(Status.SERVICE_DOWN, base, "", false,
                    LangText.of("本机转写服务没有运行。", "The on-device transcription service is not running."),
                    LangText.of("重启 AI WorkDeck 让它自动拉起；仍不行时到「系统管理 - 组件管理」查看本机转写组件。",
                            "Restart AI WorkDeck to bring it up; if it persists, check the on-device transcription component "
                                    + "under System settings - Components."));
        }
```

解析异常分支（返回 `SERVICE_DOWN`）保持原样：服务确实应答了，只是内容看不懂。

`MeetingTranscriptionLocalPathTest` 里凡是 `new LocalAsrClient(...)` 的地方补一个 `mock(NativePackService.class)` 参数（该测试用 `stubProbe(...)` 打桩，其余断言不动）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn -B -Dtest='LocalAsrClientTest,MeetingTranscriptionLocalPathTest' test`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add backend/src/main/java/com/checkba/service/meeting/LocalAsrClient.java backend/src/test/java/com/checkba/service/meeting/
git commit -m "feat(asr): 本机转写探测扩成四态，区分「组件没装」与「服务没起」"
```

---

### Task 8: 只读接口 GET /api/packs/optional-components

**Files:**
- Create: `backend/src/main/java/com/checkba/service/pack/OptionalComponents.java`
- Create: `backend/src/main/java/com/checkba/service/pack/ModelPresence.java`
- Modify: `backend/src/main/java/com/checkba/service/pack/NativePackService.java`（新增 `Sizes` / `knownSizes` / 装完落 `sizes.json`）
- Modify: `backend/src/main/java/com/checkba/controller/PackController.java`
- Create: `backend/src/test/java/com/checkba/controller/PackControllerOptionalComponentsTest.java`

**Interfaces:**
- Consumes: Task 2 的 `Component.unpackedSize()`；Task 7 无关。
- Produces（**#530 计划逐字依赖**）：

```
GET /api/packs/optional-components      权限：登录（与 /list 同）
200 {
  "code": 0,
  "components": [
    {
      "packId": "pptx-runtime",
      "service": "pptx-service",
      "state": "not_installed",          // NativePackService 状态机原值
      "installed": false,                // = state == "ready"
      "installedVersion": null,
      "latestVersion": null,             // 内存快照；本端点绝不为它发网络请求
      "downloadBytes": 0,                // 本平台组件压缩字节和；0 = 未知
      "unpackedBytes": 0,                // 解压后字节和；0 = 未知
      "modelId": null,                   // 无模型的组件为 null
      "modelInstalled": false,
      "modelBytes": 0,
      "featureKeys": ["pptxGenerate","pptxFormat","pdfToWordLayout","scannedOcrEntry"]
    },
    { "packId": "mineru-runtime", "service": "mineru-service", "modelId": "mineru-models",
      "modelBytes": 3221225472, "featureKeys": ["scannedPdfToWord","ocrParse"], ... },
    { "packId": "kokoro-runtime", "service": "kokoro-service", "modelId": "kokoro-models",
      "modelBytes": 314572800, "featureKeys": ["ttsPanel"], ... },
    { "packId": "asr-runtime", "service": "asr-service", "modelId": "asr-models",
      "modelBytes": 1610612736, "featureKeys": ["localTranscription"], ... }
  ]
}
```
  数组顺序固定 = 面板卡片顺序。`downloadBytes` / `unpackedBytes` 为 0 时前端异步用 `GET /api/packs/{id}/info` 补（那条会发网络请求、带 5 分钟缓存）。
- 同时产出 `OptionalComponents.PACK_IDS: Set<String>`（Task 9 复用）。

- [ ] **Step 1: 写失败测试**

新建 `PackControllerOptionalComponentsTest.java`：

```java
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;

import com.checkba.service.pack.ModelPresence;
import com.checkba.service.pack.NativePackService;
import com.checkba.service.pack.OptionalComponents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PackControllerOptionalComponentsTest {

    @Test
    @DisplayName("四个可选组件的 id / 服务名 / 模型 id 与文案键固定，顺序即面板卡片顺序")
    void registryIsStable() {
        List<OptionalComponents.Entry> all = OptionalComponents.ALL;
        assertEquals(4, all.size());
        assertEquals(List.of("pptx-runtime", "mineru-runtime", "kokoro-runtime", "asr-runtime"),
                all.stream().map(OptionalComponents.Entry::packId).toList());
        assertEquals(List.of("pptx-service", "mineru-service", "kokoro-service", "asr-service"),
                all.stream().map(OptionalComponents.Entry::service).toList());
        assertNull(all.get(0).modelId(), "pptx 没有模型");
        assertEquals("mineru-models", all.get(1).modelId());
        assertEquals("kokoro-models", all.get(2).modelId());
        assertEquals("asr-models", all.get(3).modelId());
        assertTrue(all.get(0).featureKeys().contains("pdfToWordLayout"));
    }

    @Test
    @DisplayName("端点回四条，state/installed/模型状态/体积都从服务拿，且一次网络请求都不发")
    void listsFourComponentsWithoutNetwork() {
        NativePackService packs = mock(NativePackService.class);
        ModelPresence models = mock(ModelPresence.class);
        NativePackService.PackStatus ready = new NativePackService.PackStatus();
        ready.setState(NativePackService.STATE_READY);
        ready.setInstalledVersion("1.0.0");
        when(packs.status(anyString())).thenReturn(new NativePackService.PackStatus());
        when(packs.status("kokoro-runtime")).thenReturn(ready);
        when(packs.knownSizes(anyString())).thenReturn(new NativePackService.Sizes(0, 0));
        when(packs.knownSizes("kokoro-runtime")).thenReturn(new NativePackService.Sizes(200_000_000L, 760_000_000L));
        when(models.installed("kokoro-models")).thenReturn(true);

        List<Map<String, Object>> out = PackController.optionalComponentViews(packs, models);

        assertEquals(4, out.size());
        Map<String, Object> kokoro = out.get(2);
        assertEquals("kokoro-runtime", kokoro.get("packId"));
        assertEquals("kokoro-service", kokoro.get("service"));
        assertEquals(Boolean.TRUE, kokoro.get("installed"));
        assertEquals("1.0.0", kokoro.get("installedVersion"));
        assertEquals(200_000_000L, kokoro.get("downloadBytes"));
        assertEquals(760_000_000L, kokoro.get("unpackedBytes"));
        assertEquals(Boolean.TRUE, kokoro.get("modelInstalled"));
        assertEquals(314_572_800L, kokoro.get("modelBytes"));
        assertEquals(Boolean.FALSE, out.get(0).get("installed"));
        assertEquals(0L, out.get(0).get("modelBytes"), "pptx 无模型，体积 0");
        // 本端点不许发网络请求：镜像不可达时 20s 超时 × 两个源会把首次登录面板拖死
        verify(packs, never()).info(anyString());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn -B -Dtest=PackControllerOptionalComponentsTest test`
Expected: FAIL — 编译错（`OptionalComponents` / `ModelPresence` / `knownSizes` / `optionalComponentViews` 不存在）。

- [ ] **Step 3: 最小实现**

新建 `OptionalComponents.java`：

```java
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.pack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 四个「可选组件」的唯一注册表（设计 §3.1 / §4.1）。
 *
 * <p>它同时是三处的判据：首次登录面板与组件管理页的卡片（{@code /api/packs/optional-components}）、
 * {@code PackAutoInstaller} 的跳过名单（这四个包**绝不**自动补下，用户要求提示后再下）、
 * 以及工具侧 {@code component_required} 的 payload。写成三份必然漂移。
 *
 * <p>modelBytes 是给面板显示「含模型约多大」的估计值，与 desktop/main/services/model-manager.js
 * 的 sizeHint 同源（3 GB / 300 MB / 1.5 GB）；运行时体积不写死，从 manifest 读。
 */
public final class OptionalComponents {

    public record Entry(String packId, String service, String modelId, long modelBytes, List<String> featureKeys) {}

    public static final List<Entry> ALL = List.of(
            new Entry("pptx-runtime", "pptx-service", null, 0L,
                    List.of("pptxGenerate", "pptxFormat", "pdfToWordLayout", "scannedOcrEntry")),
            new Entry("mineru-runtime", "mineru-service", "mineru-models", 3L * 1024 * 1024 * 1024,
                    List.of("scannedPdfToWord", "ocrParse")),
            new Entry("kokoro-runtime", "kokoro-service", "kokoro-models", 300L * 1024 * 1024,
                    List.of("ttsPanel")),
            new Entry("asr-runtime", "asr-service", "asr-models", 1536L * 1024 * 1024,
                    List.of("localTranscription")));

    public static final Set<String> PACK_IDS =
            Set.copyOf(new LinkedHashSet<>(ALL.stream().map(Entry::packId).toList()));

    public static boolean isOptionalRuntime(String packId) {
        return packId != null && PACK_IDS.contains(packId);
    }

    public static Entry byPackId(String packId) {
        return ALL.stream().filter(e -> e.packId().equals(packId)).findFirst().orElse(null);
    }

    /** 按服务名反查（工具侧只知道自己打不通哪个服务）。 */
    public static Entry byService(String service) {
        return ALL.stream().filter(e -> e.service().equals(service)).findFirst().orElse(null);
    }

    private OptionalComponents() {}
}
```

新建 `ModelPresence.java`：

```java
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.pack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 「模型下没下」的只读判定。落盘由 Electron 的 model-manager.js 负责
 * （{@code ~/.aiworkdeck/models/<name>/.aiworkdeck-complete}，写标记是下载成功的最后一步），
 * 后端只需要读一眼——打包态后端 cwd 就是 ~/.aiworkdeck，与 ai.packs.dir 同一套相对路径惯例。
 */
@Component
public class ModelPresence {

    private static final String MARKER = ".aiworkdeck-complete";

    private final Path root;

    public ModelPresence(@Value("${ai.models.dir:models}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** modelId 形如 {@code mineru-models} → 目录 {@code <root>/mineru}。 */
    public boolean installed(String modelId) {
        if (modelId == null) return false;
        String name = modelId.endsWith("-models")
                ? modelId.substring(0, modelId.length() - "-models".length())
                : modelId;
        if (!name.matches("^[a-z0-9-]{1,32}$")) return false; // 兼防路径穿越
        return Files.isRegularFile(root.resolve(name).resolve(MARKER));
    }
}
```

`NativePackService` 新增（放在 `info(...)` 之后）：

```java
    private static final String SIZES_JSON = "sizes.json";

    /** 一个 pack 在本平台的下载体积与解压体积（字节）；0 = 未知。 */
    public record Sizes(long downloadBytes, long unpackedBytes) {}

    /**
     * 体积快照。<b>不发网络请求</b>：只读 manifest 内存缓存（安装 / {@code /info} /
     * {@link PackUpdater} 写入）与已装版本目录里的 {@code sizes.json}。
     * 两处都没有就是「未知」（两个 0），前端再决定要不要去打会发请求的 {@code /info}。
     */
    public Sizes knownSizes(String packId) {
        CachedManifest cached = manifestCache.get(packId);
        if (cached != null) {
            // 刻意不走 cachedManifest()：这里宁可用过期几分钟的数字，也不能为它发一次请求
            List<Component> cs = componentsForPlatform(cached.manifest());
            return new Sizes(totalSize(cs), totalUnpacked(cs));
        }
        Optional<Path> dir = currentVersionDir(packId);
        if (dir.isPresent()) {
            try {
                JSONObject j = JSONUtil.parseObj(Files.readString(dir.get().resolve(SIZES_JSON), StandardCharsets.UTF_8));
                return new Sizes(j.getLong("downloadBytes", 0L), j.getLong("unpackedBytes", 0L));
            } catch (Exception e) {
                // 0.38.0 之前装的版本目录没有这个文件：按未知处理
            }
        }
        return new Sizes(0L, 0L);
    }
```

`doInstall` 里，在 `Files.writeString(versionDir.resolve(COMPLETE_MARKER), ...)` **之前**插入：

```java
                // 版本目录留一份体积快照：可选组件面板要在不发网络请求的前提下报出
                // 「下载多大 / 占盘多大」，而重启后 manifest 内存缓存是空的。
                JSONObject sizes = new JSONObject();
                sizes.set("downloadBytes", totalSize(components));
                sizes.set("unpackedBytes", totalUnpacked(components));
                Files.writeString(versionDir.resolve(SIZES_JSON), sizes.toString(), StandardCharsets.UTF_8);
```

`PackController` 新增端点与静态视图函数（静态便于单测直接调，不起 Spring 上下文）：

```java
    private final ModelPresence modelPresence;   // 加进 @RequiredArgsConstructor 的字段列表

    /**
     * 四个可选组件的一次性快照（设计 §3.2 / §4.1）。首次登录面板与「设置 - 组件管理」共用它。
     * <b>绝不发网络请求</b>：体积取内存/落盘快照，0 = 未知，前端要精确值再去打 /info。
     */
    @GetMapping("/optional-components")
    public ResponseEntity<Map<String, Object>> optionalComponents(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isLoggedIn(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(LangText.of("未登录", "Not signed in")));
        }
        Map<String, Object> result = ok();
        result.put("components", optionalComponentViews(packService, modelPresence));
        return ResponseEntity.ok(result);
    }

    static List<Map<String, Object>> optionalComponentViews(NativePackService packs, ModelPresence models) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OptionalComponents.Entry e : OptionalComponents.ALL) {
            NativePackService.PackStatus st = packs.status(e.packId());
            NativePackService.Sizes sizes = packs.knownSizes(e.packId());
            Map<String, Object> m = new HashMap<>();
            m.put("packId", e.packId());
            m.put("service", e.service());
            m.put("state", st.getState());
            m.put("installed", NativePackService.STATE_READY.equals(st.getState()));
            m.put("installedVersion", st.getInstalledVersion());
            m.put("latestVersion", packs.knownLatestVersion(e.packId()));
            m.put("downloadBytes", sizes.downloadBytes());
            m.put("unpackedBytes", sizes.unpackedBytes());
            m.put("modelId", e.modelId());
            m.put("modelInstalled", models.installed(e.modelId()));
            m.put("modelBytes", e.modelBytes());
            m.put("featureKeys", e.featureKeys());
            out.add(m);
        }
        return out;
    }
```

`PackStatus` 的默认 `state` 已是 `not_installed`（构造时字段初值），无需额外处理。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn -B -Dtest='PackControllerOptionalComponentsTest,NativePackServiceTest' test`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add backend/src/main/java/com/checkba/service/pack/OptionalComponents.java \
        backend/src/main/java/com/checkba/service/pack/ModelPresence.java \
        backend/src/main/java/com/checkba/service/pack/NativePackService.java \
        backend/src/main/java/com/checkba/controller/PackController.java \
        backend/src/test/java/com/checkba/controller/PackControllerOptionalComponentsTest.java
git commit -m "feat(pack): 新增只读接口 GET /api/packs/optional-components"
```

---

### Task 9: PackAutoInstaller 对四个 runtime pack 不自动补下

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/pack/PackAutoInstaller.java:59-78`
- Create: `backend/src/test/java/com/checkba/service/pack/PackAutoInstallerTest.java`

**Interfaces:**
- Consumes: Task 8 的 `OptionalComponents.isOptionalRuntime(String)`。
- Produces: `PackAutoInstaller.checkAndInstall()` 的返回列表永不含四个 runtime pack id。

- [ ] **Step 1: 写失败测试**

```java
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.pack;

import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PackAutoInstallerTest {

    private static SkillDefinition skill(String id, String packId) {
        SkillDefinition s = new SkillDefinition();
        s.setId(id);
        s.setRequiresPack(packId);
        return s;
    }

    @Test
    @DisplayName("四个可选运行时 pack 绝不自动补下——用户明确要求「提示之后才下」")
    void neverAutoInstallsOptionalRuntimePacks() {
        PackProperties props = new PackProperties();
        NativePackService packs = mock(NativePackService.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(packs.resourceReady(anyString())).thenReturn(false);
        when(skills.isEnabled(anyString())).thenReturn(true);
        when(skills.getSkills()).thenReturn(List.of(
                skill("text-to-speech", "kokoro-runtime"),
                skill("litigation-visual", "litigation-visual")));

        List<String> triggered = new PackAutoInstaller(props, packs, skills).checkAndInstall();

        assertEquals(List.of("litigation-visual"), triggered);
        verify(packs, never()).installAsync("kokoro-runtime");
        verify(packs).installAsync("litigation-visual");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn -B -Dtest=PackAutoInstallerTest test`
Expected: FAIL — `triggered` 含 `kokoro-runtime`。

- [ ] **Step 3: 最小实现**

`PackAutoInstaller.checkAndInstall()` 循环里，`resourceReady` 判定之前插入：

```java
            // 四个 Python 服务运行时是**可选组件**，走的是「先问用户再下」那条路（设计 §3.2 / §4）：
            // 一个 2GB 级的下载不能因为某个 skill 默认启用（text-to-speech 就是
            // enabled_by_default: true）就在后端启动 10 秒后无声开跑。
            // 它们的入口是首次登录的「可选组件」面板与各功能触发点的提示。
            if (OptionalComponents.isOptionalRuntime(packId)) continue;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn -B -Dtest=PackAutoInstallerTest test`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add backend/src/main/java/com/checkba/service/pack/PackAutoInstaller.java \
        backend/src/test/java/com/checkba/service/pack/PackAutoInstallerTest.java
git commit -m "fix(pack): 自动补下载跳过四个可选运行时 pack"
```

---

### Task 10: PptxTools / PdfTools 发 component_required

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/ai/EditorBridgeService.java:431-451` 之后新增方法
- Modify: `backend/src/main/java/com/checkba/service/ai/tools/PptxTools.java:220-233`（`pptx_check_service`）、`:255-275`（`pptx_generate`）
- Modify: `backend/src/main/java/com/checkba/service/ai/tools/PdfTools.java:243-258`（扫描件分支）
- Create: `backend/src/test/java/com/checkba/service/ai/tools/ComponentRequiredTest.java`

**Interfaces:**
- Consumes: Task 8 的 `OptionalComponents.byService(String)` / `NativePackService.isReady` / `knownSizes`。
- Produces（**#530 计划逐字依赖**）：
  - `EditorBridgeService.sendComponentRequiredAction(String packId, String service, String modelId, long sizeMb, List<String> features, String trigger)`
  - SSE `client_action` payload：
    `{"action":"component_required","packId":"pptx-runtime","service":"pptx-service","modelId":null,"sizeMb":165,"features":["pptxGenerate","pdfToWordLayout"],"trigger":"pptx_generate"}`
  - `PptxTools.componentRequiredNotice(String trigger)`：发完动作后返回给模型的文本，**明确说「已请用户确认下载」**，不再说「稍后重试」。

- [ ] **Step 1: 写失败测试**

```java
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.pack.NativePackService;
import com.checkba.service.pack.OptionalComponents;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComponentRequiredTest {

    @Test
    @DisplayName("component_required 的 payload 形状固定：packId/service/modelId/sizeMb/features/trigger")
    void payloadShapeIsStable() throws Exception {
        com.checkba.service.SseEmitterService sse = mock(com.checkba.service.SseEmitterService.class);
        EditorBridgeService bridge = EditorBridgeServiceTestFactory.withSse(sse);
        bridge.setCurrentConversationId("conv-1");

        bridge.sendComponentRequiredAction("pptx-runtime", "pptx-service", null, 165,
                List.of("pptxGenerate", "pdfToWordLayout"), "pptx_generate");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sse).send(eq("conv-1"), eq("client_action"), body.capture());
        Map<?, ?> p = new ObjectMapper().readValue(body.getValue(), Map.class);
        assertEquals("component_required", p.get("action"));
        assertEquals("pptx-runtime", p.get("packId"));
        assertEquals("pptx-service", p.get("service"));
        assertNull(p.get("modelId"));
        assertEquals(165, p.get("sizeMb"));
        assertEquals(List.of("pptxGenerate", "pdfToWordLayout"), p.get("features"));
        assertEquals("pptx_generate", p.get("trigger"));
    }

    @Test
    @DisplayName("服务不可达且 pack 未装：pptx_check_service 发提示，返回文本说「已请用户确认下载」而不是「稍后重试」")
    void checkServicePromptsInsteadOfRetry() {
        com.checkba.service.ai.PptxServiceClient client = mock(com.checkba.service.ai.PptxServiceClient.class);
        when(client.isHealthy()).thenReturn(false);
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("pptx-runtime")).thenReturn(false);
        when(packs.knownSizes("pptx-runtime")).thenReturn(new NativePackService.Sizes(173_015_040L, 0L));
        EditorBridgeService bridge = mock(EditorBridgeService.class);
        PptxTools tools = PptxToolsTestFactory.with(client, bridge, packs);

        String out = tools.pptx_check_service();

        verify(bridge).sendComponentRequiredAction(eq("pptx-runtime"), eq("pptx-service"), isNull(),
                eq(165L), eq(OptionalComponents.byPackId("pptx-runtime").featureKeys()), eq("pptx_check_service"));
        assertTrue(out.contains("已请用户确认下载"), out);
        assertFalse(out.contains("稍后重试"), "pack 没装时让用户「稍后重试」是死路：" + out);
    }

    @Test
    @DisplayName("pack 已装只是服务没起：维持原来的「稍后重试」，不弹下载提示")
    void installedButDownStillSaysRetry() {
        com.checkba.service.ai.PptxServiceClient client = mock(com.checkba.service.ai.PptxServiceClient.class);
        when(client.isHealthy()).thenReturn(false);
        NativePackService packs = mock(NativePackService.class);
        when(packs.isReady("pptx-runtime")).thenReturn(true);
        EditorBridgeService bridge = mock(EditorBridgeService.class);

        String out = PptxToolsTestFactory.with(client, bridge, packs).pptx_check_service();

        verify(bridge, never()).sendComponentRequiredAction(anyString(), anyString(), any(), anyLong(), anyList(), anyString());
        assertTrue(out.contains("稍后重试"), out);
    }
}
```

> `EditorBridgeServiceTestFactory` / `PptxToolsTestFactory` 是本测试文件里的两个私有静态辅助类，用反射注入被测对象的 final 字段（本仓 `@RequiredArgsConstructor` 组件的既有测试手法）：
>
> ```java
> final class PptxToolsTestFactory {
>     static PptxTools with(com.checkba.service.ai.PptxServiceClient client,
>                           EditorBridgeService bridge, NativePackService packs) {
>         PptxTools t = org.springframework.beans.BeanUtils.instantiateClass(PptxTools.class);
>         org.springframework.test.util.ReflectionTestUtils.setField(t, "pptxServiceClient", client);
>         org.springframework.test.util.ReflectionTestUtils.setField(t, "editorBridgeService", bridge);
>         org.springframework.test.util.ReflectionTestUtils.setField(t, "packService", packs);
>         return t;
>     }
>     private PptxToolsTestFactory() {}
> }
> ```
> 若 `PptxTools` 没有无参构造，改用 `ReflectionTestUtils.setField` + `Objenesis` 不可行时，直接给 `PptxTools` 加一个包级可见的测试构造函数 `PptxTools(PptxServiceClient, EditorBridgeService, NativePackService, ...)` 并在两个工厂里调用它。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn -B -Dtest=ComponentRequiredTest test`
Expected: FAIL — `sendComponentRequiredAction` 不存在。

- [ ] **Step 3: 最小实现**

`EditorBridgeService.java` 在 `sendPptConfigAction` 之后追加：

```java
    /**
     * 可选组件缺失（设计 §3.2 / §4.2）：前端收到后弹「要不要下载」，用户确认则
     * POST /api/packs/{packId}/install → （有 modelId 就接着下模型）→
     * host.services.ensure(service) → 自动把原消息重发一次。
     *
     * <p>它不是编辑器命令，不进 libreofficeExecutorClient 的 EDITOR_ACTIONS 白名单——
     * 前端在 ChatInterface 的 onClientAction 接缝里就地拦下（同 ppt_config_required），
     * 不会往下透到执行器（透下去只会得到一句 "Unknown action"）。
     *
     * @param sizeMb 运行时压缩包体积（MB，整数；0 = 未知，前端会去打 /api/packs/{id}/info 补）
     * @param features 解锁功能的文案键（frontend/src/locales/*/components.js 的 features.*）
     * @param trigger 触发它的工具名，只进日志与埋点
     */
    public void sendComponentRequiredAction(String packId, String service, String modelId,
                                            long sizeMb, java.util.List<String> features, String trigger) {
        String conversationId = currentConversationId.get();
        if (conversationId == null) {
            log.warn("No conversation ID set, cannot send component_required for {}", packId);
            return;
        }
        try {
            java.util.Map<String, Object> payloadMap = new java.util.HashMap<>();
            payloadMap.put("action", "component_required");
            payloadMap.put("packId", packId);
            payloadMap.put("service", service);
            payloadMap.put("modelId", modelId);
            payloadMap.put("sizeMb", sizeMb);
            payloadMap.put("features", features == null ? java.util.List.of() : features);
            payloadMap.put("trigger", trigger);
            sseEmitterService.send(conversationId, "client_action", objectMapper.writeValueAsString(payloadMap));
            log.info("Sent component_required for pack {} (trigger={})", packId, trigger);
        } catch (Exception e) {
            log.error("Failed to send component_required for pack " + packId, e);
        }
    }
```

`PptxTools`：注入 `private final NativePackService packService;`，新增私有方法并改 `pptx_check_service`：

```java
    /**
     * 服务打不通时分两种情况处置：
     * - runtime pack 没装（0.38.0 起新装机器的常态）→ 发 component_required 引导下载，
     *   返回给模型的文本明说「已请用户确认下载」，模型不该再喊「稍后重试」；
     * - pack 装了只是进程没起 → 维持原状（重试是有意义的）。
     * @return 已发提示则返回给模型的说明文本；不该发提示时返回 null
     */
    private String promptComponentIfMissing(String trigger) {
        OptionalComponents.Entry e = OptionalComponents.byService("pptx-service");
        if (packService.isReady(e.packId())) return null;
        long sizeMb = packService.knownSizes(e.packId()).downloadBytes() / (1024 * 1024);
        editorBridgeService.sendComponentRequiredAction(
                e.packId(), e.service(), e.modelId(), sizeMb, e.featureKeys(), trigger);
        return "本机的「PPT 与 PDF 组件」还没安装，已请用户确认下载（界面上已经弹出提示）。"
                + "用户确认后组件会自动装好并重试这一步，你现在不需要重复调用本工具，"
                + "也不要建议用户「稍后重试」。这只影响 PPT 生成与 PDF 版式级转换，不影响读文件。";
    }

    @ToolMeta(displayName = "检查PPT服务", category = "pptx")
    @Tool("检查 PPTX 生成服务是否可用。在生成 PPT 之前应先调用此工具确认服务状态。")
    public String pptx_check_service() {
        log.info("Tool: pptx_check_service called");
        try {
            if (pptxServiceClient.isHealthy()) {
                return "PPTX 生成服务运行正常，可以开始生成 PPT。";
            }
            String prompt = promptComponentIfMissing("pptx_check_service");
            if (prompt != null) return prompt;
            return "PPTX 生成服务当前不可用（本机的 PPT 服务组件没有就绪）。请稍后重试；这只影响 PPT 生成，不影响读文件与 OCR。";
        } catch (Exception e) {
            log.error("Failed to check PPTX service", e);
            return "检查服务状态失败: " + e.getMessage() + "。这只影响 PPT 生成，不影响读文件与 OCR。";
        }
    }
```

`pptx_generate(...)`（内部版，`:255` 起）在 `sendPptConfigAction` 之前加一道：

```java
        // 组件没装就不要先弹生成配置弹窗：用户填完一堆选项才发现没引擎是最糟的顺序
        String prompt = promptComponentIfMissing("pptx_generate");
        if (prompt != null) return prompt;
```

`PdfTools`：注入 `private final com.checkba.service.pack.NativePackService packService;`，把扫描件 OCR 失败且无文本层那条 `return`（`:255-256`）改成：

```java
                    } else {
                        OptionalComponents.Entry me = OptionalComponents.byService("mineru-service");
                        if (!packService.isReady(me.packId())) {
                            long sizeMb = packService.knownSizes(me.packId()).downloadBytes() / (1024 * 1024);
                            editorBridgeService.sendComponentRequiredAction(
                                    me.packId(), me.service(), me.modelId(), sizeMb, me.featureKeys(), "pdf_to_word");
                            return "该 PDF 是扫描件（无文本层），本机的「文档解析引擎」组件还没安装，"
                                    + "已请用户确认下载（界面上已经弹出提示，含 3GB 模型）。"
                                    + "用户确认后会自动装好并重试这一步，不要建议用户「稍后重试」。";
                        }
                        return "Error: 该 PDF 是扫描件（无文本层），已尝试本地 MinerU OCR 但失败：" + e.getMessage() +
                                "\n请确认桌面端 MinerU 组件已下载并启动（设置-组件管理），或稍后重试。";
                    }
```

同样在 `convertPdfToDocx` 的 catch 分支（`:302`）之后、回退结构级转换之前，若 `pptx-runtime` 未装则补发一次 `component_required`（用 `PdfTools` 自己的 `promptComponentIfMissing("pdf_to_word")` 同款私有方法，指向 `pptx-service`），但**仍然照常完成结构级降级转换**——降级路径可用就不能因为提示而失败。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn -B -Dtest='ComponentRequiredTest,PdfToolsTest' test`（若无 `PdfToolsTest` 则只跑前者），随后 `mvn -B test` 全量。
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add backend/src/main/java/com/checkba/service/ai/EditorBridgeService.java \
        backend/src/main/java/com/checkba/service/ai/tools/PptxTools.java \
        backend/src/main/java/com/checkba/service/ai/tools/PdfTools.java \
        backend/src/test/java/com/checkba/service/ai/tools/ComponentRequiredTest.java
git commit -m "feat(ai): PPT/PDF 工具在组件缺失时发 component_required 引导下载"
```

---

### Task 11: desktop-build.yml / patch 链路摘除 pysvc

**Files:**
- Modify: `.github/workflows/desktop-build.yml:196-252`（mac 缓存与四步 Bundle）、`:279-336`（win 同）、`:394-484`（mac 四个冒烟）、`:486-493`（Pack pysvc archive mac）、`:605-694`（win 四个冒烟）、`:696-702`（Pack pysvc archive win）、`:748`（`--pysvc` 参数）
- Delete: `desktop/scripts/pack-pysvc.js`
- Modify: `desktop/scripts/build-patch-assets.js:18`（用法注释）、`:41-45`（`PYSVC_SRC_EXCLUDE_DIRS`）、`:198-210`（pysvc-src 组件）、`:250`
- Modify: `desktop/scripts/patch-gate.sh:72-76`、`:84`
- Modify: `desktop/tests/build-patch-assets.test.js`、`desktop/tests/workflow-release-gating.test.js:104-136`

**Interfaces:**
- Consumes: Task 5 的 `desktop/package.json` extraResources 已摘除 pysvc。
- Produces: 补丁组件收敛为三个：`backend-app` / `frontend-h5` / `zetaoffice-wrapper`；`build-patch-assets.js` 不再接受 `--pysvc`。

- [ ] **Step 1: 写失败测试**

`desktop/tests/workflow-release-gating.test.js` 把「pysvc 缓存 key 必须覆盖各服务源码」那条整体替换为：

```js
test('desktop-build.yml：安装包链路里不再有任何 pysvc 痕迹（四个服务改走 native pack）', () => {
  const yml = fs.readFileSync(path.join(WORKFLOW_DIR, 'desktop-build.yml'), 'utf8')
  for (const sym of ['pysvc', 'pack-pysvc.js', 'prepare-python-service.js']) {
    assert.ok(!yml.includes(sym), `desktop-build.yml 仍引用 ${sym}`)
  }
})

test('desktop-build.yml：python 运行时仍随包（litviz 与 pack 里的服务共用它）', () => {
  const yml = fs.readFileSync(path.join(WORKFLOW_DIR, 'desktop-build.yml'), 'utf8')
  assert.match(yml, /bundled\/mac-arm64\/python/)
})
```

`desktop/tests/build-patch-assets.test.js` 追加：

```js
test('补丁组件收敛为三个，pysvc-src 已废除（服务源码修复走 pack 新版本 + 24h 自动追新）', () => {
  const src = fs.readFileSync(path.join(__dirname, '../scripts/build-patch-assets.js'), 'utf8')
  assert.ok(!src.includes('pysvc-src'), 'build-patch-assets.js 仍在产 pysvc-src')
  assert.ok(!src.includes('--pysvc'), '仍接受 --pysvc 参数')
  const gate = fs.readFileSync(path.join(__dirname, '../scripts/patch-gate.sh'), 'utf8')
  assert.ok(!gate.includes('pysvc-src'), 'patch-gate 文案仍提 pysvc-src')
  assert.match(gate, /lock 改动走 pack 发版/, 'requirements.lock 那条的处置要改写成「走 pack 发版」')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/workflow-release-gating.test.js tests/build-patch-assets.test.js`
Expected: FAIL —「desktop-build.yml 仍引用 pysvc」。

- [ ] **Step 3: 最小实现**

1. `.github/workflows/desktop-build.yml`：
   - mac 与 win 的 `Cache python runtime / pysvc / graphviz` 步骤里，`path` 删掉 `desktop/bundled/<plat>/pysvc` 一行，`key` 的 `hashFiles(...)` 删掉 `'*/requirements.lock'`、`'desktop/scripts/prepare-python-service.js'`、`'pptx-service/backend/**'`、`'kokoro-service/**'`、`'asr-service/**'`，只留 `'desktop/scripts/prepare-graphviz.js'`；步骤名改为 `Cache python runtime / graphviz (<plat>)`。
   - 删除两平台各四个 `Bundle <svc>-service` 步骤、各四个 `Smoke test bundled <svc>-service` 步骤、各一个 `Pack pysvc archive` 步骤。
   - `python` 运行时的产出不能跟着没了：`prepare-python-service.js` 里 `ensurePython()` 是唯一下载 CPython 的地方。在两平台的 graphviz 步骤之前各插入一步：

```yaml
      - name: Bundle CPython runtime (macOS arm64)
        # 运行时随包（litviz 与四个 runtime pack 共用它，设计 §3.1）；服务的 lib/app 不再进包。
        if: runner.os == 'macOS' && steps.cache-pybundle-mac.outputs.cache-hit != 'true'
        run: node desktop/scripts/prepare-python-service.js --runtime-only --out desktop/bundled/mac-arm64
```
   并在 `desktop/scripts/prepare-python-service.js` 的 `main()` 开头加：

```js
  // 只烙 CPython 运行时、不装任何服务依赖：安装包只需要解释器，
  // 四个服务的 lib/app 由 pack-release.yml 打进各自的 native pack。
  if (args['runtime-only'] !== undefined) {
    console.log(`runtime: ${ensurePython(outDir)}`)
    return
  }
```
   （`parseArgs` 是 `--k v` 成对解析，所以调用写成 `--runtime-only 1`；把上面 yaml 里的参数改成 `--runtime-only 1 --out ...`。）
   - `build-patch-assets.js` 调用处删掉 `--pysvc desktop/bundled/win-x64/pysvc \` 这一行。
2. `git rm desktop/scripts/pack-pysvc.js`。
3. `desktop/scripts/build-patch-assets.js`：删 `PYSVC_SRC_EXCLUDE_DIRS` 常量、删 `:198-210` 的 pysvc-src 分支、用法注释里去掉 `[--pysvc ...]`，`:250` 附近的注释改成「LOWA 引擎与 CJK 字体」。
4. `desktop/scripts/patch-gate.sh`：

```bash
# 4) Python 服务依赖（requirements.lock）——服务运行时已搬 native pack，
#    lock 改动走 pack 发版（build-pack + pack-release + publish-pack.sh），不进小版本补丁
req_changed=$(git diff --name-only "$PREV..$TAG" -- '*requirements.lock' || true)
if [ -n "$req_changed" ]; then
  violations+=("requirements.lock 有改动（lock 改动走 pack 发版，不进补丁）：$req_changed")
fi
```
   `:84` 的依据行改为：
```bash
  echo "  依据：docs/INCREMENTAL_UPDATE_DESIGN.md §2.2（补丁只含 backend-app / frontend-h5 / zetaoffice-wrapper）。"
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && npm test`；再 `python3 -c "import yaml;yaml.safe_load(open('.github/workflows/desktop-build.yml'))"`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add .github/workflows/desktop-build.yml desktop/scripts/ desktop/tests/
git rm desktop/scripts/pack-pysvc.js
git commit -m "chore(build): 安装包链路摘除 pysvc，补丁组件废除 pysvc-src"
```

---

### Task 12: 发布顺序 runbook 与规范条目（版本号不在本 PR 改）

> **主会话裁定**：`desktop/package.json` 的版本号**不在本实施 PR 里改**。本仓惯例是发版时单独一个
> `chore(release): bump desktop version to 0.38.0` PR（见 #767 先例）。下面凡涉及把版本改成 0.38.0
> 的步骤与「必须已升到 0.38.0」的测试一律跳过；`minAppVersionFor()` 的测试改为与常量 `'0.38.0'` 比对，
> 不与 package.json 比对。


**Files:**
- Modify: `desktop/package.json:3`（`"version": "0.38.0"`）
- Modify: `docs/NATIVE_PACK_DISTRIBUTION.md`（§7.2 之后新增 §7.5「四个 Python 运行时 pack（v0.38.0）」）
- Create: `docs/superpowers/plans/2026-09-09-pack-release-runbook.md`

**Interfaces:**
- Consumes: Task 3 的 workflow、Task 8 的接口、Task 11 的构建链。
- Produces: 一份可照抄的发布清单，供主会话按序执行；无代码接口。

- [ ] **Step 1: 写失败测试**

`desktop/tests/workflow-release-gating.test.js` 追加：

```js
test('本次改动只能随大版本走：desktop/package.json 必须已升到 0.38.0', () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'))
  assert.strictEqual(pkg.version, '0.38.0',
    'patch-gate 会拦下带 desktop/main 与 package.json 改动的小版本 tag')
})

test('build-pack 的 minAppVersion 与安装包版本对得上（pack 装到老壳上只会找不到 resolveServiceRoot）', () => {
  const { minAppVersionFor } = require('../scripts/build-pack')
  const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'))
  assert.strictEqual(minAppVersionFor('pptx-runtime'), pkg.version)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/workflow-release-gating.test.js`
Expected: FAIL — `'0.37.0' !== '0.38.0'`。

- [ ] **Step 3: 最小实现**

1. `desktop/package.json` 版本改 `0.38.0`。
2. `docs/NATIVE_PACK_DISTRIBUTION.md` §7.2 之后追加：

```markdown
### 7.5 四个 Python 运行时 pack（v0.38.0）

`pptx-runtime` / `mineru-runtime` / `kokoro-runtime` / `asr-runtime`，一服务一 pack，
组件 `lib`（平台相关，`unpackDir: lib`）+ `app`（平台无关，`unpackDir: app`；mineru 没有）。
`minAppVersion: 0.38.0`。落盘 `~/.aiworkdeck/packs/<id>/<version>/{lib,app}`，
桌面壳经 `desktop/main/services/pysvc-runtime.js` 的 `resolveServiceRoot()` 解析
（env 覆盖 → pack current → dev bundled）。模型仍走 `model-manager.js`，不并入 pack。

- 单包解压上限因此抬到 150000 条目 / 2.5 GB（`ai.packs.max-archive-entries` /
  `ai.packs.max-unpacked-bytes`），安全前提是 manifest 有 Ed25519 签名。
- 这四个 pack **不进** `PackAutoInstaller` 的自动补下（用户要求先提示后下），
  但照常受 `PackUpdater` 的 24h 追新——服务源码的修复由 pack 新版本承载，
  小版本补丁的 `pysvc-src` 组件同批废除。
```

3. 新建 runbook（内容即下面这份清单，落到 `docs/superpowers/plans/2026-09-09-pack-release-runbook.md`）：

```markdown
# 0.38.0 发布顺序（四个 Python 运行时 pack 先行）

**硬约束：四个 pack 必须先于 0.38.0 安装包上两站镜像并 verify 通过。** 顺序反了，
新装用户点「下载组件」会得到 404，而这四个组件覆盖了 PPT 生成、PDF 版式级转换、
语音合成与本机转写四条功能线。

1. 对每个 id ∈ {pptx-runtime, mineru-runtime, kokoro-runtime, asr-runtime}：
   Actions → Pack Release → pack_id=<id>，version=1.0.0 → 等 mac/win 两腿冒烟绿 + release job 出 prerelease。
2. 下载该 release 的全部资产到本机同一目录，逐个跑：
   ```
   bash deploy/publish-pack.sh check   ~/Downloads/pack-<id>-v1.0.0
   bash deploy/publish-pack.sh sign    ~/Downloads/pack-<id>-v1.0.0
   bash deploy/publish-pack.sh publish ~/Downloads/pack-<id>-v1.0.0 <id> 1.0.0
   bash deploy/publish-pack.sh verify  <id> 1.0.0
   ```
   `verify` 要求北京（www.aiworkdeck.com）与新加坡（workdeck.ai）两站的 manifest 与
   每个组件的 sha256/size 全部一致；任一站不过就不许继续。
3. 四个 id 全部 verify 通过后，再打应用 tag `v0.38.0`，走 desktop-build.yml。
4. 真机验收（新装 DMG）：
   - 安装包体积：mac ≤ 700MB（Phase 2 目标口径），Resources 下**没有** pysvc.tar.gz；
   - 首启没有「正在准备本地组件…约一分钟」的解压窗；
   - 登录后出现「可选组件」面板（#530），四张卡片体积数字非 0；
   - 「稍后再说」→ AI 对话让它生成 PPT → 弹下载提示 → 下载完自动重发原消息并成功出 PPT；
   - 语音面板下载「语音合成」→ 运行时 + 模型两段进度 → 合成成功；
   - 会议面板打开「录音不出本机」→ 提示 RUNTIME_MISSING → 装完组件与模型 → 转写成功。
5. 回滚：pack 侧回退 = 按镜像上旧版本的 manifest 快照重装（旧版本常年在架）；
   应用侧回退 = 官网发 0.37.x（它自带 pysvc，与 pack 互不影响）。
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && npm test`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add desktop/package.json docs/NATIVE_PACK_DISTRIBUTION.md docs/superpowers/plans/2026-09-09-pack-release-runbook.md desktop/tests/workflow-release-gating.test.js
git commit -m "chore(release): 版本落 0.38.0，补四个运行时 pack 的规范条目与发布 runbook"
```
```

---
