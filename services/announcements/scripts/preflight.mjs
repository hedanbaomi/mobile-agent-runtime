// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { readdir, readFile } from "node:fs/promises";
const config = await readFile(new URL("../wrangler.toml", import.meta.url), "utf8");
const schema = await readFile(new URL("../src/schema.sql", import.meta.url), "utf8");
const migrationNames = (await readdir(new URL("../migrations", import.meta.url)))
  .filter((name) => /^\d+_.*\.sql$/.test(name))
  .sort();

const failures = [];
const requireSecrets = process.argv.includes("--require-secrets");
const requiredConfig = [
  ["worker name", /name\s*=\s*"mobile-agent-runtime-announcements"/],
  ["entrypoint", /main\s*=\s*"src\/worker\.mjs"/],
  ["node compatibility", /nodejs_compat/],
  ["D1 binding", /binding\s*=\s*"ANNOUNCEMENTS_DB"/],
  ["D1 database name", /database_name\s*=\s*"mobile-agent-runtime-announcements-prod"/],
  ["D1 database id", /database_id\s*=\s*"06cf40c7-dd84-4560-859a-1a417f47207e"/],
  ["migrations directory", /migrations_dir\s*=\s*"migrations"/],
  ["production mode", /MAR_ENV\s*=\s*"production"/],
  ["admin origin", /MAR_ADMIN_ORIGIN\s*=\s*"https:\/\/announcements\.luotianyi\.fun"/],
  ["key id", /MAR_ANNOUNCE_KEY_ID\s*=\s*"mar-prod-20260829-1"/],
];
for (const [label, pattern] of requiredConfig) if (!pattern.test(config)) failures.push(`missing ${label}`);
if (/REPLACE_WITH|PRIVATE_KEY|BEGIN .*PRIVATE|api[_-]?token|password/i.test(config)) failures.push("production config contains a secret or placeholder");
if (!migrationNames.length || migrationNames[0] !== "0001_initial.sql") failures.push("migration sequence does not start at 0001_initial.sql");
if (!schema.includes("admin_idempotency") || !schema.includes("mutation_token") || !schema.includes("before_json")) failures.push("canonical schema is missing durable CAS/audit fields");
if (requireSecrets) {
  if (!process.env.MAR_ANNOUNCE_PRIVATE_KEY_PKCS8) failures.push("MAR_ANNOUNCE_PRIVATE_KEY_PKCS8 is not present in the deployment environment");
  if (!process.env.MAR_ANNOUNCE_KEY_ID) failures.push("MAR_ANNOUNCE_KEY_ID is not present in the deployment environment");
}
if (failures.length) {
  console.error(`production preflight failed: ${failures.join("; ")}`);
  process.exitCode = 1;
} else {
  console.log(`production config preflight passed; migrations=${migrationNames.join(",")}`);
  if (!requireSecrets) console.log("secret presence not checked; use --require-secrets only in the authorized deployment shell");
}
