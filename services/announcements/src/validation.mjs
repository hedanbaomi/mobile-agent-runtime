// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { HttpError } from "./errors.mjs";

export const PENDING = new Set(["draft", "scheduled"]);
export const CATEGORIES = new Set(["GENERAL", "FEATURE", "MAINTENANCE", "SERVICE_INCIDENT", "UPDATE", "SECURITY", "DEPRECATION"]);
export const SEVERITIES = new Set(["INFO", "NOTICE", "WARNING", "CRITICAL"]);
export const DISPLAY_MODES = new Set(["CENTER_ONLY", "BANNER", "MODAL"]);
export const ALLOWED_ACTIONS = new Set(["OPEN_HTTPS_URL", "OPEN_APP_ROUTE", "DISMISS", "ACKNOWLEDGE"]);
export const ALLOWED_ROUTES = new Set([
  "app://settings/providers",
  "app://settings/knowledge",
  "app://announcements",
  "app://about",
  "app://update",
]);
export const EVENT_TYPES = new Set([
  "install_seen",
  "app_active",
  "announcement_fetched",
  "announcement_displayed",
  "announcement_opened",
  "announcement_acknowledged",
  "action_clicked",
]);
export const EVENT_KEYS = new Set([
  "eventId",
  "type",
  "installId",
  "platform",
  "channel",
  "versionCode",
  "locale",
  "announcementId",
  "revision",
  "actionId",
  "occurredAt",
]);
export const TOKEN = /^[A-Za-z0-9._-]+$/;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const SIX_HOURS_MS = 6 * 60 * 60 * 1000;

export function isToken(value) {
  return typeof value === "string" && value.length > 0 && value.length <= 128 && TOKEN.test(value);
}

export function isUuid(value) {
  return typeof value === "string" && UUID.test(value);
}

export function assertInstant(value, field) {
  if (typeof value !== "string" || !value || !Number.isFinite(Date.parse(value))) {
    throw new HttpError(400, `invalid ${field}`);
  }
}

export function normalizeAnnouncementBody(input = {}, options = {}) {
  const target = input.target && typeof input.target === "object" && !Array.isArray(input.target) ? input.target : {};
  const body = {
    category: input.category || "GENERAL",
    severity: input.severity || "INFO",
    displayMode: input.displayMode || "CENTER_ONLY",
    mustAcknowledge: Boolean(input.mustAcknowledge),
    dismissible: input.dismissible !== false,
    pinned: Boolean(input.pinned),
    target: {
      platform: target.platform || "all",
      channel: target.channel || "all",
      minVersionCode: target.minVersionCode ?? null,
      maxVersionCode: target.maxVersionCode ?? null,
      locales: Array.isArray(target.locales) ? target.locales : [],
      rolloutPercent: target.rolloutPercent ?? 100,
      rolloutSalt: target.rolloutSalt || "default",
    },
    actions: Array.isArray(input.actions) ? input.actions : [],
    image: input.image || null,
    startsAt: input.startsAt || null,
    endsAt: input.endsAt || null,
    publishedAt: input.publishedAt || null,
  };
  if (options.strict) assertAnnouncementBody(body);
  return body;
}

export function assertAnnouncementBody(body) {
  if (!body || typeof body !== "object") throw new HttpError(400, "announcement body must be an object");
  if (!CATEGORIES.has(body.category)) throw new HttpError(400, "invalid category");
  if (!SEVERITIES.has(body.severity)) throw new HttpError(400, "invalid severity");
  if (!DISPLAY_MODES.has(body.displayMode)) throw new HttpError(400, "invalid displayMode");
  if (body.startsAt) assertInstant(body.startsAt, "startsAt");
  if (body.endsAt) assertInstant(body.endsAt, "endsAt");
  if (body.publishedAt) assertInstant(body.publishedAt, "publishedAt");
  if (body.startsAt && body.endsAt && Date.parse(body.endsAt) <= Date.parse(body.startsAt)) {
    throw new HttpError(400, "endsAt must be after startsAt");
  }
  assertTarget(body.target);
  if (!Array.isArray(body.actions) || body.actions.length > 4) throw new HttpError(400, "invalid actions");
}

export function assertTarget(target = {}) {
  if (!isToken(target.platform) || !["android", "desktop", "ios", "all"].includes(target.platform)) {
    throw new HttpError(400, "invalid target platform");
  }
  if (!isToken(target.channel) || !["stable", "beta", "nightly", "all"].includes(target.channel)) {
    throw new HttpError(400, "invalid target channel");
  }
  if (target.minVersionCode != null && (!Number.isInteger(target.minVersionCode) || target.minVersionCode < 0)) {
    throw new HttpError(400, "invalid minVersionCode");
  }
  if (target.maxVersionCode != null && (!Number.isInteger(target.maxVersionCode) || target.maxVersionCode < 0)) {
    throw new HttpError(400, "invalid maxVersionCode");
  }
  if (target.minVersionCode != null && target.maxVersionCode != null && target.maxVersionCode < target.minVersionCode) {
    throw new HttpError(400, "maxVersionCode must be at least minVersionCode");
  }
  if (!Array.isArray(target.locales) || target.locales.length > 32 || target.locales.some((locale) => typeof locale !== "string" || locale.length < 1 || locale.length > 32)) {
    throw new HttpError(400, "invalid target locales");
  }
  if (!Number.isInteger(target.rolloutPercent) || target.rolloutPercent < 0 || target.rolloutPercent > 100) {
    throw new HttpError(400, "invalid rolloutPercent");
  }
  if (!isToken(target.rolloutSalt)) throw new HttpError(400, "invalid rolloutSalt");
}

