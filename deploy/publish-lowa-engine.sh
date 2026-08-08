#!/usr/bin/env bash
# publish-lowa-engine.sh — 把新构建的 LOWA 引擎发布到官网托管的两台服务器，并验证。
# 在**本机**运行（不是服务器上）：
#
#   bash deploy/publish-lowa-engine.sh check-build <本地引擎目录>          # 只在本地验产物，不碰服务器
#   bash deploy/publish-lowa-engine.sh publish     <本地引擎目录> <版本号>
#   bash deploy/publish-lowa-engine.sh verify      <版本号>
#
#   例：bash deploy/publish-lowa-engine.sh publish ~/lowa-build/out 24.2.8-zhcn-r5
#
# 为什么要有这个脚本：引擎换版是「改 workflow 一行 + 把产物按正确形态传到两台机」的组合动作，
# 而两个失败面**都不会在上线当时报错**，只在下一次 CI 打包时爆炸，且症状完全不同——
# 2026-08-08 的 r4 两个坑都踩了（issue #310）：
#
#   1) 形态传错：nginx 的 `location ~ ^/lowa-engine/.*\.(wasm|data)$` 对这两类文件**无条件**
#      add_header Content-Encoding: br，所以磁盘上必须是 brotli 字节；soffice.js 与
#      soffice.data.js.metadata 必须是原始字节。传错 -> 客户端拿 br 头解裸 wasm -> CI 挂。
#   2) 漏同步海外镜像：lowa-engine 在新加坡是**本地镜像直出、不回源北京**，GitHub CI 从境外
#      解析走的正是新加坡。只传北京的话，境内 curl 全 200 而 CI 是 404，极易误判成已修好。
#
# 设计要点（都是踩过的坑换来的）：
#   - 落盘先落 web root 之外的暂存区，校验通过再 rename 换入。`location ^~ /lowa-engine/`
#     覆盖整个目录，半截文件留在里面就是对外可下载的坏引擎。
#   - 换入前把同名旧版本 rename 进备份目录，可回滚。
#   - 校验不依赖「自己算的哈希对自己」这种闭环：wasm 断言解压后是 \0asm 魔数，
#     data 断言解压后字节数等于 metadata 里的 remote_package_size（emscripten 自带的真值），
#     这样截断、双重压缩、文件配错都能被抓住。
#   - 远端命令一律走「引号化 heredoc + 位置参数」，不做本地插值，杜绝嵌套引号踩空。
#
# 相关：desktop/lowa-build/mega-build.sh（产出引擎）、
#       .github/workflows/desktop-build.yml 的 LOWA_BASE_URL（版本指针，唯一一处）。

set -euo pipefail

BJ_HOST="${BJ_HOST:-root@8.152.169.44}"       # 必须是新加坡也能解析到的地址（SG 侧 rsync 要用）
SG_HOST="${SG_HOST:-root@8.219.94.204}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SG_TO_BJ_KEY="${SG_TO_BJ_KEY:-/root/.ssh/sg_to_bj}"  # 存放在**新加坡**机器上
ENGINE_ROOT="${ENGINE_ROOT:-/www/wwwroot/lowa-engine}"
STAGE_ROOT="${STAGE_ROOT:-/root/lowa-stage}"          # web root 之外
BACKUP_ROOT="${BACKUP_ROOT:-/root/lowa-engine-backup}"
PUBLIC_HOST="${PUBLIC_HOST:-www.aiworkdeck.com}"
SYNC_TIMEOUT="${SYNC_TIMEOUT:-1800}"

BR_FILES=(soffice.wasm soffice.data)              # 磁盘上必须是 brotli 字节
RAW_FILES=(soffice.js soffice.data.js.metadata)   # 磁盘上必须是原始字节

SSH="ssh -i $SSH_KEY -o IdentitiesOnly=yes -o ConnectTimeout=20"

# EXIT trap 必须能看到 stage：写成函数内的 local 会在成功返回后变成未定义，
# set -u 下 trap 触发时报 unbound variable、退出码变 1、暂存目录还泄漏（实测必现）。
stage=""
trap 'rm -rf "${stage:-}"' EXIT

die() { echo "错误：$*" >&2; exit 1; }
step() { echo; echo "== $*"; }

