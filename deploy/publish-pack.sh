#!/usr/bin/env bash
# publish-pack.sh — 校验、签名、发布 native pack 到两台官网镜像
# （docs/NATIVE_PACK_DISTRIBUTION.md §3/§7.3）。骨架照抄 publish-lowa-engine.sh。
#
# 在**本机**运行（不是服务器上）：
#
#   bash deploy/publish-pack.sh check   <本地产物目录>
#     只在本地验产物，不碰网络：manifest.json 字段完整、每个组件的实物 sha256/size
#     与 manifest 声明一致、tar 可解、条目防护抽查（无绝对路径/../条目、无符号链接
#     残留——build-pack.js 打包时已 -h 物化过软链，出现说明产物不对劲）。
#
#   bash deploy/publish-pack.sh sign <本地产物目录>
#     ssh 到北京官网机，在服务器上用官网应用 env 里的 AWD_PLUGIN_SIGNING_KEY 对
#     manifest.json 的原始字节做 Ed25519 签名，取回 manifest.json.sig 写进本地
#     产物目录。私钥全程只在服务器内存里过一次：不落本机、不进任何本地/远端日志、
#     远端临时文件签完即删。
#
#   bash deploy/publish-pack.sh publish <本地产物目录> <id> <版本号>
#     先 check，要求 manifest.json.sig 已经存在（没有就提示先跑 sign）。
#     传北京 /www/wwwroot/plugin-packs/<id>/<版本号>/（先落 /root/pack-staging 暂存，
#     逐组件复核 sha256/size 通过才 rename 换入 —— 半截产物不会出现在对外目录）→
#     新加坡用它自己的 sg_to_bj 密钥从北京拉取同步、同样暂存校验后换入 →
#     两台机各自 curl 公网镜像逐组件核对 sha256/size（终验）→ 全部通过才把
#     manifest.json + manifest.json.sig 复制为 <id>/manifest.json（+.sig）—— 这是
#     客户端实际读取的「最新版」指针，指针切换发生在两台机都验证通过之后，
#     且两台机都要切（lowa r4 只传北京、CI 从新加坡拉到 404 的教训，见
#     publish-lowa-engine.sh 顶部注释）。
#
#   bash deploy/publish-pack.sh verify <id> <版本号>
#     独立验证：分别 curl 北京（www.aiworkdeck.com）与新加坡（workdeck.ai）两个公网
#     镜像的 manifest 与各组件 archive，逐一核对 sha256/size。publish 内部也会跑
#     这一步；单独调用用于事后复核或排障。
#
# 例：
#   bash deploy/publish-pack.sh check ~/Downloads/pack-litigation-visual-v1.0.0
#   bash deploy/publish-pack.sh sign ~/Downloads/pack-litigation-visual-v1.0.0
#   bash deploy/publish-pack.sh publish ~/Downloads/pack-litigation-visual-v1.0.0 litigation-visual 1.0.0
#   bash deploy/publish-pack.sh verify litigation-visual 1.0.0

set -euo pipefail

BJ_HOST="${BJ_HOST:-root@8.152.169.44}"
SG_HOST="${SG_HOST:-root@8.219.94.204}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SG_TO_BJ_KEY="${SG_TO_BJ_KEY:-/root/.ssh/sg_to_bj}"      # 存放在**新加坡**机器上
PACKS_ROOT="${PACKS_ROOT:-/www/wwwroot/plugin-packs}"
STAGE_ROOT="${STAGE_ROOT:-/root/pack-staging}"           # web root 之外
# 官网 Next.js 应用的 env 文件（AWD_PLUGIN_SIGNING_KEY 就存在这里，见
# .agent/ACCOUNTS.md「官网服务器密钥」与 OPERATIONS.md 的官网部署路径）。
WEBSITE_ENV_FILE="${WEBSITE_ENV_FILE:-/www/wwwroot/www.aiworkdeck.com/new/.env.local}"
SYNC_TIMEOUT="${SYNC_TIMEOUT:-600}"