export function assertAction(action = {}) {
  if (!action || typeof action !== "object" || !ALLOWED_ACTIONS.has(action.type)) {
    throw new HttpError(400, "action type not allowed");
  }
  for (const key of Object.keys(action)) {
    if (!["type", "key", "label", "url"].includes(key)) throw new HttpError(400, `unknown action field ${key}`);
  }
  if (typeof action.key !== "string" || !isToken(action.key) || action.key.length > 64) {
    throw new HttpError(400, "action key is invalid");
  }
  if (typeof action.label !== "string" || action.label.length < 1 || action.label.length > 256) {
    throw new HttpError(400, "action label is invalid");
  }
  if (action.type === "OPEN_HTTPS_URL") assertHttpsUrl(action.url, "action url");
  if (action.type === "OPEN_APP_ROUTE" && !ALLOWED_ROUTES.has(action.url)) {
    throw new HttpError(400, "OPEN_APP_ROUTE is not in the allowlist");
  }
}

export function assertHttpsUrl(value, field) {
  if (typeof value !== "string" || value.length > 2048 || /[\u0000-\u001f\u007f]/.test(value)) {
    throw new HttpError(400, `${field} must be a valid https URL`);
  }
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new HttpError(400, `${field} must be a valid https URL`);
  }
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
    throw new HttpError(400, `${field} must be https`);
  }
}

export function assertTranslationsValue(translations) {
  if (!translations || typeof translations !== "object" || Array.isArray(translations) || !translations.default) {
    throw new HttpError(400, "default translation is required");
  }
  const locales = Object.keys(translations);
  if (locales.length > 32) throw new HttpError(400, "too many translations");
  for (const [locale, value] of Object.entries(translations)) {
    if (!isToken(locale) || locale.length > 32 || !value || typeof value !== "object" ||
        typeof value.title !== "string" || value.title.length < 1 || value.title.length > 512 ||
        typeof value.summary !== "string" || value.summary.length < 1 || value.summary.length > 4096 ||
        typeof value.bodyMarkdown !== "string" || value.bodyMarkdown.length > 32 * 1024) {
      throw new HttpError(400, `translation ${locale} is incomplete`);
    }
  }
}

export function assertMarkdown(markdown) {
  if (/<[a-zA-Z/!]/.test(markdown) || /javascript:|intent:|file:/i.test(markdown)) {
    throw new HttpError(400, "markdown must not contain HTML, scripts, or blocked schemes");
  }
}

export function assertPublishData(body, translations) {
  assertAnnouncementBody(body);
  assertTranslationsValue(translations);
  for (const action of body.actions) assertAction(action);
  if (body.image != null) assertHttpsUrl(body.image, "image");
  for (const translation of Object.values(translations)) assertMarkdown(translation.bodyMarkdown);
  if (body.mustAcknowledge) {
    if (!["WARNING", "CRITICAL"].includes(body.severity) || body.displayMode !== "MODAL") {
      throw new HttpError(400, "mustAcknowledge requires WARNING/CRITICAL and MODAL");
    }
    if (!body.actions.some((action) => action.type === "ACKNOWLEDGE")) {
      throw new HttpError(400, "mustAcknowledge requires an ACKNOWLEDGE action");
    }
  }
}

export function assertEventValue(event) {
  for (const key of Object.keys(event || {})) {
    if (key === "installIdHash") continue;
    if (!EVENT_KEYS.has(key)) throw new HttpError(400, `unknown event field ${key}`);
  }
  if (!event || !isUuid(event.eventId)) throw new HttpError(400, "eventId must be a UUID");
  if (!EVENT_TYPES.has(event.type)) throw new HttpError(400, "event type not allowed");
  if (!isInstallIdValue(event.installId) && !event.installIdHash) throw new HttpError(400, "invalid installId");
  if (event.platform != null && !["android", "desktop", "ios", "all"].includes(event.platform)) throw new HttpError(400, "invalid platform");
  if (event.channel != null && !["stable", "beta", "nightly", "all"].includes(event.channel)) throw new HttpError(400, "invalid channel");
  if (event.versionCode != null && (!Number.isInteger(event.versionCode) || event.versionCode < 0)) throw new HttpError(400, "invalid versionCode");
  if (event.revision != null && (!Number.isInteger(event.revision) || event.revision < 1)) throw new HttpError(400, "invalid revision");
  if (event.occurredAt != null) assertInstant(event.occurredAt, "occurredAt");
  if (event.actionId != null && (typeof event.actionId !== "string" || event.actionId.length > 64 || !isToken(event.actionId))) {
    throw new HttpError(400, "invalid actionId");
  }
  if (event.type === "action_clicked" && !event.actionId) throw new HttpError(400, "action_clicked requires actionId");
  if (event.announcementId != null && (typeof event.announcementId !== "string" || event.announcementId.length > 128 || !isToken(event.announcementId))) {
    throw new HttpError(400, "invalid announcementId");
  }
  if (event.locale != null && (typeof event.locale !== "string" || event.locale.length > 32 || !isToken(event.locale))) {
    throw new HttpError(400, "invalid event locale");
  }
  if (/chat|prompt|api[_-]?key|authorization|skill|knowledge/i.test(JSON.stringify(event))) {
    throw new HttpError(400, "event contains forbidden content");
  }
}

function isInstallIdValue(value) {
  return typeof value === "string" && UUID.test(value);
}
