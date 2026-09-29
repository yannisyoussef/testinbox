"""ADR-035 rev 3 review follow-up probes: E9 (signed Content-Length), M1-M3 (multipart listing semantics)."""
import datetime, hashlib, hmac, socket, urllib.parse, boto3
from botocore.config import Config
EP = "http://127.0.0.1:19000"; B = "probe2"; HOST = "127.0.0.1:19000"
s3 = boto3.client("s3", endpoint_url=EP, aws_access_key_id="probe", aws_secret_access_key="probeprobe",
                  region_name="us-east-1", config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}))
try: s3.create_bucket(Bucket=B)
except Exception: pass
def presign(key, expires, signed_len=None, extra=None):
    now = datetime.datetime.now(datetime.timezone.utc); amz = now.strftime("%Y%m%dT%H%M%SZ"); day = now.strftime("%Y%m%d")
    scope = f"{day}/us-east-1/s3/aws4_request"; path = f"/{B}/{key}"
    hdrs = {"host": HOST}
    if signed_len is not None: hdrs["content-length"] = str(signed_len)
    hdrs.update(extra or {})
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
inm = {"if-none-match": "*"}
print("I1 create-only (signed If-None-Match:*), key absent:        ", put(presign("i1", 300, N, inm), {"Content-Length": N, "If-None-Match": "*"}, b"a" * N), obj("i1"))
print("I2 SAME URL replayed, key now exists:                      ", put(presign("i1", 300, N, inm), {"Content-Length": N, "If-None-Match": "*"}, b"b" * N), obj("i1"))
url = presign("i3", 300, N, inm)
print("I3 URL used once:                                          ", put(url, {"Content-Length": N, "If-None-Match": "*"}, b"a" * N))
print("I4 exact same URL replayed with different bytes:           ", put(url, {"Content-Length": N, "If-None-Match": "*"}, b"c" * N))
print("I5 same URL, If-None-Match header OMITTED (signed header): ", put(url, {"Content-Length": N}, b"d" * N))
body = s3.get_object(Bucket=B, Key="i3")["Body"].read()[:1]
print("I6 i3 content after replays is still the first write:      ", body == b"a", body)
