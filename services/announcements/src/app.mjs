// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import { audienceHash, sha256Hex, signPayload } from "./sign.mjs";
import { HttpError, MemoryStore } from "./store.mjs";
import { CHANNELS, PLATFORMS, isInstallId, pickTranslation, targetingMatches } from "./targeting.mjs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_PAYLOAD_BYTES = 1024 * 1024;
const MAX_ITEMS = 100;
const MAX_BODY = 32 * 1024;
const MAX_ACTIONS = 4;
const MAX_EVENTS = 50;
const MAX_EVENT_BYTES = 64 * 1024;

export function createWorker(options) {
  const store = options.store || new MemoryStore();
  const keys = options.keys;
  const adminToken = options.adminToken || "";
  const clock = options.clock || (() => new Date());
  store.feedState.keyId = keys.keyId;

  return {
    store,
    keys,
    fetch(request) {
      return handle(request, { store, keys, adminToken, clock });
    },
  };
}

async function handle(request, ctx) {
  const url = new URL(request.url);
  try {
    if (request.method === "OPTIONS") {
      return cors(new Response(null, { status: 204 }), request);
    }
    if (url.pathname === "/api/v1/announcements" && request.method === "GET") {
      return cors(await publicFeed(request, url, ctx), request);
    }
    if (url.pathname === "/api/v1/events" && request.method === "POST") {
      return cors(await publicEvents(request, ctx), request);
    }
    if (url.pathname.startsWith("/admin/v1/")) {
      return cors(await admin(request, url, ctx), request);
    }
    return cors(new Response("not found", { status: 404 }), request);
  } catch (error) {
    if (error instanceof HttpError) {
      return cors(Response.json({ error: error.message, ...error.extra }, { status: error.status }), request);
    }
    return cors(Response.json({ error: "internal error" }, { status: 500 }), request);
  }
}

function cors(response, request) {
  const origin = request.headers.get("Origin") || "";
  const allow = /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(origin) ? origin : "";
  if (allow) {
    response.headers.set("Access-Control-Allow-Origin", allow);
    response.headers.set("Vary", "Origin");
    response.headers.set("Access-Control-Allow-Headers", "Content-Type, X-Admin-Token, X-Request-Id, X-Install-ID, If-None-Match, X-Stats-Consent");
    response.headers.set("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS");
  }
  return response;
}

async function publicFeed(request, url, ctx) {
  const now = ctx.clock();
  const client = parseClient(request, url);
  const snapshot = buildSnapshot(ctx.store, client, now);
  const payloadObject = {
    feedVersion: ctx.store.feedState.contentVersion,
    issuedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString(),
    requestTarget: {
      platform: client.platform,
      channel: client.channel,
      versionCode: client.versionCode,
      locale: client.locale,
    },
    audienceHash: audienceHash(client.installId),
    complete: true,
    items: snapshot.items,
    withdrawn: snapshot.withdrawn,
  };
  const payloadText = JSON.stringify(payloadObject);
  if (Buffer.byteLength(payloadText, "utf8") > MAX_PAYLOAD_BYTES || payloadObject.items.length > MAX_ITEMS) {
    throw new HttpError(413, "feed exceeds size limits");
  }
  const payloadBase64 = Buffer.from(payloadText, "utf8").toString("base64");
  const etag = `"${sha256Hex(payloadText).slice(0, 32)}"`;
  const inm = request.headers.get("If-None-Match");
  const remainingMs = Date.parse(payloadObject.expiresAt) - now.getTime();
  if (inm && inm === etag && remainingMs > 60 * 60 * 1000) {
    return new Response(null, {
      status: 304,
      headers: {
        ETag: etag,
        "Cache-Control": "private, no-store",
      },
    });
  }
  const envelope = {
    schemaVersion: 1,
    keyId: ctx.keys.keyId,
    payloadBase64,
    signatureBase64: signPayload(ctx.keys.privateKey, payloadBase64),
  };
  return Response.json(envelope, {
    headers: {
      ETag: etag,
      "Cache-Control": "private, no-store",
    },
  });
}

function buildSnapshot(store, client, now) {
  const { withdrawn, candidates } = store.publicRows(now);
  const items = [];
  for (const row of candidates) {
    if (!targetingMatches(row.body.target, client, row.announcement.id)) continue;
    const translation = pickTranslation(row.translations, client.locale);
    if (!translation) continue;
    if (String(translation.bodyMarkdown).length > MAX_BODY) {
      throw new HttpError(413, "announcement body exceeds 32 KiB");
    }
    const actions = (row.body.actions || []).slice(0, MAX_ACTIONS);
    items.push({
      id: row.announcement.id,
      revision: row.rev.revision,
      category: row.body.category,
      severity: row.body.severity,
      displayMode: row.body.displayMode,
      title: translation.title,
      summary: translation.summary,
      bodyMarkdown: translation.bodyMarkdown,
      mustAcknowledge: row.body.mustAcknowledge,
      dismissible: row.body.dismissible,
      pinned: row.body.pinned,
      target: row.body.target,
      actions,
      image: row.body.image,
      startsAt: row.body.startsAt,
      endsAt: row.body.endsAt,
      publishedAt: row.body.publishedAt,
      locale: translation.locale,
    });
  }
  items.sort(compareItems);
  return { items, withdrawn };
}

