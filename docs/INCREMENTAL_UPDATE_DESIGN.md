# 桌面端增量更新总体设计（小版本补丁 / 大版本全量）

状态：已实施（P1-P3 一次性落地，2026-08-06；electron-updater 全量自动更新按 §7 留作 v2）。
日期：2026-08-06。
决策人：韩泽伟。

## 0. 一句话

版本号规则定为 `0.X.Y`：**X 是大版本，走全量安装包；Y 是小版本，走应用内补丁更新**。
补丁只下发真正变化的组件（后端业务 jar + 前端 h5 + 编辑器壳层），典型体积从 1.3GB 降到 10MB 以内。

## 1. 背景与实测数据

现状：没有任何自动更新机制。`desktop/package.json` 无 electron-updater，`desktop/main/` 无 autoUpdater 代码。用户更新 = 去 Release 页手动下载 dmg/exe 重装。

安装包（v0.10.1 实测）：mac dmg 1324MB，win exe 1409MB。已安装 app 1.5GB，组成：

| 组件 | 体积 | 小版本间是否变化 |
|---|---|---|
| pysvc.tar.gz（三 Python 服务依赖） | 728M | 几乎不变 |
| backend.jar（fat jar） | 364M | 变——但其中**业务代码（BOOT-INF/classes）仅 1.4MB**，其余全是依赖 jar |
| frontend（h5 4MB + zetaoffice 引擎约 100M） | 106M | h5 常变（4MB）；引擎不变 |
| jre（jlink 裁剪） | 67M | 不变 |
| python 运行时 | 66M | 不变 |

v0.10.0 → v0.10.1 实际变更：22 个文件、751 行。有效增量 <10MB，用户却要下 1324MB。

## 2. 版本号与发布纪律

### 2.1 规则

- 版本号 `0.X.Y`，单一来源仍是 `desktop/package.json` 的 version。
- **X +1 = 大版本**：全量安装包，用户下载 dmg/exe 重装（未来可选接 electron-updater 自动化，见 §7）。
- **Y +1 = 小版本**：应用内补丁更新，后台下载、重启生效。
- 每个小版本**同时**照常产出全量安装包（新用户首装用），补丁只服务存量用户。

### 2.2 小版本允许改什么（硬边界）

小版本补丁**只能**包含以下三个组件，其余任何变化都必须升大版本：

| 补丁组件 | 内容 | 典型体积 |
|---|---|---|
| `backend-app` | 后端业务 jar（BOOT-INF/classes 拆出，见 §4.1） | ~1.5MB |
| `frontend-h5` | h5 整包（frontend/dist/build/h5） | ~4MB |
| `zetaoffice-wrapper` | 编辑器壳层文件（zetaoffice bundle 里除 LOWA 引擎外的自建文件） | <1MB |

**必须升大版本的变化**（技术原因，不是任意规定）：

1. **Electron 壳（desktop/main、desktop/preload、app.asar）**——mac 上 .app 内所有文件被代码签名密封，改任何一个字节 Gatekeeper 直接拒启。壳代码只能随安装包走。
2. **后端依赖变化（pom.xml 引起 lib 目录变化）**——补丁只发业务 jar，依赖 jar 留在安装包里（含 javacpp 平台原生库，且 mac 侧已被 sign-mac-natives.sh 签名，动不得）。
3. **JRE / Python 运行时 / pysvc / LOWA 引擎**——同理在签名密封内或体积过大。
4. **数据库 schema 不兼容迁移**——H2 单机库，补丁回滚时旧代码要能读新库；小版本内只允许加列加表这类向后兼容迁移。

这是**发布纪律**：改了壳就得攒到大版本一起发。CI 会机器强制（§2.3），不靠自觉。

### 2.3 CI 守门（小版本 tag 上的断言）

tag `v0.X.Y`（Y>0）触发时，desktop-build.yml 新增前置 job `patch-gate`：

1. 找到同大版本的上一个 tag（`v0.X.(Y-1)`，不存在则 `v0.X.0`）。
2. `git diff <prev>..<this> -- desktop/` 必须为空（仅允许 package.json 的 version 行变化）。
3. `git diff <prev>..<this> -- backend/pom.xml backend/**/pom.xml` 必须为空。
4. `git diff <prev>..<this> -- frontend/` 中不允许触及 zetaoffice 引擎来源（fetch-lowa-assets 的 LOWA_BASE_URL / 引擎版本号）。
5. 任一断言失败 → 整个 workflow 失败，报错信息明说"该变更需要升大版本 0.(X+1).0"。

守门失败不是灾难，删 tag 改版本号重打即可（教训见 v0.9.4：tag 必须打在发版 PR 合并之后）。

