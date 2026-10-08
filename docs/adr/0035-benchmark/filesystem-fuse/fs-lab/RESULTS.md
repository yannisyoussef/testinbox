# Filesystem-fuse lab, 2026-10-08 (isolated: 2 GiB ext4 rw,relatime loop, MinIO member 3f97c565, 1536m, kernel 6.8.0-142-generic)
A amplification (per object: physical / inodes): 512 B -> 12 308 B / 3; 4 KiB -> 16 404 / 3; 16 KiB -> 28 692 / 3; 64 KiB -> 77 865 / 3;
  128 KiB -> 143 401 / 3; 256 KiB -> 282 706 / 5; 1 MiB -> 1 069 193 / 5; 15 MiB -> 15 749 530 / 5   (fixed ~12 KiB/object + 4 KiB rounding)
B ENOSPC, 16 x 15 MiB writers: MinIO filled to 0 B available (no free-space reserve); failures 13 x 500 InternalError
  "cause(no space left on device)" + 3 x 507 XMinioStorageFull; 113 acknowledged all byte-identical; 16 failed all absent
C1 at 0 B available: 1 KiB inline PUT OK, 0-byte PUT OK (root-reserved blocks), LIST/HEAD OK, DELETE OK and freed
  immediately for single/15-object deletes; 15 MiB PUT OK right after deletes
C2 restart at full: ready in 4 s; 115 objects listed + read, 0 size mismatches
D DeleteObjects(1000 x 1 MiB): 4 of 1020 MiB freed after 60 s (moved to .minio.sys/tmp/.trash); purged after ~251 s
E ENOSPC late-commit check: 16 failed keys absent via S3 AND on disk at +17.8 min (>= C_max + margin)
Live 48 GiB fs (tune2fs): 12 582 912 blocks, 587 198 reserved (2.24 GiB), 3 145 728 inodes; df size 50 407 927 808 B
