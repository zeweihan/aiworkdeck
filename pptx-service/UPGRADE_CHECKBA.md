# pptx-service (banana-slides) 升级与兼容验证说明（checkba 侧）

本目录是**上游 [banana-slides](https://github.com/Anionex/banana-slides) 的 vendored 源码**（当前 v0.4.0，2026-07-09 re-vendor）。
我们通过 `backend/.../service/ai/PptxServiceClient.java` 以 HTTP 契约调用它（默认 `http://localhost:5001`）。

## checkba 侧定制清单（re-vendor 时必须重新套用，代码内均有 `[checkba]` 标记）
| 文件 | 定制内容 |
|---|---|
| `backend/services/file_parser_service.py` | 本地 MinerU 优先逻辑：`_truthy`/`_should_force_cloud`/`_get_local_mineru_url` 辅助函数、`__init__` 的 `mineru_local_url` 参数（缺省自动读 Flask config/env，调用点无需改动）、`_check_local_service`/`_parse_with_local_service`/`_save_local_mineru_result` 三个方法、`parse_file` 里"本地优先→云端兜底→无 token 报错"路由 |
| `backend/config.py` | `MINERU_LOCAL_URL`（默认 `http://mineru-service:8000`）与 `MINERU_FORCE_CLOUD`（默认 `'1'`，桌面端 spawn 时会传 env=0 放开本地优先） |
| `backend/app.py` | `PPTX_DATA_DIR` 数据目录外置（桌面打包态 resources 只读，DB/uploads 必须写到注入目录）；配套测试 `backend/tests/test_data_dir.py`。**v0.7.0 tag 首次构建就是因 re-vendor 漏掉此项+下面端口语义变化而红** |
| `.env.example` | MinerU 本地服务段 + `BACKEND_PORT=5001` |
| `docker-compose.yml` / `docker-compose.prod.yml` | 宿主机端口默认 `5001:5000`（对齐 PptxServiceClient 默认 base-url） |
| `requirements.lock` | 桌面打包/CI 用（`desktop/scripts/prepare-python-service.js`、`.github/workflows/desktop-build.yml`）。再生成：`uv export --no-dev --no-hashes --no-emit-project -o requirements.lock` |
| `compat_smoke_test.sh` / 本文件 | checkba 侧新增，上游没有 |
| `backend/utils/text_sanitizer.py` | **checkba 新增**：markdown 治理（行内标记转真格式、列表前缀转 bullet 语义、纯剥离），落字防线 |
| `backend/utils/pptx_format_utils.py` | **checkba 新增**：run/段落格式读写（东亚字体 `<a:ea>`、删除线、高亮、buChar/buAutoNum 的 oxml 补齐；HOUSE 字体常量 楷体_GB2312/Arial，env `PPTX_HOUSE_EA_FONT`/`PPTX_HOUSE_LATIN_FONT` 可覆盖） |
| `backend/utils/pptx_builder.py` | **checkba 改造**：`add_text_element`/`add_table_element` 落字走 sanitizer（markdown → 真格式）、写 HOUSE 字体、多行文本逐行成段修复只有首段吃到样式的缺陷、列表行写真实项目符号；`_set_core_properties` 去掉必抛的 `last_printed=None` |
| `backend/services/pptx_format_service.py` + `backend/controllers/pptx_edit_controller.py` | **checkba 新增**：存量 pptx 格式识别与操作端点 `POST /api/pptx/inspect`、`POST /api/pptx/format`（六种 op：run 格式/段落格式/替换文本/整框重写/单元格文本/单元格格式），注册见 `app.py`、`controllers/__init__.py` 的 `[checkba]` 标记 |
| `backend/services/pdf_convert_service.py` + `backend/controllers/pdf_convert_controller.py` | **checkba 新增**：PDF 转换端点 `POST /api/pdf/to-docx`（pdf2docx 版式级转 Word，限文本型）、`POST /api/pdf/ocr-markdown`（扫描件经 FileParserService 走本地 MinerU 优先/云端兜底出 markdown，不引入第三方云 OCR）。依赖 `pdf2docx`（连带 pymupdf/opencv-headless，desktop 包体积 +~130MB）已进 pyproject 与 requirements.lock |
| `backend/services/prompts.py` | **checkba 改动**：大纲生成 prompt 增加禁 markdown 指令（`Do NOT use markdown formatting symbols ...`） |
| `backend/tests/unit/test_text_sanitizer.py` / `test_pptx_formatting.py` | **checkba 新增**：上述能力的回归测试（re-vendor 后跑它们即可验证定制是否套全） |

> 注：0.4.0 上游把「可编辑 PPTX 导出」改为 image_editability 混合抽取器（MinerU 云端 + 可选百度高精 OCR，
> 见 `BAIDU_OCR_API_KEY`）。该链路的 FileParserService 未显式传 `mineru_local_url`，但由于缺省会
> 自动读 config/env，本地优先逻辑同样生效。
>
> **端口语义变化（0.4.0）**：应用监听端口从读 `PORT` 改为读 `BACKEND_PORT`（`IN_DOCKER=1` 时固定 5000）。
> 桌面 spawn（desktop/main/services/pptx-service.js）与 CI 冒烟（desktop-build.yml）已两个变量都传，
> 升降级均兼容——再升级时留意上游是否又改此语义。

## 为什么升级要单独走这个流程
- 升级 = **重新 vendor 一整份上游源码**（0.1 → 0.4 是大 diff），不是改一行 pin；
- 真跑生成还需要图像/文本模型的 **API key**；
- 因此不在通用代码体检里自动做，需按下面步骤在真机验证后再合并。

## 升级步骤
1. 取上游 v0.4.0 源码，替换本目录内容（保留我们自定义的 `.env`、`docker-compose.yml` 端口映射 5001:5000、
   以及 `MINERU_LOCAL_URL` 等集成配置）。
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
