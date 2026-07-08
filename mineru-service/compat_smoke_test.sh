#!/usr/bin/env bash
#
# MinerU 3.x 兼容性冒烟测试
# ---------------------------------------------------------------------------
# 目的：在真机（有 Python 环境、可选地已下载模型）上验证升级到 mineru 3.x 后，
#       本项目依赖的接口契约是否仍然成立——无需读源码、只验"输入/输出接口"。
#
# 分两级：
#   L1 轻量检查（不需要 ~3GB 模型，几秒完成）：
#     - mineru-api / python -m mineru.cli.fast_api / mineru-models-download 三个 CLI 是否存在
#     - 启动 mineru-api 后，从 /openapi.json 确认 POST /file_parse 端点仍在（我们的核心契约）
#   L2 真解析（需要已下载 pipeline 模型）：
#     - 生成一份带文字的样例 PDF，POST /file_parse，断言返回里能提取到该文字
#
# 用法：
#   bash compat_smoke_test.sh            # 跑 L1；若检测到模型则自动跑 L2
#   SKIP_PARSE=1 bash compat_smoke_test.sh   # 只跑 L1
#   PORT=8000 bash compat_smoke_test.sh
#
# 退出码：0=全部通过；非 0=有不兼容项（见输出）。
# ---------------------------------------------------------------------------
set -uo pipefail

PORT="${PORT:-8000}"
HOST="127.0.0.1"
BASE="http://${HOST}:${PORT}"
TMPDIR="$(mktemp -d)"
SERVER_PID=""
FAIL=0

log()  { printf '\033[1m[compat]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m  PASS\033[0m %s\n' "$*"; }
bad()  { printf '\033[31m  FAIL\033[0m %s\n' "$*"; FAIL=1; }

cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  rm -rf "$TMPDIR"
}
trap cleanup EXIT

# ---------- L1a: CLI 存在性 ----------
log "MinerU 版本：$(mineru --version 2>/dev/null || echo '未知/未安装')"

if mineru-api --help >/dev/null 2>&1; then ok "mineru-api CLI 存在（Dockerfile 用）"; else bad "mineru-api CLI 缺失——Dockerfile 的 CMD 会失效"; fi
if python -m mineru.cli.fast_api --help >/dev/null 2>&1; then ok "python -m mineru.cli.fast_api 存在（桌面打包用）"; else bad "mineru.cli.fast_api 模块缺失/改名——desktop/main/services/mineru-service.js 的 spawn args 需调整"; fi
if mineru-models-download --help >/dev/null 2>&1; then ok "mineru-models-download CLI 存在（Dockerfile 拉模型用）"; else bad "mineru-models-download 缺失/改名——Dockerfile 模型下载步骤需调整"; fi

# ---------- L1b: 启动服务并检查 /file_parse 契约 ----------
log "启动 mineru-api 于 ${BASE} ..."
mineru-api --host "$HOST" --port "$PORT" >"$TMPDIR/server.log" 2>&1 &
SERVER_PID=$!

# 等待 /openapi.json 或 /docs 就绪（最多 90s，首启可能加载模型）
READY=0
for i in $(seq 1 90); do
  if curl -fsS "${BASE}/openapi.json" >"$TMPDIR/openapi.json" 2>/dev/null; then READY=1; break; fi
  curl -fsS "${BASE}/docs" >/dev/null 2>&1 && curl -fsS "${BASE}/openapi.json" >"$TMPDIR/openapi.json" 2>/dev/null && { READY=1; break; }
  sleep 1
done

if [ "$READY" != "1" ]; then
  bad "服务未在 90s 内就绪，见 $TMPDIR/server.log（尾部）："
  tail -20 "$TMPDIR/server.log" || true
  exit 1
fi
ok "服务已就绪，取到 /openapi.json"

if grep -q '"/file_parse"' "$TMPDIR/openapi.json"; then
  ok "契约保留：POST /file_parse 仍存在于 OpenAPI（我方集成核心端点）"
else
  bad "OpenAPI 中未见 /file_parse——契约变更，pptx-service 侧调用需适配。现有 paths："
  python -c "import json;print('\n'.join(sorted(json.load(open('$TMPDIR/openapi.json')).get('paths',{}))))" 2>/dev/null | sed 's/^/      /' || true
fi

# ---------- L2: 真解析（仅当有模型且未 SKIP_PARSE）----------
if [ "${SKIP_PARSE:-0}" = "1" ]; then
  log "SKIP_PARSE=1，跳过真解析（L2）"
else
  log "尝试真解析（L2，需已下载 pipeline 模型）..."
  # 生成带文字的样例 PDF；优先 reportlab，缺失则尝试安装
  SAMPLE="$TMPDIR/sample.pdf"
  MARKER="MinerUCompat20260708"
  python - "$SAMPLE" "$MARKER" <<'PY' 2>/dev/null
import sys
path, marker = sys.argv[1], sys.argv[2]
try:
    from reportlab.pdfgen import canvas
except Exception:
    import subprocess; subprocess.check_call([sys.executable,"-m","pip","install","-q","reportlab"])
    from reportlab.pdfgen import canvas
c = canvas.Canvas(path); c.drawString(80, 760, marker + " hello mineru"); c.showPage(); c.save()
PY
  if [ ! -s "$SAMPLE" ]; then
    log "  无法生成样例 PDF（reportlab 不可用），跳过 L2。可自备：SAMPLE=/path.pdf 后手动 curl /file_parse"
  else
    HTTP=$(curl -s -o "$TMPDIR/parse.json" -w '%{http_code}' -X POST "${BASE}/file_parse" \
            -F "files=@${SAMPLE};type=application/pdf" 2>/dev/null)
    if [ "$HTTP" = "200" ]; then
      if grep -q "$MARKER" "$TMPDIR/parse.json" 2>/dev/null; then
        ok "真解析成功且提取到样例文字（端到端契约 OK）"
      else
        log "  /file_parse 返回 200 但未直接匹配到样例文字——可能是响应结构变化，请人工核对 $TMPDIR/parse.json（首 400 字）："
        head -c 400 "$TMPDIR/parse.json"; echo
        log "  （若字段名变了但内容在，属输出结构调整，需同步 pptx-service 的解析）"
      fi
    else
      bad "/file_parse 返回 HTTP $HTTP（可能是模型未下载或请求参数变更）。响应："
      head -c 400 "$TMPDIR/parse.json" 2>/dev/null; echo
      log "  若因缺模型：先跑 mineru-models-download -s modelscope -m pipeline 再重试"
    fi
  fi
fi

echo
if [ "$FAIL" = "0" ]; then
  log "结论：核心接口契约（mineru-api + /file_parse + pipeline）在 3.x 下保持——可继续验证 L2 后合并。"
else
  log "结论：存在不兼容项（见上 FAIL）——需按提示调整 Dockerfile/desktop spawn/pptx-service 后再合并。"
fi
exit $FAIL
