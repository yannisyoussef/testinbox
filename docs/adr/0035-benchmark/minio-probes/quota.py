import boto3, time
from botocore.config import Config
s3 = boto3.client("s3", endpoint_url="http://127.0.0.1:19000", aws_access_key_id="probe", aws_secret_access_key="probeprobe",
                  region_name="us-east-1", config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}))
def put(k, n):
    try: s3.put_object(Bucket="quota", Key=k, Body=b"x" * n); return "200"
    except Exception as e: r = e.response; return f"{r['ResponseMetadata']['HTTPStatusCode']} {r['Error']['Code']}"
print("Q1 single 2 MiB object into a 1 MiB-quota bucket:", put("q1", 2 << 20))
print("Q2..Q6 five 400 KiB objects back to back:", [put(f"q{i}", 400 << 10) for i in range(2, 7)])
time.sleep(12); print("Q7 after 12 s:", put("q7", 400 << 10))
print("objects:", [o["Key"] for o in s3.list_objects_v2(Bucket="quota").get("Contents", [])])
