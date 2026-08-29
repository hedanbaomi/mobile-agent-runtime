<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Announcements production readiness evidence

Date: 2026-08-29 (Asia/Taipei)  
Scope: `services/announcements/**`, `admin/announcements/**` only.  
Worker: `mobile-agent-runtime-announcements`  
D1: `mobile-agent-runtime-announcements-prod` / `06cf40c7-dd84-4560-859a-1a417f47207e`  
Origin: `https://mobile-agent-runtime-announcements.gmailforzhibai.workers.dev`  
Signing key id: `mar-prod-20260829-1`

## Boundary

This evidence records implementation and local verification by the
announcements subtask. No `wrangler deploy`, remote D1 migration, remote D1
query, secret write, Access application change, browser authentication, commit,
push, or release was performed here. Production resource creation and final
deployment remain owner controlled. The Access team domain and audience are
intentionally absent, so production `/admin/v1` must remain fail closed while
the public signed feed can operate after authorized deployment.

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
| Source route | `/source` serves the hash named source archive staged by `scripts/build-source-artifact.mjs --stage-assets`; generator excludes env/private/node_modules paths and emits SPDX metadata for generated HTML plus a REUSE sidecar for `manifest.json` | Implemented; final hash `07f164ef5f473ff426488eeeddf0bb7d1cb522286c5389e85baa2351bb473ae3` deployed as recorded below |

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

The owner subsequently authorized the main agent to deploy this independent
announcements system. The private signing key was injected through Wrangler's
secret interface without being printed or copied into this evidence.

- Remote D1 migration inspection reports `No migrations to apply!` for
  `mobile-agent-runtime-announcements-prod`.
- The deployed Worker version is
  `dd2be020-85ff-48ef-8b83-779a7a9cc02b`, created at
  `2026-08-28T17:49:11.929Z`.
- Wrangler version inspection confirms `fetch` and `scheduled` handlers,
  `nodejs_compat`, the independent D1 binding, Assets, the private-key secret
  binding, `MAR_ENV=production` and `MAR_ANNOUNCE_KEY_ID=mar-prod-20260829-1`.
- The deployed Assets include the final licensed source artifact
  `07f164ef5f473ff426488eeeddf0bb7d1cb522286c5389e85baa2351bb473ae3`.
  Deployment evidence is retained at
  `.private/overnight/announcements-production/deploy-source-license-update.log`.
- Access team domain and audience remain empty. Production `/admin/v1` is
  therefore intentionally fail closed; local production-mode verification
  confirmed that `X-Admin-Token` cannot bypass it.

Two owner-controlled gates remain. The Cloudflare account does not yet have the
Zero Trust organization/authentication domain needed to define the Access
identity policy, and creating that account-level identity configuration while
the owner is absent would be unsafe. In addition, this host resolves the
`workers.dev` endpoint through reserved address `198.18.2.56`; the TLS client
fails before receiving HTTP headers. The deployment/version/bindings are
confirmed through Wrangler, but the public signature/ETag and browser Access
post-checks must be repeated from a normal network after the owner configures
the identity policy.
