// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { verify } from "node:crypto";
import { createWorker } from "./app.mjs";
import { SIGN_PREFIX, audienceHash, generateKeyPair, keyPairFromSeed, signPayload } from "./sign.mjs";
import { MemoryStore } from "./store.mjs";
import { localeFallback } from "./targeting.mjs";
import { rolloutBucket } from "./rollout.mjs";

const ADMIN = "test-admin-token";
const INSTALL_A = "00000000-0000-4000-8000-000000000001";
const INSTALL_B = "00000000-0000-4000-8000-000000000002";
const BASE = "http://127.0.0.1:8787";

function app(clock = () => new Date("2026-08-28T12:00:00.000Z")) {
  return createWorker({ store: new MemoryStore(), keys: generateKeyPair(), adminToken: ADMIN, clock });
}

function adminHeaders(requestId = crypto.randomUUID()) {
  return {
    "content-type": "application/json",
    "X-Admin-Token": ADMIN,
    "X-Request-Id": requestId,
    Origin: "http://127.0.0.1:8787",
  };
}

function sample(overrides = {}) {
  return {
    id: "security-demo",
    category: "SECURITY",
    severity: "WARNING",
    displayMode: "MODAL",
    mustAcknowledge: true,
    dismissible: false,
    pinned: true,
    target: {
      platform: "android",
      channel: "stable",
      locales: [],
      rolloutPercent: 100,
      rolloutSalt: "stable-salt",
    },
    actions: [
      { type: "ACKNOWLEDGE", key: "ack", label: "Acknowledge" },
      { type: "OPEN_HTTPS_URL", key: "docs", label: "Docs", url: "https://example.invalid/notice" },
    ],
    translations: {
      default: { title: "Security notice", summary: "Please read", bodyMarkdown: "Signed announcement body." },
      "zh-CN": { title: "安全公告", summary: "请阅读", bodyMarkdown: "已签名公告正文。" },
    },
    ...overrides,
  };
}

async function json(worker, path, init = {}) {
  const response = await worker.fetch(new Request(`${BASE}${path}`, init));
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { response, body };
}

function payloadOf(envelope) {
  return JSON.parse(Buffer.from(envelope.payloadBase64, "base64").toString("utf8"));
}

async function publishSample(worker, body = sample()) {
  const created = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(body),
  });
  assert.equal(created.response.status, 201, JSON.stringify(created.body));
  const published = await json(worker, `/admin/v1/announcements/${body.id}/publish`, {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify({ expectedRevision: 1 }),
  });
  assert.equal(published.response.status, 200, JSON.stringify(published.body));
  return published.body;
}

async function decodeFeed(worker, installId = INSTALL_B, extra = {}, query = "platform=android&channel=stable&versionCode=1&locale=en") {
  return json(worker, `/api/v1/announcements?${query}`, {
    headers: { "X-Install-ID": installId, ...extra },
  });
}

assert.equal(rolloutBucket("security-demo", "stable-salt", INSTALL_A), 44);
assert.equal(rolloutBucket("security-demo", "stable-salt", INSTALL_B), 27);
assert.deepEqual(localeFallback("zh-Hans-CN"), ["zh-Hans-CN", "zh-CN", "zh-Hans", "zh", "default"]);

{
  const keys = keyPairFromSeed();
  const payloadBase64 = Buffer.from("{}", "utf8").toString("base64");
  const signature = signPayload(keys.privateKey, payloadBase64);
  const ok = verify(null, Buffer.from(SIGN_PREFIX + payloadBase64), keys.publicKey, Buffer.from(signature, "base64"));
  assert.equal(ok, true);
  console.log("test-only seed sign/verify ok");
  console.log(`TEST_ONLY_PUBLIC_HEX=${keys.publicKeyRaw.toString("hex")}`);
  console.log(`TEST_ONLY_EMPTY_JSON_SIG=${signature}`);
}

{
  const worker = app();
  await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(sample({ id: "draft-only" })),
  });
  const draftFeed = await decodeFeed(worker);
  assert.equal(payloadOf(draftFeed.body).items.length, 0);

  const future = app();
  await json(future, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(
      sample({
        id: "scheduled-later",
        startsAt: "2026-08-29T00:00:00.000Z",
        mustAcknowledge: false,
        displayMode: "BANNER",
        severity: "INFO",
        actions: [{ type: "DISMISS", key: "d", label: "OK" }],
      }),
    ),
  });
  await json(future, "/admin/v1/announcements/scheduled-later/schedule", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify({ startsAt: "2026-08-29T00:00:00.000Z", expectedRevision: 1 }),
  });
  assert.equal(payloadOf((await decodeFeed(future)).body).items.length, 0);

  const publishedFuture = app();
  await publishSample(
    publishedFuture,
    sample({
      id: "not-yet",
      startsAt: "2026-08-29T00:00:00.000Z",
      mustAcknowledge: false,
      displayMode: "BANNER",
      severity: "INFO",
      actions: [{ type: "DISMISS", key: "d", label: "OK" }],
    }),
  );
  assert.equal(payloadOf((await decodeFeed(publishedFuture)).body).items.length, 0);
  console.log("N01 draft/schedule/future not leaked ok");
}

