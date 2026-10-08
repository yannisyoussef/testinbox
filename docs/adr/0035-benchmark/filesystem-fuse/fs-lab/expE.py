from common import *
import concurrent.futures as cf, threading, sys
phase = sys.argv[1]
if phase == "fill":
    s3.create_bucket(Bucket="late"); body = os.urandom(15 * 2**20); lock = threading.Lock(); log = []
    def w(i):
        n = 0
        while n < 40:
            k = f"w{i}/m-{n}/raw.eml"
            try: s3.put_object(Bucket="late", Key=k, Body=body); r = {"k": k, "ok": True}
            except Exception as e: r = {"k": k, "ok": False, **err(e)}
            with lock: log.append(r)
            if not r["ok"]: return
            n += 1
    with cf.ThreadPoolExecutor(16) as ex: list(ex.map(w, range(16)))
    json.dump({"t": time.time(), "log": log}, open("/w/expE-log.json", "w"))
    print(json.dumps({"ok": sum(r["ok"] for r in log), "failed": sum(not r["ok"] for r in log), "fs": fs()}), flush=True)
else:
    d = json.load(open("/w/expE-log.json")); age = round((time.time() - d["t"]) / 60, 1)
    failed = [r["k"] for r in d["log"] if not r["ok"]]; present = absent = errors = 0; ondisk = 0
    for k in failed:
        try: s3.head_object(Bucket="late", Key=k); present += 1
        except botocore.exceptions.ClientError as e:
            if e.response["ResponseMetadata"]["HTTPStatusCode"] == 404: absent += 1
            else: errors += 1
        ondisk += os.path.exists(f"/fs/data/late/{k}/xl.meta")
    print(json.dumps({"sweep_minutes_after_fill": age, "failed_keys": len(failed), "present_via_s3": present, "absent": absent, "check_errors": errors, "present_on_disk": ondisk}), flush=True)
