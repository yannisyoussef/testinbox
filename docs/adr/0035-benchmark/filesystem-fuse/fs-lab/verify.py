from common import *
n = bad = 0; tok = None
while True:
    kw = {"Bucket": "fill", "MaxKeys": 1000, **({"ContinuationToken": tok} if tok else {})}
    r = s3.list_objects_v2(**kw)
    for o in r.get("Contents", []):
        g = s3.get_object(Bucket="fill", Key=o["Key"]); data = g["Body"].read(); n += 1; bad += len(data) != o["Size"]
    if not r.get("IsTruncated"): break
    tok = r["NextContinuationToken"]
print(json.dumps({"listed_and_read": n, "size_mismatch": bad}), json.dumps(fs()), flush=True)
