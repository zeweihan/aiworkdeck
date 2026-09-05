# 文件关联到当事人/争议焦点（标签类型维度）设计 spec

日期：2026-08-20 ｜ 看板卡：dev-board#63 ｜ 状态：待维护者过目

## 背景与目标

发布视频立项（dev-board#61）时暴露的产品缺口：文件只能打普通标签，没有「这份证据属于哪个当事人 / 支撑哪个争议焦点」的归档维度。维护者口径：「要么接下来很快迭代上，要么用现在的 tag 系统」。视频脚本 v2 暂按现有标签体系写，此卡做真功能；若在视频录制周（约 2026-08-27）前落地，视频镜头随之升级。

目标：律师（或 AI）能把文件标到具体当事人与争议焦点上，文件树与搜索面板能按这两个维度分组浏览/筛选。

## 方案取舍

**A. 给标签加「类型」维度（推荐，本 spec 采纳）**：`project_tag` 表加一列 `type`（`NORMAL` / `PARTY` / `ISSUE`），全部下游管线（文件-标签关联、去重、鉴权、搜索筛选、色条、chip 渲染）原样复用。当事人/争点就是两类特殊标签。

**B. 独立实体（party 表 + issue 表 + 各自的文件关联表）**：结构化更强（可挂原告/被告角色），但要把标签管线的 CRUD、鉴权、UI、搜索整套复制两遍；且今天没有任何消费方需要结构——诉讼可视化的当事人是每张图的 semantic-map JSON 里的字符串（无 DB 实体），`project_memory.parties` 是喂 AI 的 JSON 列。为不存在的消费方建结构违反 YAGNI，一周也做不完。

**裁决：A。** 与诉讼可视化/项目记忆的打通留一条不需要改 schema 的桥：将来谁要当事人清单，查 `type=PARTY` 的标签即得（一条查询，届时再做）。

一个文件可以同时挂多个当事人/多个争点（一份合同涉及双方当事人是常态），多对多天然由现有 `project_file_tag` 承载——这也是 A 优于「文件加一列 partyId」的原因。

## 数据模型

`Tag` 实体加一列：

```java
/** 标签类型：NORMAL 普通 / PARTY 当事人 / ISSUE 争议焦点；null 视同 NORMAL（存量行） */
@Column(length = 16)
private String type;
```

- 可空、无 DB 默认值：存量行全部为 null，读侧（前端与 AI 工具）把 null 当 NORMAL，**零迁移**。
- 白名单校验在 TagService（非法值抛 IllegalArgumentException），不做 JPA enum（存量 H2 列演进走 ddl-auto 加列，字符串最稳）。
- 同名唯一约束不变（projectId+name 全局唯一，不分型）：同一个「张三」不该既是普通标签又是当事人，撞名时提示已存在，用户可去标签管理改型。

## API（全部沿用现有端点，只加字段）

- `GET /api/projects/{id}/tags` — Tag 实体直接序列化，自动带上 `type`，前端无需新端点。
- `POST /api/projects/{id}/tags`、`PUT /tags/{tagId}` — 请求体加可选 `type`；`PUT` 允许改型（存量普通标签「张三」可升级成当事人）。
- 文件挂标签/摘标签端点不动。

## AI 工具（新建 `TagTools`，照 `TaskTools` 形制）

| 工具 | 签名要点 | 说明 |
|---|---|---|
| `tag_list` | 无参 | 返回项目标签清单按类型分组。工具描述明确：打标签前**必须**先看清单、优先复用既有名字（AutoTagging 同义词膨胀到单文件 338 标签的教训写进描述） |
| `tag_file` | fileId, tagName, type | get-or-create（复用 projectId+name 去重）后挂到文件，幂等（已挂即返回成功）。type 缺省 NORMAL |
| `tag_remove_from_file` | fileId, tagName | 摘错了能改；只解关联不删标签 |

- `tag_file` 撞上同名但类型不同的既有标签时：**复用既有标签、不改型**，工具返回文本里说明实际类型（改型是用户在标签管理里的决定，AI 不许静默改）。
- projectId/userId 走 `SERVER_CONTEXT_PARAMS` 注入（防跨项目越权，同 TaskTools）。
- 接线三处：自动注册（`@Component` + `AgentToolComponent`）之外，手工同步 `RealToolBeans.instantiateAll()`（漏了 `EvalToolBeanParityTest` 会红）+ `frontend/src/utils/toolDisplayNames.js`（中英显示名）。base 工具不进任何 skill.yml 的 allowed_tools（留空语义那颗雷与此无关，这三个是通用工具）。
- 视频镜头由此成立：对话里说「把这批证据按当事人和争议焦点归档」→ AI `tag_list` → `extract_file_text` → `tag_file`。

## 前端 UI

1. **TagSelector.vue**（管理标签弹窗里的选择器）：可选标签按「当事人 / 争议焦点 / 标签」三组展示；新建入口加类型三段控件（默认普通）。
2. **TagManager.vue**（全局标签管理）：列表显示类型、编辑可改型。
3. **TagChip.vue / 文件树色条**：不动。类型的视觉表达靠**建型时的默认色系**：当事人默认 `#B45309`（琥珀褐）、争点默认 `#9B1C31`（深红褐）、普通仍默认蓝，颜色照旧可改。不加图标不加 emoji（全局红线）。
4. **SearchPanel.vue**：标签筛选区展开后按「当事人 / 争议焦点 / 其他标签」三个分组头（沿用 `--awd-panel-*` 分组头形制）分块渲染，组内排序规则不变（已选优先 → 命中数 → 名称）；折叠态常驻已选行、24 个截断、过滤框等机制全部不动。
5. **i18n**：zh + en 双份 locale（EN 版红线），涉及 `fileTree.*`、`files.*`、标签管理与工具显示名。

## 非目标（本期不做）

- **AutoTaggingService 不动**：自动打标签继续只产普通系统标签。当事人/争点判定需要通读案情，不适合挂在「每次自动保存跑一次」的便宜档链路上；按需归档走对话 AI 工具。
- 不做当事人角色（原告/被告）、不做与诉讼可视化 semantic-map 的实体级打通、不做 `project_memory.parties` 回写——桥已留（`type=PARTY` 查询），消费方出现再接。

## 实施清单与验证

1. 后端：Tag 加列 + TagService 白名单/型参 + TagController 请求体 → `mvn test -Dtest='TagServiceTest,TagControllerTest'`（新增）
2. 后端：TagTools 三工具 + RealToolBeans → `TagToolsTest`（新增，照 TaskToolsTest）+ `EvalToolBeanParityTest`
3. 前端：四个组件 + i18n + toolDisplayNames → `npm run check:emits` + `npm run build:h5`
4. 人工走查（dev H5 接 5269 后端配方）：建当事人标签 → 文件挂标 → 搜索面板分组筛选 → 对话让 AI 归档一批文件

预估改动量：后端约 300 行、前端约 250 行，视频录制周前可完成。

## 开放问题（默认答案，维护者可推翻）

1. 类型档位就三档（普通/当事人/争点）够吗？「证据」等更多档位本期不加（YAGNI，加档位是加一个枚举值的事）。
2. 默认色系（当事人琥珀褐 / 争点深红褐）接受吗？
3. FileTree 文件树本体要不要也出「按当事人分组」的视图？本期不做（文件树是目录结构，分组浏览由搜索面板承担），说得动就加。
