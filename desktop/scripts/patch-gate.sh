#!/usr/bin/env bash
# patch-gate.sh — 小版本发布纪律的机器强制（增量更新设计 §2.3）。
#
# tag v0.X.Y（Y>0）时校验：与同大版本上一个 tag 相比，
#   1. desktop/ 无改动（仅允许 desktop/package.json 的 version 行）——
#      Electron 壳在 mac 签名密封内，补丁到不了用户手里；
#   2. backend 任何 pom.xml 无改动——补丁只发业务 jar，依赖 lib/ 留在安装包；
#   3. LOWA 引擎来源（desktop-build.yml 的 LOWA_BASE_URL / fetch-lowa-assets.js）无改动。
# 违反任一条 → 构建失败，提示改发大版本 0.(X+1).0。
#
# Usage: patch-gate.sh <tag>   例: patch-gate.sh v0.11.2
set -euo pipefail

TAG="${1:?usage: patch-gate.sh <tag>}"
VER="${TAG#v}"

if ! [[ "$VER" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "[patch-gate] 非标准版本 tag（$TAG），跳过守门"
  exit 0
fi
X="${BASH_REMATCH[2]}"
Y="${BASH_REMATCH[3]}"
MAJOR="${BASH_REMATCH[1]}.${X}"

if [ "$Y" -eq 0 ]; then
  echo "[patch-gate] $TAG 是大版本首发（Y=0），全量发布不设限"
  exit 0
fi

# 同大版本上一个 tag：优先 v0.X.(Y-1)，缺失则取该大版本内比当前小的最大 tag
PREV="v${MAJOR}.$((Y - 1))"
if ! git rev-parse -q --verify "refs/tags/$PREV" >/dev/null; then
  PREV=$(git tag -l "v${MAJOR}.*" | sort -V | awk -v cur="$TAG" '$0 < cur' | tail -1 || true)
fi
if [ -z "${PREV:-}" ]; then
  echo "[patch-gate] 警告：找不到 v${MAJOR}.* 的上一个 tag，无法对比，放行（首个可对比版本从下个小版本开始生效）"
  exit 0
fi
echo "[patch-gate] 对比 $PREV..$TAG"

violations=()

# 1) 进包的 Electron 壳改动。只看真正进 .app 的路径——package.json 的 files
# 字段是 main/** 与 preload/**，加上 build/（entitlements 与图标，进签名与安装器）。
# desktop/scripts/ 与 desktop/tests/ 是 CI 侧产物，不进包：改它们只影响下一个
# 全量安装包的构建方式，对存量用户的 .app 是 no-op，故豁免（否则连"修补丁打包
# 脚本"这种纯 CI 修复都会逼出一个大版本）。
desktop_changed=$(git diff --name-only "$PREV..$TAG" -- desktop/main/ desktop/preload/ desktop/build/ || true)
if [ -n "$desktop_changed" ]; then
  violations+=("进包的 Electron 壳有改动（mac 签名密封，补丁到不了用户手里）：$(echo "$desktop_changed" | head -5 | tr '\n' ' ')")
fi
pkg_diff=$(git diff "$PREV..$TAG" -- desktop/package.json | grep -E '^[+-]' | grep -vE '^\+\+\+|^---' | grep -v '"version"' || true)
if [ -n "$pkg_diff" ]; then
  violations+=("desktop/package.json 有 version 以外的改动：$(echo "$pkg_diff" | head -3 | tr '\n' ' ')")
fi

# 2) 后端依赖（pom.xml）
pom_changed=$(git diff --name-only "$PREV..$TAG" -- 'backend/pom.xml' 'backend/**/pom.xml' || true)
if [ -n "$pom_changed" ]; then
  violations+=("backend pom.xml 有改动（依赖 lib/ 只随大版本走）：$pom_changed")
fi

# 3) LOWA 引擎来源
engine_diff=$(git diff "$PREV..$TAG" -- .github/workflows/desktop-build.yml | grep -E '^[+-].*LOWA_BASE_URL' || true)
fetch_changed=$(git diff --name-only "$PREV..$TAG" -- desktop/scripts/fetch-lowa-assets.js || true)
if [ -n "$engine_diff" ] || [ -n "$fetch_changed" ]; then
  violations+=("LOWA 引擎来源有改动（引擎只随大版本走）")
fi

# 4) Python 服务依赖（requirements.lock）——pysvc-src 补丁只发源码，pip 依赖随大版本
req_changed=$(git diff --name-only "$PREV..$TAG" -- '*requirements.lock' || true)
if [ -n "$req_changed" ]; then
  violations+=("requirements.lock 有改动（Python 依赖只随大版本走）：$req_changed")
fi

if [ ${#violations[@]} -gt 0 ]; then
  echo ""
  echo "[patch-gate] 小版本 $TAG 含越界变更，拒绝发布："
  for v in "${violations[@]}"; do echo "  - $v"; done
  echo ""
  echo "  处理：删 tag（git push origin :refs/tags/$TAG），把版本号升为大版本 0.$((X + 1)).0 后重新打 tag。"
  echo "  依据：docs/INCREMENTAL_UPDATE_DESIGN.md §2.2（补丁只含 backend-app / frontend-h5 / zetaoffice-wrapper / pysvc-src）。"
  exit 1
fi

echo "[patch-gate] 通过：$TAG 的变更全部在补丁边界内"