# macOS 没有 sha256sum（只有 shasum）；xxd 也不保证存在，统一用 od
if command -v sha256sum >/dev/null 2>&1; then sha256() { sha256sum; }
else sha256() { shasum -a 256; }; fi
sha256_of() { sha256 < "$1" | cut -d' ' -f1; }
filesize() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; }
magic4() { od -An -tx1 -N4 < "$1" | tr -d ' \n'; }

WASM_MAGIC=0061736d   # "\0asm"，与 fetch-lowa-assets.js 的 checkMagic 同一判据

# 版本号会被插进远端的 rsync / mv / find 目标路径，必须先收窄字符集
valid_version() {
  [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]] || die "版本号只允许 [A-Za-z0-9._-]，收到：$1"
}

usage() {
  cat >&2 <<'EOF'
用法：
  publish-lowa-engine.sh check-build <本地引擎目录>          仅本地校验产物，不连服务器
  publish-lowa-engine.sh publish     <本地引擎目录> <版本号>  发布到两台机并验证
  publish-lowa-engine.sh verify      <版本号>                只验证（切 LOWA_BASE_URL 前必跑）

版本号形如 24.2.8-zhcn-r5，对应 https://www.aiworkdeck.com/lowa-engine/<版本号>/
EOF
  exit 2
}

# ---------------------------------------------------------------- 本地产物准备
# 把本地引擎目录整理成「可直接上传的形态」并做**内容级**自检。不碰任何服务器。
prepare_stage() {
  local src="$1" out="$2"
  [ -d "$src" ] || die "本地引擎目录不存在：$src"
  command -v brotli >/dev/null || die "缺 brotli（macOS: brew install brotli）"
  command -v python3 >/dev/null || die "缺 python3（用于读 metadata 的 remote_package_size）"
  local f
  for f in "${BR_FILES[@]}" "${RAW_FILES[@]}"; do
    [ -f "$src/$f" ] || die "本地目录缺文件：$f"
  done

  # emscripten 自带的真值：data 解压后应当恰好这么大。它是本脚本最强的一条判据，
  # 截断 / 双重压缩 / 拿错文件都会在这里现形（下游 fetch-lowa-assets.js 对 data
  # 只查「长度 >= 1024」，兜不住）。
  local want_data_size
  want_data_size=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["remote_package_size"])' \
    "$src/soffice.data.js.metadata" 2>/dev/null) \
    || die "soffice.data.js.metadata 不是合法 JSON 或缺 remote_package_size"

  step "准备产物（判形态：裸字节才压，已是 brotli 的原样带走）"
  : > "$out/sha256.txt"
  for f in "${BR_FILES[@]}"; do
    local m; m=$(magic4 "$src/$f")
    if [ "$f" = soffice.wasm ] && [ "$m" = "$WASM_MAGIC" ]; then
      echo -n "  $f 裸 WASM，压缩中…"
      brotli -q 5 -f -o "$out/$f" "$src/$f"
      echo " $(filesize "$src/$f") -> $(filesize "$out/$f")"
    elif brotli -t "$src/$f" 2>/dev/null; then
      echo "  $f 已是完整 brotli，原样使用"
      cp "$src/$f" "$out/$f"
    elif [ "$f" = soffice.data ] && [ "$(filesize "$src/$f")" = "$want_data_size" ]; then
      # data 没有魔数，靠 metadata 的真值确认它是「完整的原始 data」才压
      echo -n "  $f 原始字节（大小与 metadata 相符），压缩中…"
      brotli -q 5 -f -o "$out/$f" "$src/$f"
      echo " $(filesize "$src/$f") -> $(filesize "$out/$f")"
    else
      # 既不是可识别的裸字节，也不是完整 brotli。此时若当成裸字节再压一层，会得到
      # 「双重压缩」：本地自检与线上校验和都能对上（因为基线也是它自己），
      # 但引擎到用户机器上才炸。必须在这里拦死，并说清到底是哪种不对。
      local sz; sz=$(filesize "$src/$f")
      if [ "$f" = soffice.data ]; then
        die "$f 形态无法确认：brotli -t 未通过（不是完整 brotli），且大小 $sz 与 metadata 声明的 $want_data_size 不符（魔数 $m）。要么该文件被截断，要么它与这份 metadata 不是同一次构建的产物。"
      fi
      die "$f 形态无法确认：既不是裸 WASM（魔数 $m，应为 $WASM_MAGIC），brotli -t 也未通过（大小 $sz）——疑似下载截断，已中止"
    fi
    # sha256.txt 记的一律是**解压后**内容的校验和（verify 用 curl --compressed 对照）
    brotli -d -c "$out/$f" | sha256 | awk -v n="$f" '{print $1"  "n}' >> "$out/sha256.txt"
  done
  for f in "${RAW_FILES[@]}"; do
    cp "$src/$f" "$out/$f"
    sha256_of "$src/$f" | awk -v n="$f" '{print $1"  "n}' >> "$out/sha256.txt"
  done

  step "本地内容自检（不依赖 sha256.txt，避免自己验自己的闭环）"
  local got
  # od -N4 读满 4 字节即退出，会给上游 brotli 送 SIGPIPE；在 pipefail 下这条命令因此非零，
  # set -e 会让脚本在这里**静默中止**（本文件早期版本就这么中止过，还被误以为通过了）。
  # 故此处显式关 pipefail；brotli 自身的完整性由下面的 size 断言与 sha256 两道兜底。
  set +o pipefail
  got=$(brotli -d -c "$out/soffice.wasm" | od -An -tx1 -N4 | tr -d ' \n')
  set -o pipefail
  [ "$got" = "$WASM_MAGIC" ] || die "soffice.wasm 解压后不是 WASM（首字节 $got，应为 $WASM_MAGIC）"
  echo "  soffice.wasm 解压后是 \\0asm"
  got=$(brotli -d -c "$out/soffice.data" | wc -c | tr -d ' ')
  [ "$got" = "$want_data_size" ] || die "soffice.data 解压后 $got 字节，metadata 声明 $want_data_size —— 内容不匹配"
  echo "  soffice.data 解压后 $got 字节，与 metadata 的 remote_package_size 相符"
  [ "$(filesize "$out/soffice.js")" -ge 1024 ] || die "soffice.js 过小，疑似截断"
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$out/soffice.data.js.metadata" \
    || die "metadata 不是合法 JSON"
  echo "  soffice.js 与 metadata 形态正常"
}

