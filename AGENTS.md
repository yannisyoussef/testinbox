# Repository entry point

Read `CLAUDE.md` before changing this repository. Its Accepted ADRs,
dependency rules, contract-first process, test requirements and deployment
boundaries apply to every contributor and coding tool.

For the current 30-account beta delivery, read these next:

1. `docs/delivery/ti-delivery-001/STATUS.md` — current checkpoint and next action.
2. `docs/delivery/ti-delivery-001/PLAN.md` — scope, milestones and completion gates.
3. `docs/delivery/ti-delivery-001/DISCOVERY.md` — verified baseline and reusable work.
4. `prompts/TI-DELIVERY/TI-DELIVERY-001.md`, if present — the original Ops handoff.
   `prompts/` is intentionally ignored; the tracked delivery documents must
   remain sufficient to resume when that local file is absent.

Before starting a milestone, inspect the working tree, refresh remote refs
and check whether its dependencies have changed. Preserve unrelated work and
other worktrees. Work on a feature branch, never directly on a shared branch.

At every completed milestone and before any interruption, update `STATUS.md`
with the implementation state, actual verification results, unresolved items
and exact next action. Save a local Conventional Commit when the work forms
a coherent checkpoint. A saved checkpoint is not a deployment approval.

Use the owner's configured Git identity. Do not add attribution or co-author
trailers to commits, pull requests, issues, comments or delivery documents.
Never change global Git identity settings.

Production promotion, access widening, admitting non-owner users and live
infrastructure changes remain separate owner decisions. Do not weaken an
existing test, promotion gate, storage restriction or security boundary to
make the beta appear ready.