BJ_BASE_URL="${BJ_BASE_URL:-https://www.aiworkdeck.com}"
SG_BASE_URL="${SG_BASE_URL:-https://workdeck.ai}"

SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o ConnectTimeout=20"

die() { echo "错误：$*" >&2; exit 1; }
step() { echo; echo "== $*"; }

# macOS 没有 sha256sum（只有 shasum）
if command -v sha256sum >/dev/null 2>&1; then sha256() { sha256sum; }
else sha256() { shasum -a 256; }; fi
sha256_of() { sha256 < "$1" | cut -d' ' -f1; }
filesize() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; }

valid_id() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]{1,49}$ ]] || die "id 不合法（须匹配 ^[a-z0-9][a-z0-9-]{1,49}\$）：$1"
}
valid_version() {
  [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]] || die "版本号只允许 [A-Za-z0-9._-]，收到：$1"
}

usage() {
  cat >&2 <<'EOF'
用法：
  publish-pack.sh check   <本地产物目录>                本地验产物，不碰网络
  publish-pack.sh sign    <本地产物目录>                服务器侧签名，取回 .sig
  publish-pack.sh publish <本地产物目录> <id> <版本号>   推两台镜像并验证、切指针
  publish-pack.sh verify  <id> <版本号>                 只验证两台镜像上的产物
EOF
  exit 2
}

# ---------------------------------------------------------------- check
# manifest 里读出 "<name>\t<archive>\t<size>\t<sha256>"，每行一个组件。
manifest_components_tsv() {
  local manifest="$1"
  # 用 %-格式化而非 f-string：这段 python 源码整体嵌在 bash 单引号字符串里，
  # f-string 表达式里的 c["key"] 会与外层引号打架，%-格式化没有这个问题。
  python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
for c in m["components"]:
    print("%s\t%s\t%s\t%s" % (c["name"], c["archive"], c["size"], c["sha256"]))
' "$manifest"
}

cmd_check() {
  local dir="$1"
  [ -d "$dir" ] || die "目录不存在：$dir"
  local manifest="$dir/manifest.json"
  [ -f "$manifest" ] || die "缺 manifest.json：$manifest"

  step "manifest.json 字段完整性"
  python3 - "$manifest" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
required = ["schema", "id", "version", "publishedAt", "minAppVersion", "engineApi", "components"]
missing = [k for k in required if k not in m]
if missing:
    print("缺字段:", missing); sys.exit(1)
if not isinstance(m["components"], list) or not m["components"]:
    print("components 为空"); sys.exit(1)
for c in m["components"]:
    for k in ("name", "platforms", "archive", "size", "sha256", "unpackDir"):
        if k not in c:
            print("组件缺字段:", c.get("name"), k); sys.exit(1)
print(f"OK，{len(m['components'])} 个组件")
PY

  step "逐组件对账实物（size / sha256）+ tar 完整性 + 条目防护抽查"
  local name archive want_size want_sha archpath got_size got_sha entries
  while IFS=$'\t' read -r name archive want_size want_sha; do
    [ -n "$name" ] || continue
    archpath="$dir/$archive"
    [ -f "$archpath" ] || die "组件 $name 的产物缺失：$archpath"

    got_size=$(filesize "$archpath")
    [ "$got_size" = "$want_size" ] || die "组件 $name 体积不符：manifest=$want_size 实物=$got_size"
    got_sha=$(sha256_of "$archpath")
    [ "$got_sha" = "$want_sha" ] || die "组件 $name sha256 不符：manifest=$want_sha 实物=$got_sha"

    entries=$(tar tzf "$archpath" 2>/dev/null) || die "组件 $name 的 tar 无法解出条目列表：$archive"
    if grep -qE '^/|(^|/)\.\./' <<< "$entries"; then
      die "组件 $name 的 tar 里有绝对路径或 .. 条目，疑似 zip-slip：$archive"
    fi
    if tar tvzf "$archpath" 2>/dev/null | awk '{print substr($1,1,1)}' | grep -q '^l$'; then
      die "组件 $name 的 tar 里有符号链接条目（build-pack.js 打包时应已 -h 物化）：$archive"
    fi
    echo "  $name  $archive  ${got_size} bytes  sha256 OK  tar 条目 $(wc -l <<< "$entries" | tr -d ' ') 个"
  done < <(manifest_components_tsv "$manifest")

  echo; echo "本地校验通过：$dir"
}