cmd_check_build() {
  stage=$(mktemp -d)
  prepare_stage "$1" "$stage"
  step "产物清单（这就是将被上传的形态）"
  ls -l "$stage"
  echo; echo "sha256.txt（解压后内容的基线，verify 用它对拍）："
  sed 's/^/  /' "$stage/sha256.txt"
  echo; echo "自检通过。发布：bash deploy/publish-lowa-engine.sh publish $1 <版本号>"
}

# ---------------------------------------------------------------- 远端探测
# 单机探测：在目标机上就地跑（因此测得到「境外线路源站」的真实内容）。
# 远端脚本走引号化 heredoc + 位置参数，**不做任何本地插值**，从结构上杜绝嵌套引号问题。
# 输出协议：每行 `KEY|文件名|值`，未知行一律判失败（缺一条命令不能等于少验一项）。
probe_host() {
  local ssh_target="$1" version="$2"
  $SSH "$ssh_target" "bash -s -- '$version' '$PUBLIC_HOST' '$ENGINE_ROOT'" <<'REMOTE'
set -u
version="$1"; public_host="$2"; engine_root="$3"
base="https://127.0.0.1/lowa-engine/${version}"
H="Host: ${public_host}"
# 缺命令必须报成「缺命令」，不能让 `curl | brotli -t` 的失败被读成「形态错误」
for c in curl brotli python3 sha256sum; do
  command -v "$c" >/dev/null 2>&1 || echo "TOOL|$c|missing"
done
for f in soffice.js soffice.wasm soffice.data soffice.data.js.metadata; do
  echo "HTTP|$f|$(curl -s -o /dev/null -w '%{http_code}' -k -H "$H" "$base/$f" || echo 000)"
done
# 形态正判：磁盘上的原始字节必须是**完整的 brotli 流**（不是「首字节不像 wasm」这种反证）
for f in soffice.wasm soffice.data; do
  if curl -s -k -H "$H" "$base/$f" | brotli -t 2>/dev/null; then
    echo "FORM|$f|brotli"
  else
    echo "FORM|$f|NOT_BROTLI"
  fi
done
# 解码后校验和（wasm/data 声明了 br，用 --compressed 让 curl 解一层）
for f in soffice.wasm soffice.data; do
  echo "SHA|$f|$(curl -s -k -H "$H" --compressed "$base/$f" | sha256sum | cut -d' ' -f1)"
done
# 原始字节文件必须以 identity 直出，同样比对校验和
for f in soffice.js soffice.data.js.metadata; do
  echo "SHA|$f|$(curl -s -k -H "$H" -H 'Accept-Encoding: identity' "$base/$f" | sha256sum | cut -d' ' -f1)"
done
# 端到端交叉校验：解码后的 data 字节数必须等于线上 metadata 自称的 remote_package_size
want=$(curl -s -k -H "$H" -H 'Accept-Encoding: identity' "$base/soffice.data.js.metadata" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["remote_package_size"])' 2>/dev/null || echo NA)
got=$(curl -s -k -H "$H" --compressed "$base/soffice.data" | wc -c | tr -d ' ')
echo "SIZE|soffice.data|${got}/${want}"
REMOTE
}

