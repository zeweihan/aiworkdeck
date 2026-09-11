# SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""从 ModelScope 下载一个模型仓，落成标准 HuggingFace 缓存布局（dev-board#583）。

为什么不直接用 huggingface_hub.snapshot_download 走 hf-mirror.com：镜像只代理元数据，
大文件 302 到 HF 官方 CDN（cas-bridge.xethub.hf.co），国内无代理网络连不上，报
LocalEntryNotFoundError。ModelScope 上同名仓的大文件由 cdn-lfs-cn-*.modelscope.cn 直出。

为什么落成 HF 缓存布局：运行侧（asr/kokoro 服务，在 runtime pack 里）以 HF_HUB_OFFLINE=1
按 repo id 读 HF_HOME，只认 hub/models--{org}--{name}/{refs/main, snapshots/<rev>/...}。

只用标准库（pack 里没有 modelscope SDK；asr pack 连 requests 都没有），certifi 有就用
（python-build-standalone 在 macOS 上找不到系统 CA）。

用法：python model-fetch.py --repo Systran/faster-whisper-medium --hf-home <dir>
进度：父进程按目录字节数算，这里只把字节写进目录；stdout 最后一行给人看。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENDPOINT = "https://www.modelscope.cn"
CHUNK = 1 << 20
RETRIES = 4
TIMEOUT = 60
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")

HOSTS = []  # 实际访问过的主机（含重定向目标），收尾打印，证明全程不碰 HF 域名


class ChecksumError(Exception):
    pass


def log(msg):
    print(msg, flush=True)


def _note_host(url):
    host = urllib.parse.urlsplit(url).hostname
    if host and host not in HOSTS:
        HOSTS.append(host)


class _TrackRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _note_host(newurl)
        # 自带实现会把 req.headers（含 Range）带到新地址
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _ssl_context():
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:
        return ssl.create_default_context()


_OPENER = urllib.request.build_opener(
    urllib.request.HTTPSHandler(context=_ssl_context()), _TrackRedirect
)


def _open(url, headers=None):
    _note_host(url)
    req = urllib.request.Request(url, headers={"User-Agent": "AIWorkDeck-model-fetch", **(headers or {})})
    return _OPENER.open(req, timeout=TIMEOUT)


def list_files(repo):
    """返回 (revision, [blob 条目])。revision 取仓内最新一次提交（40 位十六进制）。"""
    url = f"{ENDPOINT}/api/v1/models/{repo}/repo/files?Revision=master&Recursive=true"
    with _open(url) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if body.get("Code") != 200 or not isinstance(body.get("Data"), dict):
        raise RuntimeError(f"ModelScope file list failed for {repo}: {str(body)[:200]}")
    files = [f for f in body["Data"].get("Files") or [] if f.get("Type") == "blob"]
    if not files:
        raise RuntimeError(f"ModelScope returned no files for {repo}")
    latest = max(files, key=lambda f: f.get("CommittedDate") or 0)
    rev = str(latest.get("Revision") or "").lower()
    if not HEX40.match(rev):
        # 目录名只要稳定且是 40 位十六进制即可（运行侧经 refs/main 找到它）
        digest = "\n".join(sorted(f"{f['Path']}:{f.get('Sha256', '')}" for f in files))
        rev = hashlib.sha1(digest.encode("utf-8")).hexdigest()
    return rev, files


def _sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            b = fh.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def fetch_blob(repo, entry, blobs_dir, label):
    """下载一个文件到 blobs/<sha256>（断点续传 + 校验 + 重试），返回 blob 路径。"""
    rel = entry["Path"]
    size = int(entry.get("Size") or 0)
    sha = str(entry.get("Sha256") or "").lower()
    sha = sha if HEX64.match(sha) else None
    if sha:
        blob = os.path.join(blobs_dir, sha)
        if os.path.isfile(blob) and os.path.getsize(blob) == size:
            return blob
    # 命名与 huggingface_hub 的 LFS 临时文件一致（blobs/<etag>.incomplete，LFS 的 etag 即 sha256）；
    # 本脚本自己中断后重跑会从这里续传
    tmp_name = sha or ("ms-" + hashlib.sha1(rel.encode("utf-8")).hexdigest())
    tmp = os.path.join(blobs_dir, tmp_name + ".incomplete")
    url = f"{ENDPOINT}/models/{repo}/resolve/master/{urllib.parse.quote(rel)}"

    last_err = None
    for attempt in range(1, RETRIES + 1):
        try:
            have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
            if have > size:
                os.remove(tmp)
                have = 0
            if have < size or size == 0:
                headers = {"Range": f"bytes={have}-"} if have else {}
                with _open(url, headers) as resp:
                    if have and resp.status != 206:
                        have = 0  # 服务端没理 Range：从头来
                    done, last_print = have, 0.0
                    with open(tmp, "ab" if have else "wb") as out:
                        while True:
                            b = resp.read(CHUNK)
                            if not b:
                                break
                            out.write(b)
                            done += len(b)
                            now = time.monotonic()
                            if now - last_print > 5:
                                last_print = now
                                log(f"{label} {rel} {done >> 20}/{max(size, 1) >> 20} MB")
            got = os.path.getsize(tmp)
            if got != size:
                raise ChecksumError(f"{rel}: size {got} != expected {size}")
            actual = _sha256_of(tmp)
            if sha and actual != sha:
                os.remove(tmp)  # 内容坏了，续传只会接着坏
                raise ChecksumError(f"{rel}: sha256 mismatch")
            blob = os.path.join(blobs_dir, sha or actual)
            os.replace(tmp, blob)
            return blob
        except ChecksumError as e:
            last_err = e
        except OSError as e:
            # URLError / 超时 / SSL 错误都是 OSError 的子类；磁盘满重试没有意义
            if getattr(e, "errno", None) == 28:
                raise
            last_err = e
        if attempt < RETRIES:
            log(f"{label} {rel} retry {attempt}/{RETRIES - 1}: {type(last_err).__name__}: {last_err}")
            time.sleep(2 ** attempt)
    raise last_err


