<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Announcements Worker deployment runbook

This runbook covers only the independent announcements Worker and its D1
database. It does not create or reuse resources from another product. The
checked-in `wrangler.toml` contains public resource identifiers and the
non-secret key id; it deliberately contains no private key, Access token,
cookie, or API credential.

## Public configuration

| Setting | Value |
| --- | --- |
| Worker | `mobile-agent-runtime-announcements` |
| D1 | `mobile-agent-runtime-announcements-prod` |
| D1 id | `06cf40c7-dd84-4560-859a-1a417f47207e` |
| Origin | `https://announcements.luotianyi.fun` |
| signing key id | `mar-prod-20260829-1` |
| Access team/audience | owner supplies after the Access application exists; empty values keep admin fail closed |

The production signing secret is `MAR_ANNOUNCE_PRIVATE_KEY_PKCS8`: a standard
base64 encoding of the Ed25519 PKCS#8 DER private key. It is a Worker secret,
never a repository variable. The public Ed25519 key is distributed separately
to clients. A key id change requires the client key overlap and rotation plan
from the announcement protocol before changing `MAR_ANNOUNCE_KEY_ID`.

## Local D1 verification

Run these commands from this directory. They use the explicit local config and
the all-zero local D1 id; none of them contacts a remote database.

```text
npm ci
npm run check
npm test
npx --no-install wrangler d1 migrations apply mobile-agent-runtime-announcements-local --local --config wrangler.local.toml
npx --no-install wrangler dev --local --config wrangler.local.toml --var MAR_ADMIN_TOKEN:<local-test-token>
```

The local Worker must exercise create, schedule, publish, revision CAS,
withdraw/archive, feed signing, ETag/304, event consent and event dedup. Trigger
the scheduler in a second local shell with the URL Wrangler prints for the
local scheduled event. Keep local persistence outside the repository when
possible; `.wrangler/` is ignored.

`npm run preflight:production` checks the production binding and source shape
without checking secret presence. In the authorized deployment shell,
`node scripts/preflight.mjs --require-secrets` additionally checks that the two
secret variables are present without printing their values.

## Source artifact and `/source`

Before deploying a source revision, generate the public, hash named artifact:

```text
node scripts/build-source-artifact.mjs --stage-assets
```

This writes `admin/announcements/source/<sourceHash>/` and a generated index
under the assets root. The archive contains only the announcements service,
its related admin page, migrations, production config, and the repository
license policy. The generator rejects `.env*`, `.dev.vars*`, private/secret
paths, `.wrangler`, and `node_modules`; inspect `manifest.json` before release.
After deployment, `GET /source` must expose the same `sourceHash` and a
downloadable archive as the deployed Worker. Do not call the route a source
release until this post-deploy check succeeds.

## Authorized production sequence

Only the owner/operator with the production authorization performs these
steps. Record the exact source hash, current Git state, command exit codes,
database backup identifier, and deployment version in the evidence report.

1. Confirm the Worker name, independent D1 id/name, origin, account and source
   hash against the values above. Inspect the generated source manifest. Do
   not continue if a `.env`, private key, Access credential or `node_modules`
   entry is present.
2. Take and verify a D1 backup/export using the owner's approved Cloudflare
   procedure. Keep the backup identifier with the rollback record.
3. Apply only the checked-in migrations to the independent D1:

   ```text
   npx --no-install wrangler d1 migrations apply mobile-agent-runtime-announcements-prod --remote --config wrangler.toml
   ```

   Stop on any migration failure. Never use `--remote` with the local config or
   with another product's database name.
4. Set `MAR_ANNOUNCE_PRIVATE_KEY_PKCS8` with `wrangler secret put` in the
   authorized shell. Confirm the configured key id is
   `mar-prod-20260829-1`; do not echo or paste the secret into a log.
5. If Access is already provisioned, set the non-secret
   `MAR_ACCESS_TEAM_DOMAIN` and `MAR_ACCESS_AUDIENCE` vars to the exact values
   from the Access application and deploy. If Access is not provisioned, leave
   both empty: public signed feed deployment may proceed, while every admin API
   route must remain a generic 503 (`admin disabled until Cloudflare Access is
   configured`). Never add a local token to production.
6. Deploy the Worker and assets with the owner-approved Wrangler command. A
   deployment is not a release until the post-checks below pass.

## Post-checks and rollback

Use a fresh random install UUID and verify the public response's schema,
`keyId`, `feedVersion`, audience hash, ETag and Ed25519 signature. Repeat with
`If-None-Match` for 304 and with an invalid client context for 400. Confirm an
empty D1 returns a signed complete feed and no admin data. Confirm `/source`
returns the recorded source hash and archive.

When Access is absent, verify an attempted admin request (including any
`X-Admin-Token`) returns 503 and that no database mutation occurred. When
Access is configured, test a valid same-origin Access session, wrong audience,
expired assertion, missing assertion, wrong Origin and a mutating request with
no Origin. Only the valid assertion and exact configured Origin may mutate;
the D1 audit row must contain the Access actor and request id.

For rollback, first stop administrative writes at Access, preserve the failed
deployment/source hash and logs, and use the owner's approved Wrangler
version rollback plus the verified D1 backup/forward-fix plan. D1 migrations
are forward-only in this service; do not delete tables or run an unreviewed
reverse SQL script. Re-run the public signature, `/source`, Access and audit
post-checks after rollback.
