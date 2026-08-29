// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { audienceHash, sha256Hex, signPayload } from "./sign.mjs";
import { HttpError } from "./errors.mjs";
import { MemoryStore } from "./store.mjs";
import { CHANNELS, PLATFORMS, isInstallId, pickTranslation, targetingMatches } from "./targeting.mjs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_PAYLOAD_BYTES = 1024 * 1024;
const MAX_ITEMS = 100;
const MAX_BODY = 32 * 1024;
const MAX_ACTIONS = 4;
const MAX_EVENTS = 50;
const MAX_EVENT_BYTES = 64 * 1024;
const MAX_ADMIN_BYTES = 1024 * 1024;
const LOCAL_ORIGIN = /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/;
const ADMIN_BLOCKED_HTML = `<!doctype html><meta charset="utf-8"><title>Announcements admin unavailable</title><meta name="viewport" content="width=device-width,initial-scale=1"><main><h1>Announcements administration is unavailable</h1><p>Production administration is protected by Cloudflare Access. Ask an operator to configure the Access application and audience before using this page.</p></main>`;

export function createWorker(options = {}) {
  const store = options.store || new MemoryStore();
  const keys = options.keys;
  if (!keys?.keyId || !keys.privateKey) throw new Error("announcement signing keys are required");
  const adminToken = options.adminToken || "";
  const clock = options.clock || (() => new Date());
  const environment = options.environment || (adminToken ? "local" : "production");
  const allowLocalAdmin = environment === "local" && options.allowLocalAdmin !== false;
  const accessAuthenticator = options.accessAuthenticator || null;
  const adminOrigins = new Set(options.adminOrigins || (environment === "local" ? [] : []));
  if (store.feedState) store.feedState.keyId = keys.keyId;
  if (!store.signedSnapshots) store.signedSnapshots = new Map();

  const context = {
    store,
    keys,
    adminToken,
    clock,
    environment,
    allowLocalAdmin,
    accessAuthenticator,
    adminOrigins,
    publicOrigins: new Set(options.publicOrigins || []),
    assets: options.assets || null,
  };

  return {
    store,
    keys,
    async fetch(request) {
      return handle(request, context);
    },
    async scheduled(event = {}) {
      const now = clock();
      await store.promoteDue(now);
      if (typeof store.cleanup === "function") await store.cleanup(now);
      return { scheduledTime: event.scheduledTime || now.getTime() };
    },
  };
}

export function createUnavailableWorker(message = "announcement service unavailable") {
  const response = () => new Response(JSON.stringify({ error: message }), {
    status: 503,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
  return {
    store: null,
    keys: null,
    fetch: async () => response(),
    scheduled: async () => { throw new Error(message); },
  };
}

async function handle(request, ctx) {
  const url = new URL(request.url);
  try {
    if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }), request, ctx);
    if ((url.pathname === "/admin/announcements" || url.pathname === "/admin/announcements/") && request.method === "GET") {
      return cors(await serveAdmin(request, ctx), request, ctx);
    }
    if (url.pathname === "/source" || url.pathname === "/source/" || url.pathname.startsWith("/source/")) {
      return cors(await serveSource(request, ctx), request, ctx);
    }
    if (url.pathname === "/api/v1/announcements" && request.method === "GET") {
      return cors(await publicFeed(request, url, ctx), request, ctx);
    }
    if (url.pathname === "/api/v1/events" && request.method === "POST") {
      return cors(await publicEvents(request, ctx), request, ctx);
    }
    if (url.pathname.startsWith("/admin/v1/")) {
      return cors(await admin(request, url, ctx), request, ctx);
    }
    return cors(new Response("not found", { status: 404 }), request, ctx);
  } catch (error) {
    if (error instanceof HttpError) {
      return cors(Response.json({ error: error.message, ...error.extra }, { status: error.status }), request, ctx);
    }
    // Production failures are deliberately indistinguishable. In particular,
    // missing migrations, a bad key secret, or D1 outages must not produce a
    // signed empty feed or expose database details.
    const status = ctx.environment === "production" ? 503 : 500;
    return cors(Response.json({ error: status === 503 ? "announcement service unavailable" : "internal error" }, { status }), request, ctx);
  }
}

async function serveAdmin(request, ctx) {
  if (!ctx.assets || typeof ctx.assets.fetch !== "function") {
    return secureHeaders(new Response(ADMIN_BLOCKED_HTML, { status: 503, headers: { "content-type": "text/html; charset=utf-8" } }));
  }
  const assetUrl = new URL(request.url);
  assetUrl.pathname = "/index.html";
  const assetResponse = await ctx.assets.fetch(new Request(assetUrl, { method: "GET" }));
  const response = new Response(assetResponse.body, {
    status: assetResponse.status,
    statusText: assetResponse.statusText,
    headers: assetResponse.headers,
  });
  return secureHeaders(response);
}

