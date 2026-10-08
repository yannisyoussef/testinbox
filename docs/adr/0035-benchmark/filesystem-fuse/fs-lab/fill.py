from common import *
import concurrent.futures as cf
body = os.urandom(15 * 2**20)
def w(i):
    n = 0
    while True:
        try: s3.put_object(Bucket="fill", Key=f"c2-{i}/m-{n}", Body=body); n += 1
        except Exception as e: return err(e)["code"]
with cf.ThreadPoolExecutor(8) as ex: codes = list(ex.map(w, range(8)))
print("refill stopped with:", sorted(set(codes)), json.dumps(fs()), flush=True)