# ---------------------------------------------------------------- sign
cmd_sign() {
  local dir="$1"
  local manifest="$dir/manifest.json"
  [ -f "$manifest" ] || die "缺 manifest.json：$manifest"

  local remote_tmp="/root/pack-sign-$$"
  step "上传 manifest.json 到服务器临时目录（不进 web root）"
  $SSH "$BJ_HOST" "mkdir -p '$remote_tmp'"
  scp -i "$SSH_KEY" -o IdentitiesOnly=yes -q "$manifest" "$BJ_HOST:$remote_tmp/manifest.json"

  step "服务器侧 Ed25519 签名（私钥只在服务器内存里过一次，不落本机、不进日志）"
  local sig
  set +e
  sig=$($SSH "$BJ_HOST" "bash -s -- '$remote_tmp' '$WEBSITE_ENV_FILE'" <<'REMOTE'
set -euo pipefail
remote_tmp="$1"; env_file="$2"
[ -f "$env_file" ] || { echo "官网 env 文件不存在：$env_file" >&2; exit 1; }
# 私钥绝不经过 shell 变量/argv（多行 PEM 会被拆参、还可能进 ps 输出）：
# node 自己读 env 文件，跨行解析三种常见形态——双引号多行块 / 单引号多行块 /
# 单行 \n 转义。北京官网机实测是「双引号 + 真换行 PEM」（2026-08-19）。
node -e '
  const crypto = require("crypto");
  const fs = require("fs");
  const envText = fs.readFileSync(process.argv[1], "utf8");
  const m = envText.match(/^AWD_PLUGIN_SIGNING_KEY=("([\s\S]*?)"|\x27([\s\S]*?)\x27|(.*))$/m);
  if (!m) { console.error("env 文件里没有 AWD_PLUGIN_SIGNING_KEY"); process.exit(1); }
  const raw = m[2] !== undefined ? m[2] : (m[3] !== undefined ? m[3] : m[4]);
  const keyPem = raw.replace(/\\n/g, "\n").trim();
  const privateKey = crypto.createPrivateKey(keyPem);
  const bytes = fs.readFileSync(process.argv[2]);
  const sig = crypto.sign(null, bytes, privateKey);
  process.stdout.write(sig.toString("base64"));
' "$env_file" "$remote_tmp/manifest.json"
REMOTE
)
  local rc=$?
  set -e
  # 无论成败都先清掉远端临时文件（manifest 本身不是秘密，但不该留垃圾）
  $SSH "$BJ_HOST" "rm -rf '$remote_tmp'" || true
  [ $rc -eq 0 ] || die "服务器侧签名失败（见上方远端报错）"
  [ -n "$sig" ] || die "签名结果为空"

  printf '%s' "$sig" > "$dir/manifest.json.sig"
  echo "已写入：$dir/manifest.json.sig"
}

