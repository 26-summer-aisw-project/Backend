#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const SHA = /^[0-9a-f]{40}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const KOREAN_COMMIT = /^(feat|fix|refactor|test|docs|chore)(\([a-z0-9-]+\))?!?: .*?[가-힣].*$/;
const BRANCHES = [
  "feat/photo-first-vision-registration",
  "feat/found-item-finalization",
  "feat/center-directory-p0",
  "feat/found-item-handover-lifecycle",
  "feat/lost-report-matching",
];
const COMPATIBILITY_EXCEPTIONS = {
  2: "src/main/java/kr/lostory/backend/lostcenter/domain/LostCenterRepository.java",
  5: "src/main/java/kr/lostory/backend/lostcenter/application/LostCenterService.java",
};
const ALLOWLISTS = [
  ["build.gradle", "src/main/resources/application.properties", "src/main/resources/db/migration/V2[0-9]__*.sql", "src/main/java/kr/lostory/backend/{common,config,founditem,lostreport}/**", "src/test/java/kr/lostory/backend/**"],
  ["src/main/resources/application.properties", "src/main/resources/db/migration/V2[0-9]__*.sql", "src/main/java/kr/lostory/backend/{common,config,founditem,lostreport}/**", COMPATIBILITY_EXCEPTIONS[2], "src/test/java/kr/lostory/backend/**"],
  ["src/main/resources/application.properties", "src/main/resources/db/migration/V2[0-9]__*.sql", "src/main/java/kr/lostory/backend/{common,config,lostcenter}/**", "src/test/java/kr/lostory/backend/**"],
  ["src/main/resources/db/migration/V2[0-9]__*.sql", "src/main/java/kr/lostory/backend/{audit,common,founditem,lostcenter,lostreport}/**", "src/test/java/kr/lostory/backend/**"],
  ["src/main/resources/application.properties", "src/main/resources/db/migration/V2[0-9]__*.sql", "src/main/java/kr/lostory/backend/{common,config,founditem,lostreport}/**", COMPATIBILITY_EXCEPTIONS[5], "src/test/java/kr/lostory/backend/**", "docs/API_SPEC.md", "docs/ERD.md", "docs/IMPLEMENTATION_PLAN.md", "docs/operations/vision-and-storage-demo.md", "scripts/validate-p0-pr-manifests.mjs", "scripts/validate-p0-pr-manifests.test.mjs"],
];
const FORBIDDEN = [/^\.omo\//, /(^|\/)\.DS_Store$/, /^docs\/LOSTORY_PRODUCT_PLAN\.md$/, /^docs\/erd-.*\.html$/, /^src\/main\/java\/kr\/lostory\/backend\/(point|partner|partnership|dashboard|return)(\/|$)/];
const RETIRED_ROUTES = ["/api/v1/found-items/{itemId}/images", "/api/v1/nearby-lost-centers"];
const CLI_FLAGS = ["evidence-dir", "worktree", "template", "workflow"];

function fail(field, detail) {
  throw new Error(`${field}: ${detail}`);
}

export function parseCliArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value === undefined) fail("CLI", `invalid argument near ${key ?? "<missing>"}`);
    const name = key.slice(2);
    if (!CLI_FLAGS.includes(name)) fail("CLI", `unknown flag ${key}`);
    if (Object.hasOwn(values, name)) fail("CLI", `duplicate flag ${key}`);
    values[name] = value;
  }
  for (const key of CLI_FLAGS) {
    if (!values[key]) fail("CLI", `missing --${key}`);
  }
  return values;
}

function command(binary, commandArgs, cwd) {
  const result = spawnSync(binary, commandArgs, { cwd, encoding: "utf8" });
  if (result.status !== 0) fail(`${binary} ${commandArgs.join(" ")}`, (result.stderr || result.stdout).trim() || `exit ${result.status}`);
  return result.stdout.trim();
}

