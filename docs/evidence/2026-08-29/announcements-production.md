<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Announcements production readiness evidence

Date: 2026-08-29 (Asia/Taipei)
Scope: `services/announcements/**`, `admin/announcements/**` only.
Worker: `mobile-agent-runtime-announcements`
D1: `mobile-agent-runtime-announcements-prod` / `06cf40c7-dd84-4560-859a-1a417f47207e`
Origin: `https://announcements.luotianyi.fun`
Signing key id: `mar-prod-20260829-1`

## Boundary

The initial implementation subtask performed only local verification. The
owner later authorized the main agent to back up the independent production D1
database and deploy the issue #1 Worker update. The final production execution
and HTTPS post-check below supersede the earlier local-only boundary without
claiming an authenticated admin-browser acceptance or Android store release.

The production private key was not read or copied into this workspace. The
required secret interface is `MAR_ANNOUNCE_PRIVATE_KEY_PKCS8`, standard base64
Ed25519 PKCS#8 DER. The public key is distributed separately by the owner.

## Implementation evidence

| Area | Observation | Conclusion |
| --- | --- | --- |
| D1 adapter | `src/d1-store.mjs` uses D1 `prepare/bind/first/all/run/batch`; mutation markers, request idempotency, CAS expected revisions, feed state and audit writes are batched | Implemented; local D1 verified |
| Migration | `migrations/0001_initial.sql` + `0002_hardened_contract.sql`; latest schema includes pending unique index, status guards, mutation markers, audit before/after, idempotency and feed singleton | Implemented; fresh local migration verified |
| Worker | `src/worker.mjs` binds `ANNOUNCEMENTS_DB`, imports PKCS#8 key secret in production, has cron handler, accepts only explicit `local` or normalized `production` modes, and returns generic 503 for missing/invalid configuration | Implemented; authorized production deployment recorded below |
| Admin auth | `src/access.mjs` verifies Access RS256 assertion signature, exact issuer/audience, `exp`/`nbf`/`iat`; production does not read `MAR_ADMIN_TOKEN` | Implemented; Access application setup pending |
| Admin UI | same origin editor/list/preview and revision-aware actions; production page explains Access requirement; no secret input outside loopback local server | Implemented; browser acceptance pending |
| Source route | `/source` serves the hash named source archive staged by `scripts/build-source-artifact.mjs --stage-assets`; generator excludes env/private/node_modules paths and emits SPDX metadata for generated HTML plus a REUSE sidecar for `manifest.json` | Implemented; final hash `b835d4709d29b1111f1673f19a5a64d5d8ae09c14138c3f363e4d6d5de40ca25` deployed and downloaded back byte-for-byte |

## Commands and results

The following commands were run from `E:\mobileAgentRuntime\services\announcements`.
All local Wrangler commands used `--local` and an explicit local config or
local persistence directory.

| Command | Result |
| --- | --- |
| `node --check src/app.mjs`, `src/access.mjs`, `src/d1-store.mjs`, `src/sign.mjs`, `src/store.mjs`, `src/worker.mjs` | exit 0 |
| `npm test` | exit 0; rollout vectors, N01/N02/N05/N06/N07/N08/N09, NAR01-NAR05 and Access JWT tests passed |
| `python` SQLite `src/schema.sql` execution | exit 0; all canonical tables created |
| fresh local `npx --no-install wrangler d1 migrations apply mobile-agent-runtime-announcements-local --local --config wrangler.local.toml --persist-to .wrangler\\fresh-env-final-20260829-2` | exit 0; 0001 and 0002 applied, 18 SQL commands |
| local Wrangler Worker + `node scripts/local-smoke.mjs http://127.0.0.1:8790 test-admin-token` | exit 0; real local D1 create/schedule/promotion/publish, signed feed, ETag 304, event dedup and stats passed (`feedVersion=8`) |
| production-mode local Wrangler Worker with test-only seed secret, public feed request | exit 0; 200 signed complete feed with `keyId=mar-prod-20260829-1` |
| same production-mode local Worker with `X-Admin-Token` and no Access config | exit 0; 503 `admin disabled until Cloudflare Access is configured` |
| production-mode local `/admin/announcements` and `/cdn-cgi/local/scheduled` | exit 0; assets 200 and scheduler trigger 200 |
| `node scripts/build-source-artifact.mjs --stage-assets` | exit 0; generated 28-file hash named zip and manifest at candidate `07f164ef5f473ff426488eeeddf0bb7d1cb522286c5389e85baa2351bb473ae3`; generated HTML has SPDX headers and `manifest.json.license` has SPDX/REUSE metadata |
| `npm run preflight:production` | exit 0; public config, binding, migration and source shape checks passed; secret presence deliberately not checked |