## 3. 总体架构：overlay（覆盖层）机制

核心约束：**mac 上绝不能写 .app 内部任何文件**（签名密封，pysvc-runtime.js 顶部注释同款地雷）。所以补丁不"打进"安装目录，而是落到用户数据目录，启动时**覆盖优先、内置兜底**：

```
~/.aiworkdeck/overlay/                     (win: %USERPROFILE%\.aiworkdeck\overlay\)
  0.11/                                    <- 大版本号命名空间
    current.json                           <- 原子指针：当前生效的补丁版本与组件清单
    backend-app/0.11.2/app.jar
    frontend-h5/0.11.2/...                 <- h5 整目录
    zetaoffice-wrapper/0.11.2/...          <- 壳层文件（与内置目录同相对路径）
    staging/                               <- 下载与校验中的临时区，激活前不可见
```

解析顺序（三处 seam，全部已收口在单点）：

| 组件 | 现有 seam | 改造 |
|---|---|---|
| backend | `backend-service.js` 的 `jarPath(ctx)` | overlay 有 app.jar 且 current.json 生效 → 用 overlay app.jar + 内置 lib/；否则内置 |
| frontend-h5 | `main.js:307` loadFile 路径 | overlay 目录存在 → loadFile(overlay/index.html)；否则内置 |
| zetaoffice-wrapper | `zetaoffice-server.js` 静态文件根 | 改为**双根查找**：先查 overlay 根，miss 再查内置根（引擎大文件永远命中内置根） |

规则：

- overlay 的大版本命名空间必须等于 app 自身的 `0.X`，否则忽略（大版本升级后旧 overlay 自动失效）。
- 新大版本首启：删除所有 `overlay/<非本大版本>/` 目录（安装器不会替我们清理）。
- `current.json` 用"写临时文件 + rename"原子切换；组件目录按版本号隔离，激活失败可指回旧版本。
- 保留当前 + 上一个补丁版本，更早的清理。

## 4. 组件拆分改造

### 4.1 backend：fat jar 拆成 lib/ + app.jar（最大的单项收益）

现状 fat jar 364MB（压缩后），业务代码仅 1.4MB。改造：

- `prepare-backend.js`：解开 fat jar，产出 `bundled/<plat>/backend/lib/*.jar`（全部依赖）+ `bundled/<plat>/backend/app.jar`（BOOT-INF/classes + META-INF 打回一个薄 jar）。
- `backend-service.js` 启动命令从 `java -jar backend.jar` 改为 `java -cp <app.jar><sep><lib/*> com.checkba.CheckbaApplication`（sep 平台分隔符 `:`/`;`；用参数数组 spawn，无 shell 引号问题；Spring Boot 应用类路径直启完全支持）。
- `sign-mac-natives.sh` 适配：原来扫 fat jar 内嵌 dylib，现在改扫 lib/ 下各 jar——嵌套 dylib 集合完全相同，zip -0 回写逻辑复用，工作量在路径遍历。
- extraResources 从 `backend.jar` 一项改为 `backend/` 目录。

附带收益：启动不再需要 fat jar 的嵌套 classloader，冷启动更快。

风险点：javacpp 平台裁剪（-Djavacpp.platform）本来就发生在 mvn package，拆包不影响；desktop-e2e 与 CI 冒烟（/api/admin/wizard 120s 探针）沿用即可验证。

### 4.2 frontend-h5：整目录替换

4MB 整包下发，不做文件级 diff——不值得为 4MB 引入合并复杂度。overlay 目录整体原子换。

### 4.3 zetaoffice-wrapper：双根静态服务

`build:zetaoffice` 产物 = 自建壳层（editor 页面 js/html，小）+ fetch-lowa-assets 拉入的引擎（soffice.wasm 等，约 100M，brotli + .encodings.json 侧车）。构建时生成**壳层文件清单**（路径+hash，排除引擎文件模式），补丁即按清单打包。zetaoffice-server.js 双根查找对引擎文件零影响（永远 miss overlay）。

## 5. 更新通道：manifest 与分发

### 5.1 manifest 格式（每大版本一个频道文件）

`https://<官网域>/update/desktop/manifest.json`：

```json
{
  "schema": 1,
  "latestMajor": "0.12",
  "majorDownloadPage": "https://<官网域>/download",
  "channels": {
    "0.11": {
      "latest": "0.11.2",
      "notes": "https://github.com/<repo>/releases/tag/v0.11.2",
      "components": [
        { "name": "backend-app",        "version": "0.11.2", "sha256": "…", "size": 1572864, "urls": ["<镜像URL>", "<GitHub Release asset URL>"] },
        { "name": "frontend-h5",        "version": "0.11.2", "sha256": "…", "size": 4194304, "urls": ["…"] },
        { "name": "zetaoffice-wrapper", "version": "0.11.1", "sha256": "…", "size": 262144,  "urls": ["…"] }
      ]
    }
  }
}
```

