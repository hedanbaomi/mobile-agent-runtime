// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "../../..");
const stageAssets = process.argv.includes("--stage-assets");
const outputRoot = stageAssets
  ? join(repoRoot, "admin", "announcements", "source")
  : join(repoRoot, "services", "announcements", ".wrangler", "source-artifact");
const files = [
  "services/announcements/src/access.mjs",
  "services/announcements/src/app.mjs",
  "services/announcements/src/d1-store.mjs",
  "services/announcements/src/errors.mjs",
  "services/announcements/src/local-server.mjs",
  "services/announcements/src/rollout.mjs",
  "services/announcements/src/schema.sql",
  "services/announcements/src/sign.mjs",
  "services/announcements/src/store.mjs",
  "services/announcements/src/targeting.mjs",
  "services/announcements/src/validation.mjs",
  "services/announcements/src/worker.mjs",
  "services/announcements/src/access.test.mjs",
  "services/announcements/src/rollout.test.mjs",
  "services/announcements/src/worker.test.mjs",
  "services/announcements/migrations/0001_initial.sql",
  "services/announcements/migrations/0002_hardened_contract.sql",
  "services/announcements/package.json",
  "services/announcements/package-lock.json",
  "services/announcements/wrangler.toml",
  "services/announcements/wrangler.local.toml",
  "services/announcements/DEPLOYMENT.md",
  "services/announcements/scripts/build-source-artifact.mjs",
  "services/announcements/scripts/local-smoke.mjs",
  "services/announcements/scripts/preflight.mjs",
  "admin/announcements/index.html",
  "LICENSE",
  "LICENSE_POLICY.md",
];

const entries = [];
const hash = createHash("sha256");
for (const relativePath of files) {
  const diskPath = join(repoRoot, relativePath);
  if (!existsSync(diskPath)) continue;
  if (/\.env|node_modules|private|secret|\.wrangler/i.test(relativePath)) throw new Error(`forbidden artifact path: ${relativePath}`);
  const content = await readFile(diskPath);
  const archivePath = relativePath.replaceAll("\\", "/");
  hash.update(archivePath);
  hash.update("\0");
  hash.update(content);
  entries.push({ diskPath, archivePath, bytes: content.length });
}
const sourceHash = hash.digest("hex");
const artifactDir = join(outputRoot, sourceHash);
const zipPath = join(artifactDir, "mobile-agent-runtime-announcements-source.zip");
await mkdir(artifactDir, { recursive: true });

const python = [
  "import pathlib,sys,zipfile",
  "out=pathlib.Path(sys.argv[1])",
  "with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED) as z:",
  "  args=sys.argv[2:]",
  "  for i in range(0,len(args),2): z.write(args[i],args[i+1])",
].join("\n");
const zipResult = spawnSync(process.env.PYTHON || "python", ["-c", python, zipPath, ...entries.flatMap((entry) => [entry.diskPath, entry.archivePath])], { encoding: "utf8" });
if (zipResult.status !== 0) throw new Error(`source archive creation failed: ${zipResult.stderr || "python failed"}`);

const manifest = {
  schemaVersion: 1,
  sourceHash,
  artifact: `mobile-agent-runtime-announcements-source.zip`,
  files: entries.map(({ archivePath, bytes }) => ({ path: archivePath, bytes })),
  excludes: [".env*", ".dev.vars*", "node_modules/", ".wrangler/", "private/", "secrets/"],
};
await writeFile(join(artifactDir, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
await writeFile(
  join(artifactDir, "manifest.json.license"),
  "SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors\nSPDX-License-Identifier: AGPL-3.0-only\n",
  "utf8",
);
const publicArtifactPath = `/source/${sourceHash}`;
const page = `<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->
<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Announcements source</title><h1>Announcements source</h1><p>Public source snapshot for the deployed announcements Worker.</p><p>Source hash: <code>${sourceHash}</code></p><p><a download href="${publicArtifactPath}/mobile-agent-runtime-announcements-source.zip">Download source archive</a></p><p><a href="${publicArtifactPath}/manifest.json">View manifest</a></p>`;
await writeFile(join(outputRoot, "index.html"), `${page}\n`, "utf8");
console.log(JSON.stringify({ sourceHash, artifact: relative(repoRoot, zipPath).replaceAll("\\", "/"), files: entries.length, stagedForAssets: stageAssets }));