# ---------------------------------------------------------------- publish
# 换入前在暂存区逐组件复核 size/sha256，通过才把 staging 目录 rename 进正式
# 版本目录（同文件系统，原子）；旧同名版本目录 rename 进 .replaced.<ts> 备份，
# 不直接覆盖（可回滚）。
swap_in_pack() {
  local ssh_target="$1" id="$2" version="$3"
  $SSH "$ssh_target" "bash -s -- '$id' '$version' '$PACKS_ROOT' '$STAGE_ROOT'" <<'REMOTE'
set -euo pipefail
id="$1"; version="$2"; packs_root="$3"; stage_root="$4"
src="${stage_root}/${id}/${version}"
dst="${packs_root}/${id}/${version}"
[ -d "$src" ] || { echo "暂存目录不存在：$src" >&2; exit 1; }
[ -f "$src/manifest.json" ] || { echo "暂存目录缺 manifest.json：$src" >&2; exit 1; }
python3 - "$src" <<'PY'
import hashlib, json, os, sys
src = sys.argv[1]
m = json.load(open(os.path.join(src, "manifest.json")))
for c in m["components"]:
    p = os.path.join(src, c["archive"])
    if not os.path.isfile(p):
        print("缺产物:", c["archive"]); sys.exit(1)
    if os.path.getsize(p) != c["size"]:
        print("体积不符:", c["archive"]); sys.exit(1)
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != c["sha256"]:
        print("sha256 不符:", c["archive"]); sys.exit(1)
print("暂存区校验通过，共", len(m["components"]), "个组件")
PY
mkdir -p "$(dirname "$dst")"
if [ -d "$dst" ]; then
  mv "$dst" "${dst}.replaced.$(date +%Y%m%d-%H%M%S)"
fi
mv "$src" "$dst"
chmod 755 "$dst"
find "$dst" -maxdepth 1 -type f -exec chmod 644 {} +
echo "已换入：$dst"
REMOTE
}

# 新加坡侧：用它自己的 sg_to_bj 密钥从北京（此时已换入正式目录）拉取，带重试。
sg_pull_pack() {
  local id="$1" version="$2"
  $SSH "$SG_HOST" "bash -s -- '$id' '$version' '$BJ_HOST' '$SG_TO_BJ_KEY' '$PACKS_ROOT' '$STAGE_ROOT' '$SYNC_TIMEOUT'" <<'REMOTE'
set -euo pipefail
id="$1"; version="$2"; bj_host="$3"; key="$4"; packs_root="$5"; stage_root="$6"; timeout="$7"
dst_stage="${stage_root}/${id}/${version}"
rm -rf "$dst_stage"
mkdir -p "$dst_stage"
rc=1
for i in 1 2 3; do
  if rsync -a --partial --timeout="$timeout" \
      -e "ssh -i ${key} -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o ServerAliveInterval=15" \
      "${bj_host}:${packs_root}/${id}/${version}/" "${dst_stage}/"; then
    rc=0; break
  fi
  echo "第 $i 次 rsync 失败，10 秒后重试" >&2
  sleep 10
done
[ $rc -eq 0 ] || { echo "新加坡拉取北京失败" >&2; exit 1; }
REMOTE
  swap_in_pack "$SG_HOST" "$id" "$version"
}

