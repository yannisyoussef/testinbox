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

Three states of `scripts/check-storage-activation.sh` gate D (sha256s in "Gate
scripts" below):

| Label | State |
|---|---|
| `0aa27fb` | before the window was held: achieved/offered alone |
| `efab78a` | the window held at 30 s, a long window inferred from `scheduledEvents` only |
| `WORKTREE` | the commit that adds this text: both widening gaps closed (`scheduledEvents` > N₃₀ **or** `durationSeconds` > 30 marks a long window, the lower reading counts; a CHOSEN 2R scenario without `scheduledEvents` blocks) |

The harness's `GateEvaluator.kt` is byte-identical at `0aa27fb` and `efab78a`;
the commit that adds this text replaces it with
`app/fasttrack-gate-d-a-prime`'s (848359e) verbatim, adding
`sustainedRatio`, so the harness's own verdict reads over 30 s as gate D does.

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
docs/adr/0035-benchmark/terminal-allowance-regression.sh 0aa27fb efab78a WORKTREE
```

(`WORKTREE` reads the checked-out script and fixtures; run it at the commit
that adds this text.)

The script reads each revision's gate script, the last revision's fixtures and
every evidence file from git objects (or, for `WORKTREE`, the checkout), sets up every other gate exactly as
`scripts/check-storage-activation.test.sh` does (so gate D is the only gate
that can differ), declares R_gated as each evidence's `harness.expectedRate`,
runs `check-storage-activation.sh --mode ALL ... --benchmark-evidence <file>`
at each revision, and prints the tables below. Run with jq 1.7.1 on macOS; two
consecutive runs produce byte-identical output.

## Result

**Real staging evidence.** For every 30 s input (the four gate-D runs, run 3
included, 28 load-gated 2R scenarios) the gate's whole output is
byte-identical at all three revisions: no verdict and no message moved.

**`0aa27fb` → `efab78a`** (the window held): of the 53 scenarios whose schedule
is at most 30 s, 53 keep their verdict. Two verdicts change, both 60 s and
both pass → BLOCK:

- `run4-focused-cohort-7b09bcb` `chosen-ws31-c10-r62` (non-gate evidence,
  gate D BLOCKED at every revision): 231.35 ms, 0.99643 read alone, 0.99288
  read over 30 s;
- synthetic `boundary-60s-lagmax-plus-1us`: ACTIVATION READY → BLOCKED (the
  widening the owner ruled out).

**`efab78a` → `WORKTREE`** (the gaps closed): one further verdict changes,
pass → BLOCK:

- `fixture-incomplete` `chosen-ws10000-c10-r520`: a 30 s schedule
  (`scheduledEvents` 15600 = N₃₀) with `durationSeconds` 30.1 and **no
  `terminalLagMs`** (null). Its measured span is above 30 s, so it must also
  be read over 30 s, and without its terminal lag it cannot be: fail closed.
  This is the only one of the 53 rows at a 30 s schedule whose verdict moved,
  and it moved towards blocking. It is a gate-script fixture; the harness
  always records `terminalLagMs`, and no committed staging evidence lacks it.
  Gate D was already BLOCKED on this fixture (it is the INCOMPLETE fixture)
  at every revision.

**Nothing moved from BLOCK to pass**, for any scenario, and no gate D or
overall verdict moved from BLOCKED to PASS/READY at any step.

Other facts the tables show:

- Run 3's `chosen-ws200-c100-r62` (204.77 ms, ratio 0.99375) blocks and its
  `chosen-ws200-c10-r62` (158.10 ms, ratio 0.99529) passes, at all three
  revisions.
- The 30 s boundary decides at the microsecond identically at all three:
  lag_max(62/s) − 1 µs passes, + 1 µs blocks. The boundary fixtures now carry
  a realistic `durationSeconds` (30.15 s), so `WORKTREE` reads them over 30 s
  too, and agrees.
- `fixture-beta` is now derived over a 30 s window (`scheduledEvents` =
  ⌊rate × 30⌋, the completion fraction kept): 1860 at 62/s, where the
  derivation used to keep the 520/s fixture's 15600 (a 251 s window at 62/s,
  which `efab78a` read as long). Its four rows show `30 s` and pass at every
  revision; the comparison derives it the new way for all three.
- Rows in all: 57 at a 30 s schedule (the 53 above plus `fixture-beta`'s 4),
  30 long; 3 changed, all pass → BLOCK.

## Findings and their closure

Both widening paths found while writing the precision section are closed by
the commit that adds this text; their demonstration tests now pass.

1. **Gate script, closed.** `efab78a` inferred a long window only from
   `(.scheduledEvents // 0) > N₃₀`, so a 60 s scenario that omitted
   `scheduledEvents` kept the 60 s allowance (300 ms passed). Now a CHOSEN
   2R scenario without `scheduledEvents` blocks, `durationSeconds` > 30 also
   marks a long window, and the lower reading counts, so neither input can
   relax anything. Tests: "a 60 s window that omits scheduledEvents (and
   durationSeconds) ... blocks", "60 s with durationSeconds but without
   scheduledEvents blocks", "a 60 s durationSeconds is read over 30 s even
   when scheduledEvents says 30 s", "a durationSeconds of 29 s does not
   shorten a 60 s schedule", "a 30 s schedule measured over more than 30 s
   without its terminal lag blocks (fail closed)".
2. **Harness, closed.** This branch's `GateEvaluator` read sustained2x as
   achieved/offered alone, so a `--duration-seconds 60` run recorded PASS up
   to 317.6 ms (gate D blocked it, the harness's own verdict did not). It now
   carries a-prime's `sustainedRatio`; "a window longer than 30 s does not
   widen the allowance - lag_max plus 1 us fails" passes, with a-prime's
   window tests alongside.

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
| `fixture-pass` | WORKTREE:scripts/testdata/storage-activation/benchmark/pass.json | `1653ef72bf9a9286c2600ad099924e5eb0cd5e9985050ab73478ba27022fd3b6` |
| `fixture-fail` | WORKTREE:scripts/testdata/storage-activation/benchmark/fail.json | `7ef5048ae168feabedf89b6150f0f009e2d7e66efe1eac8eebcff911fb12fcc1` |
| `fixture-pass-but-t1-violates` | WORKTREE:scripts/testdata/storage-activation/benchmark/pass-but-t1-violates.json | `5ef4ed6874877c0e81986d76797af542012af45458ef39b6c692357ddc4f14b5` |
| `fixture-missing-reference` | WORKTREE:scripts/testdata/storage-activation/benchmark/missing-reference.json | `86ea901e5d017728ca0598dcb5ca67c8545eee9034eff67635fb38ee02dbd489` |
| `fixture-incomplete` | WORKTREE:scripts/testdata/storage-activation/benchmark/incomplete.json | `ee4ac23cf814171a5d2d0a1235e7711633eb16545282a9164257357e03e1ee13` |
| `fixture-beta` | derived from fixture-pass.json as check-storage-activation.test.sh derives bench-beta.json | `a0a8d4de60412a07c5ece7866be29af644bfd0042591dd6336cc6930b1757f43` (gitSha removed) |
| `boundary-30s-lagmax-minus-1us` | synthetic: 30 s window, c100 lag = lag_max(62/s) - 0.001 ms = 166.88180110228296 ms | `d50dd73d932c8613d9be6757411851a052b60c953fa0490605f3e093f2bf9df3` (gitSha removed) |
| `boundary-30s-lagmax-plus-1us` | synthetic: 30 s window, c100 lag = lag_max(62/s) + 0.001 ms = 166.88380110228297 ms | `469dd70c0c4a22a4c441a6b113b0ce01cd8d36648106fb07e2b9270e40ec4097` (gitSha removed) |
| `boundary-60s-lagmax-minus-1us` | synthetic: 60 s window, c100 lag = lag_max(62/s) - 0.001 ms = 166.88180110228296 ms | `08f362e3956ebf2ea490c57bce96f07e281c43ebd4794d36d146ff83999a4972` (gitSha removed) |
| `boundary-60s-lagmax-plus-1us` | synthetic: 60 s window, c100 lag = lag_max(62/s) + 0.001 ms = 166.88380110228297 ms | `b506a2c4a09b74e6d595bfa3249857180e71a164353f83aa1262375e9b13cb47` (gitSha removed) |

### Gate scripts

| Revision | scripts/check-storage-activation.sh sha256 |
|---|---|
| 0aa27fb | `f72a56cd6a0c0f6d0ee5538806aef5f1f21b1a9a184dd95913807dac0f496be8` |
| efab78a | `f3c6f6e6137114503bd0442092a2245d2641b7ccf803f8030589328a26493654` |
| WORKTREE | `fe0f72348301f30d48636287f2c7b19366fd7478ab2e027a74766c9bc932f05b` |

### Gate D and the overall verdict

| Input | D @0aa27fb | overall @0aa27fb | D @efab78a | overall @efab78a | D @WORKTREE | overall @WORKTREE | output = @0aa27fb @efab78a | output = @0aa27fb @WORKTREE |
|---|---|---|---|---|---|---|---|---|
| `2026-11-03-c10dd3d-FAIL` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-05-9d06ab2-run1` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-1023af5-run2-part1-ws10000` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-6347627-run3-r31` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run1-focused-cohort-82eeee3` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run2-focused-cohort-7b09bcb` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run3-focused-cohort-82eeee3` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run4-focused-cohort-7b09bcb` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | NO | NO |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run5-focused-82eeee3` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run6-focused-7b09bcb` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run7-focused-82eeee3` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run8-focused-7b09bcb` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `fixture-pass` | PASS | ACTIVATION READY | PASS | ACTIVATION READY | PASS | ACTIVATION READY | yes | yes |
| `fixture-fail` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `fixture-pass-but-t1-violates` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `fixture-missing-reference` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `fixture-incomplete` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | NO |
| `fixture-beta` | PASS | ACTIVATION READY | PASS | ACTIVATION READY | PASS | ACTIVATION READY | yes | yes |
| `boundary-30s-lagmax-minus-1us` | PASS | ACTIVATION READY | PASS | ACTIVATION READY | PASS | ACTIVATION READY | yes | yes |
| `boundary-30s-lagmax-plus-1us` | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | yes | yes |
| `boundary-60s-lagmax-minus-1us` | PASS | ACTIVATION READY | PASS | ACTIVATION READY | PASS | ACTIVATION READY | yes | yes |
| `boundary-60s-lagmax-plus-1us` | PASS | ACTIVATION READY | BLOCKED | ACTIVATION BLOCKED | BLOCKED | ACTIVATION BLOCKED | NO | NO |

### Per-scenario sustained2x (CHOSEN, 2R, load-gated concurrency 10/25/50/100)

N30 = floor(rate x 30). sched = scheduledEvents, dur = durationSeconds (measured span, terminal lag included).
"30 s" = scheduledEvents <= N30 (the scheduled window); "long" = scheduledEvents > N30. ratio = achievedRate / offeredRate;
over30 = (completed / scheduled) x N30 / ((N30 - 1) + rate x lag), shown wherever it is computable.
changed = the verdict differs from @0aa27fb.

| Input | Scenario | sched | N30 | dur s | window | lag ms | ratio | over30 | @0aa27fb | @efab78a | @WORKTREE | changed |
|---|---|---:|---:|---:|---|---:|---:|---:|---|---|---|---|
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c10-r520` | 15600 | 15600 | 260.030471604 | 30 s | 230032.394681 | 0.11537109406810965 | 0.11537109406807552 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c25-r520` | 15600 | 15600 | 253.430561155 | 30 s | 223432.484232 | 0.11837562077468541 | 0.1183756207746495 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c50-r520` | 15600 | 15600 | 251.831179938 | 30 s | 221833.103015 | 0.11889833379520118 | 0.11889833379516485 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws200-c100-r520` | 15600 | 15600 | 272.408586445 | 30 s | 242410.509522 | 0.10842734014737808 | 0.10842734014734746 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 298.53037798 | 30 s | 268532.301057 | 0.10049228558579001 | 0.10049228558576412 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c25-r520` | 15600 | 15600 | 309.564485341 | 30 s | 279566.408418 | 0.09691034153014538 | 0.09691034153012133 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c50-r520` | 15600 | 15600 | 315.141348197 | 30 s | 285143.271274 | 0.09457904969086176 | 0.09457904969083868 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-03-c10dd3d-FAIL` | `chosen-ws10000-c100-r520` | 15600 | 15600 | 357.966153709 | 30 s | 327968.076786 | 0.0800731053521971 | 0.0800731053521799 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c10-r520` | 15600 | 15600 | 226.961031061 | 30 s | 196962.954138 | 0.13218128178108665 | 0.13218128178104185 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c25-r520` | 15600 | 15600 | 226.100448624 | 30 s | 196102.371701 | 0.13268438953825046 | 0.1326843895382053 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c50-r520` | 15600 | 15600 | 251.111827726 | 30 s | 221113.750803 | 0.11938444487193765 | 0.11938444487190109 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws200-c100-r520` | 15600 | 15600 | 298.05378228 | 30 s | 268055.705357 | 0.09842054397101269 | 0.0984205439709873 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 239.925208332 | 30 s | 209927.131409 | 0.1250389661368432 | 0.12503896613680313 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c25-r520` | 15600 | 15600 | 249.578572533 | 30 s | 219580.49561 | 0.12020262675407888 | 0.12020262675404182 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c50-r520` | 15600 | 15600 | 267.401096375 | 30 s | 237403.019452 | 0.11210471267060106 | 0.1121047126705688 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-05-9d06ab2-run1` | `chosen-ws10000-c100-r520` | 15600 | 15600 | 273.096443369 | 30 s | 243098.366446 | 0.10828803392037822 | 0.10828803392034772 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 238.001148779 | 30 s | 208003.071856 | 0.1260498117505181 | 0.12604981175047736 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c25-r520` | 15600 | 15600 | 253.291696199 | 30 s | 223293.619276 | 0.1184405191729236 | 0.11844051917288763 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c50-r520` | 15600 | 15600 | 256.503634786 | 30 s | 226505.557863 | 0.11688993153140598 | 0.11688993153137094 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-1023af5-run2-part1-ws10000` | `chosen-ws10000-c100-r520` | 15600 | 15600 | 291.370428644 | 30 s | 261372.351721 | 0.10112028758573972 | 0.10112028758571302 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c10-r62` | 1860 | 1860 | 30.141968148 | 30 s | 158.09718 | 0.9952900173172858 | 0.9952900173258071 | pass | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c25-r62` | 1860 | 1860 | 30.005691035 | 30 s | 21.820067 | 0.9998103348130406 | 0.9998103348216395 | pass | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c50-r62` | 1860 | 1860 | 30.00041545 | 30 s | 16.544482 | 0.9999861518584404 | 0.9999861518670422 | pass | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws200-c100-r62` | 1860 | 1860 | 30.188645208 | 30 s | 204.77424 | 0.9937511204394821 | 0.9937511204479771 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c10-r62` | 1860 | 1860 | 30.064860003 | 30 s | 80.989035 | 0.9978426640605169 | 0.997842664069082 | pass | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c25-r62` | 1860 | 1860 | 30.001557193 | 30 s | 17.686225 | 0.9999480962608047 | 0.9999480962694058 | pass | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c50-r62` | 1860 | 1860 | 30.001023671 | 30 s | 17.152703 | 0.9999658787976295 | 0.9999658788062311 | pass | pass | pass | no |
| `2026-11-06-6347627-run3-r31` | `chosen-ws10000-c100-r62` | 1860 | 1860 | 30.009679722 | 30 s | 25.808754 | 0.9996774466742173 | 0.999677446682814 | pass | pass | pass | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws200-c10-r62` | 3720 | 1860 | 61.303930794 | long | 1320.059826 | 0.978730062214418 | 0.958346100292216 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws200-c100-r62` | 3720 | 1860 | 60.526184703 | long | 542.313735 | 0.9899740935717654 | 0.9814419230931204 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 60.121893772 | long | 138.022804 | 0.9979725560132511 | 0.9959533164592631 | pass | pass | pass | no |
| `2026-11-06-80482e6-DIAGNOSTIC-not-gate-D-evidence` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 66.570318222 | long | 6586.447254 | 0.8947608742141707 | 0.8143833445455189 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 60.010245699 | long | 26.374731 | 0.9998292675045626 | 0.9996585933069397 | pass | pass | pass | no |
| `2026-11-06-80482e6-FOCUSED-P-ASSESSMENT-not-gate-D-evidence` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 60.018140611 | long | 34.269643 | 0.999697747867306 | 0.9993956784006987 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run1-focused-cohort-82eeee3` | `chosen-ws31-c10-r62` | 3720 | 1860 | 72.532085409 | long | 12548.214441 | 0.8272201145419572 | 0.7053498485130446 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run1-focused-cohort-82eeee3` | `chosen-ws31-c100-r62` | 3720 | 1860 | 76.019569721 | long | 16035.698753 | 0.7729334003306971 | 0.6384030193316388 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run2-focused-cohort-7b09bcb` | `chosen-ws31-c10-r62` | 3720 | 1860 | 67.948746092 | long | 7964.875124 | 0.8830185021922599 | 0.7905399542708035 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run2-focused-cohort-7b09bcb` | `chosen-ws31-c100-r62` | 3720 | 1860 | 61.969759125 | long | 1985.888157 | 0.9682141878101095 | 0.9383868011937099 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run3-focused-cohort-82eeee3` | `chosen-ws31-c10-r62` | 3720 | 1860 | 74.075263166 | long | 14091.392198 | 0.8099869974885159 | 0.6806539052798642 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run3-focused-cohort-82eeee3` | `chosen-ws31-c100-r62` | 3720 | 1860 | 80.634035067 | long | 20650.164099 | 0.721499541806363 | 0.5744892667368269 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run4-focused-cohort-7b09bcb` | `chosen-ws31-c10-r62` | 3720 | 1860 | 60.215219523 | long | 231.348555 | 0.996425828474846 | 0.9928771153696253 | pass | BLOCK | BLOCK | YES |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run4-focused-cohort-7b09bcb` | `chosen-ws31-c100-r62` | 3720 | 1860 | 70.514777119 | long | 10530.906151 | 0.8401350455081904 | 0.7311151596643728 | BLOCK | BLOCK | BLOCK | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run5-focused-82eeee3` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 60.052336499 | long | 68.465531 | 0.9991284852172093 | 0.9982584881962796 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run5-focused-82eeee3` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 60.027510525 | long | 43.639557 | 0.9995417013839256 | 0.9990838226592489 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run6-focused-7b09bcb` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 60.0291882 | long | 45.317232 | 0.9995137665379922 | 0.9990280057007274 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run6-focused-7b09bcb` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 60.005030037 | long | 21.159069 | 0.999916173077542 | 0.9998323602164112 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run7-focused-82eeee3` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 60.146078973 | long | 162.208005 | 0.9975712635720514 | 0.9951542960902471 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run7-focused-82eeee3` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 60.006356382 | long | 22.485414 | 0.9998940715220312 | 0.9997881654919687 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run8-focused-7b09bcb` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 60.105311951 | long | 121.440983 | 0.9982478761430296 | 0.9965018814316142 | pass | pass | pass | no |
| `2026-11-06-NON-GATE-A-prime-FOCUSED-not-gate-D-evidence__run8-focused-7b09bcb` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 60.041896029 | long | 58.025061 | 0.9993022200867913 | 0.9986054132967556 | pass | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c25-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c50-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-pass` | `chosen-ws10000-c100-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-fail` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9576923076923077 | 0.9997974769213416 | BLOCK | BLOCK | BLOCK | no |
| `fixture-fail` | `chosen-ws10000-c25-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-fail` | `chosen-ws10000-c50-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-fail` | `chosen-ws10000-c100-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c25-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c50-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-pass-but-t1-violates` | `chosen-ws10000-c100-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c25-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c50-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-missing-reference` | `chosen-ws10000-c100-r520` | 15600 | 15600 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 0.9997974769213416 | pass | pass | pass | no |
| `fixture-incomplete` | `chosen-ws10000-c10-r520` | 15600 | 15600 | 30.1 | 30 s | absent | 0.9965384615384616 | - | pass | pass | BLOCK | YES |
| `fixture-beta` | `chosen-ws10000-c10-r62` | 1860 | 1860 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | pass | no |
| `fixture-beta` | `chosen-ws10000-c25-r62` | 1860 | 1860 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | pass | no |
| `fixture-beta` | `chosen-ws10000-c50-r62` | 1860 | 1860 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | pass | no |
| `fixture-beta` | `chosen-ws10000-c100-r62` | 1860 | 1860 | 30.0 | 30 s | 8.0 | 0.9965000000000002 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c10-r62` | 1860 | 1860 | 29.991870967741935 | 30 s | 8.0 | 1.0002710411853535 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c25-r62` | 1860 | 1860 | 29.991870967741935 | 30 s | 8.0 | 1.0002710411853535 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c50-r62` | 1860 | 1860 | 29.991870967741935 | 30 s | 8.0 | 1.0002710411853535 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-30s-lagmax-minus-1us` | `chosen-ws10000-c100-r62` | 1860 | 1860 | 30.150752768844217 | 30 s | 166.88180110228296 | 0.9950000330008346 | 0.9950000330008345 | pass | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c10-r62` | 1860 | 1860 | 29.991870967741935 | 30 s | 8.0 | 1.0002710411853535 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c25-r62` | 1860 | 1860 | 29.991870967741935 | 30 s | 8.0 | 1.0002710411853535 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c50-r62` | 1860 | 1860 | 29.991870967741935 | 30 s | 8.0 | 1.0002710411853535 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-30s-lagmax-plus-1us` | `chosen-ws10000-c100-r62` | 1860 | 1860 | 30.15075476884422 | 30 s | 166.88380110228297 | 0.9949999669991678 | 0.9949999669991678 | BLOCK | BLOCK | BLOCK | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 59.99187096774194 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c25-r62` | 3720 | 1860 | 59.99187096774194 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c50-r62` | 3720 | 1860 | 59.99187096774194 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-60s-lagmax-minus-1us` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 60.15075276884422 | long | 166.88180110228296 | 0.9974937509190691 | 0.9950000330008345 | pass | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c10-r62` | 3720 | 1860 | 59.99187096774194 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c25-r62` | 3720 | 1860 | 59.99187096774194 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c50-r62` | 3720 | 1860 | 59.99187096774194 | long | 8.0 | 1.0001355022293343 | 1.0002710411853535 | pass | pass | pass | no |
| `boundary-60s-lagmax-plus-1us` | `chosen-ws10000-c100-r62` | 3720 | 1860 | 60.150754768844216 | long | 166.88380110228297 | 0.9974937177526109 | 0.9949999669991678 | pass | BLOCK | BLOCK | YES |
