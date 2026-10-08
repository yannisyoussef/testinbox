from common import *
s3.create_bucket(Bucket="amp")
rows = []
for size, n in [(512, 200), (4096, 200), (16384, 200), (65536, 100), (131072, 100), (262144, 50), (1048576, 30), (15 * 2**20, 10)]:
    time.sleep(2); b = fs(); body = os.urandom(size)
    for i in range(n): s3.put_object(Bucket="amp", Key=f"s{size}/m-{i}/raw.eml", Body=body)
    time.sleep(3); a = fs()
    rows.append({"payloadBytes": size, "objects": n, "physPerObject": round((a["used"] - b["used"]) / n), "amplification": round((a["used"] - b["used"]) / (n * size), 3),
                 "inodesPerObject": round((a["inodes_used"] - b["inodes_used"]) / n, 2)})
    print(json.dumps(rows[-1]), flush=True)