async function serveSource(request, ctx) {
  if (!["GET", "HEAD"].includes(request.method)) return new Response("method not allowed", { status: 405 });
  if (!ctx.assets || typeof ctx.assets.fetch !== "function") {
    return secureHeaders(new Response("source artifact unavailable", { status: 503, headers: { "content-type": "text/plain; charset=utf-8" } }));
  }
  const sourceUrl = new URL(request.url);
  if (sourceUrl.pathname === "/source" || sourceUrl.pathname === "/source/") sourceUrl.pathname = "/source/index.html";
  const assetResponse = await ctx.assets.fetch(new Request(sourceUrl, { method: "GET" }));
  const response = new Response(assetResponse.body, {
    status: assetResponse.status,
    statusText: assetResponse.statusText,
    headers: assetResponse.headers,
  });
  if (response.ok) response.headers.set("Cache-Control", "public, max-age=300");
  return secureHeaders(response);
}

function cors(response, request, ctx) {
  const origin = request.headers.get("Origin") || "";
  const allow = ctx.publicOrigins.has(origin) || (ctx.environment === "local" && LOCAL_ORIGIN.test(origin)) ? origin : "";
  if (allow) {
    response.headers.set("Access-Control-Allow-Origin", allow);
    response.headers.set("Vary", "Origin");
    response.headers.set("Access-Control-Allow-Headers", ctx.environment === "local"
      ? "Content-Type, X-Admin-Token, X-Request-Id, X-Install-ID, If-None-Match, X-Stats-Consent, Cf-Access-Jwt-Assertion"
      : "Content-Type, X-Request-Id, X-Install-ID, If-None-Match, X-Stats-Consent, Cf-Access-Jwt-Assertion");
    response.headers.set("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS");
    if (ctx.environment === "production") response.headers.set("Access-Control-Allow-Credentials", "true");
  }
  return secureHeaders(response);
}

function secureHeaders(response) {
  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("X-Frame-Options", "DENY");
  response.headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  response.headers.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src https: data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
  return response;
}

async function publicFeed(request, url, ctx) {
  const now = ctx.clock();
  await ctx.store.promoteDue(now);
  const feedState = await getFeedState(ctx.store);
  const client = parseClient(request, url);
  const snapshot = await buildSnapshot(ctx.store, client, now);
  const contentObject = {
    feedVersion: feedState.contentVersion,
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
  const contentHash = sha256Hex(JSON.stringify(contentObject));
  const cacheKey = [client.platform, client.channel, String(client.versionCode), client.locale, client.installId, String(contentObject.feedVersion), contentHash].join("|");
  const cached = ctx.store.signedSnapshots.get(cacheKey);
  const inm = request.headers.get("If-None-Match");
  if (cached && Date.parse(cached.expiresAt) - now.getTime() > 60 * 60 * 1000) {
    if (inm && inm === cached.etag) {
      return new Response(null, { status: 304, headers: { ETag: cached.etag, "Cache-Control": "private, no-store" } });
    }
    return Response.json(cached.envelope, { headers: { ETag: cached.etag, "Cache-Control": "private, no-store" } });
  }
  const payloadObject = {
    ...contentObject,
    issuedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString(),
  };
  const payloadText = JSON.stringify(payloadObject);
  if (Buffer.byteLength(payloadText, "utf8") > MAX_PAYLOAD_BYTES || payloadObject.items.length > MAX_ITEMS) {
    throw new HttpError(413, "feed exceeds size limits");
  }
  const payloadBase64 = Buffer.from(payloadText, "utf8").toString("base64");
  const etag = `"${contentHash.slice(0, 32)}"`;
  const envelope = {
    schemaVersion: 1,
    keyId: ctx.keys.keyId,
    payloadBase64,
    signatureBase64: signPayload(ctx.keys.privateKey, payloadBase64),
  };
  ctx.store.signedSnapshots.set(cacheKey, { etag, envelope, expiresAt: payloadObject.expiresAt });
  return Response.json(envelope, { headers: { ETag: etag, "Cache-Control": "private, no-store" } });
}

async function getFeedState(store) {
  if (typeof store.getFeedState === "function") return store.getFeedState();
  return store.feedState;
}

async function buildSnapshot(store, client, now) {
  const { withdrawn, candidates } = await store.publicRows(now);
  const items = [];
  for (const row of candidates) {
    if (!targetingMatches(row.body.target, client, row.announcement.id)) continue;
    const translation = pickTranslation(row.translations, client.locale);
    if (!translation) continue;
    if (String(translation.bodyMarkdown).length > MAX_BODY) throw new HttpError(413, "announcement body exceeds 32 KiB");
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
      actions: (row.body.actions || []).slice(0, MAX_ACTIONS),
      image: row.body.image,
      startsAt: row.body.startsAt,
      endsAt: row.body.endsAt,
      publishedAt: row.body.publishedAt,
      locale: translation.locale,
    });
  }
  items.sort(compareItems);
  withdrawn.sort((left, right) => left.id.localeCompare(right.id) || left.revision - right.revision);
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
  if (request.headers.get("X-Stats-Consent") !== "1") return new Response(null, { status: 204 });
  const raw = await request.text();
  if (Buffer.byteLength(raw, "utf8") > MAX_EVENT_BYTES) throw new HttpError(413, "event batch too large");
  let body;
  try { body = JSON.parse(raw || "{}"); } catch { throw new HttpError(400, "invalid event batch"); }
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new HttpError(400, "invalid event batch");
  const events = body.events || [];
  if (!Array.isArray(events) || events.length > MAX_EVENTS) throw new HttpError(400, "invalid event batch");
  const hashed = events.map((event) => {
    if (!isInstallId(event?.installId)) throw new HttpError(400, "invalid installId");
    const { installId, ...rest } = event;
    return { ...rest, installIdHash: audienceHash(installId) };
  });
  await ctx.store.recordEvents(hashed, ctx.clock());
  return new Response(null, { status: 204 });
}

