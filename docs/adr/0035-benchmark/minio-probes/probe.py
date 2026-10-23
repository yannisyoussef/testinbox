"""MinIO RELEASE.2025-04-22T22-12-26Z behaviour probes for ADR-035 §4/§6 (TI-DEC-001). Scratch only."""
import socket, time, datetime, urllib.parse, boto3
from botocore.config import Config
from botocore.auth import SigV4QueryAuth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials
EP = "http://127.0.0.1:19000"; B = "probe"; MIB = 1024 * 1024
s3 = boto3.client("s3", endpoint_url=EP, aws_access_key_id="probe", aws_secret_access_key="probeprobe",
                  region_name="us-east-1", config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}))
try: s3.create_bucket(Bucket=B)
except Exception: pass
def keys(prefix): return [o["Key"] for o in s3.list_objects_v2(Bucket=B, Prefix=prefix).get("Contents", [])]
def mpu(): return s3.list_multipart_uploads(Bucket=B).get("Uploads", [])
import hashlib, hmac
def presign(key, expires, now=None):
    """Explicit SigV4 query presign (UNSIGNED-PAYLOAD), signing clock injectable."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    amz = now.strftime("%Y%m%dT%H%M%SZ"); day = now.strftime("%Y%m%d")
    scope = f"{day}/us-east-1/s3/aws4_request"; host = "127.0.0.1:19000"; path = f"/{B}/{key}"
    q = {"X-Amz-Algorithm": "AWS4-HMAC-SHA256", "X-Amz-Credential": f"probe/{scope}", "X-Amz-Date": amz,
         "X-Amz-Expires": str(expires), "X-Amz-SignedHeaders": "host"}
    cq = "&".join(f"{urllib.parse.quote(k, safe='~')}={urllib.parse.quote(v, safe='~')}" for k, v in sorted(q.items()))
    creq = "\n".join(["PUT", urllib.parse.quote(path), cq, f"host:{host}\n", "host", "UNSIGNED-PAYLOAD"])
    sts = "\n".join(["AWS4-HMAC-SHA256", amz, scope, hashlib.sha256(creq.encode()).hexdigest()])
    k = ("AWS4" + "probeprobe").encode()
    for part in (day, "us-east-1", "s3", "aws4_request"): k = hmac.new(k, part.encode(), hashlib.sha256).digest()
    sig = hmac.new(k, sts.encode(), hashlib.sha256).hexdigest()
    return f"{EP}{path}?{cq}&X-Amz-Signature={sig}"
def raw_put(url, body_len, send_plan, read_response=True):
    """send_plan: list of (bytes_to_send, sleep_after). Returns status line or exception text."""
    u = urllib.parse.urlsplit(url); s = socket.create_connection((u.hostname, u.port), timeout=30)
    s.sendall(f"PUT {u.path}?{u.query} HTTP/1.1\r\nHost: {u.netloc}\r\nContent-Length: {body_len}\r\n\r\n".encode())
    for n, pause in send_plan:
        s.sendall(b"x" * n); time.sleep(pause)
    if not read_response: s.close(); return "closed-without-reading"
    try: data = s.recv(4096).decode(errors="replace")
    except Exception as e: data = repr(e)
    s.close(); first = data.split("\r\n")[0]
    code = data[data.find("<Code>") + 6: data.find("</Code>")] if "<Code>" in data else ""
    return f"{first} {code}".strip()

print("E1 single put 15 MiB via SDK put_object:", end=" ")
s3.put_object(Bucket=B, Key="e1/raw.eml", Body=b"x" * (15 * MIB)); print("objects", keys("e1/"), "multipart uploads", len(mpu()))

print("C0 CONTROL presigned 300 s, immediate PUT:", raw_put(presign("c0/raw.eml", 300), 1024, [(1024, 0)]), "| objects", keys("c0/"))
print("C1 CONTROL presigned signed with clock 60 s in the past, expires 300 s:", raw_put(presign("c1/raw.eml", 300, now=datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(seconds=60)), 1024, [(1024, 0)]), "| objects", keys("c1/"))
print("E2 PUT aborted mid-body (5 of 15 MiB, then close):", end=" ")
r = raw_put(presign("e2/raw.eml", 300), 15 * MIB, [(5 * MIB, 0)], read_response=False); time.sleep(2)
print(r, "| objects", keys("e2/"), "| multipart uploads", len(mpu()))

print("E3 presigned 3 s, request STARTED at 5 s:", raw_put(presign("e3/raw.eml", 3), 1024, [(0, 5.0), (1024, 0)]) if False else "", end="")
url = presign("e3/raw.eml", 3); time.sleep(5); print(raw_put(url, 1024, [(1024, 0)]), "| objects", keys("e3/"))

print("E4 presigned 3 s, headers at 0 s, body trickled until 8 s:", end=" ")
print(raw_put(presign("e4/raw.eml", 3), 2048, [(1024, 8.0), (1024, 0)]), "| objects", keys("e4/"))

print("E5 full body sent, client closes BEFORE reading response:", end=" ")
r = raw_put(presign("e5/raw.eml", 300), 4 * MIB, [(4 * MIB, 0.3)], read_response=False); time.sleep(2)
print(r, "| objects", keys("e5/"))

print("E6 presigned signed with a clock 10 min AHEAD of the server (expires 60 s):", end=" ")
ahead = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=10)
print(raw_put(presign("e6/raw.eml", 60, now=ahead), 1024, [(1024, 0)]), "| objects", keys("e6/"))

print("E7 presigned signed with a clock 20 min AHEAD (expires 60 s):", end=" ")
ahead = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=20)
print(raw_put(presign("e7/raw.eml", 60, now=ahead), 1024, [(1024, 0)]), "| objects", keys("e7/"))

print("E8 presigned signed 5 min in the PAST, expires 120 s (already expired server-side):", end=" ")
past = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(minutes=5)
print(raw_put(presign("e8/raw.eml", 120, now=past), 1024, [(1024, 0)]), "| objects", keys("e8/"))
