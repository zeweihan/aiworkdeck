# IDE 化本地文件夹项目 设计文档

日期：2026-07-31
状态：已获用户批准（就地编辑 / localRoot 全面重构 / 实时 watcher / 单文件过渡版）

## 目标

项目交互与 IDE（VSCode 等）对齐：

1. 新建项目 = 「打开文件夹...」或「新建项目文件夹...」，取消项目类型选择。
2. 文件就地存放在用户自选的本地文件夹（localRoot），Finder 完全联动。
3. 实时 watcher：Finder 里的增删改实时反映进软件文件树，并进版本记录。
4. 「打开文件...」过渡版：选单个文件 → 以其所在文件夹为项目并打开该文件。
5. 存量托管项目零迁移，行为不变。

## 现状与差距（探查结论）

- 新建项目是「项目创建向导」：8 种法律项目类型 + 公司名表单，创建纯 DB 记录，文件靠上传复制进 `data/projects/{id}/`。
- `ProjectFile.filePath` 存 `projects/{id}/...` 逻辑路径（相对 data 根），数据库是文件树真源。
- 物理路径解析一半走 `StorageService`（`LocalFileStorageService.resolveFilePath()` 唯一收口，约 20 处调用），另一半自己拼（AI 工具/RAG/版本记录/脱敏链路）。
- rootPath 解析逻辑被复制 3 份：`LocalFileStorageService:34-63`（正本）、`ProjectRepoService:62-72`、`ProjectRagService:182-196`。
- 现存 bug（本次顺手修）：
  - `DocumentEditTools:138-139` 无条件 `.getParent()`，打包态（cwd=~/.aiworkdeck）解析到 `~/data/projects/` 错误路径。
  - `FileTools:48` / `PptxTools:61` 用 repo 根 + 硬编码 `"data"` 绕过 StorageProperties，prod 绝对 root-path 下是坏的。
  - `FileContextLoader:176` 把相对 filePath 当 CWD 相对路径 `new File`，几乎必然 exists()==false 静默跳过。
- `ProjectService.deleteProject` 只删 DB 行，不碰磁盘（保持）。
- 桌面 IPC `window.checkbaDesktop.fs.showOpenDialog` 已可用。

## 设计

### 存储架构（PR1）

- `Project` 新增 `localRoot` 列（TEXT，绝对路径）。null = 存量托管项目，物理位置照旧 `data/projects/{id}`，零迁移。
- `ProjectFile.filePath` 保持 `projects/{id}/...` 格式不动，降格为纯逻辑路径。版本记录/云同步/清单 v2 契约全部建立在此格式上，不动即不用回归。
- 新建 `ProjectStorageResolver`（`com.checkba.storage`）：唯一「逻辑路径 → 物理路径」映射点。
  - `projects/{id}/x` → localRoot 非空则 `localRoot/x`，否则 `globalRoot/projects/{id}/x`。
  - `avatars/`、`clipboard/`、`favorites/`、`checkpoints/`、`ocr/`、`repos/` 等全局命名空间照旧 `globalRoot/...`。
  - rootPath 解析（user.dir 结尾 backend 取 parent 再 resolve）收拢为唯一实现。
- `LocalFileStorageService.resolveFilePath()` 委托 resolver → 走 StorageService 的调用点零改动。
- ① 类「自己拼路径」的点全部改走 resolver：`FileTools`、`PptxTools`、`DocumentEditTools`、`ProjectRagService`、`ProjectRepoService`、`SensitiveController`/`SensitiveService` 链路、`FileContextLoader`。
- `ProjectRepoService.workTree()` = `resolver.projectRoot(projectId)`：git 工作树 = 用户文件夹成为契约保证。gitDir 照旧 `data/repos/`，用户文件夹永不出现 `.git`。
- 用户文件夹已有自己的 `.git`：写进我们仓库的 info/exclude，版本记录视而不见。

### 交互层（PR2）

- newproject 页重做：删项目类型/公司名表单，两个动作——「打开文件夹...」（openDirectory 对话框，项目名默认文件夹名）、「新建项目文件夹...」（选父目录 + 输名字，真建目录）。
- `createProject` 接受 `localRoot`；创建后扫描磁盘导入文件树（复用 createFile，跳过隐藏文件/.awd/.git）。
- 「打开文件...」：选文件 → 按 localRoot 查已有项目，命中复用，否则以父目录建项目 → 进工作台打开该文件。
- 文件树右键「在 Finder 中显示」。
- 非桌面环境降级：保留托管空白项目入口。云端接入项目继续托管（localRoot=null）。
- 存量项目类型相关面板（公司信息/Tushare）对新项目自然不出现（无类型），旧项目不动。

### watcher（PR3）

- 后端 `io.methvin:directory-watcher`（macOS 原生 FSEvents；JDK 21 WatchService 在 mac 是轮询，不用）。项目打开时启动、关闭/退出时停。
- 事件防抖后触发幂等对账：扫描磁盘 ↔ DB 文件树 diff，一致则无事发生。幂等天然免疫软件自写盘回环。
- 变更推送前端刷新文件树（复用现有事件通道，兜底窗口聚焦刷新），并调 `onChangeSignal` 进版本记录。

### 安全红线与边界

- 删除项目只删 DB 记录，绝不碰用户文件夹（固化为契约）。
- localRoot 消失（移走/改名/外置盘拔出）：降级态「文件夹不见了」+「重新定位」，绝不静默重建或清空。
- macOS TCC 授权失败给明确提示。
- 选文件夹校验：拒绝 data 根内部、拒绝嵌套进其他项目 localRoot。

## 交付

- PR1：resolver + localRoot + ①类收拢 + 3 个现存 bug 修。纯后端，存量行为不变，mvn test 全绿（JDK 21）。
- PR2：新建项目 UI + 导入 + 单文件过渡版 + Finder 显示。
- PR3：watcher + 对账 + 推送 + 版本信号。

端到端验证：临时文件夹建项目 → 树见既有文件；软件编辑 → 字节落原文件夹；Finder 增删 → 树跟上；开版本记录 → 退回/另起一稿作用在用户文件夹。
