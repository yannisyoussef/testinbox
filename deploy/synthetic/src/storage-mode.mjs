/**
 * The storage enforcement mode a deployment DECLARES, and what the deployment
 * gate may assert about it from outside (ADR-035 §14, ADR-037).
 *
 * The mode is declared by whoever runs the suite, never inferred from what the
 * environment answers: a gate that learns "ALL" from a response would pass on a
 * deployment whose mode silently changed. It is OPTIONAL and defaults to `OFF`,
 * because every committed environment runs `OFF` and an absent variable must
 * not break the existing deployment gate. Declaring `ALL` is part of the
 * activation step that switches the deployables (production.md, "Storage
 * activation"); a deployment switched without it fails here as soon as its
 * footprint report says `enforced: true`.
 *
 * What is observable through the synthetic credential, and therefore what is
 * asserted:
 *   - a fresh inbox records no storage refusal in ANY mode;
 *   - when the workspace's private usage carries the optional `footprint`
 *     object (only a workspace with an explicit cohort share has one), it is
 *     the caller's own six members, internally consistent, and `enforced` is
 *     true exactly when the declared mode enforces workspace ceilings
 *     (`TENANT_LIMITS` or `ALL`). Its absence proves nothing about the mode.
 */

export const STORAGE_MODES = Object.freeze(["OFF", "TENANT_LIMITS", "ALL"]);

export const FOOTPRINT_MEMBERS = Object.freeze([
  "availableBytes",
  "enforced",
  "limitBytes",
  "overLimit",
  "pendingCleanupBytes",
  "usedBytes",
]);

/** `TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED`: `OFF` (the default), `TENANT_LIMITS` or `ALL`, exactly. */
export function storageEnforcementExpected(env = process.env) {
  const raw = env.TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED;
  if (raw === undefined || raw.trim() === "") return "OFF";
  const mode = raw.trim();
  if (!STORAGE_MODES.includes(mode)) {
    throw new Error(
      `TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED must be one of ${STORAGE_MODES.join(", ")} (exact case), got ${raw}`,
    );
  }
  return mode;
}

/** Whether the declared mode lets the workspace ceilings refuse (ADR-035 §14). */
export function enforcesWorkspace(mode) {
  return mode === "TENANT_LIMITS" || mode === "ALL";
}

/**
 * Problems with a workspace usage's optional `footprint` under the declared
 * mode; an empty list when it is absent or consistent.
 */
export function footprintProblems(usage, mode) {
  if (!STORAGE_MODES.includes(mode)) return [`unknown declared mode ${mode}`];
  if (usage === null || typeof usage !== "object" || !("footprint" in usage)) return [];
  const fp = usage.footprint;
  if (fp === null || typeof fp !== "object" || Array.isArray(fp)) return ["footprint is not an object"];
  const problems = [];
  const names = Object.keys(fp).sort();
  if (JSON.stringify(names) !== JSON.stringify([...FOOTPRINT_MEMBERS])) {
    problems.push(`footprint members are [${names.join(", ")}], not exactly [${FOOTPRINT_MEMBERS.join(", ")}]`);
  }
  for (const name of ["limitBytes", "usedBytes", "pendingCleanupBytes", "availableBytes"]) {
    if (!Number.isInteger(fp[name]) || fp[name] < 0) problems.push(`footprint.${name} is not a non-negative integer`);
  }
  for (const name of ["overLimit", "enforced"]) {
    if (typeof fp[name] !== "boolean") problems.push(`footprint.${name} is not a boolean`);
  }
  if (problems.length > 0) return problems;
  if (fp.limitBytes < 1) problems.push("footprint.limitBytes is not a real limit");
  if (fp.pendingCleanupBytes > fp.usedBytes) problems.push("footprint.pendingCleanupBytes exceeds usedBytes (cleanup is counted once, inside used)");
  if (fp.availableBytes !== Math.max(0, fp.limitBytes - fp.usedBytes)) problems.push("footprint.availableBytes is not max(0, limit - used)");
  if (fp.overLimit !== fp.usedBytes > fp.limitBytes) problems.push("footprint.overLimit is not used > limit");
  if (fp.enforced !== enforcesWorkspace(mode)) {
    problems.push(
      `footprint.enforced is ${fp.enforced} but the declared mode is ${mode}; ` +
        "the deployment does not run the enforcement mode it declares",
    );
  }
  return problems;
}
