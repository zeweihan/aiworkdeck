# pptx-service (banana-slides) 升级与兼容验证说明（checkba 侧）

本目录是**上游 [banana-slides](https://github.com/Anionex/banana-slides) 的 vendored 源码**（当前 v0.1.0）。
我们通过 `backend/.../service/ai/PptxServiceClient.java` 以 HTTP 契约调用它（默认 `http://localhost:5001`）。

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