## Review and remaining gates

The static review checked that production code has no in-memory fallback, no
local token fallback, no private key in config or response, no raw install ID in
event persistence, no other product D1 binding, no unsafe admin asset mutation,
and no unrecognized `MAR_ENV` value can enter the local token path. D1 marker
counts after local mutations were all zero. The local production-mode test
confirmed missing Access blocks admin without blocking a signed public feed.

The subtask handed off the following owner-controlled gates. Items 1-3 were
subsequently completed by the authorized main agent and are superseded by the
production execution section below; items 4-5 remain open:

1. Run the authorized D1 backup/export and apply the checked-in migrations to
   the independent remote database.
2. Inject the private key secret without printing it; confirm the published
   public key matches the owner generated key.
3. Stage and verify candidate source archive/hash
   `07f164ef5f473ff426488eeeddf0bb7d1cb522286c5389e85baa2351bb473ae3`,
   then deploy the Worker and assets. Keep the currently deployed
   `9366cf1d9642f89436769b0f8df3585e5f5b669ac01aacf47a2864c2fc754168`
   archive until the new deployment's `/source` post-check passes.
4. Configure Cloudflare Access for the admin API, set the exact team domain and
   audience vars, and perform browser same-origin acceptance. Until then,
   retain the generic admin 503 boundary.
5. Perform production public signature/ETag/withdrawal/scheduler post-checks,
   Access negative tests, audit verification, and rollback evidence.

## Main-agent authorized production execution

The private signing key was not read or printed. Wrangler's secret inventory
confirmed only the required secret name; the checked-in key id remains
`mar-prod-20260829-1`.

### Pre-deploy and rollback evidence

- Clean Android/final-review source commit:
  `dbdb526df2a4f5ab58c015cfc06f4fa650390806`.
- Final Worker protocol source commit:
  `1582a89c67b3d74722da93f5981eb392ede793b7`.
- Remote migration inspection before deployment: `No migrations to apply!` for
  `mobile-agent-runtime-announcements-prod`.
- Pre-deploy D1 export:
  `.private/agent-handoff/announcements-production/2026-08-29/d1-before-issue1-dbdb526.sql`,
  4,183 bytes, SHA-256
  `dcff7e39f88ce9ac51c16149a9821882e3676b56a63f274ceec00a18660c7c7b`.
  The private export is ignored by Git and its SQL content is not reproduced in
  this evidence.

### Deployment and targeted production repair

The first issue #1 deployment exposed a real production-only ETag failure: a
conditional request returned 200 because signed snapshots were isolate-local.
Commit `6baefddc3afe808b04acb0ce5f353e39d0199131` bound envelopes and ETags to a
deterministic 12-hour rotation window. A second HTTPS check showed Cloudflare
weakening the compressed response ETag; commit
`1582a89c67b3d74722da93f5981eb392ede793b7` added RFC-compatible weak comparison.
Both changes added cold-cache/weak-ETag regression coverage. This was a targeted
repair of the failed deployment gate, not another broad review cycle.

- Final Worker version: `70b42812-5fd9-45e7-902f-ae35d56151a2`, 100% traffic.
- Final licensed source hash:
  `b835d4709d29b1111f1673f19a5a64d5d8ae09c14138c3f363e4d6d5de40ca25`.
- Source manifest: 29 files. Remote archive: 79,655 bytes, SHA-256
  `48afdbd4606278d48bb0559563e2b331e6b3bcc4f27171b520d97ca66d0c95d7`,
  identical to the local committed archive.
- Wrangler reports the `fetch` and `scheduled` handlers, five-minute cron,
  independent production D1, Assets, `nodejs_compat`, production mode and the
  existing Access/signing bindings. No Access policy or secret was changed.

### HTTPS post-check

The custom origin passed the final read-only protocol check:

| Check | Result |
| --- | --- |
| Signed feed | HTTP 200; envelope schema 1; key id matched; `feedVersion=0`; complete empty snapshot |
| Ed25519 verification | Passed against the Android-pinned production public key |
| Audience and target | Install UUID hash and android/stable/version 1/zh-CN target matched |
| Conditional fetch | Cloudflare weak ETag returned; matching `If-None-Match` produced HTTP 304 |
| Invalid client context | HTTP 400 |
| `/source` | HTTP 200; final hash, 29-file manifest and archive bytes matched |
| Unauthenticated `/admin/v1/stats` | HTTP 302 to Cloudflare Access; no anonymous admin data returned |

The empty feed is the current database state, not an error. No test announcement,
admin mutation, migration, Access-policy change or production event upload was
performed. Authenticated admin-browser acceptance and a natural scheduled
publication remain owner-operated checks; they do not block the public issue #1
deployment recorded here.