# 返回 0=全通过 1=有失败 2=降级（无 sha256.txt，内容未验）
verify_one() {
  local label="$1" ssh_target="$2" version="$3" sums="$4"
  local out ok=0 degraded=0
  out=$(probe_host "$ssh_target" "$version") || die "$label 探测失败（SSH 不通或远端命令缺失）"
  [ -n "$out" ] || die "$label 探测无输出"

  local kind name val expect
  while IFS='|' read -r kind name val; do
    [ -n "$kind" ] || continue
    case "$kind" in
      TOOL)
        echo "  $label 远端缺命令 $name —— 本次探测结果不可信"; ok=1;;
      HTTP)
        if [ "$val" = "200" ]; then echo "  $label $name HTTP 200"
        else echo "  $label $name HTTP $val（应为 200）"; ok=1; fi;;
      FORM)
        if [ "$val" = "brotli" ]; then echo "  $label $name 磁盘为完整 brotli 流"
        else echo "  $label $name **不是 brotli 字节**，而 nginx 声明了 Content-Encoding: br —— 形态错误"; ok=1; fi;;
      SHA)
        expect=$(awk -v n="$name" '$2==n{print $1}' <<< "$sums")
        if [ -z "$expect" ]; then
          echo "  $label $name 未比对校验和（该版本无 sha256.txt）"; degraded=1
        elif [ "$val" != "$expect" ]; then
          echo "  $label $name 校验和不符：$val != $expect"; ok=1
        else
          echo "  $label $name 校验和 OK（${val:0:16}…）"
        fi;;
      SIZE)
        local got want; got=${val%%/*}; want=${val##*/}
        if [ "$want" = "NA" ]; then echo "  $label $name 无法读取线上 metadata 的 remote_package_size"; ok=1
        elif [ "$got" != "$want" ]; then echo "  $label $name 解码后 $got 字节 != metadata 声明 $want"; ok=1
        else echo "  $label $name 解码后 $got 字节，与线上 metadata 相符"; fi;;
      *)
        echo "  $label 意外的探测输出：$kind|$name|$val"; ok=1;;
    esac
  done <<< "$out"

  [ $ok -eq 1 ] && return 1
  [ $degraded -eq 1 ] && return 2
  return 0
}

