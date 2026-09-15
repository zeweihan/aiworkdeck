# pptx-service (banana-slides) 升级与兼容验证说明（checkba 侧）

本目录是**上游 [banana-slides](https://github.com/Anionex/banana-slides) 的 vendored 源码**。
我们通过 `backend/.../service/ai/PptxServiceClient.java` 以 HTTP 契约调用它（默认 `http://localhost:5001`）。

## 当前 vendor 基线：上游提交 `a9a5c3638d059fd8d1cf704baf0cf5e88ae51444`（2026-02-17，2026-09-09 re-vendor）

**re-vendor 一律按提交号，不按 tag。** 理由是上游改证：

- `a9a5c36`「chore: change license to AGPL-3.0」把 `LICENSE` 从 **CC BY-NC-SA 4.0** 整体换成
  **AGPL-3.0** 全文（+645/-36），配套 PR #225 重写了未签 CLA 的贡献者代码。
- 而 **`v0.4.0` tag（2026-02-09）打在改证之前**——`git merge-base --is-ancestor a9a5c36 v0.4.0` 为否。
  按 tag 取版本会取回 CC BY-NC-SA 的快照，NC 条款禁止的正是「装进收费产品分发」这种用法。
  2026-07-09 那次 re-vendor（PR#129/#132）就是这么取错的。
- 所以本目录的许可现在是 **AGPL-3.0**，与我们社区版一致；`pptx-service/LICENSE` 应当是 AGPL-3.0 全文，
  升级后请确认这一点。决策与法律分析见 `docs/UPSTREAM_LICENSE_MEMO_2026-09-09.md`（第 1.3、1.7、1.8 节）。
- `a9a5c36` 与 `v0.4.0` 相差 32 个提交。下次升级同样**先查目标提交的 LICENSE 是什么**，再决定取哪个点。

**上游原文文件（不是我们写的，保留但别当成本项目的规则）**：`CLA.md`、`CONTRIBUTING.md`、
`CODE_OF_CONDUCT.md` 都是 banana-slides 的文档，逐字节 vendored（`CODE_OF_CONDUCT.md` 是
a9a5c36 这一版新进来的）。本项目自己的 CLA 在 `legal/CLA.md`、治理规则在仓根 `GOVERNANCE.md`、
行为准则在仓根 `CODE_OF_CONDUCT.md`——给外部贡献者指路时别指到这里来。

## checkba 侧定制清单（re-vendor 时必须重新套用，代码内均有 `[checkba]` 标记）
| 文件 | 定制内容 |
|---|---|
| `backend/services/ai_service_manager.py` + `backend/controllers/project_controller.py` + `backend/controllers/export_controller.py` | **模型配置 model_config 消费端**（见下方专节，2026-08-08 补回）：主后端在每个生成请求体里下发供应商/密钥/模型，本服务据此建 AIService，而不是用自己的 `GOOGLE_API_KEY` |
| `backend/services/file_parser_service.py` | 本地 MinerU 优先逻辑：`_truthy`/`_should_force_cloud`/`_get_local_mineru_url` 辅助函数、`__init__` 的 `mineru_local_url` 参数（缺省自动读 Flask config/env，调用点无需改动）、`_check_local_service`/`_parse_with_local_service`/`_save_local_mineru_result` 三个方法、`parse_file` 里"本地优先→云端兜底→无 token 报错"路由 |
| `backend/config.py` | `MINERU_LOCAL_URL`（默认 `http://mineru-service:8000`）与 `MINERU_FORCE_CLOUD`（默认 `'1'`，桌面端 spawn 时会传 env=0 放开本地优先） |
| `backend/app.py` | 四项：① `PPTX_DATA_DIR` 数据目录外置（桌面打包态 resources 只读，DB/uploads 必须写到注入目录）；配套测试 `backend/tests/test_data_dir.py`。**v0.7.0 tag 首次构建就是因 re-vendor 漏掉此项+下面端口语义变化而红**；② 注册 `pptx_edit_bp` / `pdf_convert_bp` 两个自有蓝图（见下方对应行）；③ `create_app()` 末尾调 `task_manager.reconcile_orphaned_tasks()` 做启动对账（见 task_manager 行）；④ 两处安全加固——`debug` 改成只认显式 `FLASK_ENV=development`（上游默认值就是 `development`，漏配等于开着 Werkzeug 交互式控制台），`app.run` 的 host 改成 `os.getenv('PPTX_BIND_HOST', '127.0.0.1')`（上游硬编码 `0.0.0.0`，本服务端点不做鉴权，桌面版绑 0.0.0.0 等于把按路径读写文件的能力开放给同一局域网；容器内由 compose 显式设 `PPTX_BIND_HOST=0.0.0.0`） |
| `.env.example` | MinerU 本地服务段（`MINERU_LOCAL_URL` / `MINERU_FORCE_CLOUD=0`）+ `BACKEND_PORT=5001` + `MINERU_TOKEN` 默认置空（上游是占位串 `your-mineru-token`，非空会让「本地优先→云端兜底」的兜底分支拿着假 token 去打云端） |
| `docker-compose.yml` / `docker-compose.prod.yml` | 宿主机端口默认 `5001:5000`（对齐 PptxServiceClient 默认 base-url） |
| `requirements.lock` | 桌面打包/CI 用（`desktop/scripts/prepare-python-service.js`、`.github/workflows/desktop-build.yml`）。再生成：`uv export --no-dev --no-hashes --no-emit-project -o requirements.lock` |
| `uv.lock` | 上游文件，但要随 `pyproject.toml` 多出的 `pdf2docx` 重新解析。**不要手改**，`uv lock` / `uv export` 会顺带更新它 |
| `compat_smoke_test.sh` / 本文件 | checkba 侧新增，上游没有 |
| `backend/utils/text_sanitizer.py` | **checkba 新增**：markdown 治理（行内标记转真格式、列表前缀转 bullet 语义、纯剥离），落字防线 |
| `backend/utils/pptx_format_utils.py` | **checkba 新增**：run/段落格式读写（东亚字体 `<a:ea>`、删除线、高亮、buChar/buAutoNum 的 oxml 补齐；HOUSE 字体常量 楷体_GB2312/Arial，env `PPTX_HOUSE_EA_FONT`/`PPTX_HOUSE_LATIN_FONT` 可覆盖） |
| `backend/utils/pptx_builder.py` | **checkba 改造**：`add_text_element`/`add_table_element` 落字走 sanitizer（markdown → 真格式）、写 HOUSE 字体、多行文本逐行成段修复只有首段吃到样式的缺陷、列表行写真实项目符号；`_set_core_properties` 去掉必抛的 `last_printed=None` |
| `backend/services/pptx_format_service.py` + `backend/controllers/pptx_edit_controller.py` | **checkba 新增**：存量 pptx 格式识别与操作端点 `POST /api/pptx/inspect`、`POST /api/pptx/format`（六种 op：run 格式/段落格式/替换文本/整框重写/单元格文本/单元格格式），注册见 `app.py`、`controllers/__init__.py` 的 `[checkba]` 标记 |
| `backend/services/pdf_convert_service.py` + `backend/controllers/pdf_convert_controller.py` | **checkba 新增**：PDF 转换端点 `POST /api/pdf/to-docx`（pdf2docx 版式级转 Word，限文本型）、`POST /api/pdf/ocr-markdown`（扫描件经 FileParserService 走本地 MinerU 优先/云端兜底出 markdown，不引入第三方云 OCR）。依赖 `pdf2docx`（连带 pymupdf/opencv-headless，desktop 包体积 +~130MB）已进 pyproject 与 requirements.lock |
| `backend/controllers/settings_controller.py` | **checkba 改动（PR#241）**：新增 `_reject_without_settings_token()`——`PPTX_SETTINGS_TOKEN` 未配置即 403 关闭写入面（而不是默认放开），配置了则用 `hmac.compare_digest` 比 `X-Settings-Token` 头。三个调用点：`update_settings`（PUT /api/settings/）、`reset_settings`（POST /api/settings/reset）、`run_settings_test`（POST /api/settings/tests/<name>，它能用请求体的 `api_base_url` 覆盖出网地址却沿用已存的真实 key，等于一次不落库的凭据外泄，所以与写设置同级把关）。**全仓从不设置这个变量，所以这三个端点在产品里恒 403**——这正是 model_config 必须由主后端按请求下发的原因 |
| `backend/tests/unit/test_api_settings_provider.py` | **上游用例，checkba 打了补丁**：上游 a9a5c36 新增的 `test_update_settings_accepts_lazyllm_provider` 裸调 `update_settings()`，撞上上一行那道 403 闸。补丁给它塞 `PPTX_SETTINGS_TOKEN` 环境变量与 `X-Settings-Token` 请求头，用例仍测「lazyllm 是合法取值」。**下次 re-vendor 若这条用例又红成 `assert 403 == 200`，就是这处补丁没跟上** |
| `backend/services/task_manager.py` | **checkba 新增（PR#526）**：`TaskManager.reconcile_orphaned_tasks()` 静态方法——进程重启后数据库里残留的 `PENDING`/`PROCESSING` 任务一律判 `FAILED`（`active_tasks` 是进程内字典，执行器早没了，前端会永远轮询转圈）。由 `app.py` 的 `create_app()` 在 app context 里调用。回归用例 `backend/tests/unit/test_task_reconcile.py` |
| `pyproject.toml` | **checkba 新增依赖**：`pdf2docx>=0.5.13`（`/api/pdf/to-docx` 用，连带 pymupdf / opencv-python-headless / python-docx / fonttools / fire / termcolor）。改完必须重跑 `uv lock` 与 `uv export` |
| `backend/server.log` / `backend/server_running.log` | **上游误入库的运行日志，我们删掉**（每次 re-vendor 都会随上游整包回来，记得再删一次） |
| `backend/services/prompts.py` | **checkba 改动**：大纲生成 prompt 增加禁 markdown 指令（`Do NOT use markdown formatting symbols ...`） |
| `backend/tests/unit/test_text_sanitizer.py` / `test_pptx_formatting.py` / `test_task_reconcile.py` / `test_pdf_convert.py` + `backend/tests/test_data_dir.py` | **checkba 新增**：上述能力的回归测试共五个文件（re-vendor 后跑它们即可验证定制是否套全）。跑法：`cd pptx-service && uv run --with pytest pytest backend/tests/unit backend/tests/test_data_dir.py -q` |

> 注：0.4.0 上游把「可编辑 PPTX 导出」改为 image_editability 混合抽取器（MinerU 云端 + 可选百度高精 OCR，
> 见 `BAIDU_OCR_API_KEY`）。该链路的 FileParserService 未显式传 `mineru_local_url`，但由于缺省会
> 自动读 config/env，本地优先逻辑同样生效。
>
> **端口语义变化（0.4.0 起）**：应用监听端口从读 `PORT` 改为读 `BACKEND_PORT`（`IN_DOCKER=1` 时固定 5000）。
> 桌面 spawn（desktop/main/services/pptx-service.js）与 CI 冒烟（desktop-build.yml）已两个变量都传，
> 升降级均兼容——再升级时留意上游是否又改此语义。
>
> **a9a5c36 又改了兜底分支**：`BACKEND_PORT` 未设时不再固定 5000，而是
> `_compute_worktree_port(5000)`——按项目根目录名的 MD5 算一个 5000-5499 的端口。
> 我们的两条链路都显式传 `BACKEND_PORT`，所以不受影响；但**如果哪天有调用点漏传，
> 症状会是「服务起在一个看不出来的随机端口上」而不是「端口冲突」**，排查时留意。

## model_config：模型与密钥由主后端下发（最容易在 re-vendor 时丢的一项）

**为什么必须有这层定制**：本服务自己那套 AI 配置（`GOOGLE_API_KEY` / `AI_PROVIDER_FORMAT` / `TEXT_MODEL`）
在 AI WorkDeck 里**没有任何配置入口**——桌面端不写 `.env`，写设置的接口 `POST /api/settings/*` 又要求
`PPTX_SETTINGS_TOKEN`，而全仓从不设置这个变量（等于恒 403）。所以模型与密钥只能由主后端
（`backend/.../service/ai/tools/PptxTools.java` 的 `buildModelConfig`）在**每次请求的 body 里下发**，
键名 `model_config`：

```json
{"provider": "openai", "api_key": "sk-...", "api_base": "https://openrouter.ai/api/v1",
 "text_model": "deepseek/deepseek-v4-flash", "image_model": "google/gemini-3-pro-image-preview"}
```

`provider` 是本服务侧的 SDK 格式标识（`openai` = OpenAI 兼容，OpenRouter 与 AI WorkDeck 云端平台通道都走它），
不是主后端的 `ai.activeProvider`。

**消费端落点（re-vendor 后逐项核对，代码内均有 `[checkba]` 标记）**：

| 文件 | 改动 |
|---|---|
| `backend/services/ai_service_manager.py` | 新增 `create_ai_service_with_config()`（按配置指纹缓存 AIService）、`set_active_model_config()` / `_active_model_config`（进程级兜底）、`get_ai_service()` 增加 `model_config` 形参且**动态配置优先于本服务单例**；`clear_ai_service_cache()` 连带清动态实例缓存 |
| `backend/controllers/project_controller.py` | `generate_outline` / `generate_descriptions` / `generate_images` 三个端点从 body 取 `model_config`，先 `set_active_model_config()` 再 `get_ai_service(model_config=...)` |
| `backend/controllers/export_controller.py` | `export_editable_pptx` 取 `model_config` 并 `set_active_model_config()`（干净背景图由递归分析链路自行取 AIService，只能靠进程级口径） |

**为什么需要进程级兜底而不是纯参数透传**：`services/task_manager.py` 的子线程与
`services/image_editability/factories.py` 的 `create_*` 工厂都自己调无参 `get_ai_service()`，
拿不到请求体。因此 `set_active_model_config()` 记下最近一次下发的配置。
代价是**本服务必须保持「单用户本机进程」形态**（桌面端就是；多用户共享一个 pptx-service 时，
按用户 provision 的平台密钥会有串用风险）。

**不带 model_config 的端点**：`/refine/outline`、`/pages/{id}/edit/image` 主后端目前不下发配置，
靠上面的进程级兜底工作——正常流程它们一定跟在生成之后，配置已经在。
只有「pptx-service 在生成之后重启、再单独调修改类端点」这一种情形会退回本服务自己的配置并报缺 key，
要彻底闭合就在 `PptxServiceClient.refineOutline` / `editPageImage` 也带上 `model_config`。

**丢掉这层定制的表现**：大纲阶段直接 `ValueError: GOOGLE_API_KEY ... is required`
（`backend/services/ai_providers/__init__.py` 的 `_get_provider_config`，默认 provider 见 `config.py` 的
`AI_PROVIDER_FORMAT=gemini`），产品内无处可配。0.1.0 → 0.4.0 re-vendor（PR#129，2026-07-09）
就是这么丢的，且当时定制清单里没有这一行，两道防线都没照到——**这次补上，下次务必照表移植**。

**真机验证步骤**（本轮只改代码，未起服务，以下留给验证阶段执行）：
1. 源码级防线：`bash pptx-service/compat_smoke_test.sh` —— 末尾四条「定制在」必须全 PASS（不需要服务在跑）。
2. 起服务：`cd pptx-service && docker compose up -d`（或跑打包版桌面端，pptx 走动态回环端口）。
3. 只发一次请求验证链路（把 KEY 换成设置页里真实的 OpenRouter key）：
   ```bash
   PID=$(curl -s -X POST http://localhost:5001/api/projects \
     -H 'Content-Type: application/json' \
     -d '{"creation_type":"idea","idea_prompt":"AI 在法律行业的应用"}' \
     | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["project_id"])')
   curl -s -X POST "http://localhost:5001/api/projects/$PID/generate/outline" \
     -H 'Content-Type: application/json' \
     -d '{"language":"zh","model_config":{"provider":"openai","api_key":"KEY",
          "api_base":"https://openrouter.ai/api/v1","text_model":"deepseek/deepseek-v4-flash",
          "image_model":"google/gemini-3-pro-image-preview"}}' | head -c 400
   ```
   期望：返回 `data.pages` 非空；服务日志出现 `[checkba] creating AIService from model_config`，
   **不得**出现 `GOOGLE_API_KEY ... is required`。
4. 产品内端到端：桌面端在设置页分别选「AI WorkDeck 云端」与 OpenRouter，各让 AI 生成一份 PPT
   （AI 面板说「做一份关于 X 的 PPT」→ 配置卡选可编辑版），确认大纲/配图/可编辑导出三段都成。
   切到本地 Ollama 时应立刻收到中文提示「AI PPT 需要云端模型」，而不是跑到一半失败。
5. 平台通道负例：账户未连接/额度未就绪时选「AI WorkDeck 云端」生成 PPT，
   必须报账户侧的中文业务错误，**不能**悄悄用上用户自己填的 OpenRouter key（看后端日志里的 provider/key 指纹）。

**已知缺口**：图像生成（幻灯片图、干净背景图）按张计费，模型 ID 是 `PptxServiceClient.IMAGE_MODEL`
单列的常量，刻意不进主后端的 `AllowedModels` 白名单（那里的每个模型都要有 prompt/completion 单价
才能按输入长度分档）。因此**AI PPT 的图像花费目前不进 `token_usage` 记账**，用量看板里看不到它。

## 为什么升级要单独走这个流程
- 升级 = **重新 vendor 一整份上游源码**（0.1 → 0.4 是大 diff），不是改一行 pin；
- 真跑生成还需要图像/文本模型的 **API key**；
- 因此不在通用代码体检里自动做，需按下面步骤在真机验证后再合并。

## 升级步骤
1. 取上游**目标提交**（不是 tag，见顶部「当前 vendor 基线」一节）的源码，替换本目录内容：
   先把上表里 14 个 checkba 新增文件与 `.env`（若有）拷出来，`git archive <commit> | tar -x` 铺上游全量，
   删掉上游误入库的 `backend/server.log` / `server_running.log`，再把拷出来的文件放回去，
   最后按上表逐项重新套用对上游文件的 15 处改动。
2. `cd pptx-service && docker compose build && docker compose up -d`（或桌面打包链路）。
3. **跑契约兼容测试**（关键）：
   ```bash
   cd pptx-service
   BASE=http://localhost:5001 bash compat_smoke_test.sh          # 只验端点契约（无需 key）
   RUN_FULL=1 BASE=http://localhost:5001 bash compat_smoke_test.sh  # 配好 .env 的 key 后端到端
   ```
4. 脚本会逐项 PASS/FAIL。若某端点 404（被删/改名）或返回结构变了，按提示同步修改
   `PptxServiceClient.java` 对应方法（路径 / 请求体字段 / `data.*` 响应字段）。
5. 全绿后再合并升级、重打包桌面版。

## 我们依赖的契约（compat_smoke_test.sh 校验的端点）
| 方法 | 路径 | 我方读取的关键字段 |
|---|---|---|
| GET | /health | 200 |
| POST | /api/projects | `data.project_id` |
| POST | /api/projects/{id}/generate/outline | `data.pages` |
| POST | /api/projects/{id}/generate/descriptions | `data.task_id` |
| POST | /api/projects/{id}/generate/images | `data.task_id` |
| GET | /api/projects/{id}/tasks/{taskId} | `data.status` / `data.progress.{completed,total}` |
| GET | /api/projects/{id}/export/pptx | `data.download_url` |
| POST | /api/projects/{id}/export/editable-pptx | `data.task_id` |
| GET | /api/projects/{id} | `data`（含 pages） |

> 其余较少用到的端点（`/pages/{id}/edit/image`、`/refine/outline`、`/files/screenshot`、
> `/projects/edit-standalone-image`、`/projects/edit-pptx-slide`）如上游有改动，同样在 PptxServiceClient 里对齐即可。
>
> **已知缺口（2026-08-01 实测）**：`/api/files/screenshot`、`/api/projects/edit-standalone-image`、
> `/api/projects/edit-pptx-slide` 三个端点在 0.4.0 re-vendor 后已不存在（上游删除，checkba 侧未重建）。
> Java 侧调用已清理：`PptxServiceClient` 对应方法与 `pptx_get_page_screenshot`、`pptx_smart_modify`
> 工具已随文本/格式能力切到 `/api/pptx/*`（inspectPptx/formatPptx）一并下线；纯图像页 AI 改图能力
> 暂无入口，恢复时需在服务侧重建端点后再加回工具。
>
> **checkba 自有端点（上游没有，re-vendor 时必须连同上表文件一起保留）**：
> | 方法 | 路径 | 用途 |
> |---|---|---|
> | POST | /api/pptx/inspect | 存量 pptx 结构化格式全览（字体/字号/粗斜删下/高亮/颜色/对齐/行距/项目符号/表格） |
> | POST | /api/pptx/format | 批量格式操作（set_run_format / set_paragraph_format / replace_text / set_shape_text / set_cell_text / set_cell_format），落字自动去 markdown |
> | POST | /api/pdf/to-docx | 版式级 PDF→Word（pdf2docx；文本型未加密 PDF） |
> | POST | /api/pdf/ocr-markdown | 扫描件 MinerU OCR 出 markdown（本地 mineru-service 优先，云端 token 兜底） |