{
  const worker = app();
  await publishSample(worker);
  const conflict = await Promise.all([
    json(worker, "/admin/v1/announcements/security-demo/revisions", {
      method: "POST",
      headers: adminHeaders(),
      body: JSON.stringify(
        sample({
          mustAcknowledge: false,
          displayMode: "BANNER",
          severity: "NOTICE",
          actions: [{ type: "DISMISS", key: "d", label: "OK" }],
        }),
      ),
    }),
    json(worker, "/admin/v1/announcements/security-demo/revisions", {
      method: "POST",
      headers: adminHeaders(),
      body: JSON.stringify(
        sample({
          mustAcknowledge: false,
          displayMode: "BANNER",
          severity: "NOTICE",
          actions: [{ type: "DISMISS", key: "d", label: "OK" }],
        }),
      ),
    }),
  ]);
  const statuses = conflict.map((item) => item.response.status).sort();
  assert.deepEqual(statuses, [201, 409]);
  assert.equal(payloadOf((await decodeFeed(worker)).body).items[0].revision, 1);
  await json(worker, "/admin/v1/announcements/security-demo/publish", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify({ expectedRevision: 2 }),
  });
  assert.equal(payloadOf((await decodeFeed(worker)).body).items[0].revision, 2);
  await json(worker, "/admin/v1/announcements/security-demo/withdraw", { method: "POST", headers: adminHeaders() });
  const withdrawnPayload = payloadOf((await decodeFeed(worker)).body);
  assert.equal(withdrawnPayload.items.length, 0);
  assert.equal(withdrawnPayload.withdrawn[0].id, "security-demo");
  const archived = app();
  await publishSample(archived, sample({ id: "old-notice", mustAcknowledge: false, displayMode: "CENTER_ONLY", severity: "INFO", actions: [{ type: "DISMISS", key: "d", label: "OK" }] }));
  await json(archived, "/admin/v1/announcements/old-notice/archive", { method: "POST", headers: adminHeaders() });
  assert.equal(payloadOf((await decodeFeed(archived)).body).items.length, 0);
  console.log("N01 publish/revise/withdraw/archive/409 ok");
}

{
  const worker = app();
  await publishSample(
    worker,
    sample({ target: { platform: "android", channel: "stable", rolloutPercent: 30, rolloutSalt: "stable-salt", locales: [] } }),
  );
  assert.equal(payloadOf((await decodeFeed(worker, INSTALL_A)).body).items.length, 0);
  assert.equal(payloadOf((await decodeFeed(worker, INSTALL_B)).body).items.length, 1);
  const ios = await decodeFeed(worker, INSTALL_B, {}, "platform=ios&channel=stable&versionCode=1&locale=en");
  assert.equal(payloadOf(ios.body).items.length, 0);
  const localeRestricted = app();
  await publishSample(
    localeRestricted,
    sample({
      id: "locale-demo",
      mustAcknowledge: false,
      displayMode: "CENTER_ONLY",
      severity: "INFO",
      actions: [{ type: "DISMISS", key: "d", label: "OK" }],
      target: { platform: "android", channel: "all", locales: ["zh-CN"], rolloutPercent: 100, rolloutSalt: "stable-salt" },
    }),
  );
  assert.equal(payloadOf((await decodeFeed(localeRestricted, INSTALL_B)).body).items.length, 0);
  const zh = await decodeFeed(localeRestricted, INSTALL_B, {}, "platform=android&channel=stable&versionCode=1&locale=zh-CN");
  assert.equal(payloadOf(zh.body).items[0].title, "安全公告");
  console.log("N02 targeting/rollout/locale ok");
}

{
  const worker = app();
  await publishSample(worker);
  const first = await decodeFeed(worker);
  const second = await decodeFeed(worker, INSTALL_B, { "If-None-Match": first.response.headers.get("ETag") });
  assert.equal(second.response.status, 304);
  const nearExpiry = createWorker({
    store: worker.store,
    keys: worker.keys,
    adminToken: ADMIN,
    clock: () => new Date("2026-08-29T11:30:00.000Z"),
  });
  const refreshed = await decodeFeed(nearExpiry, INSTALL_B, { "If-None-Match": first.response.headers.get("ETag") });
  assert.equal(refreshed.response.status, 200);
  console.log("N05 ETag/304 and near-expiry resign ok");
}