要点：

- 组件版本独立记录：某小版本没动编辑器壳层，就沿用旧版本号，客户端已有则跳过下载。
- 补丁组件全部平台无关（业务 jar / h5 / 壳层 js 都不含原生二进制），**mac 和 win 共用同一份补丁产物**——这是绕开"mac 不支持差量"问题的根本手段：我们根本不做二进制差量，做组件级替换。
- manifest 旁挂 `manifest.json.sig`（Ed25519 签名，见 §8）。

### 5.2 分发与大陆可达性

- CI（tag 构建）把补丁产物 + manifest + 签名附到 GitHub Release（现有 softprops/action-gh-release 步骤追加 asset）。
- 官网 ECS（8.152.169.44）跑一个同步脚本（发版后手动触发或 cron），把最新 Release 的补丁 asset 拉到本地由 nginx 托管，作为大陆主镜像。
- 客户端按 `urls` 数组顺序尝试：镜像优先，GitHub 兜底。补丁只有几 MB，即使走 GitHub 直连也远比 1.3GB 可忍。

### 5.3 签名密钥

- Ed25519 私钥存 GitHub Actions secret，CI 签 manifest；公钥硬编码进 Electron 壳。
- 签名/验签实现复用插件分发已有的 canonical JSON + Ed25519 对拍配方（见插件分发安全模型），不新造轮子。
- 公钥在壳里意味着换钥需要大版本——可接受，本来就该如此。

## 6. 客户端更新流程

新模块 `desktop/main/services/update-service.js`，全程在 Electron 主进程：

1. **检查**：启动后延迟 2 分钟静默检查一次 + 每 6 小时一次 + 设置页"检查更新"按钮手动触发。拉 manifest → 验签 → 比对。
2. **判定**：
   - `latestMajor` > 本机大版本 → 大版本流程（§7）。
   - 同大版本且有组件版本更新 → 补丁流程（下一步）。
3. **下载**：逐组件下载到 `overlay/<X>/staging/`，逐个验 sha256。失败静默重试，尊重 urls 顺序降级。全程不打扰用户。
4. **就绪提示**：全部组件就绪后，渲染层出非模态提示："新版本 0.X.Y 已就绪，重启后生效"，附"立即重启"按钮。IPC 走 preload 白名单（沿用 checkbaDesktop 通道模式，注意剪贴板去重那次的页面栈多实例订阅地雷——用活跃实例指针）。
5. **激活**：staging 目录 rename 到正式位置 → 原子重写 current.json → （立即重启路径）relaunch。下次启动三个 seam 自然读到新版本。
6. **自愈回滚**：backend-service 已有 120s 健康探针。若 overlay 生效状态下后端起不来：current.json 记 `bootAttempts`，连续 2 次失败 → 把 current.json 指回上一版本（没有上一版本则清空回内置），下次启动自动恢复。这是对"补丁把用户搞挂"的最后保险。
7. **版本显示**：关于页/设置页显示 `0.X.Y`，Y 取 current.json 生效版本（无 overlay 则取壳版本）。壳版本停在 `0.X.0`（壳在小版本不变，这是纪律的自然结果——设置页同时展示壳版本与补丁版本，排障用）。

## 7. 大版本流程

v1 从简：检查到新大版本 → 弹窗（版本亮点 + 体积提示）→ "前往下载"打开官网下载页，用户手动安装（覆盖安装，用户数据在 ~/.aiworkdeck 不受影响）。

v2（可选，不在本期）：接 electron-updater 做全量自动更新。届时 mac 需要追加 zip target + latest-mac.yml；win NSIS 顺带获得 blockmap 差量。此项独立不阻塞本设计任何部分。

明确不做：mac 二进制 delta（Sparkle/BinaryDelta 路线）。理由：包体 93% 是运行时，组件级增量已把问题消解，无需为 delta 引入第二套更新框架。

## 8. 安全模型

- **完整性**：manifest Ed25519 验签 + 逐组件 sha256。任一步失败 → 丢弃整批补丁，本次不更新，下次重来。绝不激活未验证内容。
- **信任根**：公钥在签名密封的 .app 内，篡改公钥需先破壳签名。
- **降级攻击**：客户端只接受 ≥ 当前生效版本的补丁（current.json 记录），manifest 重放旧版本无效。
- **传输**：manifest 走官网 HTTPS；asset URL 允许 http 镜像也无妨，sha256 在验。
- **执行边界**：补丁内容是我们自己签发的业务代码，与插件分发"不做沙箱、签名即信任"的既有安全模型一致。

