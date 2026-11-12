// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { verify } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { createWorker, BoundedSnapshotCache, MAX_SNAPSHOT_CACHE_ENTRIES, MAX_SNAPSHOT_CACHE_BYTES } from "./app.mjs";
import workerEntry from "./worker.mjs";
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
  worker.store.signedSnapshots.clear();
  const coldIsolate = await decodeFeed(worker, INSTALL_B, { "If-None-Match": `W/${first.response.headers.get("ETag")}` });
  assert.equal(coldIsolate.response.status, 304);
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
  const database = new DatabaseSync(":memory:");
  try {
    database.exec(schema);
    const objects = database.prepare("SELECT COUNT(*) AS count FROM sqlite_master").get().count;
    assert.ok(objects > 0);
    const feedState = database.prepare(
      "SELECT id, sequence, content_version, key_id FROM feed_state",
    ).get();
    assert.deepEqual({ ...feedState }, { id: 1, sequence: 0, content_version: 0, key_id: "" });

    const schemaTimestamp = "2026-08-29T12:00:00.000Z";
    database.prepare(
      "INSERT INTO announcements (id, current_published_revision, status, created_at, updated_at) VALUES (?, NULL, 'draft', ?, ?)",
    ).run("node-sqlite-fixture", schemaTimestamp, schemaTimestamp);
    const addRevision = database.prepare(
      "INSERT INTO announcement_revisions (announcement_id, revision, revision_status, body_json, rollout_salt, updated_at) VALUES (?, ?, ?, '{}', 'fixture', ?)",
    );
    addRevision.run("node-sqlite-fixture", 1, "draft", schemaTimestamp);
    assert.throws(
      () => addRevision.run("node-sqlite-fixture", 2, "scheduled", schemaTimestamp),
      /UNIQUE constraint failed/,
      "the migrated partial index must allow only one pending revision",
    );
    database.prepare(
      "UPDATE announcement_revisions SET revision_status = 'published' WHERE announcement_id = ? AND revision = 1",
    ).run("node-sqlite-fixture");
    addRevision.run("node-sqlite-fixture", 2, "draft", schemaTimestamp);
    assert.equal(
      database.prepare("SELECT COUNT(*) AS count FROM announcement_revisions WHERE announcement_id = ?").get("node-sqlite-fixture").count,
      2,
    );
  } finally {
    database.close();
  }
  console.log("NAR02 schema.sql executes in Node SQLite and enforces the pending-revision index ok");
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

