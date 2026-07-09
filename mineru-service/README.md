# MinerU 本地服务

基于 [MinerU](https://github.com/opendatalab/MinerU) 官方文档构建的本地文档解析服务。

## 功能

- 使用官方 `mineru-api` 命令启动 HTTP API 服务器
- 支持 PDF、图片文档解析
- 提取文字、表格、图片等内容
- 输出 Markdown 格式

## API 端点

服务启动后，访问 `http://localhost:8001/docs` 查看完整 API 文档。

主要端点：
- `POST /file_parse` - 上传并解析文件

## 部署方式

### 使用 Docker Compose（推荐）

```bash
cd /path/to/checkba_cloud
docker-compose up -d mineru-service
```

### 手动构建

```bash
cd mineru-service
docker build -t checkba-mineru .
docker run -d -p 8001:8000 checkba-mineru
```

## 配置说明

环境变量：
- `MINERU_DEVICE_MODE`: 推理设备，默认 `cpu`
- `MINERU_MODEL_SOURCE`: 模型来源，默认 `modelscope`（国内用户推荐）

## 注意事项

1. **首次启动较慢**：需要下载模型文件（约 2-3GB）
2. **内存需求**：建议至少 8GB 内存
3. **CPU 模式**：纯 CPU 运行，解析速度较慢但无需 GPU

## 与 pptx-service 集成

pptx-service 会自动检测本地 MinerU 服务：
1. 优先使用本地服务（无需 token）
2. 如本地服务不可用，回退到云服务（需要配置 MINERU_TOKEN）

配置 `.env`：
```
MINERU_LOCAL_URL=http://mineru-service:8000
```

## 升级到 MinerU 3.x（进行中，需真机验证后合并）

本分支已把 pin 从 `mineru[core]>=2.7.0,<3` 提升到 `>=3.4,<4`（requirements.in + Dockerfile）。

据官方 3.x 说明，我们依赖的接口契约**保留**：`mineru-api` 启动命令、同步端点 `POST /file_parse`
（向后兼容 legacy plugins）、CPU `pipeline` 后端都还在；3.x 另新增异步 `POST /tasks`（我们不用）。
主要变化在模型（PP-OCRv6、新 VLM），不影响我们的调用方式。

**但升级仍必须在有模型的真机上做兼容性验证后再合并/发版**（本环境无法跑 ML 服务）：

```bash
cd mineru-service
# L1 轻量检查（不需模型，几秒）：CLI 存在性 + 从 /openapi.json 确认 /file_parse 契约仍在
SKIP_PARSE=1 bash compat_smoke_test.sh

# 全量（含真解析，需先下载 pipeline 模型）：
mineru-models-download -s modelscope -m pipeline   # 首次
bash compat_smoke_test.sh
```

脚本会逐项报 PASS/FAIL，并在契约变化时打印当前 OpenAPI 路径 / 响应结构，指出需要同步调整的位置
（Dockerfile CMD、desktop `mineru.cli.fast_api` spawn 参数、pptx-service 的 `/file_parse` 调用）。
全绿后再把本分支合并 master 并重打包桌面版。


