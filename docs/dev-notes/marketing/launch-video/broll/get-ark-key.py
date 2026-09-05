"""用账号 AK/SK 签名调用方舟 GetApiKey，换取临时 API Key。
密钥只从本地凭据文件读取，不打印、不入库。"""
import datetime, hashlib, hmac, json, re, sys, urllib.request, urllib.error

CRED = "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.agent/CREDENTIALS.md"
HOST, REGION, SERVICE, VERSION = "open.volcengineapi.com", "cn-beijing", "ark", "2024-01-01"


def load_ak_sk():
    txt = open(CRED, encoding="utf-8").read()
    i = txt.find("## 火山引擎")
    if i < 0:
        sys.exit("凭据文件里找不到火山引擎段落")
    seg = txt[i:i + 1200]
    ak = re.search(r"\|\s*AccessKey\s*\|\s*([A-Za-z0-9_\-=+/]+)\s*\|", seg)
    sk = re.search(r"\|\s*SecretKey\s*\|\s*([A-Za-z0-9_\-=+/]+)\s*\|", seg)
    if not (ak and sk):
        sys.exit("解析 AK/SK 失败")
    return ak.group(1), sk.group(1)


def sign(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def call(action, body):
    ak, sk = load_ak_sk()
    payload = json.dumps(body)
    now = datetime.datetime.now(datetime.timezone.utc)
    xdate = now.strftime("%Y%m%dT%H%M%SZ")
    datestamp = xdate[:8]
    body_hash = hashlib.sha256(payload.encode()).hexdigest()
    query = f"Action={action}&Version={VERSION}"

    canonical_headers = f"host:{HOST}\nx-content-sha256:{body_hash}\nx-date:{xdate}\n"
    signed_headers = "host;x-content-sha256;x-date"
    canonical_request = "\n".join(
        ["POST", "/", query, canonical_headers, signed_headers, body_hash])
    scope = f"{datestamp}/{REGION}/{SERVICE}/request"
    string_to_sign = "\n".join(
        ["HMAC-SHA256", xdate, scope, hashlib.sha256(canonical_request.encode()).hexdigest()])

    k = sign(sk.encode(), datestamp)
    k = sign(k, REGION)
    k = sign(k, SERVICE)
    k = sign(k, "request")
    signature = hmac.new(k, string_to_sign.encode(), hashlib.sha256).hexdigest()

    req = urllib.request.Request(
        f"https://{HOST}/?{query}", data=payload.encode(), method="POST",
        headers={
            "Host": HOST,
            "Content-Type": "application/json; charset=UTF-8",
            "X-Date": xdate,
            "X-Content-Sha256": body_hash,
            "Authorization": (f"HMAC-SHA256 Credential={ak}/{scope}, "
                              f"SignedHeaders={signed_headers}, Signature={signature}"),
        })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


if __name__ == "__main__":
    attempts = [
        {"DurationSeconds": 2592000, "ResourceType": "presetendpoint",
         "ProjectName": "default", "ResourceIds": ["doubao-seedance-1-0-pro-fast-251015"]},
        {"DurationSeconds": 2592000, "ResourceType": "endpoint",
         "ResourceIds": ["doubao-seedance-1-0-pro-fast-251015"]},
    ]
    for i, body in enumerate(attempts, 1):
        status, data = call("GetApiKey", body)
        result = (data or {}).get("Result") or {}
        key = result.get("ApiKey")
        err = (data or {}).get("ResponseMetadata", {}).get("Error") or data.get("Error")
        print(f"[尝试{i}] ResourceType={body['ResourceType']} HTTP {status} "
              f"{'拿到 key' if key else ''} {json.dumps(err, ensure_ascii=False) if err else ''}")
        if key:
            out = "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.agent/ark_api_key.env"
            with open(out, "w", encoding="utf-8") as f:
                f.write(f"# 方舟临时 API Key，GetApiKey 换取，过期时间 {result.get('ExpiredTime')}\n")
                f.write(f"export ARK_API_KEY={key}\n")
            import os
            os.chmod(out, 0o600)
            print(f"已写入 {out}（权限 600），过期时间 {result.get('ExpiredTime')}")
            break
