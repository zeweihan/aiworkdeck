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
