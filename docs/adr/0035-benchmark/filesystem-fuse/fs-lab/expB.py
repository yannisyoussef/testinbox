from common import *
import concurrent.futures as cf, threading
s3.create_bucket(Bucket="fill")
lock = threading.Lock(); log = []; body = os.urandom(15 * 2**20); digest = hashlib.sha256(body).hexdigest()
print("start", json.dumps(fs()), flush=True)
def writer(w):
    n = 0
    while n < 40:
        k = f"w{w}/m-{n}/raw.eml"; t = time.time()
        try:
            s3.put_object(Bucket="fill", Key=k, Body=body); r = {"k": k, "ok": True}
        except Exception as e:
            r = {"k": k, "ok": False, **err(e)}
        r["t"] = round(time.time() - t, 3); r["fs_avail"] = fs()["avail"]
        with lock: log.append(r)
        if not r["ok"]: return
        n += 1
with cf.ThreadPoolExecutor(16) as ex: list(ex.map(writer, range(16)))
oks = [r for r in log if r["ok"]]; bad = [r for r in log if not r["ok"]]
print("after fill", json.dumps(fs()), "ok", len(oks), "failed", len(bad), flush=True)
from collections import Counter
print("failure kinds:", json.dumps(Counter((r["status"], r["code"]) for r in bad).most_common()), flush=True)
print("sample failure:", json.dumps(bad[0] if bad else None), flush=True)
print("min fs_avail at a success:", min((r["fs_avail"] for r in oks), default=None), "| fs_avail at first failure:", bad[0]["fs_avail"] if bad else None, flush=True)
# integrity: every acknowledged PUT is complete; every failed key is absent
corrupt = 0
for r in oks:
    o = s3.get_object(Bucket="fill", Key=r["k"]); data = o["Body"].read()
    corrupt += hashlib.sha256(data).hexdigest() != digest
absent = present = errs = 0
for r in bad:
    try: s3.head_object(Bucket="fill", Key=r["k"]); present += 1
    except botocore.exceptions.ClientError as e:
        if e.response["ResponseMetadata"]["HTTPStatusCode"] == 404: absent += 1
        else: errs += 1
print(json.dumps({"acknowledged": len(oks), "corrupt_or_short": corrupt, "failed_keys_absent": absent, "failed_keys_present": present, "head_errors": errs}), flush=True)
json.dump({"log": log}, open("/w/expB-log.json", "w"))