async function authenticateAdmin(request, ctx) {
  if (ctx.environment === "production") {
    if (!ctx.accessAuthenticator) throw new HttpError(503, "admin disabled until Cloudflare Access is configured");
    return ctx.accessAuthenticator.authenticate(request);
  }
  if (ctx.accessAuthenticator) return ctx.accessAuthenticator.authenticate(request);
  if (!ctx.allowLocalAdmin || !ctx.adminToken) throw new HttpError(503, "local admin is not configured");
  const provided = request.headers.get("X-Admin-Token") || "";
  if (!timingSafeEqual(provided, ctx.adminToken)) throw new HttpError(401, "admin authentication failed");
  return { actor: "local-test-admin", subject: null, claims: null };
}

function checkAdminOrigin(request, ctx, mutating) {
  const origin = request.headers.get("Origin") || "";
  if (ctx.environment === "local") {
    if (origin && !LOCAL_ORIGIN.test(origin)) throw new HttpError(403, "CSRF origin rejected");
    return;
  }
  if (origin && !ctx.adminOrigins.has(origin)) throw new HttpError(403, "CSRF origin rejected");
  if (mutating && !origin) throw new HttpError(403, "CSRF origin required");
}

async function admin(request, url, ctx) {
  const auth = await authenticateAdmin(request, ctx);
  const mutating = !["GET", "HEAD", "OPTIONS"].includes(request.method);
  checkAdminOrigin(request, ctx, mutating);
  const requestId = request.headers.get("X-Request-Id") || "";
  const actor = auth.actor;
  const now = ctx.clock();
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts[0] !== "admin" || parts[1] !== "v1") throw new HttpError(404, "not found");

  if (url.pathname === "/admin/v1/announcements" && request.method === "GET") {
    await ctx.store.promoteDue(now);
    return Response.json({ items: await ctx.store.listAdmin() });
  }
  if (url.pathname === "/admin/v1/announcements" && request.method === "POST") {
    requireRequestId(requestId);
    return Response.json(await ctx.store.createAnnouncement(requireObject(await readJson(request)), actor, requestId, now), { status: 201 });
  }
  if (url.pathname === "/admin/v1/audit" && request.method === "GET") {
    const items = typeof ctx.store.listAudit === "function" ? await ctx.store.listAudit() : ctx.store.audit;
    return Response.json({ items });
  }
  if (url.pathname === "/admin/v1/stats" && request.method === "GET") return Response.json(await ctx.store.stats(now));
  if (parts[2] === "announcements" && parts[3]) {
    const id = parts[3];
    const action = parts[4];
    if (!action && request.method === "GET") return Response.json(await ctx.store.getAdmin(id));
    if (!action && request.method === "PATCH") {
      requireRequestId(requestId);
      const input = requireObject(await readJson(request));
      return Response.json(await ctx.store.patchDraft(id, input, actor, requestId, now, input.expectedRevision));
    }
    requireRequestId(requestId);
    if (action === "revisions" && request.method === "POST") return Response.json(await ctx.store.addRevision(id, requireObject(await readJson(request)), actor, requestId, now), { status: 201 });
    if (action === "schedule" && request.method === "POST") {
      const input = requireObject(await readJson(request));
      return Response.json(await ctx.store.schedule(id, input.startsAt, actor, requestId, now, input.expectedRevision));
    }
    if (action === "publish" && request.method === "POST") {
      const input = requireObject(await readJson(request, {}));
      return Response.json(await ctx.store.publish(id, actor, requestId, now, input.expectedRevision));
    }
    if (action === "withdraw" && request.method === "POST") {
      const input = requireObject(await readJson(request, {}));
      return Response.json(await ctx.store.withdraw(id, actor, requestId, now, input.expectedRevision));
    }
    if (action === "archive" && request.method === "POST") {
      const input = requireObject(await readJson(request, {}));
      return Response.json(await ctx.store.archive(id, actor, requestId, now, input.expectedRevision));
    }
  }
  throw new HttpError(404, "not found");
}

async function readJson(request, fallback) {
  const text = await request.text();
  if (Buffer.byteLength(text, "utf8") > MAX_ADMIN_BYTES) throw new HttpError(413, "request body too large");
  if (!text && fallback !== undefined) return fallback;
  try {
    return JSON.parse(text || "{}");
  } catch {
    throw new HttpError(400, "invalid JSON body");
  }
}

function requireObject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HttpError(400, "request body must be an object");
  return value;
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
