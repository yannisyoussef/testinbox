import boto3, botocore, os, time, json, hashlib
from botocore.config import Config
s3 = boto3.client("s3", endpoint_url="http://fuselab-minio:9000", aws_access_key_id="labroot", aws_secret_access_key="labrootpw12345",
                  region_name="us-east-1", config=Config(retries={"max_attempts": 1, "mode": "standard"}, connect_timeout=5, read_timeout=120, s3={"addressing_style": "path"}))
def fs():
    os.sync() if hasattr(os, "sync") else None
    v = os.statvfs("/fs"); return {"used": (v.f_blocks - v.f_bfree) * v.f_frsize, "avail": v.f_bavail * v.f_frsize, "inodes_used": v.f_files - v.f_ffree, "inodes_free": v.f_ffree}
def err(e):
    if isinstance(e, botocore.exceptions.ClientError):
        r = e.response; return {"status": r.get("ResponseMetadata", {}).get("HTTPStatusCode"), "code": r.get("Error", {}).get("Code"), "msg": (r.get("Error", {}).get("Message") or "")[:140]}
    return {"status": None, "code": type(e).__name__, "msg": str(e)[:140]}