{
  const worker = app();
  const denied = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Request-Id": crypto.randomUUID() },
    body: JSON.stringify(sample({ id: "no-auth" })),
  });
  assert.equal(denied.response.status, 401);
  const csrf = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: { ...adminHeaders(), Origin: "https://evil.example" },
    body: JSON.stringify(sample({ id: "csrf" })),
  });
  assert.equal(csrf.response.status, 403);
  await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(
      sample({
        id: "html-body",
        translations: { default: { title: "x", summary: "y", bodyMarkdown: "hi <script>alert(1)</script>" } },
      }),
    ),
  });
  const publishedHtml = await json(worker, "/admin/v1/announcements/html-body/publish", {
    method: "POST",
    headers: adminHeaders(),
    body: "{}",
  });
  assert.equal(publishedHtml.response.status, 400);
  const intent = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(
      sample({
        id: "bad-action",
        actions: [{ type: "OPEN_HTTPS_URL", key: "x", label: "x", url: "intent://scan" }],
      }),
    ),
  });
  const publishedIntent = await json(worker, "/admin/v1/announcements/bad-action/publish", {
    method: "POST",
    headers: adminHeaders(),
    body: "{}",
  });
  assert.equal(publishedIntent.response.status, 400);
  console.log("N06/N07 admin auth, CSRF, HTML/intent reject ok");
}

{
  const worker = app();
  await publishSample(worker);
  const event = {
    eventId: "22222222-2222-4222-8222-222222222222",
    type: "announcement_opened",
    installId: INSTALL_B,
    platform: "android",
    channel: "stable",
    versionCode: 1,
    locale: "en",
    announcementId: "security-demo",
    revision: 1,
    occurredAt: "2026-08-28T12:00:00.000Z",
  };
  const noConsent = await json(worker, "/api/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ events: [event] }),
  });
  assert.equal(noConsent.response.status, 204);
  assert.equal(worker.store.receipts.size, 0);
  assert.equal((await json(worker, "/api/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Stats-Consent": "1" },
    body: JSON.stringify({ events: [event] }),
  })).response.status, 204);
  assert.equal((await json(worker, "/api/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Stats-Consent": "1" },
    body: JSON.stringify({ events: [event] }),
  })).response.status, 204);
  assert.equal([...worker.store.receipts.values()][0].count, 1);
  const concurrent = await Promise.all(
    [0, 1, 2, 3].map(() =>
      json(worker, "/api/v1/events", {
        method: "POST",
        headers: { "content-type": "application/json", "X-Stats-Consent": "1" },
        body: JSON.stringify({
          events: [{ ...event, eventId: "33333333-3333-4333-8333-333333333333", type: "app_active" }],
        }),
      }),
    ),
  );
  assert.ok(concurrent.every((item) => item.response.status === 204));
  assert.equal(worker.store.installState.size, 1);
  const sensitive = await json(worker, "/api/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Stats-Consent": "1" },
    body: JSON.stringify({
      events: [{ ...event, eventId: "44444444-4444-4444-8444-444444444444", chat: "secret prompt" }],
    }),
  });
  assert.equal(sensitive.response.status, 400);
  console.log("N08 stats off, dedup, app_active, forbidden fields ok");
}

{
  const worker = app();
  await publishSample(worker, sample({ id: "visible" }));
  const feed = await decodeFeed(worker);
  assert.equal(feed.response.headers.get("authorization"), null);
  const payload = payloadOf(feed.body);
  assert.equal(payload.complete, true);
  assert.equal(payload.items[0].id, "visible");
  assert.equal(payload.audienceHash, audienceHash(INSTALL_B));
  const { verify: verifySig } = await import("node:crypto");
  assert.equal(
    verifySig(
      null,
      Buffer.from(SIGN_PREFIX + feed.body.payloadBase64),
      worker.keys.publicKey,
      Buffer.from(feed.body.signatureBase64, "base64"),
    ),
    true,
  );
  console.log("N09 local admin publish to signed feed ok");
}

