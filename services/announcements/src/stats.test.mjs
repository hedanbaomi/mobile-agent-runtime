// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createWorker } from "./app.mjs";
import { D1Store } from "./d1-store.mjs";
import { audienceHash, generateKeyPair } from "./sign.mjs";
import { MemoryStore } from "./store.mjs";

const NOW = new Date("2026-08-29T12:00:00.000Z");
const INSTALLS = {
  day: "00000000-0000-4000-8000-000000000101",
  week: "00000000-0000-4000-8000-000000000102",
  month: "00000000-0000-4000-8000-000000000103",
  stale: "00000000-0000-4000-8000-000000000104",
};

function event(id, type, installId, receivedAt, dimensions) {
  return {
    eventId: `00000000-0000-4000-8000-${String(id).padStart(12, "0")}`,
    type,
    installId,
    installIdHash: audienceHash(installId),
    ...dimensions,
    occurredAt: receivedAt,
  };
}

function recordAt(store, installId, receivedAt, dimensions, suffix) {
  const now = new Date(receivedAt);
  store.recordEvents([
    event(`${suffix}1`, "install_seen", installId, receivedAt, dimensions),
    event(`${suffix}2`, "app_active", installId, receivedAt, dimensions),
  ], now);
}

{
  const store = new MemoryStore();
  recordAt(store, INSTALLS.day, "2026-08-29T11:00:00.000Z", {
    platform: "android", channel: "stable", versionCode: 101, locale: "en",
  }, "11");
  recordAt(store, INSTALLS.week, "2026-08-27T11:00:00.000Z", {
    platform: "android", channel: "beta", versionCode: 202, locale: "en",
  }, "12");
  recordAt(store, INSTALLS.month, "2026-08-19T11:00:00.000Z", {
    platform: "desktop", channel: "nightly", versionCode: 303, locale: "zh-CN",
  }, "13");
  recordAt(store, INSTALLS.stale, "2026-07-01T11:00:00.000Z", {
    platform: "ios", channel: "stable", versionCode: 404, locale: "en",
  }, "14");

  const stats = store.stats(NOW);
  assert.equal(stats.consentedInstalls, 4);
  assert.equal(stats.installSeen, 4);
  assert.equal(stats.appActive, 4);
  assert.equal(stats.dau, 1);
  assert.equal(stats.wau, 2);
  assert.equal(stats.mau, 3);
  assert.doesNotMatch(JSON.stringify(stats), /installIdHash|eventId|occurredAt|installId/);
  assert.deepEqual(stats.byVersion, [
    { versionCode: 101, count: 1 },
    { versionCode: 202, count: 1 },
    { versionCode: 303, count: 1 },
  ]);
  assert.deepEqual(stats.byChannel, [
    { channel: "beta", count: 1 },
    { channel: "nightly", count: 1 },
    { channel: "stable", count: 1 },
  ]);
  assert.deepEqual(stats.byPlatform, [
    { platform: "android", count: 2 },
    { platform: "desktop", count: 1 },
  ]);
  console.log("MemoryStore install_seen/app_active DAU/WAU/MAU distributions ok");
}

{
  const worker = createWorker({
    store: new MemoryStore(),
    keys: generateKeyPair(),
    adminToken: "stats-test-token",
    clock: () => NOW,
  });
  const events = [
    event("301", "install_seen", INSTALLS.day, NOW.toISOString(), { platform: "android", channel: "stable", versionCode: 501, locale: "en" }),
    event("302", "app_active", INSTALLS.day, NOW.toISOString(), { platform: "android", channel: "stable", versionCode: 501, locale: "en" }),
  ];
  const accepted = await worker.fetch(new Request("http://127.0.0.1:8787/api/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Stats-Consent": "1" },
    body: JSON.stringify({ events }),
  }));
  assert.equal(accepted.status, 204);
  const response = await worker.fetch(new Request("http://127.0.0.1:8787/admin/v1/stats", {
    headers: { "X-Admin-Token": "stats-test-token" },
  }));
  assert.equal(response.status, 200);
  const stats = await response.json();
  assert.equal(stats.installSeen, 1);
  assert.equal(stats.appActive, 1);
  assert.equal(stats.dau, 1);
  assert.deepEqual(stats.byVersion, [{ versionCode: 501, count: 1 }]);
  console.log("Worker stats endpoint exposes aggregate dimensions ok");
}

{
  const store = new MemoryStore();
  assert.throws(() => store.recordEvents([{
    eventId: "00000000-0000-4000-8000-000000000201",
    type: "app_active",
    installIdHash: "a".repeat(64),
    platform: "android",
    channel: "stable",
    versionCode: 1,
    locale: "prompt",
  }], NOW), /forbidden content/);
  assert.throws(() => store.recordEvents([{
    eventId: "00000000-0000-4000-8000-000000000202",
    type: "app_active",
    installIdHash: "b".repeat(64),
    platform: "android",
    channel: "stable",
    versionCode: 1,
    locale: "en",
    announcementId: "knowledge",
  }], NOW), /forbidden content/);
  console.log("stats sensitive content rejection remains enforced");
}

{
  const admin = readFileSync(new URL("../../../admin/announcements/index.html", import.meta.url), "utf8");
  assert.match(admin, /普通公告/);
  assert.match(admin, /重要公告/);
  assert.match(admin, /版本更新/);
  assert.match(admin, /<details[^>]*id="advancedFields"/);
  assert.match(admin, /OPEN_APP_ROUTE/);
  assert.match(admin, /app:\/\/update/);
  console.log("admin preset controls preserve full model and app update route ok");
}

{
  const responses = new Map([
    ["SELECT COUNT(*) AS count FROM install_state", { count: 4 }],
    ["SELECT COUNT(*) AS count FROM announcement_receipts", { count: 8 }],
    ["FROM announcement_receipts", { install_seen: 4, app_active: 4 }],
    ["COUNT(DISTINCT CASE", { dau: 1, wau: 2, mau: 3 }],
  ]);
  const grouped = new Map([
    ["GROUP BY version_code", [{ version_code: 101, count: 1 }, { version_code: 202, count: 1 }, { version_code: 303, count: 1 }]],
    ["GROUP BY channel", [{ channel: "beta", count: 1 }, { channel: "nightly", count: 1 }, { channel: "stable", count: 1 }]],
    ["GROUP BY platform", [{ platform: "android", count: 2 }, { platform: "desktop", count: 1 }]],
  ]);
  const db = {
    prepare(sql) {
      const statement = {
        bind() { return statement; },
        async first() {
          for (const [key, value] of responses) if (sql.includes(key)) return value;
          throw new Error(`unexpected D1 first SQL: ${sql}`);
        },
        async all() {
          for (const [key, value] of grouped) if (sql.includes(key)) return { results: value };
          throw new Error(`unexpected D1 all SQL: ${sql}`);
        },
      };
      return statement;
    },
    async batch() { return []; },
  };
  const stats = await new D1Store(db, { keyId: "local-dev-1" }).stats(NOW);
  assert.equal(stats.installSeen, 4);
  assert.equal(stats.appActive, 4);
  assert.equal(stats.dau, 1);
  assert.equal(stats.wau, 2);
  assert.equal(stats.mau, 3);
  assert.deepEqual(stats.byVersion[0], { versionCode: 101, count: 1 });
  assert.deepEqual(stats.byChannel[1], { channel: "nightly", count: 1 });
  assert.deepEqual(stats.byPlatform[0], { platform: "android", count: 2 });
  console.log("D1Store stats query exposes event types, active windows, and dimensions ok");
}