def place(blob, snap_path):
    """snapshots/<rev>/<path> 指向 blob：能建相对软链就建（与 huggingface_hub 一致）；
    建不了（Windows 无软链权限）就把 blob 挪进去——这也是 huggingface_hub 在
    are_symlinks_supported() 为 False 时的做法（file_download._create_symlink 的
    shutil.move 分支），离线读取只看 snapshots 下的文件。"""
    os.makedirs(os.path.dirname(snap_path), exist_ok=True)
    if os.path.lexists(snap_path):
        os.remove(snap_path)
    try:
        os.symlink(os.path.relpath(blob, os.path.dirname(snap_path)), snap_path)
    except OSError:
        os.replace(blob, snap_path)


def fetch_repo(repo, hf_home):
    org, name = repo.split("/", 1)
    storage = os.path.join(hf_home, "hub", f"models--{org}--{name}")
    blobs_dir = os.path.join(storage, "blobs")
    os.makedirs(blobs_dir, exist_ok=True)

    rev, files = list_files(repo)
    snap_root = os.path.join(storage, "snapshots", rev)
    pending = []
    for f in files:
        snap = os.path.join(snap_root, *f["Path"].split("/"))
        # isfile 跟随软链：软链指向的 blob 在、大小对才算已就位
        if os.path.isfile(snap) and os.path.getsize(snap) == int(f.get("Size") or 0):
            continue
        pending.append(f)

    need = sum(int(f.get("Size") or 0) for f in pending)
    free = shutil.disk_usage(blobs_dir).free
    if need > free:
        raise OSError(28, f"No space left on device: need {need >> 20} MB, free {free >> 20} MB")

    log(f"ModelScope {repo}@{rev[:8]}: {len(files) - len(pending)}/{len(files)} files present, "
        f"{need >> 20} MB to fetch")
    for i, f in enumerate(pending, 1):
        blob = fetch_blob(repo, f, blobs_dir, f"[{i}/{len(pending)}]")
        place(blob, os.path.join(snap_root, *f["Path"].split("/")))

    # 最后才写 refs/main：旧的半截缓存里 refs/main 可能指向一个根本不存在的 snapshot，
    # 这里整体覆盖；中途失败则保持旧值，不会指向半成品
    refs = os.path.join(storage, "refs")
    os.makedirs(refs, exist_ok=True)
    tmp_ref = os.path.join(refs, "main.tmp")
    with open(tmp_ref, "w") as fh:
        fh.write(rev)
    os.replace(tmp_ref, os.path.join(refs, "main"))
    return rev, len(files)


def _classify(e):
    if isinstance(e, OSError) and getattr(e, "errno", None) == 28:
        return "disk"
    if isinstance(e, ChecksumError):
        return "checksum"
    if isinstance(e, OSError):  # URLError / 超时 / SSL / 连接被拒
        return "network"
    return "error"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--hf-home", default=os.environ.get("HF_HOME"))
    args = ap.parse_args(argv)
    if not args.hf_home:
        ap.error("--hf-home (or HF_HOME) is required")
    try:
        rev, n = fetch_repo(args.repo, args.hf_home)
    except Exception as e:  # noqa: BLE001 —— 最后一行给父进程归类、给人看
        log("hosts contacted: " + ", ".join(HOSTS))
        log(f"error[{_classify(e)}]: {type(e).__name__}: {e}")
        return 1
    log("hosts contacted: " + ", ".join(HOSTS))
    log(f"done: {args.repo}@{rev[:8]} ({n} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