# 单个镜像的公网终验：curl manifest，逐组件核对远端 Content-Length 与内容 sha256。
verify_mirror() {
  local base="$1" id="$2" version="$3"
  local manifest_url="$base/plugin-packs/$id/$version/manifest.json"
  local body
  body=$(curl -sf "$manifest_url") || { echo "  manifest 不可达：$manifest_url"; return 1; }
  local lines
  lines=$(python3 -c '
import json, sys
m = json.loads(sys.argv[1])
for c in m["components"]:
    print("%s\t%s\t%s" % (c["archive"], c["size"], c["sha256"]))
' "$body") || { echo "  manifest 不是合法 JSON：$manifest_url"; return 1; }

  local ok=0 archive size sha url got_size got_sha
  while IFS=$'\t' read -r archive size sha; do
    [ -n "$archive" ] || continue
    url="$base/plugin-packs/$id/$version/$archive"
    got_size=$(curl -sI "$url" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-length"{print $2}' | tail -1)
    if [ "$got_size" != "$size" ]; then
      echo "  $archive 体积不符：manifest=$size 实得=$got_size"
      ok=1
      continue
    fi
    got_sha=$(curl -s "$url" | sha256 | cut -d' ' -f1)
    if [ "$got_sha" != "$sha" ]; then
      echo "  $archive sha256 不符"
      ok=1
      continue
    fi
    echo "  $archive OK（${got_size} bytes，sha256 ${got_sha:0:16}…）"
  done <<< "$lines"
  return $ok
}

cmd_verify() {
  local id="$1" version="$2"
  valid_id "$id"; valid_version "$version"
  local rc=0
  step "验证北京（$BJ_BASE_URL）"
  verify_mirror "$BJ_BASE_URL" "$id" "$version" || rc=1
  step "验证新加坡（$SG_BASE_URL）"
  verify_mirror "$SG_BASE_URL" "$id" "$version" || rc=1
  echo
  if [ $rc -eq 0 ]; then
    echo "两站校验通过：$id@$version"
  else
    echo "校验未通过：$id@$version" >&2
  fi
  return $rc
}

# 指针切换：把版本目录里的 manifest.json(+.sig) 原子复制为 <id>/manifest.json(.sig)，
# 客户端与安装脚本读的正是这个「最新版」指针（no-cache，见 nginx 配置）。
switch_pointer() {
  local ssh_target="$1" id="$2" version="$3"
  $SSH "$ssh_target" "bash -s -- '$id' '$version' '$PACKS_ROOT'" <<'REMOTE'
set -euo pipefail
id="$1"; version="$2"; packs_root="$3"
src="${packs_root}/${id}/${version}"
for f in manifest.json manifest.json.sig; do
  [ -f "$src/$f" ] || { echo "版本目录缺 $f，无法切指针：$src" >&2; exit 1; }
  tmp="${packs_root}/${id}/.${f}.tmp"
  cp "$src/$f" "$tmp"
  mv "$tmp" "${packs_root}/${id}/${f}"
done
chmod 644 "${packs_root}/${id}"/manifest.json "${packs_root}/${id}"/manifest.json.sig
echo "指针已切换到 ${version}：${packs_root}/${id}/manifest.json"
REMOTE
}

cmd_publish() {
  local dir="$1" id="$2" version="$3"
  valid_id "$id"; valid_version "$version"

  cmd_check "$dir"
  [ -f "$dir/manifest.json.sig" ] || die "缺 manifest.json.sig，先跑：bash $0 sign $dir"

  step "上传到北京暂存区 ${STAGE_ROOT}/${id}/${version}/（web root 之外）"
  $SSH "$BJ_HOST" "mkdir -p '$STAGE_ROOT/$id/$version'"
  rsync -a --partial --partial-dir=/root/.pack-partial --timeout="$SYNC_TIMEOUT" -e "$SSH" \
    "$dir"/ "$BJ_HOST:$STAGE_ROOT/$id/$version/"

  step "北京：校验暂存区并换入正式版本目录（immutable）"
  swap_in_pack "$BJ_HOST" "$id" "$version"

  step "新加坡：用 sg_to_bj 拉取同步并换入"
  sg_pull_pack "$id" "$version"

  step "双机公网终验（manifest + 各组件 sha256）"
  local rc=0
  verify_mirror "$BJ_BASE_URL" "$id" "$version" || rc=1
  verify_mirror "$SG_BASE_URL" "$id" "$version" || rc=1
  [ $rc -eq 0 ] || die "双机终验未通过，不切换 <id>/manifest.json 指针（版本目录已就位，可重试 verify 排障）"

  step "切指针：两台机都把该版本设为 <id>/manifest.json（no-cache）"
  switch_pointer "$BJ_HOST" "$id" "$version"
  switch_pointer "$SG_HOST" "$id" "$version"

  echo; echo "发布完成：$id@$version"
}

# ---------------------------------------------------------------- main
[ $# -ge 1 ] || usage
case "${1:-}" in
  check)   [ $# -eq 2 ] || usage; cmd_check "$2";;
  sign)    [ $# -eq 2 ] || usage; cmd_sign "$2";;
  publish) [ $# -eq 4 ] || usage; cmd_publish "$2" "$3" "$4";;
  verify)  [ $# -eq 3 ] || usage; cmd_verify "$2" "$3";;
  *) usage;;
esac