## 9. CI 改造清单（desktop-build.yml + 脚本）

1. 新 job `patch-gate`（§2.3），小版本 tag 时前置运行。
2. `prepare-backend.js` 拆 lib/app（§4.1），`sign-mac-natives.sh` 适配 lib 目录。
3. 新脚本 `desktop/scripts/build-patch-assets.js`：tag 构建末尾产出 `patch/backend-app-<ver>.jar`、`patch/frontend-h5-<ver>.tar.gz`、`patch/zetaoffice-wrapper-<ver>.tar.gz`、`patch/manifest.json` + `.sig`（读上一版 manifest 决定组件版本是否沿用——组件产物 hash 未变则不发新版本号）。补丁产物平台无关，**只在一个 runner（windows，快）上生成一次**。
4. Release 附件追加补丁产物。
5. ECS 镜像同步脚本 `deploy/update-mirror-sync.sh`（拉 Release asset 到 nginx 目录，原子换 manifest）。
6. 冒烟扩展：desktop `npm test` 新增 update-service 单测（manifest 解析/验签/降级拒绝/回滚指针）；desktop-e2e 增加"伪造本地 manifest → 打补丁 → 重启 → 版本号与功能生效"链路。

## 10. 官网文档（下载页/文档站文案草稿）

官网独立仓库，以下文案交付后粘贴（保持全站无 emoji、衬线视觉红线）：

> ### 版本与更新说明
>
> AI Workdeck 的版本号形如 0.X.Y。
>
> **小版本更新（Y 变化，如 0.11.1 → 0.11.2）**：应用内自动完成。软件在后台下载仅包含变化部分的补丁（通常不足 10MB），提示"重启后生效"，无需重新下载安装包。补丁经数字签名与哈希校验，来源可信、内容完整；更新失败会自动回退，不影响使用。
>
> **大版本更新（X 变化，如 0.11 → 0.12）**：涉及运行时与核心组件变更，需要从本页下载完整安装包覆盖安装。您的文档、项目与设置保存在用户目录中，覆盖安装不会丢失任何数据。
>
> **离线环境**：所有版本始终提供完整安装包，离线或内网环境可直接下载全量包完成任意版本升级。
>
> 每个版本的具体变更见发布记录。

另在下载页版本号旁加一行小字："已安装 0.X 的用户将通过应用内更新自动获得 0.X.Y 补丁，无需重新下载。"

## 11. 实施分期

**Phase 1 — 通道与壳（先让"更新"这件事存在）**
manifest 格式定稿 + update-service（检查/提示/大版本引导）+ 设置页入口 + 官网文案上线。此阶段小版本仍提示去官网下载，但用户至少能在应用内**知道**有新版本。
验证：desktop npm test 新增单测；手动 workflow_dispatch 验 manifest 产出。

**Phase 2 — overlay 与补丁（核心）**
backend 拆分（§4.1）→ 三 seam overlay 解析（§3）→ 下载/校验/激活/回滚（§6）→ CI patch-gate 与补丁产物（§9）→ ECS 镜像。
验证：四套既有验证全绿（mvn test / lowa-e2e / app-e2e / desktop-e2e，基线 858/104/169）+ 新增补丁链路 e2e + 真机走一次 0.X.Y → 0.X.(Y+1) 实弹补丁。

**Phase 3 — 加固与可选项**
pysvc 源码层补丁（把 Python 服务源码目录纳入 overlay，依赖仍走大版本）；electron-updater 全量自动更新；更新失败遥测。

**正交议题（另行决策，不阻塞本设计）**：pysvc.tar.gz 728MB 是否改为首启按需下载——安装包可立减一半，但削弱"离线可用"卖点，产品取舍单独定。

## 12. 已知地雷备忘（实施时对照）

- mac .app 内一个字节都不能改（签名密封）；overlay 一律落 ~/.aiworkdeck。
- CI 签名 → 冒烟 → 打包顺序不可乱（PR#176）；lib/ 拆分后签名步骤在 mvn package 与 electron-builder 之间的位置不变。
- tag 必须打在发版 PR 合并之后（v0.9.4 教训）。
- preload IPC 订阅要用活跃实例指针，防页面栈多实例重复订阅（剪贴板去重地雷）。
- H2 迁移小版本内只许向后兼容（回滚安全）。
- docs/ 在 .gitignore，本文件入库用 git add -f。
- 改动 backend-service.js 构造/启动契约时同步检查 EvalHarness（历史上踩过两次的是编排器，同类缝隙）。