{
  let nowMs = Date.parse("2026-08-28T12:00:00.000Z");
  const worker = createWorker({
    store: new MemoryStore(),
    keys: generateKeyPair(),
    adminToken: ADMIN,
    clock: () => new Date(nowMs),
  });
  await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(
      sample({
        id: "due-later",
        mustAcknowledge: false,
        displayMode: "BANNER",
        severity: "INFO",
        actions: [{ type: "DISMISS", key: "d", label: "OK" }],
      }),
    ),
  });
  await json(worker, "/admin/v1/announcements/due-later/schedule", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify({ startsAt: "2026-08-28T13:00:00.000Z", expectedRevision: 1 }),
  });
  nowMs = Date.parse("2026-08-28T12:30:00.000Z");
  assert.equal(payloadOf((await decodeFeed(worker)).body).items.length, 0);
  nowMs = Date.parse("2026-08-28T14:00:00.000Z");
  assert.equal(payloadOf((await decodeFeed(worker)).body).items.length, 1);
  assert.equal(payloadOf((await decodeFeed(worker)).body).items.length, 1);
  const withdrawn = createWorker({
    store: new MemoryStore(),
    keys: generateKeyPair(),
    adminToken: ADMIN,
    clock: () => new Date(nowMs),
  });
  await json(withdrawn, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(
      sample({
        id: "never-due",
        mustAcknowledge: false,
        displayMode: "BANNER",
        severity: "INFO",
        actions: [{ type: "DISMISS", key: "d", label: "OK" }],
      }),
    ),
  });
  await json(withdrawn, "/admin/v1/announcements/never-due/schedule", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify({ startsAt: "2026-08-28T13:00:00.000Z", expectedRevision: 1 }),
  });
  await json(withdrawn, "/admin/v1/announcements/never-due/withdraw", { method: "POST", headers: adminHeaders() });
  nowMs = Date.parse("2026-08-28T14:00:00.000Z");
  assert.equal(payloadOf((await decodeFeed(withdrawn)).body).items.length, 0);
  console.log("NAR01 schedule promote at due time ok");
}

{
  const schema = readFileSync(new URL("./schema.sql", import.meta.url), "utf8");
  assert.equal(schema.trimStart().startsWith("//"), false);
  const py = spawnSync(
    "python",
    ["-c", "import sqlite3,sys; sql=sys.stdin.read(); c=sqlite3.connect(':memory:'); c.executescript(sql); print(c.execute('select count(*) from sqlite_master').fetchone()[0])"],
    { input: schema, encoding: "utf8" },
  );
  assert.equal(py.status, 0, py.stderr + py.stdout);
  assert.ok(Number(py.stdout.trim()) > 0);
  console.log("NAR02 schema.sql executes in SQLite ok");
}

{
  const worker = app();
  const badCategory = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(sample({ id: "bad-cat", category: "NOT_A_CATEGORY" })),
  });
  assert.equal(badCategory.response.status, 400);
  const badDate = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(sample({ id: "bad-date", startsAt: "not-a-date" })),
  });
  assert.equal(badDate.response.status, 400);
  console.log("NAR03 invalid category/date rejected before publish ok");
}

{
  const worker = app();
  const created = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify(sample({ id: "incomplete-tr" })),
  });
  assert.equal(created.response.status, 201);
  const patch = await json(worker, "/admin/v1/announcements/incomplete-tr", {
    method: "PATCH",
    headers: adminHeaders(),
    body: JSON.stringify({ translations: { default: { title: "only-title" } } }),
  });
  assert.equal(patch.response.status, 400);
  const retry = await json(worker, "/admin/v1/announcements/incomplete-tr", {
    method: "PATCH",
    headers: adminHeaders(),
    body: JSON.stringify({
      translations: { default: { title: "ok", summary: "ok", bodyMarkdown: "ok" } },
    }),
  });
  assert.equal(retry.response.status, 200, JSON.stringify(retry.body));
  const createFail = await json(worker, "/admin/v1/announcements", {
    method: "POST",
    headers: adminHeaders(),
    body: JSON.stringify({ id: "no-tr" }),
  });
  assert.equal(createFail.response.status, 400);
  assert.equal(worker.store.announcements.has("no-tr"), false);
  console.log("NAR04 failed translation write does not leave dirty state ok");
}

{
  const worker = app();
  await publishSample(worker);
  const first = await decodeFeed(worker);
  const later = createWorker({
    store: worker.store,
    keys: worker.keys,
    adminToken: ADMIN,
    clock: () => new Date("2026-08-28T12:00:00.001Z"),
  });
  const second = await decodeFeed(later, INSTALL_B, { "If-None-Match": first.response.headers.get("ETag") });
  assert.equal(second.response.status, 304);
  assert.equal(second.response.headers.get("ETag"), first.response.headers.get("ETag"));
  console.log("NAR05 content-stable ETag 304 under advancing clock ok");
}

console.log("announcement worker protocol tests ok");