cmd_verify() {
  local version="$1"
  valid_version "$version"

  step "读取 sha256.txt（记的是**解压后**内容的校验和）"
  local sums="" rc_sums=0
  # SSH 不通与「文件不存在」必须分开：混成一个空值会把连不上服务器说成「该版本没有校验文件」
  # （ssh 自身故障回 255，远端 test -f 未命中回 1）
  $SSH "$BJ_HOST" "test -f '$ENGINE_ROOT/$version/sha256.txt'" || rc_sums=$?
  if [ $rc_sums -eq 0 ]; then
    sums=$($SSH "$BJ_HOST" "cat '$ENGINE_ROOT/$version/sha256.txt'") || die "读取 sha256.txt 失败"
    echo "  已取到 $(wc -l <<< "$sums" | tr -d ' ') 条基线"
  elif [ $rc_sums -eq 1 ]; then
    echo "  该版本没有 sha256.txt（r2/r3 等早期目录属正常），内容校验将标记为未完成"
  else
    die "无法连接北京或命令执行失败（ssh 退出码 $rc_sums）"
  fi

  # 注意别写成 `[ $r -eq 1 ] && rc=1` 这种顶层 AND 串：条件为假时整条命令返回非零，
  # set -e 会让脚本当场退出（本文件早期版本就踩过）。一律用 if。
  local rc=0 r
  merge_rc() { if [ "$1" -eq 1 ]; then rc=1; elif [ "$1" -eq 2 ] && [ "$rc" -eq 0 ]; then rc=2; fi; }
  step "验证北京（境内线路源站）"
  r=0; verify_one "北京" "$BJ_HOST" "$version" "$sums" || r=$?
  merge_rc "$r"
  step "验证新加坡（境外线路源站，CI 实际走这条）"
  r=0; verify_one "新加坡" "$SG_HOST" "$version" "$sums" || r=$?
  merge_rc "$r"

  step "公网路径抽验（真实证书与 DNS，非本机回环）"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://${PUBLIC_HOST}/lowa-engine/${version}/soffice.wasm" || echo 000)
  echo "  本机出口（境内线路）→ HTTP $code"
  [ "$code" = "200" ] || { echo "  公网抽验未通过"; rc=1; }

  echo
  case $rc in
    0) echo "两台源站均通过内容级校验。可把 .github/workflows/desktop-build.yml 的 LOWA_BASE_URL 指向："
       echo "  https://${PUBLIC_HOST}/lowa-engine/${version}/";;
    2) echo "HTTP 与形态通过，但**内容校验未完成**（无 sha256.txt）。不足以据此切换 LOWA_BASE_URL。" >&2;;
    *) echo "验证未通过，**不要**切换 LOWA_BASE_URL。" >&2;;
  esac
  return $rc
}

# ---------------------------------------------------------------- 发布
# 在一台机上：把暂存目录换入正式目录（旧版本 rename 进备份，可回滚）。
# 两次 rename 都在同一文件系统内，瞬时完成，不存在「一半新一半旧」的窗口。
swap_in() {
  local ssh_target="$1" version="$2" label="$3"
  $SSH "$ssh_target" "bash -s -- '$version' '$ENGINE_ROOT' '$STAGE_ROOT' '$BACKUP_ROOT'" <<'REMOTE'
set -euo pipefail
version="$1"; engine_root="$2"; stage_root="$3"; backup_root="$4"
src="${stage_root}/${version}"; dst="${engine_root}/${version}"
[ -d "$src" ] || { echo "暂存目录不存在：$src" >&2; exit 1; }
# 换入前先在暂存区校验一遍，杜绝传输截断被换进对外目录
if [ -f "$src/sha256.txt" ]; then
  ( cd "$src" && while read -r want name; do
      case "$name" in
        soffice.wasm|soffice.data) got=$(brotli -d -c "$name" | sha256sum | cut -d' ' -f1);;
        *) got=$(sha256sum < "$name" | cut -d' ' -f1);;
      esac
      [ "$got" = "$want" ] || { echo "暂存区校验失败：$name" >&2; exit 1; }
    done < sha256.txt )
fi
mkdir -p "$backup_root"
if [ -d "$dst" ]; then
  ts=$(date +%Y%m%d-%H%M%S)
  mv "$dst" "${backup_root}/${version}.${ts}"
  echo "旧版本已备份到 ${backup_root}/${version}.${ts}"
fi
mkdir -p "$engine_root"
mv "$src" "$dst"
# 权限显式对齐既有版本目录（755/644）：mktemp -d 是 700，经 rsync -a 原样传过来后
# 只因 nginx 恰好以 www 运行且属主是 www 才没 403，属侥幸，不能留。
chown -R www:www "$dst" 2>/dev/null || true
chmod 755 "$dst"
find "$dst" -maxdepth 1 -type f -exec chmod 644 {} +
# 北京有后台进程会给 web root 新写入的文件生成 .br 旁挂；nginx 未开 brotli_static，
# 不影响服务，但会平白多占一份体积并被 rsync 带到对端
find "$dst" -maxdepth 1 -name '*.br' -delete
ls -la "$dst"
REMOTE
}

