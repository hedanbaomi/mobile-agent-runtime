// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import assert from "node:assert/strict";

const base = process.argv[2] || "http://127.0.0.1:8788";
const adminToken = process.argv[3] || "test-admin-token";
const installId = "00000000-0000-4000-8000-000000000099";
const id = `local-smoke-${Date.now()}`;
const requestId = () => crypto.randomUUID();
const adminHeaders = () => ({
  "content-type": "application/json",
  "X-Admin-Token": adminToken,
  "X-Request-Id": requestId(),
  Origin: base,
});

async function call(path, init = {}) {
  const response = await fetch(`${base}${path}`, init);
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { /* plain response */ }
  return { response, body, text };
}

const draft = {
  id,
  category: "GENERAL",
  severity: "INFO",
  displayMode: "BANNER",
  mustAcknowledge: false,
  dismissible: true,
  pinned: false,
  target: { platform: "android", channel: "stable", rolloutPercent: 100, rolloutSalt: "local-smoke", locales: [] },
  actions: [{ type: "DISMISS", key: "dismiss", label: "Dismiss" }],
  translations: { default: { title: "Local D1 smoke", summary: "D1", bodyMarkdown: "Local D1 smoke body." } },
};

let result = await call("/admin/v1/announcements", { method: "POST", headers: adminHeaders(), body: JSON.stringify(draft) });
assert.equal(result.response.status, 201, result.text);
result = await call(`/admin/v1/announcements/${id}/schedule`, {
  method: "POST", headers: adminHeaders(), body: JSON.stringify({ startsAt: "2020-01-01T00:00:00.000Z", expectedRevision: 1 }),
});
assert.equal(result.response.status, 200, result.text);
result = await call(`/api/v1/announcements?platform=android&channel=stable&versionCode=1&locale=en`, { headers: { "X-Install-ID": installId } });
assert.equal(result.response.status, 200, result.text);
const firstFeed = JSON.parse(Buffer.from(result.body.payloadBase64, "base64"));
assert.equal(firstFeed.items.some((item) => item.id === id), true);
result = await call(`/api/v1/announcements?platform=android&channel=stable&versionCode=1&locale=en`, { headers: { "X-Install-ID": installId, "If-None-Match": result.response.headers.get("ETag") } });
assert.equal(result.response.status, 304, result.text);

const event = {
  eventId: requestId(), type: "announcement_opened", installId: installId, platform: "android", channel: "stable", versionCode: 1, locale: "en", announcementId: id, revision: 1,
};
result = await call("/api/v1/events", { method: "POST", headers: { "content-type": "application/json", "X-Stats-Consent": "1" }, body: JSON.stringify({ events: [event, event] }) });
assert.equal(result.response.status, 204, result.text);
result = await call("/admin/v1/stats", { headers: adminHeaders() });
assert.equal(result.response.status, 200, result.text);
assert.equal(result.body.consentedInstalls >= 1, true);
console.log(JSON.stringify({ ok: true, base, announcementId: id, feedVersion: firstFeed.feedVersion, dedupBatch: true }));

