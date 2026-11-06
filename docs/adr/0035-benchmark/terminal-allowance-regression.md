# Terminal-completion allowance: regression evidence (gate D, 2026-11-06)

**Owner decision (2026-11-06, relayed by Ops):** "Preserving the historical
terminal-completion allowance (≈ 166.9 ms at 62/s) is accepted, conditional on
regression evidence that the original 30-second outcomes are unchanged. Please
document its exact formula, its rate dependence and its precision. No widening
through rounding or longer windows. T1 p99 ≤ 50 ms is unchanged."

This file is that regression evidence. The formula, rate table and precision
are in [`docs/dev/storage-benchmark.md`, "Terminal-completion
allowance"](../../dev/storage-benchmark.md#terminal-completion-allowance).

## What was compared

| | Before (`0aa27fb`) | After (`efab78a`) |
|---|---|---|
| `scripts/check-storage-activation.sh` sha256 | `f72a56cd6a0c0f6d0ee5538806aef5f1f21b1a9a184dd95913807dac0f496be8` | `f3c6f6e6137114503bd0442092a2245d2641b7ccf803f8030589328a26493654` |
| `backend/benchmark/.../GateEvaluator.kt` sha256 | `d55f6201d040721a3c48ce401ac05a41c5dc0948483cfe95e41664e247e8c669` | `d55f6201d040721a3c48ce401ac05a41c5dc0948483cfe95e41664e247e8c669` (unchanged) |

`efab78a` changes only the activation gate's gate D (`scripts/check-storage-activation.sh`),
its suite and `docs/architecture/storage-activation.md`. The harness's
`GateEvaluator` is byte-identical at both commits, so the harness's own
verdicts are unchanged by construction; the comparison below runs the gate
script. (`GateEvaluator.sustainedRatio`, the harness's copy of the held
reading, is on `app/fasttrack-gate-d-a-prime`, not on this branch; see
"Findings".)

## Evidence inventory

Every committed `evidence.json` in the repository, after `git fetch origin`,
searched on every local and remote ref (`git ls-tree -r <ref> | grep evidence.json`).
None is on an `app/fasttrack-*` branch: those carry only the five gate-script
fixtures in `scripts/testdata/storage-activation/benchmark/`, whose blobs are
identical on all 14 of them. The staging evidence is on Ops branches:

| Evidence | Branch | Commit | Window | Gate D evidence? |
|---|---|---|---|---|
| `staging/2026-11-03-c10dd3d-FAIL` | `ops/adr035-gate-d-c10dd3d-fail-evidence` | `fdab4c3b7afa3f4dd5f3071af8e6c17ffd5afb8d` | 30 s, R = 260 | yes (FAIL) |
| `staging/2026-11-05-9d06ab2-run1` | `ops/adr035-gate-d-9d06ab2-run1-evidence` | `9ffe85931d476b203e1a9817c067c25243b94fb3` | 30 s, R = 260 | yes (FAIL) |
| `staging/2026-11-06-1023af5-run2-part1-ws10000` | `ops/adr035-gate-d-1023af5-run2-part1-evidence` | `4927c206b4a6a9703873f60f7616507472418393` | 30 s, R = 260 | yes (FAIL) |
| `staging/2026-11-06-6347627-run3-r31` (**run 3**) | `ops/adr035-gate-d-6347627-run3-evidence` | `59095045898cf25dac1e7ae805d5bee919aead48` | 30 s, R = 31 | yes (INCOMPLETE) |
| `staging/2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `ops/adr035-diagnostic-80482e6-evidence` | `ad53898205da61cf345119f0e7844f797a4729e0` | 60 s, R = 31 | no (`adrEvidence: false`) |
| `staging/2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | `ops/adr035-focused-p-80482e6-evidence` | `26f92956afd6e8314a6d601f01c6bdc8a112085d` | 60 s, R = 31 | no |
| `staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run1…run8` (8 files) | `ops/adr035-aprime-focused-evidence` | `ebdad5897c89505548044da61cc47d3d3b14177b` | 60 s, R = 31 | no |

Every gate-D run so far measured 30 s. The 60 s files are not gate evidence;
they are included because they are the only real longer windows, i.e. the only
real inputs on which `efab78a` may legitimately change a reading.

## Commands

```
git fetch origin
docs/adr/0035-benchmark/terminal-allowance-regression.sh 0aa27fb efab78a
```

The script reads both gate scripts, the fixtures and every evidence file from
git objects, sets up every other gate exactly as
`scripts/check-storage-activation.test.sh` does (so gate D is the only gate
that can differ), declares R_gated as each evidence's `harness.expectedRate`,
runs `check-storage-activation.sh --mode ALL ... --benchmark-evidence <file>`
at each commit, and prints the tables below. Run with jq 1.7.1 on macOS; two
consecutive runs produce byte-identical output.

## Result

- **Every scenario with a window of at most 30 s (53 rows, 28 of them from real
  staging evidence, run 3 included) has the same sustained2x verdict at both
  commits**, and for every 30 s input the gate's **whole output is
  byte-identical** at both commits (`whole output identical: yes`).
- Run 3's `chosen-ws200-c100-r62` (terminal lag 204.77 ms, ratio 0.99375)
  blocks at both commits; its `chosen-ws200-c10-r62` (158.10 ms, ratio
  0.99529) passes at both.
- The 30 s boundary decides at the microsecond and identically at both
  commits: lag_max(62/s) − 1 µs passes, + 1 µs blocks.
- Exactly two scenario verdicts change, both over a **60 s** window and both
  from pass to BLOCK (a tightening, never a loosening):
  - `run4-focused-cohort-7b09bcb` `chosen-ws31-c10-r62`: 231.35 ms, ratio
    0.99643 read alone (inside the 317.6 ms a 60 s window would allow), 0.99288
    read over 30 s. Not gate evidence; gate D was BLOCKED at both commits.
  - synthetic `boundary-60s-lagmax-plus-1us`: ACTIVATION READY at `0aa27fb`
    (the widening), BLOCKED at `efab78a`.
- No input's gate D or overall verdict moved from BLOCKED to PASS.
- `fixture-beta` (the suite's derived R = 31 fixture) keeps the 520/s
  fixture's `scheduledEvents` 15600 at 62/s, so `efab78a` reads it as a long
  window (held reading 1.00027 because its achieved rate and its 8 ms lag are
  not mutually consistent). Its verdict is unchanged; it is a fixture
  inconsistency, not a gate one.

## Findings (reported, not fixed here)

1. **Gate script: a long window without `scheduledEvents` keeps the long
   window's allowance.** The window is inferred only from
   `(.scheduledEvents // 0) > floor(rate × 30)`. A scenario that omits
   `scheduledEvents` is read as at most 30 s, so achieved/offered alone
   decides; a 60 s scenario with a 300 ms terminal lag (ratio 0.9953) then
   passes, although it records `durationSeconds` 60 and a missing
   `completedEvents` or `terminalLagMs` fails closed. The harness always
   writes `scheduledEvents`, so only evidence from another producer can hit
   it. Demonstrated by the failing case "D: a 60 s window that omits
   scheduledEvents does not widen the allowance … (KNOWN GAP: fails today)"
   in `scripts/check-storage-activation.test.sh`.
2. **Harness on this branch: `GateEvaluator` reads sustained2x as
   achieved/offered alone**, so a run made with `--duration-seconds 60`
   (which `AdrConformance` does not flag) records `sustained2x` PASS at up
   to 317.6 ms. Gate D still blocks such evidence (its recomputation
   disagrees with the recorded PASS), so the activation verdict is not
   widened, but the harness's own verdict is, and the script's comment that
   `GateEvaluator.sustainedRatio` "is the same function" is true only on
   `app/fasttrack-gate-d-a-prime`. Demonstrated by the failing
   `GateEvaluatorTest` "a window longer than 30 s does not widen the
   allowance - lag_max plus 1 us fails (KNOWN GAP - fails on this branch)";
   the same test file passes 30/30 with a-prime's `GateEvaluator.kt`
   substituted.

No rounding before comparison was found (see the precision section of
`docs/dev/storage-benchmark.md`).

## Output of the command

lag_max(62/s) as jq computes it: 166.88280110228297 ms

### Inputs

A committed input's sha256 is that of its git object's bytes (a fixture before @GIT_SHA@ is
rendered). A derived input carries the throwaway repository's commit as gitSha, which differs
on every run, so its sha256 is that of `jq -S 'del(.gitSha)'` of it.

| Input | Source | sha256 |
|---|---|---|
| `2026-11-03-c10dd3d-FAIL` | fdab4c3b7afa3f4dd5f3071af8e6c17ffd5afb8d:docs/adr/0035-benchmark/staging/2026-11-03-c10dd3d-FAIL/evidence.json | `7d530b2d2a3bebf52de35e5125940e060a620d506f4e14aa84ee14cfaa07ca47` |
| `2026-11-05-9d06ab2-run1` | 9ffe85931d476b203e1a9817c067c25243b94fb3:docs/adr/0035-benchmark/staging/2026-11-05-9d06ab2-run1/evidence.json | `8e0722fb2d2db1f08c5076f01c4b2885677679b26a44d8a26880274064d6d849` |
| `2026-11-06-1023af5-run2-part1-ws10000` | 4927c206b4a6a9703873f60f7616507472418393:docs/adr/0035-benchmark/staging/2026-11-06-1023af5-run2-part1-ws10000/evidence.json | `fc3d93c33e50c5386fb7bffe9232292b018ceaf94cb12d2bb06924ff75531389` |
| `2026-11-06-6347627-run3-r31` | 59095045898cf25dac1e7ae805d5bee919aead48:docs/adr/0035-benchmark/staging/2026-11-06-6347627-run3-r31/evidence.json | `ea566b817382e474586e8326345588b2295cf366e487e48914de453b68ef952e` |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | ad53898205da61cf345119f0e7844f797a4729e0:docs/adr/0035-benchmark/staging/2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence/evidence.json | `f9c5f72723f4f8f4121a5dd50c1aa9a01e98044d7a4cd60bbe9005f60324c38f` |
| `2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | 26f92956afd6e8314a6d601f01c6bdc8a112085d:docs/adr/0035-benchmark/staging/2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence/evidence.json | `c7e380dedae5dfb5965a04aeaffcda7e296685f1e5ce318441eed1de214a71bd` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run1-focused-cohort-82eeee3` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run1-focused-cohort-82eeee3/evidence.json | `bd2769341408d33df5b5553d850f0bc5f31a8239210aba81cb18c606c2db8aa7` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run2-focused-cohort-7b09bcb` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run2-focused-cohort-7b09bcb/evidence.json | `ed3c0fab7add2be020da0779c67bb6b23873836401b81536500aff0dca21533d` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run3-focused-cohort-82eeee3` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run3-focused-cohort-82eeee3/evidence.json | `0f12746759b31380324c8d681bf67cc94cf576f9eb7389896391242b7c8372c6` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run4-focused-cohort-7b09bcb` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run4-focused-cohort-7b09bcb/evidence.json | `9b61a7d6cbe1383b0a439bd83219ca35f0aa2cdc17393748114b342e314ece5a` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run5-focused-82eeee3` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run5-focused-82eeee3/evidence.json | `84fd03658782269a7a33e62e27aeba042d6527a56357e1cdf3d2f7dffd9f583a` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run6-focused-7b09bcb` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run6-focused-7b09bcb/evidence.json | `085316ad10055e4562f6d721c24d50cd152776bfa17e2dc369c220492eea4863` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run7-focused-82eeee3` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run7-focused-82eeee3/evidence.json | `f6465acc3477e95fef5201d3d0663545709837f44d5cd6d41d34740811817878` |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run8-focused-7b09bcb` | ebdad5897c89505548044da61cc47d3d3b14177b:docs/adr/0035-benchmark/staging/2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence/run8-focused-7b09bcb/evidence.json | `e8a70701c34dcbc24da3a26ba2500cf88baed45587d156971569d85aa8d3231f` |
| `fixture-pass` | efab78a:scripts/testdata/storage-activation/benchmark/pass.json | `1653ef72bf9a9286c2600ad099924e5eb0cd5e9985050ab73478ba27022fd3b6` |
| `fixture-fail` | efab78a:scripts/testdata/storage-activation/benchmark/fail.json | `7ef5048ae168feabedf89b6150f0f009e2d7e66efe1eac8eebcff911fb12fcc1` |
| `fixture-pass-but-t1-violates` | efab78a:scripts/testdata/storage-activation/benchmark/pass-but-t1-violates.json | `5ef4ed6874877c0e81986d76797af542012af45458ef39b6c692357ddc4f14b5` |
| `fixture-missing-reference` | efab78a:scripts/testdata/storage-activation/benchmark/missing-reference.json | `86ea901e5d017728ca0598dcb5ca67c8545eee9034eff67635fb38ee02dbd489` |
| `fixture-incomplete` | efab78a:scripts/testdata/storage-activation/benchmark/incomplete.json | `ee4ac23cf814171a5d2d0a1235e7711633eb16545282a9164257357e03e1ee13` |
| `fixture-beta` | derived from fixture-pass.json as check-storage-activation.test.sh derives bench-beta.json | `8f530e794a51b5d61091a0230f7872ca206a0c5247c3338e760fde4ce9b18e32` (gitSha removed) |
| `boundary-30s-lagmax-minus-1us` | synthetic: 30 s window, c100 lag = lag_max(62/s) - 0.001 ms = 166.88180110228296 ms | `4e0b677ba524bc6bc5a2f0af42ec22cf4d57d98a1fb61465d76181c6dd8dbea2` (gitSha removed) |
| `boundary-30s-lagmax-plus-1us` | synthetic: 30 s window, c100 lag = lag_max(62/s) + 0.001 ms = 166.88380110228297 ms | `2bd3b2f3c2175769193be2a0b35908873ca4e3b3589fe0d61a8c8d0621c65ee6` (gitSha removed) |
| `boundary-60s-lagmax-minus-1us` | synthetic: 60 s window, c100 lag = lag_max(62/s) - 0.001 ms = 166.88180110228296 ms | `e2e6875fd59049d462d029bd034f8555aa07e7d3455896f767f16a2b0198ad01` (gitSha removed) |
| `boundary-60s-lagmax-plus-1us` | synthetic: 60 s window, c100 lag = lag_max(62/s) + 0.001 ms = 166.88380110228297 ms | `eb97940d00e62a355e241992b9377bc71798c487d421429a791db6c7c130a35f` (gitSha removed) |

### Gate D and the overall verdict

| Input | D @0aa27fb | D @efab78a | overall @0aa27fb | overall @efab78a | whole output identical |
|---|---|---|---|---|---|
| `2026-11-03-c10dd3d-FAIL` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-05-9d06ab2-run1` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-1023af5-run2-part1-ws10000` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-6347627-run3-r31` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run1-focused-cohort-82eeee3` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run2-focused-cohort-7b09bcb` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run3-focused-cohort-82eeee3` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run4-focused-cohort-7b09bcb` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | NO |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run5-focused-82eeee3` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run6-focused-7b09bcb` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run7-focused-82eeee3` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run8-focused-7b09bcb` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `fixture-pass` | PASS | PASS | ACTIVATION READY | ACTIVATION READY | yes |
| `fixture-fail` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `fixture-pass-but-t1-violates` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `fixture-missing-reference` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `fixture-incomplete` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `fixture-beta` | PASS | PASS | ACTIVATION READY | ACTIVATION READY | yes |
| `boundary-30s-lagmax-minus-1us` | PASS | PASS | ACTIVATION READY | ACTIVATION READY | yes |
| `boundary-30s-lagmax-plus-1us` | BLOCKED | BLOCKED | ACTIVATION BLOCKED | ACTIVATION BLOCKED | yes |
| `boundary-60s-lagmax-minus-1us` | PASS | PASS | ACTIVATION READY | ACTIVATION READY | yes |
| `boundary-60s-lagmax-plus-1us` | PASS | BLOCKED | ACTIVATION READY | ACTIVATION BLOCKED | NO |

### Per-scenario sustained2x (CHOSEN, 2R, load-gated concurrency 10/25/50/100)

N30 = floor(rate x 30); a scenario is a long window when scheduledEvents > N30. ratio = achievedRate / offeredRate;
over30 = (completed / scheduled) x N30 / ((N30 - 1) + rate x lag), read only for a long window.

| Input | Scenario | scheduled | N30 | window | lag ms | ratio | over30 | sustained2x @0aa27fb | sustained2x @efab78a | changed |
|---|---|---:|---:|---|---:|---:|---:|---|---|---|
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c10-r520` | 15600 | 15600 | <= 30 s | 230032.394681 | 0.11537109406810965 | - | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c25-r520` | 15600 | 15600 | <= 30 s | 223432.484232 | 0.11837562077468541 | - | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c50-r520` | 15600 | 15600 | <= 30 s | 221833.103015 | 0.11889833379520118 | - | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c100-r520` | 15600 | 15600 | <= 30 s | 242410.509522 | 0.10842734014737808 | - | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | 268532.301057 | 0.10049228558579001 | - | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c25-r520` | 15600 | 15600 | <= 30 s | 279566.408418 | 0.09691034153014538 | - | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c50-r520` | 15600 | 15600 | <= 30 s | 285143.271274 | 0.09457904969086176 | - | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c100-r520` | 15600 | 15600 | <= 30 s | 327968.076786 | 0.0800731053521971 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c10-r520` | 15600 | 15600 | <= 30 s | 196962.954138 | 0.13218128178108665 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c25-r520` | 15600 | 15600 | <= 30 s | 196102.371701 | 0.13268438953825046 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c50-r520` | 15600 | 15600 | <= 30 s | 221113.750803 | 0.11938444487193765 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c100-r520` | 15600 | 15600 | <= 30 s | 268055.705357 | 0.09842054397101269 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | 209927.131409 | 0.1250389661368432 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c25-r520` | 15600 | 15600 | <= 30 s | 219580.49561 | 0.12020262675407888 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c50-r520` | 15600 | 15600 | <= 30 s | 237403.019452 | 0.11210471267060106 | - | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c100-r520` | 15600 | 15600 | <= 30 s | 243098.366446 | 0.10828803392037822 | - | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | 208003.071856 | 0.1260498117505181 | - | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c25-r520` | 15600 | 15600 | <= 30 s | 223293.619276 | 0.1184405191729236 | - | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c50-r520` | 15600 | 15600 | <= 30 s | 226505.557863 | 0.11688993153140598 | - | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c100-r520` | 15600 | 15600 | <= 30 s | 261372.351721 | 0.10112028758573972 | - | BLOCK | BLOCK | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c10-r62` | 1860 | 1860 | <= 30 s | 158.09718 | 0.9952900173172858 | - | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c25-r62` | 1860 | 1860 | <= 30 s | 21.820067 | 0.9998103348130406 | - | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c50-r62` | 1860 | 1860 | <= 30 s | 16.544482 | 0.9999861518584404 | - | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c100-r62` | 1860 | 1860 | <= 30 s | 204.77424 | 0.9937511204394821 | - | BLOCK | BLOCK | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c10-r62` | 1860 | 1860 | <= 30 s | 80.989035 | 0.9978426640605169 | - | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c25-r62` | 1860 | 1860 | <= 30 s | 17.686225 | 0.9999480962608047 | - | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c50-r62` | 1860 | 1860 | <= 30 s | 17.152703 | 0.9999658787976295 | - | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c100-r62` | 1860 | 1860 | <= 30 s | 25.808754 | 0.9996774466742173 | - | pass | pass | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws200-c10-r62` | 3720 | 1860 | long | 1320.059826 | 0.978730062214418 | 0.958346100292216 | BLOCK | BLOCK | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws200-c100-r62` | 3720 | 1860 | long | 542.313735 | 0.9899740935717654 | 0.9814419230931204 | BLOCK | BLOCK | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 138.022804 | 0.9979725560132511 | 0.9959533164592631 | pass | pass | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 6586.447254 | 0.8947608742141707 | 0.8143833445455189 | BLOCK | BLOCK | no |
| `2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 26.374731 | 0.9998292675045626 | 0.9996585933069397 | pass | pass | no |
| `2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 34.269643 | 0.999697747867306 | 0.9993956784006987 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run1-focused-cohort-82eeee3` | `chosen-ws31-c10-r62` | 3720 | 1860 | long | 12548.214441 | 0.8272201145419572 | 0.7053498485130446 | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run1-focused-cohort-82eeee3` | `chosen-ws31-c100-r62` | 3720 | 1860 | long | 16035.698753 | 0.7729334003306971 | 0.6384030193316388 | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run2-focused-cohort-7b09bcb` | `chosen-ws31-c10-r62` | 3720 | 1860 | long | 7964.875124 | 0.8830185021922599 | 0.7905399542708035 | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run2-focused-cohort-7b09bcb` | `chosen-ws31-c100-r62` | 3720 | 1860 | long | 1985.888157 | 0.9682141878101095 | 0.9383868011937099 | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run3-focused-cohort-82eeee3` | `chosen-ws31-c10-r62` | 3720 | 1860 | long | 14091.392198 | 0.8099869974885159 | 0.6806539052798642 | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run3-focused-cohort-82eeee3` | `chosen-ws31-c100-r62` | 3720 | 1860 | long | 20650.164099 | 0.721499541806363 | 0.5744892667368269 | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run4-focused-cohort-7b09bcb` | `chosen-ws31-c10-r62` | 3720 | 1860 | long | 231.348555 | 0.996425828474846 | 0.9928771153696253 | pass | BLOCK | YES |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run4-focused-cohort-7b09bcb` | `chosen-ws31-c100-r62` | 3720 | 1860 | long | 10530.906151 | 0.8401350455081904 | 0.7311151596643728 | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run5-focused-82eeee3` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 68.465531 | 0.9991284852172093 | 0.9982584881962796 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run5-focused-82eeee3` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 43.639557 | 0.9995417013839256 | 0.9990838226592489 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run6-focused-7b09bcb` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 45.317232 | 0.9995137665379922 | 0.9990280057007274 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run6-focused-7b09bcb` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 21.159069 | 0.999916173077542 | 0.9998323602164112 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run7-focused-82eeee3` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 162.208005 | 0.9975712635720514 | 0.9951542960902471 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run7-focused-82eeee3` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 22.485414 | 0.9998940715220312 | 0.9997881654919687 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run8-focused-7b09bcb` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 121.440983 | 0.9982478761430296 | 0.9965018814316142 | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run8-focused-7b09bcb` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 58.025061 | 0.9993022200867913 | 0.9986054132967556 | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c25-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c50-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c100-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-fail` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9576923076923077 | - | BLOCK | BLOCK | no |
| `fixture-fail` | `chosen-ws10000-c25-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-fail` | `chosen-ws10000-c50-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-fail` | `chosen-ws10000-c100-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c25-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c50-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c100-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c25-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c50-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c100-r520` | 15600 | 15600 | <= 30 s | 8.0 | 0.9965000000000002 | - | pass | pass | no |
| `fixture-incomplete` | `chosen-ws10000-c10-r520` | 15600 | 15600 | <= 30 s | absent | 0.9965384615384616 | - | pass | pass | no |
| `fixture-beta` | `chosen-ws10000-c10-r62` | 15600 | 1860 | long | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | no |
| `fixture-beta` | `chosen-ws10000-c25-r62` | 15600 | 1860 | long | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | no |
| `fixture-beta` | `chosen-ws10000-c50-r62` | 15600 | 1860 | long | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | no |
| `fixture-beta` | `chosen-ws10000-c100-r62` | 15600 | 1860 | long | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c10-r62` | 1860 | 1860 | <= 30 s | 8.0 | 1.0002710411853535 | - | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c25-r62` | 1860 | 1860 | <= 30 s | 8.0 | 1.0002710411853535 | - | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c50-r62` | 1860 | 1860 | <= 30 s | 8.0 | 1.0002710411853535 | - | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c100-r62` | 1860 | 1860 | <= 30 s | 166.88180110228296 | 0.9950000330008346 | - | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c10-r62` | 1860 | 1860 | <= 30 s | 8.0 | 1.0002710411853535 | - | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c25-r62` | 1860 | 1860 | <= 30 s | 8.0 | 1.0002710411853535 | - | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c50-r62` | 1860 | 1860 | <= 30 s | 8.0 | 1.0002710411853535 | - | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c100-r62` | 1860 | 1860 | <= 30 s | 166.88380110228297 | 0.9949999669991678 | - | BLOCK | BLOCK | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c25-r62` | 3720 | 1860 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c50-r62` | 3720 | 1860 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 166.88180110228296 | 0.9974937509190691 | 0.9950000330008345 | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c10-r62` | 3720 | 1860 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c25-r62` | 3720 | 1860 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c50-r62` | 3720 | 1860 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c100-r62` | 3720 | 1860 | long | 166.88380110228297 | 0.9974937177526109 | 0.9949999669991678 | pass | BLOCK | YES |
