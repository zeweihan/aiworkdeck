# Web 服务器版部署（Phase A2）

背景与整体规划见 [docs/HARMONYOS_PLAN.md](../../docs/HARMONYOS_PLAN.md)。
这是「瘦客户端」的服务端形态：鸿蒙电脑（或任何设备）用浏览器访问；
后续的鸿蒙 Electron 壳（Phase B）也连同一个后端。

> 当前限制：LibreOffice 编辑器在纯浏览器里的通道（A1，iframe bridge）尚未实现，
> 落地前编辑器功能在 Web 版不可用，其余功能（项目/文件/AI 编排/解析/TTS）不受影响。

## 构建产物

```bash
cd frontend && npm run build:h5 && npm run build:zetaoffice
node desktop/scripts/fetch-lowa-assets.js   # LOWA 运行时 + CJK 字体（落到 frontend/dist/zetaoffice/lowa/）
mvn -B -DskipTests -f backend/pom.xml package
```

## 服务器布局

```
/opt/aiworkdeck/web/            <- frontend/dist/build/h5/*
/opt/aiworkdeck/web/zetaoffice/ <- frontend/dist/zetaoffice/*
/opt/aiworkdeck/web/probe/      <- deploy/web/probe/*（可选，浏览器能力探针）
后端 jar：java -jar backend.jar --spring.profiles.active=prod   # :9696，只监听 127.0.0.1
Python 服务：docker compose up（仅内网端口，由后端编排，不对外暴露）
```

nginx 用 [nginx.conf.example](nginx.conf.example)，改域名和证书即可。
前端构建时无需设置 `VITE_API_BASE_URL`（默认同源，nginx 反代 `/api/`）。

## 鸿蒙浏览器能力探针（probe/）

判断鸿蒙设备浏览器能否跑 LOWA，只需打开 `https://<域名>/probe/` 看四项是否全绿：
crossOriginIsolated、SharedArrayBuffer、共享 WASM Memory、Worker 传递 SAB。

- 探针页自带 COI service worker 兜底（`coi-sw.js`）：即使托管方加不了 COOP/COEP 头
  （如对象存储/静态托管），首次加载注册 SW 后自动刷新一次也能得出结论。
  正式部署走 nginx 头方案，SW 只是探针的便携兜底。
- 没有真机：华为体验店现场打开 10 分钟测完，或 DevEco Studio 鸿蒙 PC 模拟器。

## 安全底线

- Python 服务端口（8001/5001 等）绝不对公网开放，只走 127.0.0.1/内网。
- 后端 9696 只监听本机，由 nginx 统一入口 + TLS。
- 管理页 `/api/admin` 依赖登录态（requireAdmin），公网部署务必改默认口令。
