#!/usr/bin/env bash
#
# banana-slides (pptx-service) 契约兼容性冒烟测试
# ---------------------------------------------------------------------------
# 目的：升级/重新 vendor banana-slides（如 0.1 → 0.4）后，验证本项目 PptxServiceClient
#       依赖的 HTTP 接口契约是否仍然成立——只验"输入/输出接口"，不读三方源码。
#
# 前提：pptx-service 已在运行（默认 http://localhost:5001）。
#   启动方式：cd pptx-service && docker compose up -d    （或桌面打包内置）
#
# 检查项（我方 PptxServiceClient 实际调用的端点）：
#   GET  /health
#   POST /api/projects                              （返回 data.project_id）
#   POST /api/projects/{id}/generate/outline
#   POST /api/projects/{id}/generate/descriptions
#   POST /api/projects/{id}/generate/images
#   GET  /api/projects/{id}/tasks/{taskId}
#   GET  /api/projects/{id}/export/pptx
#   POST /api/projects/{id}/export/editable-pptx
#   GET  /api/projects/{id}
#
# 判定：某端点返回 404 = 路由被删/改名（契约破坏，FAIL）；非 404 = 路由存在（契约在，PASS）。
#       生成/导出类端点在无 LLM/图像 key 时会 4xx/5xx，属正常——本脚本只验"端点在不在 + 关键字段"。
#       要端到端真跑，配好 pptx-service/.env 的模型 key 后用 RUN_FULL=1。
#
# 用法：
#   BASE=http://localhost:5001 bash compat_smoke_test.sh
#   RUN_FULL=1 bash compat_smoke_test.sh          # 额外尝试真实 create+outline（需 key）
#
# 退出码：0=契约全部保留；非 0=有端点缺失/契约变更。
# ---------------------------------------------------------------------------
set -uo pipefail

BASE="${BASE:-http://localhost:5001}"
FAIL=0
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

ok()  { printf '\033[32m  PASS\033[0m %s\n' "$*"; }
bad() { printf '\033[31m  FAIL\033[0m %s\n' "$*"; FAIL=1; }
log() { printf '\033[1m[compat]\033[0m %s\n' "$*"; }

# code <METHOD> <path> [json-body]
code() {
  local m="$1" p="$2" b="${3:-}"
  if [ -n "$b" ]; then
    curl -s -o "$TMP/body" -w '%{http_code}' -X "$m" "$BASE$p" \
         -H 'Content-Type: application/json' -d "$b" 2>/dev/null
  else
    curl -s -o "$TMP/body" -w '%{http_code}' -X "$m" "$BASE$p" 2>/dev/null
  fi
}

# exists <name> <METHOD> <path> [body]  —— 非 404（且非空/连不上）即视为端点存在
exists() {
  local name="$1" m="$2" p="$3" b="${4:-}"
  local c; c="$(code "$m" "$p" "$b")"
  if [ -z "$c" ] || [ "$c" = "000" ]; then bad "$name：连不上 $BASE（服务没起？）"; return; fi
  if [ "$c" = "404" ]; then bad "$name：$m $p 返回 404——路由已删/改名，PptxServiceClient 需适配"; else ok "$name：$m $p 存在（HTTP $c）"; fi
}

log "目标服务：$BASE"

# 1) health
c="$(code GET /health)"
if [ "$c" = "200" ]; then ok "GET /health = 200"; else bad "GET /health = ${c:-连不上}（服务未就绪？先 docker compose up -d）"; fi

# 2) create project —— 尽量拿到真实 project_id，并校验 data.project_id 字段契约
PID=""
c="$(code POST /api/projects '{"creation_type":"idea","idea_prompt":"compat smoke test 主题"}')"
if [ "$c" = "200" ] || [ "$c" = "201" ]; then
  PID="$(python3 -c "import json,sys;print(json.load(open('$TMP/body')).get('data',{}).get('project_id',''))" 2>/dev/null)"
  if [ -n "$PID" ]; then ok "POST /api/projects = $c，且 data.project_id 契约在（id=$PID）"; else bad "POST /api/projects = $c 但响应无 data.project_id——响应结构变更"; fi
elif [ "$c" = "404" ]; then bad "POST /api/projects = 404——创建端点缺失"
else ok "POST /api/projects 存在（HTTP $c，可能需 key 才能真建）"; fi

# 用真实 id（拿不到就用占位，仅验路由存在性）
ID="${PID:-__compat_probe__}"

# 3)-9) 其余端点存在性
exists "生成大纲"        POST "/api/projects/$ID/generate/outline"       '{"language":"zh"}'
exists "生成描述"        POST "/api/projects/$ID/generate/descriptions"  '{"language":"zh","max_workers":1}'
exists "生成图片"        POST "/api/projects/$ID/generate/images"        '{"language":"zh","max_workers":1,"use_template":true}'
exists "任务状态"        GET  "/api/projects/$ID/tasks/__probe__"
exists "导出PPTX"        GET  "/api/projects/$ID/export/pptx"
exists "导出可编辑PPTX"  POST "/api/projects/$ID/export/editable-pptx"   '{}'
exists "项目详情"        GET  "/api/projects/$ID"

# L2（可选）：真实 create+outline（需模型 key）
if [ "${RUN_FULL:-0}" = "1" ] && [ -n "$PID" ]; then
  log "RUN_FULL=1：尝试真实生成大纲（需 pptx-service/.env 配好模型 key）..."
  c="$(code POST "/api/projects/$PID/generate/outline" '{"language":"zh"}')"
  if [ "$c" = "200" ]; then
    n="$(python3 -c "import json;d=json.load(open('$TMP/body')).get('data',{});print(len(d.get('pages',[])))" 2>/dev/null)"
    if [ -n "$n" ] && [ "$n" -gt 0 ] 2>/dev/null; then ok "真实大纲生成成功，data.pages 契约在（$n 页）"; else bad "大纲返回 200 但 data.pages 结构变化，请核对 $TMP/body"; fi
  else bad "真实大纲生成 HTTP $c（key 未配或契约变更），响应首 300 字："; head -c 300 "$TMP/body"; echo; fi
fi

echo
if [ "$FAIL" = "0" ]; then
  log "结论：PptxServiceClient 依赖的接口契约全部保留——可继续（配 key 后 RUN_FULL 端到端）再合并升级。"
else
  log "结论：有端点缺失/契约变更（见 FAIL）——需同步改 backend/.../PptxServiceClient.java 后再合并。"
fi
exit $FAIL