function equal(field, actual, expected) {
  if (actual !== expected) fail(field, `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

function nonempty(field, value) {
  if (typeof value !== "string" || value.trim() === "") fail(field, "must be a non-empty string");
}

function fullSha(field, value) {
  if (typeof value !== "string" || !SHA.test(value)) fail(field, "must be a full lowercase 40-character SHA");
}

function sortedExact(field, actual, expected) {
  if (!Array.isArray(actual) || actual.some((item) => typeof item !== "string")) fail(field, "must be a string array");
  equal(field, JSON.stringify(actual), JSON.stringify([...expected].sort()));
}

function patternRegex(pattern) {
  let source = "";
  for (let index = 0; index < pattern.length;) {
    if (pattern[index] === "{" ) {
      const end = pattern.indexOf("}", index);
      if (end < 0) fail("allowlist", `invalid pattern ${pattern}`);
      source += `(?:${pattern.slice(index + 1, end).split(",").join("|")})`;
      index = end + 1;
    } else if (pattern.slice(index, index + 2) === "**") {
      source += ".*";
      index += 2;
    } else if (pattern[index] === "*") {
      source += "[^/]*";
      index += 1;
    } else if (pattern[index] === "[") {
      const end = pattern.indexOf("]", index);
      if (end < 0) fail("allowlist", `invalid pattern ${pattern}`);
      source += pattern.slice(index, end + 1);
      index = end + 1;
    } else {
      source += pattern[index].replace(/[.+?^${}()|\\]/g, "\\$&");
      index += 1;
    }
  }
  return new RegExp(`^${source}$`);
}

function validateScope(number, paths) {
  const patterns = ALLOWLISTS[number - 1].map(patternRegex);
  for (const path of paths) {
    if (FORBIDDEN.some((pattern) => pattern.test(path))) fail(`p0-pr-${number}.touched_paths`, `forbidden path ${path}`);
    if (!patterns.some((pattern) => pattern.test(path))) fail(`p0-pr-${number}.touched_paths`, `path outside PR ${number} allowlist: ${path}`);
  }
}

function mergeSha(manifest) {
  nonempty("merge_sha", manifest.merge_sha);
  fullSha("merge_sha", manifest.merge_sha);
  return manifest.merge_sha;
}

function validateRedteam(label, manifest) {
  for (const field of ["redteam_agent", "redteam_scan_id", "redteam_report"]) nonempty(`${label}.${field}`, manifest[field]);
  equal(`${label}.redteam_model`, manifest.redteam_model, "gpt-5.6-sol");
  equal(`${label}.redteam_head_sha`, manifest.redteam_head_sha, manifest.head_sha);
  equal(`${label}.redteam_verdict`, manifest.redteam_verdict, "PASS");
}

export function validateTestResults(label, section) {
  const normalized = section.trim().replaceAll("\r\n", "\n");
  const ciOnly = /^CI status: success\nCI URL: https:\/\/github\.com\/[^/\s]+\/[^/\s]+\/actions\/runs\/[1-9][0-9]*$/;
  if (!ciOnly.test(normalized)) fail(`${label}.pr.test-results`, "only exact CI status and run URL lines are allowed");
}

function validateBody(number, label, body, template, manifest) {
  const headings = (template.match(/^## .+$/gm) ?? []).map((heading) => heading.trimEnd());
  for (const heading of headings) if (!body.includes(heading)) fail(`${label}.pr.body`, `missing template heading ${heading}`);
  equal(`${label}.pr_body_sha256`, createHash("sha256").update(body).digest("hex"), manifest.pr_body_sha256);
  if (number !== 5) return;
  const testHeading = headings.find((heading) => heading.includes("테스트 결과"));
  if (!testHeading) fail("template", "missing 테스트 결과 heading");
  const start = body.indexOf(testHeading) + testHeading.length;
  const next = body.indexOf("\n## ", start);
  const tests = body.slice(start, next < 0 ? body.length : next);
  validateTestResults(label, tests);
}

function validateManifest(number, manifest, context) {
  const label = `p0-pr-${number}`;
  equal(`${label}.branch`, manifest.branch, BRANCHES[number - 1]);
  fullSha(`${label}.base_sha`, manifest.base_sha);
  fullSha(`${label}.head_sha`, manifest.head_sha);
  command("git", ["merge-base", "--is-ancestor", manifest.base_sha, manifest.head_sha], context.worktree);
  const paths = command("git", ["diff", "--name-only", `${manifest.base_sha}..${manifest.head_sha}`], context.worktree).split("\n").filter(Boolean);
  sortedExact(`${label}.touched_paths`, manifest.touched_paths, paths);
  validateScope(number, manifest.touched_paths);
  const subjects = command("git", ["log", "--format=%s", `${manifest.base_sha}..${manifest.head_sha}`], context.worktree).split("\n").filter(Boolean);
  if (subjects.length === 0) fail(`${label}.commits`, "range has no commits");
  for (const subject of subjects) if (!KOREAN_COMMIT.test(subject)) fail(`${label}.commits`, `not Korean Conventional Commit: ${subject}`);
  for (const route of RETIRED_ROUTES) if (JSON.stringify(manifest).includes(route)) fail(label, `obsolete route ${route}`);
  validateRedteam(label, manifest);
  const finalMerge = mergeSha(manifest);
  command("git", ["merge-base", "--is-ancestor", manifest.base_sha, finalMerge], context.worktree);
  command("git", ["merge-base", "--is-ancestor", manifest.head_sha, finalMerge], context.worktree);
  if (typeof manifest.pr_body_sha256 !== "string" || !SHA256.test(manifest.pr_body_sha256)) {
    fail(`${label}.pr_body_sha256`, "must be a lowercase 64-character SHA-256");
  }
  if (!Number.isInteger(manifest.pr_number) || manifest.pr_number < 1) fail(`${label}.pr_number`, "must be a positive integer");
  const pr = JSON.parse(command("gh", ["pr", "view", String(manifest.pr_number), "--repo", context.repo, "--json", "url,body,baseRefOid,headRefOid,state,mergeCommit"], context.worktree));
  equal(`${label}.pr.url`, pr.url, manifest.pr_url);
  equal(`${label}.pr.baseRefOid`, pr.baseRefOid, manifest.base_sha);
  equal(`${label}.pr.headRefOid`, pr.headRefOid, manifest.head_sha);
  equal(`${label}.pr.state`, pr.state, "MERGED");
  equal(`${label}.pr.mergeCommit`, pr.mergeCommit?.oid, finalMerge);
  validateBody(number, label, pr.body, context.template, manifest);
  const runs = JSON.parse(command("gh", ["run", "list", "--repo", context.repo, "--workflow", context.workflow, "--commit", manifest.head_sha, "--json", "url,conclusion,headSha"], context.worktree));
  const success = runs.find((run) => run.headSha === manifest.head_sha && run.conclusion === "success");
  if (!success) fail(`${label}.ci`, `no successful ${context.workflow} run at exact head`);
  equal(`${label}.ci_head_sha`, manifest.ci_head_sha, manifest.head_sha);
  equal(`${label}.ci_conclusion`, manifest.ci_conclusion, "success");
  equal(`${label}.ci_url`, success.url, manifest.ci_url);
}

function main() {
  const options = parseCliArgs(process.argv.slice(2));
  const worktree = resolve(options.worktree);
  const evidenceDir = resolve(options["evidence-dir"]);
  const expected = BRANCHES.map((_, index) => `p0-pr-${index + 1}.json`);
  const found = readdirSync(evidenceDir).filter((name) => /^p0-pr-.*\.json$/.test(name)).sort();
  equal("evidence-dir.manifests", JSON.stringify(found), JSON.stringify(expected));
  equal("worktree.clean", command("git", ["status", "--porcelain"], worktree), "");
  const remote = command("git", ["remote", "get-url", "origin"], worktree);
  const match = remote.match(/github\.com[/:]([^/]+\/[^/.]+)(?:\.git)?$/);
  if (!match) fail("origin", `cannot derive GitHub repo from ${remote}`);
  const context = {
    repo: match[1],
    template: readFileSync(resolve(worktree, options.template), "utf8"),
    workflow: options.workflow,
    worktree,
  };
  expected.forEach((name, index) => validateManifest(index + 1, JSON.parse(readFileSync(resolve(evidenceDir, name), "utf8")), context));
  console.log(`PASS: validated exactly ${expected.join(", ")}`);
  console.log(`Compatibility exceptions: PR2=${COMPATIBILITY_EXCEPTIONS[2]}, PR5=${COMPATIBILITY_EXCEPTIONS[5]}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    console.error(`FAIL: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