cmd_publish() {
  local src="$1" version="$2"
  valid_version "$version"
  stage=$(mktemp -d)
  prepare_stage "$src" "$stage"

  step "前置检查：新加坡 → 北京 的反向链路"
  # BJ_HOST 是在本地展开、却由新加坡使用的地址；不通的话后面只会表现为「同步超时」
  $SSH "$SG_HOST" "ssh -i '$SG_TO_BJ_KEY' -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=10 '$BJ_HOST' true" \
    || die "新加坡无法 ssh 到北京（密钥 $SG_TO_BJ_KEY 或地址 $BJ_HOST 有问题）"
  echo "  通"

  step "上传到北京暂存区 ${STAGE_ROOT}/${version}/（web root 之外）"
  $SSH "$BJ_HOST" "mkdir -p '$STAGE_ROOT/$version' '/root/.lowa-partial'"
  # --partial-dir 指向 web root 之外：断点续传保留，但半截文件绝不会落到对外服务路径
  rsync -a --partial --partial-dir=/root/.lowa-partial --timeout=120 -e "$SSH" \
    "$stage"/ "$BJ_HOST:$STAGE_ROOT/$version/"
  step "北京：校验暂存区并换入正式目录"
  swap_in "$BJ_HOST" "$version" "北京"

  step "同步到新加坡（SG 侧拉取；密钥在 SG 上，北京没有反向密钥）"
  # 远端脚本写成文件再 setsid 执行：跨境几十 MB 的传输中途会被掐断，前台 rsync 会随
  # SSH 会话一起死。落**终态**哨兵（不是只落成功），否则失败只能靠盲等超时暴露。
  $SSH "$SG_HOST" "cat > /root/lowa-sync-${version}.sh" <<'REMOTE'
#!/usr/bin/env bash
version="$1"; bj_host="$2"; key="$3"; engine_root="$4"; stage_root="$5"
exec 9>"/var/lock/lowa-sync-${version}.lock"
flock -n 9 || { echo "RSYNC_EXIT=200 另一个同步正在跑，本次放弃"; exit 200; }
mkdir -p "${stage_root}/${version}" /root/.lowa-partial
for i in 1 2 3; do
  rsync -a --partial --partial-dir=/root/.lowa-partial --timeout=120 \
    -e "ssh -i ${key} -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o ServerAliveInterval=15" \
    "${bj_host}:${engine_root}/${version}/" "${stage_root}/${version}/"
  rc=$?
  [ $rc -eq 0 ] && break
  echo "第 $i 次 rsync 失败（rc=$rc），30 秒后重试"
  sleep 30
done
echo "RSYNC_EXIT=$rc"
REMOTE
  $SSH "$SG_HOST" "chmod +x /root/lowa-sync-${version}.sh && rm -f /root/lowa-sync-${version}.log && nohup setsid /root/lowa-sync-${version}.sh '$version' '$BJ_HOST' '$SG_TO_BJ_KEY' '$ENGINE_ROOT' '$STAGE_ROOT' > /root/lowa-sync-${version}.log 2>&1 < /dev/null & sleep 1; echo 已启动"

  echo -n "  等待同步"
  local waited=0 line=""
  while :; do
    line=$($SSH "$SG_HOST" "grep -o 'RSYNC_EXIT=[0-9]*' /root/lowa-sync-${version}.log 2>/dev/null | tail -1" || true)
    if [ -n "$line" ]; then
      echo
      local rc="${line#RSYNC_EXIT=}"
      if [ "$rc" != "0" ]; then
        echo "--- 新加坡同步日志尾部 ---" >&2
        $SSH "$SG_HOST" "tail -n 20 /root/lowa-sync-${version}.log" >&2 || true
        die "新加坡同步失败（rc=$rc）。北京已就位，新加坡未换入，线上仍是旧状态。"
      fi
      break
    fi
    [ $waited -ge "$SYNC_TIMEOUT" ] && die "同步超时（${SYNC_TIMEOUT}s）；到 SG 看 /root/lowa-sync-${version}.log"
    sleep 20; waited=$((waited+20)); echo -n "."
  done
  echo "  同步完成"

  step "新加坡：校验暂存区并换入正式目录"
  swap_in "$SG_HOST" "$version" "新加坡"

  cmd_verify "$version"
}

# ---------------------------------------------------------------- main
[ $# -ge 1 ] || usage
case "${1:-}" in
  check-build) [ $# -eq 2 ] || usage; cmd_check_build "$2";;
  publish)     [ $# -eq 3 ] || usage; cmd_publish "$2" "$3";;
  verify)      [ $# -eq 2 ] || usage; cmd_verify "$2";;
  *) usage;;
esac
