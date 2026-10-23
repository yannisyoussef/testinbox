"""ADR-035 rev 3 review follow-up probes: E9 (signed Content-Length), M1-M3 (multipart listing semantics)."""
import datetime, hashlib, hmac, socket, urllib.parse, boto3
from botocore.config import Config
EP = "http://127.0.0.1:19000"; B = "probe2"; HOST = "127.0.0.1:19000"
s3 = boto3.client("s3", endpoint_url=EP, aws_access_key_id="probe", aws_secret_access_key="probeprobe",
                  region_name="us-east-1", config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}))
try: s3.create_bucket(Bucket=B)
except Exception: pass
def presign(key, expires, signed_len=None):
    now = datetime.datetime.now(datetime.timezone.utc); amz = now.strftime("%Y%m%dT%H%M%SZ"); day = now.strftime("%Y%m%d")
    scope = f"{day}/us-east-1/s3/aws4_request"; path = f"/{B}/{key}"
    hdrs = {"host": HOST}
    if signed_len is not None: hdrs["content-length"] = str(signed_len)
    sh = ";".join(sorted(hdrs))
    q = {"X-Amz-Algorithm": "AWS4-HMAC-SHA256", "X-Amz-Credential": f"probe/{scope}", "X-Amz-Date": amz,
         "X-Amz-Expires": str(expires), "X-Amz-SignedHeaders": sh}
    cq = "&".join(f"{urllib.parse.quote(k, safe='~')}={urllib.parse.quote(v, safe='~')}" for k, v in sorted(q.items()))
    ch = "".join(f"{k}:{hdrs[k]}\n" for k in sorted(hdrs))
    creq = "\n".join(["PUT", urllib.parse.quote(path), cq, ch, sh, "UNSIGNED-PAYLOAD"])
    sts = "\n".join(["AWS4-HMAC-SHA256", amz, scope, hashlib.sha256(creq.encode()).hexdigest()])
    k = ("AWS4" + "probeprobe").encode()
    for part in (day, "us-east-1", "s3", "aws4_request"): k = hmac.new(k, part.encode(), hashlib.sha256).digest()
    return f"{path}?{cq}&X-Amz-Signature={hmac.new(k, sts.encode(), hashlib.sha256).hexdigest()}"
def put(pathq, headers, body):
    s = socket.create_connection(("127.0.0.1", 19000), timeout=15)
    s.sendall((f"PUT {pathq} HTTP/1.1\r\nHost: {HOST}\r\n" + "".join(f"{k}: {v}\r\n" for k, v in headers.items()) + "\r\n").encode() + body)
    d = s.recv(4096).decode(errors="replace"); s.close()
    code = d[d.find("<Code>") + 6: d.find("</Code>")] if "<Code>" in d else ""
    return f"{d.split(chr(13))[0]} {code}".strip()
def obj(key):
    r = s3.list_objects_v2(Bucket=B, Prefix=key).get("Contents", [])
    return [(o["Key"], o["Size"]) for o in r]
N = 1000
print("E9a CONTROL signed Content-Length=N, sent N:  ", put(presign("e9a", 300, N), {"Content-Length": N}, b"x" * N), obj("e9a"))
print("E9b signed N, sent N+1 (Content-Length N+1):  ", put(presign("e9b", 300, N), {"Content-Length": N + 1}, b"x" * (N + 1)), obj("e9b"))
print("E9c signed N, sent N-1 (Content-Length N-1):  ", put(presign("e9c", 300, N), {"Content-Length": N - 1}, b"x" * (N - 1)), obj("e9c"))
chunk = b"%x\r\n" % 1500 + b"x" * 1500 + b"\r\n0\r\n\r\n"
print("E9d signed N, chunked transfer 1500 bytes:    ", put(presign("e9d", 300, N), {"Transfer-Encoding": "chunked"}, chunk), obj("e9d"))
print("E9e UNSIGNED length (host only), sent 1500:   ", put(presign("e9e", 300, None), {"Content-Length": 1500}, b"x" * 1500), obj("e9e"))
# Multipart listing semantics
k = "ws/inbox/msg-1/raw.eml"
up = s3.create_multipart_upload(Bucket=B, Key=k)["UploadId"]
s3.upload_part(Bucket=B, Key=k, UploadId=up, PartNumber=1, Body=b"y" * (5 * 1024 * 1024))
def mpu(prefix=None):
    kw = {"Bucket": B}
    if prefix is not None: kw["Prefix"] = prefix
    try: return [u["Key"] for u in s3.list_multipart_uploads(**kw).get("Uploads", [])]
    except Exception as e: return f"ERROR {e.response['Error']['Code']}"
print("M1 ListMultipartUploads bucket-wide (no prefix):", mpu())
print("M2 ListMultipartUploads prefix = parent 'ws/inbox/msg-1/':", mpu("ws/inbox/msg-1/"))
print("M3 ListMultipartUploads prefix = exact key:", mpu(k))
print("M4 ListParts on the exact key/uploadId:", len(s3.list_parts(Bucket=B, Key=k, UploadId=up).get("Parts", [])), "part(s)")
print("M5 ListObjectsV2 on parent prefix sees the incomplete upload?:", obj("ws/inbox/msg-1/"))
s3.abort_multipart_upload(Bucket=B, Key=k, UploadId=up)
print("M6 after abort, exact-key listing:", mpu(k))
