import assert from "node:assert/strict";
import test from "node:test";
import { parseCliArgs, validateTestResults } from "./validate-p0-pr-manifests.mjs";

const VALID_ARGS = [
  "--evidence-dir", "/evidence",
  "--worktree", "/worktree",
  "--template", ".github/pull_request_template.md",
  "--workflow", "Backend Unit Tests",
];

test("rejects unknown CLI flags before manifest processing", () => {
  assert.throws(() => parseCliArgs(["--unknown", "value", ...VALID_ARGS]), /CLI: unknown flag --unknown/);
});

test("rejects duplicate CLI flags before manifest processing", () => {
  assert.throws(() => parseCliArgs([...VALID_ARGS, "--workflow", "Other"]), /CLI: duplicate flag --workflow/);
});

test("accepts only exact CI status and run URL lines", () => {
  assert.doesNotThrow(() => validateTestResults(
    "p0-pr-5",
    "CI status: success\nCI URL: https://github.com/acme/repo/actions/runs/123",
  ));
});

test("rejects prose and Markdown in test results", () => {
  const invalid = [
    "arbitrary prose\nCI status: success\nCI URL: https://github.com/acme/repo/actions/runs/123",
    "- CI status: success\n- CI URL: https://github.com/acme/repo/actions/runs/123",
    "CI status: success\nCI URL: [run](https://github.com/acme/repo/actions/runs/123)",
  ];
  for (const section of invalid) {
    assert.throws(() => validateTestResults("p0-pr-5", section), /only exact CI status and run URL lines/);
  }
});
