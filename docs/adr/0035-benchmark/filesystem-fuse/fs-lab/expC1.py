from common import *
def attempt(label, fn):
    try: fn(); r = {"ok": True}
    except Exception as e: r = {"ok": False, **err(e)}
    print(label, json.dumps(r), json.dumps(fs()), flush=True)
attempt("PUT 1KiB (inline) at full:", lambda: s3.put_object(Bucket="fill", Key="small/one", Body=os.urandom(1024)))
attempt("PUT 0B probe at full:", lambda: s3.put_object(Bucket="fill", Key="_probe/x", Body=b""))
attempt("LIST at full:", lambda: s3.list_objects_v2(Bucket="fill", MaxKeys=5))
attempt("HEAD at full:", lambda: s3.head_object(Bucket="fill", Key="w0/m-0/raw.eml"))
attempt("DELETE one 15MiB at full:", lambda: s3.delete_object(Bucket="fill", Key="w0/m-0/raw.eml"))
attempt("HEAD deleted:", lambda: s3.head_object(Bucket="fill", Key="w0/m-0/raw.eml"))
keys = [{"Key": f"w{w}/m-{n}/raw.eml"} for w in range(1, 6) for n in range(3)]
attempt("DeleteObjects x15 (prefix-style) at full:", lambda: s3.delete_objects(Bucket="fill", Delete={"Objects": keys}))
t0 = time.time(); print("free right after deletes:", fs()["avail"], flush=True)
while fs()["avail"] < 200 * 2**20 and time.time() - t0 < 900: time.sleep(5)
print(f"free >= 200 MiB after {round(time.time() - t0)} s:", fs()["avail"], flush=True)
attempt("PUT 15MiB after purge:", lambda: s3.put_object(Bucket="fill", Key="after/one", Body=os.urandom(15 * 2**20)))