{
  // Test P2-7: Bounded snapshot cache capacity, LRU eviction, and TTL expiration
  assert.equal(MAX_SNAPSHOT_CACHE_ENTRIES, 200);
  const worker = app();
  await publishSample(worker);

  // 1. Capacity bounding and LRU eviction verification
  const totalClients = 210;
  const etags = new Map();
  for (let i = 0; i < totalClients; i += 1) {
    const id = `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`;
    const res = await decodeFeed(worker, id);
    assert.equal(res.response.status, 200);
    etags.set(id, res.response.headers.get("ETag"));
  }

  // Verify bounded size does not exceed 200
  assert.equal(worker.store.signedSnapshots.size, 200);

  // Client 0 was the oldest and must have been evicted from the LRU cache
  const client0Id = "00000000-0000-4000-8000-000000000000";
  const client0KeyPrefix = `android|stable|1|en|${client0Id}|`;
  let client0FoundInCache = false;
  for (const k of worker.store.signedSnapshots.keys()) {
    if (k.startsWith(client0KeyPrefix)) client0FoundInCache = true;
  }
  assert.equal(client0FoundInCache, false, "oldest client should be evicted from LRU cache");

  // 2. Deterministic ETag 304 response on cache miss
  // Even though client 0 was evicted, sending its previous ETag still yields 304 Not Modified!
  const client0Etag = etags.get(client0Id);
  const client0304 = await decodeFeed(worker, client0Id, { "If-None-Match": client0Etag });
  assert.equal(client0304.response.status, 304, "evicted client with matching ETag returns 304");
  assert.equal(client0304.response.headers.get("ETag"), client0Etag);

  // 3. Re-signing after LRU eviction
  // Requesting without If-None-Match re-signs and caches successfully
  const client0ReSign = await decodeFeed(worker, client0Id);
  assert.equal(client0ReSign.response.status, 200);
  assert.equal(worker.store.signedSnapshots.size, 200);

  // 4. TTL / Expiration check
  const now = new Date("2026-08-28T12:00:00.000Z");
  const cache = new BoundedSnapshotCache(10);
  cache.set("item-active", { expiresAt: new Date("2026-08-29T12:00:00.000Z").toISOString() }, now.getTime());
  cache.set("item-expired", { expiresAt: new Date("2026-08-28T11:00:00.000Z").toISOString() }, now.getTime());
  assert.equal(cache.size, 2);

  // Calling get on expired item returns undefined and evicts it
  assert.equal(cache.get("item-expired", now.getTime()), undefined);
  assert.equal(cache.size, 1);
  assert.equal(cache.get("item-active", now.getTime()) !== undefined, true);

  // Evict expired helper
  cache.set("item-expired-2", { expiresAt: new Date("2026-08-28T10:00:00.000Z").toISOString() });
  assert.equal(cache.size, 2);
  const pruned = cache.evictExpired(now.getTime());
  assert.equal(pruned, 1);
  assert.equal(cache.size, 1);

  // Capacity eviction prioritizing expired
  const smallCache = new BoundedSnapshotCache(2);
  smallCache.set("k1", { expiresAt: new Date("2026-08-28T11:00:00.000Z").toISOString() }, now.getTime());
  smallCache.set("k2", { expiresAt: new Date("2026-08-28T13:00:00.000Z").toISOString() }, now.getTime());
  // Adding k3 when full: k1 is expired, so k1 should be evicted even though it was inserted first
  smallCache.set("k3", { expiresAt: new Date("2026-08-28T14:00:00.000Z").toISOString() }, now.getTime());
  assert.equal(smallCache.size, 2);
  assert.equal(smallCache.has("k1"), false);
  assert.equal(smallCache.has("k2"), true);
  assert.equal(smallCache.has("k3"), true);

  // 5. Total UTF-8 byte budget ledger, mixed large/small envelopes, oversized bypass, 200/304, and re-signing
  assert.equal(MAX_SNAPSHOT_CACHE_BYTES, 8 * 1024 * 1024);
  const byteCache = new BoundedSnapshotCache(200, 600);
  const vSmallA = { etag: '"a"', envelope: { payloadBase64: "x".repeat(80) }, expiresAt: "2026-08-29T12:00:00.000Z" };
  const vSmallB = { etag: '"b"', envelope: { payloadBase64: "y".repeat(80) }, expiresAt: "2026-08-29T12:00:00.000Z" };
  const vLarge = { etag: '"L"', envelope: { payloadBase64: "z".repeat(260) }, expiresAt: "2026-08-29T12:00:00.000Z" };
  byteCache.set("small-a", vSmallA, now.getTime());
  const bytesA = byteCache.byteLength;
  assert.ok(bytesA > 80 && bytesA < 300);
  byteCache.set("small-b", vSmallB, now.getTime());
  assert.equal(byteCache.size, 2);
  const bytesAB = byteCache.byteLength;
  assert.ok(bytesAB <= 600);

  // Same-key overwrite updates byte ledger accurately without leak
  const vSmallAUpdated = { etag: '"a2"', envelope: { payloadBase64: "x".repeat(120) }, expiresAt: "2026-08-29T12:00:00.000Z" };
  byteCache.set("small-a", vSmallAUpdated, now.getTime());
  assert.equal(byteCache.size, 2);
  assert.ok(byteCache.byteLength > bytesAB && byteCache.byteLength <= 600);

  // Inserting vLarge exceeds 600B budget -> evicts LRU ("small-b", since "small-a" was just updated!)
  byteCache.set("large-c", vLarge, now.getTime());
  assert.ok(byteCache.byteLength <= 600);
  assert.equal(byteCache.has("small-b"), false);
  assert.equal(byteCache.has("small-a"), true);
  assert.equal(byteCache.has("large-c"), true);

  // Oversized single entry (> maxBytes) is neither inserted nor allowed to flush existing entries
  const vOversized = { etag: '"huge"', envelope: { payloadBase64: "H".repeat(900) }, expiresAt: "2026-08-29T12:00:00.000Z" };
  const beforeOversizedBytes = byteCache.byteLength;
  byteCache.set("oversized-key", vOversized, now.getTime());
  assert.equal(byteCache.has("oversized-key"), false);
  assert.equal(byteCache.has("small-a"), true);
  assert.equal(byteCache.has("large-c"), true);
  assert.equal(byteCache.byteLength, beforeOversizedBytes);

  // Delete and clear maintain zero-drift byte ledger
  assert.equal(byteCache.delete("small-a"), true);
  assert.ok(byteCache.byteLength > 0 && byteCache.byteLength < beforeOversizedBytes);
  byteCache.clear();
  assert.equal(byteCache.byteLength, 0);
  assert.equal(byteCache.size, 0);

  // End-to-end Worker test with mixed small (empty locale/rollout miss) and large (~24 KiB markdown) envelopes
  let mixedNowMs = Date.parse("2026-08-28T12:00:00.000Z");
  const keys = generateKeyPair();
  const budgetBytes = 48 * 1024;
  const mixedWorker = createWorker({
    store: new MemoryStore(),
    keys,
    adminToken: ADMIN,
    clock: () => new Date(mixedNowMs),
    snapshotCacheLimit: 200,
    snapshotCacheMaxBytes: budgetBytes,
  });
  // Publish an announcement targeting zh-CN with ~22 KiB markdown (~30 KiB Base64 envelope),
  // while locale=en receives an empty ~400 B envelope (rollout/locale mix of small + large feeds!).
  await publishSample(
    mixedWorker,
    sample({
      id: "large-zh-only",
      mustAcknowledge: false,
      displayMode: "CENTER_ONLY",
      severity: "INFO",
      actions: [{ type: "DISMISS", key: "d", label: "OK" }],
      target: { platform: "android", channel: "stable", locales: ["zh-CN"], rolloutPercent: 100, rolloutSalt: "stable-salt" },
      translations: {
        default: { title: "Large notice", summary: "Mixed cache test", bodyMarkdown: "Paragraph body for byte budget. ".repeat(700) },
        "zh-CN": { title: "大体积公告", summary: "混合缓存测试", bodyMarkdown: "段落内容验证字节预算。".repeat(700) },
      },
    }),
  );

  const smallClient1 = "00000000-0000-4000-8000-000000000901";
  const smallClient2 = "00000000-0000-4000-8000-000000000902";
  const largeClient1 = "00000000-0000-4000-8000-000000000903";
  const largeClient2 = "00000000-0000-4000-8000-000000000904";

  const s1 = await decodeFeed(mixedWorker, smallClient1);
  const l1 = await decodeFeed(mixedWorker, largeClient1, {}, "platform=android&channel=stable&versionCode=1&locale=zh-CN");
  const s2 = await decodeFeed(mixedWorker, smallClient2);
  assert.equal(s1.response.status, 200);
  assert.equal(l1.response.status, 200);
  assert.equal(s2.response.status, 200);
  assert.equal(mixedWorker.store.signedSnapshots.size, 3);
  assert.ok(mixedWorker.store.signedSnapshots.byteLength <= budgetBytes);

  // Touch smallClient1 via 200 cache hit so largeClient1 becomes the oldest entry
  const s1Hit = await decodeFeed(mixedWorker, smallClient1);
  assert.equal(s1Hit.response.status, 200);
  assert.equal(s1Hit.body.signatureBase64, s1.body.signatureBase64);

  // Fetching second ~30 KiB envelope pushes total over 48 KiB -> evicts largeClient1 while keeping smallClient2 & smallClient1!
  const l2 = await decodeFeed(mixedWorker, largeClient2, {}, "platform=android&channel=stable&versionCode=1&locale=zh-CN");
  assert.equal(l2.response.status, 200);
  assert.ok(mixedWorker.store.signedSnapshots.byteLength <= budgetBytes);
  const remainingKeys = [...mixedWorker.store.signedSnapshots.keys()];
  assert.equal(remainingKeys.some((k) => k.includes(largeClient1)), false, "largeClient1 evicted by byte budget");
  assert.equal(remainingKeys.some((k) => k.includes(smallClient1)), true, "MRU smallClient1 retained");
  assert.equal(remainingKeys.some((k) => k.includes(smallClient2)), true, "smallClient2 retained");
  assert.equal(remainingKeys.some((k) => k.includes(largeClient2)), true, "new largeClient2 cached");

  // Conditional 304 works both for cached largeClient2 and byte-evicted largeClient1
  const l2NotMod = await decodeFeed(
    mixedWorker,
    largeClient2,
    { "If-None-Match": l2.response.headers.get("ETag") },
    "platform=android&channel=stable&versionCode=1&locale=zh-CN",
  );
  assert.equal(l2NotMod.response.status, 304);
  const l1NotMod = await decodeFeed(
    mixedWorker,
    largeClient1,
    { "If-None-Match": l1.response.headers.get("ETag") },
    "platform=android&channel=stable&versionCode=1&locale=zh-CN",
  );
  assert.equal(l1NotMod.response.status, 304);

  // Re-fetching evicted largeClient1 without If-None-Match re-signs a valid 200 envelope and re-caches within byte budget
  const l1ReSigned = await decodeFeed(mixedWorker, largeClient1, {}, "platform=android&channel=stable&versionCode=1&locale=zh-CN");
  assert.equal(l1ReSigned.response.status, 200);
  assert.equal(
    verify(null, Buffer.from(SIGN_PREFIX + l1ReSigned.body.payloadBase64), keys.publicKey, Buffer.from(l1ReSigned.body.signatureBase64, "base64")),
    true,
  );
  assert.ok(mixedWorker.store.signedSnapshots.byteLength <= budgetBytes);

  // Advance clock past 24h expiry: expired entries are reclaimed and byteLength resets cleanly
  mixedNowMs = Date.parse("2026-08-29T13:00:00.000Z");
  assert.ok(mixedWorker.store.signedSnapshots.evictExpired(mixedNowMs) >= 1);
  assert.equal(mixedWorker.store.signedSnapshots.byteLength, 0);

  // Oversized signed envelope (> maxBytes, <= 1 MiB) still returns valid 200 signed feed without flushing small cached entries
  const tightWorker = createWorker({
    store: mixedWorker.store,
    keys,
    adminToken: ADMIN,
    clock: () => new Date(mixedNowMs),
    snapshotCacheLimit: 200,
    snapshotCacheMaxBytes: 4 * 1024,
  });
  const warmSmall = await decodeFeed(tightWorker, smallClient1);
  assert.equal(warmSmall.response.status, 200);
  assert.equal(tightWorker.store.signedSnapshots.size, 1);
  const uncachedLarge = await decodeFeed(tightWorker, largeClient1, {}, "platform=android&channel=stable&versionCode=1&locale=zh-CN");
  assert.equal(uncachedLarge.response.status, 200);
  assert.equal(
    verify(null, Buffer.from(SIGN_PREFIX + uncachedLarge.body.payloadBase64), keys.publicKey, Buffer.from(uncachedLarge.body.signatureBase64, "base64")),
    true,
  );
  assert.equal(tightWorker.store.signedSnapshots.size, 1, "oversized envelope must not flush warm small entry");
  assert.ok(tightWorker.store.signedSnapshots.byteLength <= 4 * 1024);

  console.log("P2-7 announcement snapshot LRU/TTL/byte-budget cache ok");
}

console.log("announcement worker protocol tests ok");

{
  function fakeD1() {
    return {
      prepare() {
        const statement = {
          bind() {
            return statement;
          },
          async first() {
            return { id: 1, sequence: 0, content_version: 0, key_id: "", updated_at: "2026-08-28T12:00:00.000Z" };
          },
          async all() {
            return { results: [] };
          },
          async run() {
            return { success: true, meta: { changes: 0 } };
          },
        };
        return statement;
      },
      async batch() {
        return [];
      },
    };
  }

  async function entryStatus(marEnv) {
    const response = await workerEntry.fetch(
      new Request(`${BASE}/admin/v1/announcements`, { headers: { "X-Admin-Token": ADMIN } }),
      { ANNOUNCEMENTS_DB: fakeD1(), MAR_ENV: marEnv, MAR_ADMIN_TOKEN: ADMIN },
    );
    return response.status;
  }

  assert.equal(await entryStatus("local"), 200);
  assert.equal(await entryStatus(" LoCaL "), 200);
  assert.equal(await entryStatus(" production "), 503);
  assert.equal(await entryStatus("prod"), 503);
  assert.equal(await entryStatus("staging"), 503);
  console.log("worker environment allowlist rejects typo values and token fallback ok");
}
