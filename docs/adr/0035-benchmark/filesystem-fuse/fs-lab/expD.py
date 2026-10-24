from common import *
import concurrent.futures as cf
def drain(prefix=""):
    while True:
        r = s3.list_objects_v2(Bucket="fill", Prefix=prefix, MaxKeys=1000); ks = [{"Key": o["Key"]} for o in r.get("Contents", [])]
        if not ks: return
        s3.delete_objects(Bucket="fill", Delete={"Objects": ks})
drain(); time.sleep(2); print("cleared:", json.dumps(fs()), flush=True)
body = os.urandom(2**20)
with cf.ThreadPoolExecutor(16) as ex: list(ex.map(lambda i: s3.put_object(Bucket="fill", Key=f"d/m-{i}", Body=body), range(1000)))
time.sleep(2); before = fs(); print("1000 x 1 MiB written:", json.dumps(before), flush=True)
t0 = time.time(); r = s3.list_objects_v2(Bucket="fill", Prefix="d/", MaxKeys=1000)
s3.delete_objects(Bucket="fill", Delete={"Objects": [{"Key": o["Key"]} for o in r["Contents"]]}); dt = time.time() - t0
for s in (0, 5, 15, 30, 60):
    time.sleep(s - (time.time() - t0 - dt) if s else 0); f = fs()
    print(f"t+{s:>2}s after DeleteObjects({len(r['Contents'])}): freed {round((before['used'] - f['used']) / 2**20)} MiB of {round(1000 * 1069193 / 2**20)} MiB expected", flush=True)
