import { test } from "node:test";
import assert from "node:assert/strict";
import { enforcesWorkspace, footprintProblems, storageEnforcementExpected } from "../src/storage-mode.mjs";

/**
 * Self-tests for the declared-mode checks the deployment gate's storage suite
 * applies (`tests/storage.test.mjs`). The fixtures are the contract's
 * `WorkspaceStorageUsage` shape. Needs no deployed environment.
 */

const usage = (footprint) => ({
  limitBytes: 1000,
  storedBytes: 0,
  reservedBytes: 0,
  availableBytes: 1000,
  overLimit: false,
  ...(footprint === undefined ? {} : { footprint }),
});
const fp = (overrides = {}) => ({
  limitBytes: 268435456,
  usedBytes: 40960,
  pendingCleanupBytes: 4096,
  availableBytes: 268435456 - 40960,
  overLimit: false,
  enforced: false,
  ...overrides,
});

test("the declared mode defaults to OFF and is otherwise exactly one of the three modes", () => {
  assert.equal(storageEnforcementExpected({}), "OFF");
  assert.equal(storageEnforcementExpected({ TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED: "  " }), "OFF");
  for (const mode of ["OFF", "TENANT_LIMITS", "ALL"]) {
    assert.equal(storageEnforcementExpected({ TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED: mode }), mode);
  }
  for (const bad of ["all", "ON", "TENANT-LIMITS", "OFF,ALL"]) {
    assert.throws(() => storageEnforcementExpected({ TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED: bad }), /must be one of OFF, TENANT_LIMITS, ALL/);
  }
});

test("workspace ceilings refuse under TENANT_LIMITS and ALL only", () => {
  assert.equal(enforcesWorkspace("OFF"), false);
  assert.equal(enforcesWorkspace("TENANT_LIMITS"), true);
  assert.equal(enforcesWorkspace("ALL"), true);
});

test("an absent footprint proves nothing and is never a problem, in any mode", () => {
  for (const mode of ["OFF", "TENANT_LIMITS", "ALL"]) assert.deepEqual(footprintProblems(usage(), mode), []);
});

test("enforced must agree with the declared mode - both directions fail", () => {
  assert.deepEqual(footprintProblems(usage(fp()), "OFF"), []);
  assert.deepEqual(footprintProblems(usage(fp({ enforced: true })), "ALL"), []);
  assert.deepEqual(footprintProblems(usage(fp({ enforced: true })), "TENANT_LIMITS"), []);
  assert.match(footprintProblems(usage(fp({ enforced: true })), "OFF").join(), /declared mode is OFF/);
  assert.match(footprintProblems(usage(fp()), "ALL").join(), /enforced is false but the declared mode is ALL/);
});

test("the footprint must be exactly the six own members and internally consistent", () => {
  assert.match(footprintProblems(usage(fp({ globalFootprintBytes: 1 })), "OFF").join(), /not exactly/);
  assert.match(footprintProblems(usage({ ...fp(), enforced: undefined }), "OFF").join(), /enforced is not a boolean/);
  assert.match(footprintProblems(usage(fp({ usedBytes: -1 })), "OFF").join(), /usedBytes is not a non-negative integer/);
  assert.match(footprintProblems(usage(fp({ pendingCleanupBytes: 50000 })), "OFF").join(), /counted once/);
  assert.match(footprintProblems(usage(fp({ availableBytes: 1 })), "OFF").join(), /availableBytes/);
  assert.match(footprintProblems(usage(fp({ overLimit: true })), "OFF").join(), /overLimit/);
  assert.match(footprintProblems(usage(null), "OFF").join(), /not an object/);
  assert.match(footprintProblems(usage(fp()), "ON").join(), /unknown declared mode/);
  const over = fp({ usedBytes: 268435457, pendingCleanupBytes: 0, availableBytes: 0, overLimit: true });
  assert.deepEqual(footprintProblems(usage(over), "OFF"), []);
});
