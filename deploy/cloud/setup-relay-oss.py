#!/usr/bin/env python3
"""建手机影像云中转的 OSS 桶 + RAM 子用户（大陆站）。

- 桶 awd-mobile-relay（cn-beijing / Standard / private），生命周期 35 天兜底 + 7 天清失败分片。
- RAM 子用户 awd-mobile-relay：仅该桶对象读写删列。
- 新 AK 追加写入 ~/.aliyun/awd-gateway-credentials.txt，stdout 只打印 AK ID，不打印 secret。

用法：ROOT_AK_ID=... ROOT_AK_SECRET=... python3 setup_relay_oss.py [--intl]
--intl 时改用国际站（桶 awd-mobile-relay-intl / ap-southeast-1 端点）。
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid
from email.utils import formatdate

AK = os.environ["ROOT_AK_ID"]
SK = os.environ["ROOT_AK_SECRET"]
INTL = "--intl" in sys.argv
BUCKET = "awd-mobile-relay-intl" if INTL else "awd-mobile-relay"
OSS_HOST = ("oss-ap-southeast-1" if INTL else "oss-cn-beijing") + ".aliyuncs.com"
RAM_USER = "awd-mobile-relay"
POLICY_NAME = "awd-mobile-relay-bucket-rw"


def oss_request(method, path_qs, body=b"", content_type="", extra_headers=None, ok=(200,)):
    """OSS V1 header 签名。path_qs 形如 /?lifecycle（资源子串参与签名）。"""
    date = formatdate(usegmt=True)
    resource = f"/{BUCKET}{path_qs if path_qs.startswith('/?') or path_qs == '/' else path_qs}"
    oss_headers = "".join(
        f"{k.lower()}:{v}\n"
        for k, v in sorted((extra_headers or {}).items())
        if k.lower().startswith("x-oss-")
    )
    to_sign = f"{method}\n\n{content_type}\n{date}\n{oss_headers}{resource}"
    sig = base64.b64encode(hmac.new(SK.encode(), to_sign.encode(), hashlib.sha1).digest()).decode()
    url = f"https://{BUCKET}.{OSS_HOST}{path_qs if path_qs != '/' else '/'}"
    req = urllib.request.Request(url, data=body if body else None, method=method)
    req.add_header("Date", date)
    req.add_header("Authorization", f"OSS {AK}:{sig}")
    if content_type:
        req.add_header("Content-Type", content_type)
    for k, v in (extra_headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def ram_request(action, params):
    """RAM RPC V1 直签（配方同 SmsService.signedForm）。"""
    p = {
        "Action": action,
        "Format": "JSON",
        "Version": "2015-05-01",
        "AccessKeyId": AK,
        "SignatureMethod": "HMAC-SHA1",
        "SignatureVersion": "1.0",
        "SignatureNonce": str(uuid.uuid4()),
        "Timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        **params,
    }
    q = "&".join(
        f"{urllib.parse.quote(k, safe='')}={urllib.parse.quote(str(v), safe='')}"
        for k, v in sorted(p.items())
    )
    to_sign = "GET&%2F&" + urllib.parse.quote(q, safe="")
    sig = base64.b64encode(hmac.new((SK + "&").encode(), to_sign.encode(), hashlib.sha1).digest()).decode()
    url = f"https://ram.aliyuncs.com/?{q}&Signature={urllib.parse.quote(sig, safe='')}"
    try:
        with urllib.request.urlopen(url) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def main():
    # 1. 建桶（已存在则 BucketAlreadyExists/409，幂等处理）
    body = b'<CreateBucketConfiguration><StorageClass>Standard</StorageClass></CreateBucketConfiguration>'
    st, out = oss_request("PUT", "/", body, "application/xml",
                          extra_headers={"x-oss-acl": "private"})
    if st == 200:
        print(f"bucket {BUCKET}: created")
    elif "BucketAlreadyExists" in out or "BucketAlreadyOwnedByYou" in out or st == 409:
        print(f"bucket {BUCKET}: already exists ({st})")
    else:
        print(f"bucket create FAILED {st}: {out[:300]}")
        sys.exit(1)

    # 2. 生命周期：35 天兜底删除（代码 TTL 30 天是主机制）+ 7 天清失败分片
    lc = (
        "<LifecycleConfiguration>"
        "<Rule><ID>expire-relay-35d</ID><Prefix></Prefix><Status>Enabled</Status>"
        "<Expiration><Days>35</Days></Expiration>"
        "<AbortMultipartUpload><Days>7</Days></AbortMultipartUpload>"
        "</Rule></LifecycleConfiguration>"
    ).encode()
    st, out = oss_request("PUT", "/?lifecycle", lc, "application/xml")
    print(f"lifecycle: {st}" + ("" if st == 200 else f" {out[:200]}"))
    if st != 200:
        sys.exit(1)

    # 3. RAM 用户（幂等）
    st, out = ram_request("CreateUser", {"UserName": RAM_USER})
    if st == 200:
        print(f"ram user {RAM_USER}: created")
    elif out.get("Code") == "EntityAlreadyExists.User":
        print(f"ram user {RAM_USER}: already exists")
    else:
        print(f"ram user FAILED: {out}")
        sys.exit(1)

    # 4. 桶级最小权限策略
    doc = json.dumps({
        "Version": "1",
        "Statement": [
            {"Effect": "Allow",
             "Action": ["oss:GetObject", "oss:PutObject", "oss:DeleteObject",
                         "oss:ListObjects", "oss:HeadObject", "oss:GetBucketStat"],
             "Resource": [f"acs:oss:*:*:{BUCKET}", f"acs:oss:*:*:{BUCKET}/*"]},
        ],
    })
    st, out = ram_request("CreatePolicy", {"PolicyName": POLICY_NAME, "PolicyDocument": doc})
    if st == 200:
        print(f"policy {POLICY_NAME}: created")
    elif out.get("Code") == "EntityAlreadyExists.Policy":
        print(f"policy {POLICY_NAME}: already exists")
    else:
        print(f"policy FAILED: {out}")
        sys.exit(1)

    st, out = ram_request("AttachPolicyToUser", {
        "PolicyType": "Custom", "PolicyName": POLICY_NAME, "UserName": RAM_USER})
    if st == 200 or out.get("Code") == "EntityAlreadyExists.User.Policy":
        print("policy attached")
    else:
        print(f"attach FAILED: {out}")
        sys.exit(1)

    # 5. AK（每次新建一把；secret 只写文件）
    st, out = ram_request("CreateAccessKey", {"UserName": RAM_USER})
    if st != 200:
        print(f"create ak FAILED: {out}")
        sys.exit(1)
    ak = out["AccessKey"]
    cred_path = os.path.expanduser("~/.aliyun/awd-gateway-credentials.txt")
    with open(cred_path, "a") as f:
        f.write(f"\n# awd-mobile-relay（{'国际站 ' if INTL else ''}{BUCKET} 桶对象读写删列，"
                f"{time.strftime('%Y-%m-%d')} 建，dev-board#236）\n")
        f.write(f"RELAY_OSS_AK_ID={ak['AccessKeyId']}\n")
        f.write(f"RELAY_OSS_AK_SECRET={ak['AccessKeySecret']}\n")
    os.chmod(cred_path, 0o600)
    print(f"access key created: {ak['AccessKeyId']} (secret -> {cred_path})")


if __name__ == "__main__":
    main()