function compareItems(left, right) {
  const severityRank = { CRITICAL: 0, WARNING: 1, NOTICE: 2, INFO: 3 };
  const bySeverity = (severityRank[left.severity] ?? 9) - (severityRank[right.severity] ?? 9);
  if (bySeverity !== 0) return bySeverity;
  if (left.pinned !== right.pinned) return left.pinned ? -1 : 1;
  const byPublished = String(right.publishedAt || "").localeCompare(String(left.publishedAt || ""));
  if (byPublished !== 0) return byPublished;
  return left.id.localeCompare(right.id);
}

function parseClient(request, url) {
  const platform = url.searchParams.get("platform") || "";
  const channel = url.searchParams.get("channel") || "";
  const versionCode = Number(url.searchParams.get("versionCode"));
  const locale = url.searchParams.get("locale") || "";
  const installId = request.headers.get("X-Install-ID") || "";
  if (!PLATFORMS.has(platform) || platform === "all") throw new HttpError(400, "invalid platform");
  if (!CHANNELS.has(channel) || channel === "all") throw new HttpError(400, "invalid channel");
  if (!Number.isInteger(versionCode) || versionCode < 0) throw new HttpError(400, "invalid versionCode");
  if (!locale || locale.length > 32) throw new HttpError(400, "invalid locale");
  if (!isInstallId(installId)) throw new HttpError(400, "invalid X-Install-ID");
  return { platform, channel, versionCode, locale, installId };
}

async function publicEvents(request, ctx) {
  if (request.headers.get("X-Stats-Consent") !== "1") {
    return new Response(null, { status: 204 });
  }
  const raw = await request.text();
  if (Buffer.byteLength(raw, "utf8") > MAX_EVENT_BYTES) throw new HttpError(413, "event batch too large");
  const body = JSON.parse(raw || "{}");
  const events = body.events || [];
  if (!Array.isArray(events) || events.length > MAX_EVENTS) throw new HttpError(400, "invalid event batch");
  const hashed = events.map((event) => {
    if (!isInstallId(event.installId)) throw new HttpError(400, "invalid installId");
    const { installId, ...rest } = event;
    return { ...rest, installIdHash: audienceHash(installId) };
  });
  ctx.store.recordEvents(hashed, ctx.clock());
  return new Response(null, { status: 204 });
}

async function admin(request, url, ctx) {
  if (!ctx.adminToken) throw new HttpError(503, "admin token is not configured");
  const provided = request.headers.get("X-Admin-Token") || "";
  if (!timingSafeEqual(provided, ctx.adminToken)) throw new HttpError(401, "admin authentication failed");
  const origin = request.headers.get("Origin");
  if (origin && !/^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(origin)) {
    throw new HttpError(403, "CSRF origin rejected");
  }
  const requestId = request.headers.get("X-Request-Id") || "";
  const actor = "local-test-admin";
  const now = ctx.clock();
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts[0] !== "admin" || parts[1] !== "v1") throw new HttpError(404, "not found");

  if (url.pathname === "/admin/v1/announcements" && request.method === "GET") {
    return Response.json({ items: ctx.store.listAdmin() });
  }
  if (url.pathname === "/admin/v1/announcements" && request.method === "POST") {
    requireRequestId(requestId);
    const input = await request.json();
    return Response.json(ctx.store.createAnnouncement(input, actor, requestId, now), { status: 201 });
  }
  if (url.pathname === "/admin/v1/audit" && request.method === "GET") {
    return Response.json({ items: ctx.store.audit });
  }
  if (url.pathname === "/admin/v1/stats" && request.method === "GET") {
    return Response.json(ctx.store.stats());
  }
  if (parts[2] === "announcements" && parts[3]) {
    const id = parts[3];
    const action = parts[4];
    if (!action && request.method === "GET") return Response.json(ctx.store.getAdmin(id));
    if (!action && request.method === "PATCH") {
      requireRequestId(requestId);
      const input = await request.json();
      return Response.json(ctx.store.patchDraft(id, input, actor, requestId, now, input.expectedRevision));
    }
    requireRequestId(requestId);
    if (action === "revisions" && request.method === "POST") {
      return Response.json(ctx.store.addRevision(id, await request.json(), actor, requestId, now), { status: 201 });
    }
    if (action === "schedule" && request.method === "POST") {
      const input = await request.json();
      return Response.json(ctx.store.schedule(id, input.startsAt, actor, requestId, now, input.expectedRevision));
    }
    if (action === "publish" && request.method === "POST") {
      const input = await request.json().catch(() => ({}));
      return Response.json(ctx.store.publish(id, actor, requestId, now, input.expectedRevision));
    }
    if (action === "withdraw" && request.method === "POST") {
      return Response.json(ctx.store.withdraw(id, actor, requestId, now));
    }
    if (action === "archive" && request.method === "POST") {
      return Response.json(ctx.store.archive(id, actor, requestId, now));
    }
  }
  throw new HttpError(404, "not found");
}

function requireRequestId(requestId) {
  if (!requestId || !UUID.test(requestId)) throw new HttpError(400, "X-Request-Id must be a UUID");
}

function timingSafeEqual(left, right) {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  if (a.length !== b.length) return false;
  let out = 0;
  for (let i = 0; i < a.length; i += 1) out |= a[i] ^ b[i];
  return out === 0;
}
