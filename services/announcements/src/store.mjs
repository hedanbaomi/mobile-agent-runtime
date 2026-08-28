// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

export class HttpError extends Error {
  constructor(status, message, extra = {}) {
    super(message);
    this.status = status;
    this.extra = extra;
  }
}

const PENDING = new Set(["draft", "scheduled"]);
const ALLOWED_ACTIONS = new Set(["OPEN_HTTPS_URL", "OPEN_APP_ROUTE", "DISMISS", "ACKNOWLEDGE"]);
const ALLOWED_ROUTES = new Set([
  "app://settings/providers",
  "app://settings/knowledge",
  "app://announcements",
  "app://about",
  "app://update",
]);
const EVENT_TYPES = new Set([
  "install_seen",
  "app_active",
  "announcement_fetched",
  "announcement_displayed",
  "announcement_opened",
  "announcement_acknowledged",
  "action_clicked",
]);
const EVENT_KEYS = new Set([
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
const SIX_HOURS_MS = 6 * 60 * 60 * 1000;

export class MemoryStore {
  constructor() {
    this.announcements = new Map();
    this.revisions = new Map();
    this.translations = new Map();
    this.receipts = new Map();
    this.eventDedup = new Map();
    this.installState = new Map();
    this.audit = [];
    this.feedState = { id: 1, sequence: 0, contentVersion: 0, keyId: "local-dev-1" };
    this.idempotency = new Map();
  }

  pendingKey(announcementId) {
    for (const rev of this.revisions.values()) {
      if (rev.announcementId === announcementId && PENDING.has(rev.revisionStatus)) {
        return `${announcementId}:${rev.revision}`;
      }
    }
    return null;
  }

  createAnnouncement(input, actor, requestId, now) {
    const existing = this.idempotency.get(requestId);
    if (existing) return existing;
    const id = input.id;
    if (this.announcements.has(id)) {
      throw new HttpError(409, "announcement already exists");
    }
    const created = now.toISOString();
    this.announcements.set(id, {
      id,
      currentPublishedRevision: null,
      status: "draft",
      createdAt: created,
      updatedAt: created,
    });
    const revision = this.writeRevision(id, 1, "draft", input, now);
    this.writeTranslations(id, 1, input.translations);
    this.auditPush(actor, "create", id, 1, requestId, now, "created draft revision 1");
    const result = { id, revision: revision.revision, status: "draft" };
    this.idempotency.set(requestId, result);
    return result;
  }

  patchDraft(id, input, actor, requestId, now, expectedRevision) {
    const existing = this.idempotency.get(requestId);
    if (existing) return existing;
    const pending = this.requirePending(id);
    if (expectedRevision != null && pending.revision !== expectedRevision) {
      throw new HttpError(409, "revision conflict");
    }
    if (pending.revisionStatus !== "draft") {
      throw new HttpError(409, "only draft revisions can be patched");
    }
    const merged = { ...JSON.parse(pending.bodyJson), ...input };
    pending.bodyJson = JSON.stringify(this.normalizeBody(merged));
    if (input.translations) this.writeTranslations(id, pending.revision, input.translations);
    this.touch(id, now);
    this.auditPush(actor, "patch", id, pending.revision, requestId, now, "patched draft");
    const result = { id, revision: pending.revision, status: "draft" };
    this.idempotency.set(requestId, result);
    return result;
  }

  addRevision(id, input, actor, requestId, now) {
    const existing = this.idempotency.get(requestId);
    if (existing) return existing;
    if (this.pendingKey(id)) {
      throw new HttpError(409, "a draft or scheduled revision already exists");
    }
    const announcement = this.requireAnnouncement(id);
    const next = this.maxRevision(id) + 1;
    this.writeRevision(id, next, "draft", input, now);
    this.writeTranslations(id, next, input.translations);
    this.touch(id, now);
    this.auditPush(actor, "revise", id, next, requestId, now, `created draft revision ${next}; published pointer ${announcement.currentPublishedRevision}`);
    const result = { id, revision: next, status: announcement.status };
    this.idempotency.set(requestId, result);
    return result;
  }

  schedule(id, startsAt, actor, requestId, now, expectedRevision) {
    const existing = this.idempotency.get(requestId);
    if (existing) return existing;
    const pending = this.requirePending(id);
    if (expectedRevision != null && pending.revision !== expectedRevision) {
      throw new HttpError(409, "revision conflict");
    }
    const body = JSON.parse(pending.bodyJson);
    body.startsAt = startsAt;
    pending.bodyJson = JSON.stringify(this.normalizeBody(body));
    pending.revisionStatus = "scheduled";
    this.touch(id, now);
    this.auditPush(actor, "schedule", id, pending.revision, requestId, now, `scheduled for ${startsAt}`);
    const result = { id, revision: pending.revision, status: "scheduled" };
    this.idempotency.set(requestId, result);
    return result;
  }

  publish(id, actor, requestId, now, expectedRevision) {
    const existing = this.idempotency.get(requestId);
    if (existing) return existing;
    const pending = this.requirePending(id);
    if (expectedRevision != null && pending.revision !== expectedRevision) {
      throw new HttpError(409, "revision conflict");
    }
    this.validatePublish(id, pending);
    const announcement = this.requireAnnouncement(id);
    if (announcement.currentPublishedRevision != null && pending.revision <= announcement.currentPublishedRevision) {
      throw new HttpError(409, "cannot publish a smaller revision over a larger one");
    }
    for (const rev of this.revisions.values()) {
      if (rev.announcementId === id && rev.revisionStatus === "published") {
        rev.revisionStatus = "superseded";
      }
    }
    pending.revisionStatus = "published";
    const body = JSON.parse(pending.bodyJson);
    if (!body.publishedAt) body.publishedAt = now.toISOString();
    pending.bodyJson = JSON.stringify(this.normalizeBody(body));
    announcement.currentPublishedRevision = pending.revision;
    announcement.status = "published";
    this.bumpFeed(now);
    this.touch(id, now);
    this.auditPush(actor, "publish", id, pending.revision, requestId, now, `published revision ${pending.revision}; feed ${this.feedState.contentVersion}`);
    const result = { id, revision: pending.revision, status: "published", feedVersion: this.feedState.contentVersion };
    this.idempotency.set(requestId, result);
    return result;
  }

  withdraw(id, actor, requestId, now) {
    const existing = this.idempotency.get(requestId);
    if (existing) return existing;
    const announcement = this.requireAnnouncement(id);
    announcement.status = "withdrawn";
    this.bumpFeed(now);
    this.touch(id, now);
    this.auditPush(actor, "withdraw", id, announcement.currentPublishedRevision, requestId, now, "withdrawn all revisions");
    const result = { id, status: "withdrawn", feedVersion: this.feedState.contentVersion };
    this.idempotency.set(requestId, result);
    return result;
  }

  archive(id, actor, requestId, now) {
    const existing = this.idempotency.get(requestId);
    if (existing) return existing;
    const announcement = this.requireAnnouncement(id);
    announcement.status = "archived";
    this.bumpFeed(now);
    this.touch(id, now);
    this.auditPush(actor, "archive", id, announcement.currentPublishedRevision, requestId, now, "archived");
    const result = { id, status: "archived", feedVersion: this.feedState.contentVersion };
    this.idempotency.set(requestId, result);
    return result;
  }

  listAdmin() {
    return [...this.announcements.values()].map((row) => ({
      ...row,
      pendingRevision: this.pendingKey(row.id)?.split(":")[1] ?? null,
      revisions: [...this.revisions.values()]
        .filter((rev) => rev.announcementId === row.id)
        .map((rev) => ({ revision: rev.revision, revisionStatus: rev.revisionStatus })),
    }));
  }

  getAdmin(id) {
    const announcement = this.requireAnnouncement(id);
    const revisions = [...this.revisions.values()]
      .filter((rev) => rev.announcementId === id)
      .map((rev) => ({
        ...rev,
        body: JSON.parse(rev.bodyJson),
        translations: this.translationMap(id, rev.revision),
      }));
    return { ...announcement, revisions };
  }

  publicRows(now) {
    const withdrawn = [];
    const candidates = [];
    for (const announcement of this.announcements.values()) {
      if (announcement.status === "withdrawn") {
        if (announcement.currentPublishedRevision != null) {
          withdrawn.push({ id: announcement.id, revision: announcement.currentPublishedRevision });
        }
        continue;
      }
      if (announcement.status !== "published") continue;
      const rev = this.revision(announcement.id, announcement.currentPublishedRevision);
      if (!rev || rev.revisionStatus !== "published") continue;
      const body = JSON.parse(rev.bodyJson);
      if (body.startsAt && Date.parse(body.startsAt) > now.getTime()) continue;
      if (body.endsAt && Date.parse(body.endsAt) <= now.getTime()) continue;
      candidates.push({ announcement, rev, body, translations: this.translationMap(announcement.id, rev.revision) });
    }
    return { withdrawn, candidates };
  }

  recordEvents(events, now) {
    const accepted = [];
    for (const event of events) {
      this.assertEvent(event);
      if (this.eventDedup.has(event.eventId)) {
        accepted.push({ eventId: event.eventId, duplicate: true });
        continue;
      }
      const installIdHash = event.installIdHash;
      if (event.type === "app_active") {
        const state = this.installState.get(installIdHash) || {
          installIdHash,
          platform: event.platform,
          channel: event.channel,
          versionCode: event.versionCode,
          locale: event.locale,
          firstSeenAt: now.toISOString(),
          lastActiveAt: now.toISOString(),
          lastCountedActivityAt: null,
        };
        state.lastActiveAt = now.toISOString();
        const lastCounted = state.lastCountedActivityAt ? Date.parse(state.lastCountedActivityAt) : 0;
        if (!state.lastCountedActivityAt || now.getTime() - lastCounted >= SIX_HOURS_MS) {
          state.lastCountedActivityAt = now.toISOString();
          this.installState.set(installIdHash, state);
        } else {
          this.installState.set(installIdHash, state);
          this.eventDedup.set(event.eventId, now.toISOString());
          accepted.push({ eventId: event.eventId, counted: false });
          continue;
        }
      } else {
        const state = this.installState.get(installIdHash) || {
          installIdHash,
          platform: event.platform,
          channel: event.channel,
          versionCode: event.versionCode,
          locale: event.locale,
          firstSeenAt: now.toISOString(),
          lastActiveAt: now.toISOString(),
          lastCountedActivityAt: null,
        };
        state.lastActiveAt = now.toISOString();
        this.installState.set(installIdHash, state);
      }
      const actionKey = event.actionId || "";
      const receiptKey = [installIdHash, event.announcementId || "", String(event.revision || 0), event.type, actionKey].join("|");
      const previous = this.receipts.get(receiptKey);
      if (previous) {
        previous.lastAt = now.toISOString();
        previous.count += 1;
      } else {
        this.receipts.set(receiptKey, {
          installIdHash,
          announcementId: event.announcementId || "",
          revision: event.revision || 0,
          eventType: event.type,
          actionKey,
          firstAt: now.toISOString(),
          lastAt: now.toISOString(),
          count: 1,
        });
      }
      this.eventDedup.set(event.eventId, now.toISOString());
      accepted.push({ eventId: event.eventId, duplicate: false });
    }
    return accepted;
  }

  stats() {
    const installs = this.installState.size;
    const receipts = [...this.receipts.values()].length;
    return { consentedInstalls: installs, receiptRows: receipts };
  }

  writeRevision(announcementId, revision, revisionStatus, input, now) {
    const body = this.normalizeBody(input);
    const row = {
      announcementId,
      revision,
      revisionStatus,
      bodyJson: JSON.stringify(body),
      rolloutSalt: body.target.rolloutSalt,
      updatedAt: now.toISOString(),
    };
    this.revisions.set(`${announcementId}:${revision}`, row);
    return row;
  }

  writeTranslations(announcementId, revision, translations) {
    if (!translations || !translations.default) {
      throw new HttpError(400, "default translation is required");
    }
    for (const [locale, value] of Object.entries(translations)) {
      if (!value?.title || !value?.summary || value.bodyMarkdown == null) {
        throw new HttpError(400, `translation ${locale} is incomplete`);
      }
      this.translations.set(`${announcementId}:${revision}:${locale}`, {
        locale,
        title: value.title,
        summary: value.summary,
        bodyMarkdown: value.bodyMarkdown,
      });
    }
  }

  translationMap(announcementId, revision) {
    const map = {};
    for (const [key, value] of this.translations.entries()) {
      if (key.startsWith(`${announcementId}:${revision}:`)) map[value.locale] = value;
    }
    return map;
  }

  normalizeBody(input) {
    const target = input.target || {};
    return {
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
        locales: target.locales || [],
        rolloutPercent: target.rolloutPercent ?? 100,
        rolloutSalt: target.rolloutSalt || "default",
      },
      actions: input.actions || [],
      image: input.image || null,
      startsAt: input.startsAt || null,
      endsAt: input.endsAt || null,
      publishedAt: input.publishedAt || null,
    };
  }

  validatePublish(id, pending) {
    const translations = this.translationMap(id, pending.revision);
    if (!translations.default) throw new HttpError(400, "default translation is required");
    const body = JSON.parse(pending.bodyJson);
    if (body.mustAcknowledge) {
      if (!["WARNING", "CRITICAL"].includes(body.severity) || body.displayMode !== "MODAL") {
        throw new HttpError(400, "mustAcknowledge requires WARNING/CRITICAL and MODAL");
      }
      if (!body.actions.some((action) => action.type === "ACKNOWLEDGE")) {
        throw new HttpError(400, "mustAcknowledge requires an ACKNOWLEDGE action");
      }
    }
    for (const action of body.actions) {
      if (!ALLOWED_ACTIONS.has(action.type)) throw new HttpError(400, "action type not allowed");
      if (action.type === "OPEN_HTTPS_URL" && !String(action.url || "").startsWith("https://")) {
        throw new HttpError(400, "OPEN_HTTPS_URL requires https");
      }
      if (action.type === "OPEN_APP_ROUTE" && !ALLOWED_ROUTES.has(action.url)) {
        throw new HttpError(400, "OPEN_APP_ROUTE is not in the allowlist");
      }
    }
    if (body.image && !String(body.image).startsWith("https://")) {
      throw new HttpError(400, "image must be https");
    }
    for (const translation of Object.values(translations)) {
      if (/<[a-zA-Z/!]/.test(translation.bodyMarkdown) || /javascript:|intent:|file:/i.test(translation.bodyMarkdown)) {
        throw new HttpError(400, "markdown must not contain HTML, scripts, or blocked schemes");
      }
    }
  }

  assertEvent(event) {
    for (const key of Object.keys(event)) {
      if (key === "installIdHash") continue;
      if (!EVENT_KEYS.has(key)) throw new HttpError(400, `unknown event field ${key}`);
    }
    if (!EVENT_TYPES.has(event.type)) throw new HttpError(400, "event type not allowed");
    if (/chat|prompt|api[_-]?key|authorization|skill|knowledge/i.test(JSON.stringify(event))) {
      throw new HttpError(400, "event contains forbidden content");
    }
  }

  requireAnnouncement(id) {
    const row = this.announcements.get(id);
    if (!row) throw new HttpError(404, "announcement not found");
    return row;
  }

  requirePending(id) {
    this.requireAnnouncement(id);
    const key = this.pendingKey(id);
    if (!key) throw new HttpError(409, "no draft or scheduled revision");
    return this.revisions.get(key);
  }

  revision(id, revision) {
    return this.revisions.get(`${id}:${revision}`);
  }

  maxRevision(id) {
    let max = 0;
    for (const rev of this.revisions.values()) {
      if (rev.announcementId === id) max = Math.max(max, rev.revision);
    }
    return max;
  }

  touch(id, now) {
    const row = this.requireAnnouncement(id);
    row.updatedAt = now.toISOString();
  }

  bumpFeed(now) {
    this.feedState.sequence += 1;
    this.feedState.contentVersion += 1;
    this.feedState.updatedAt = now.toISOString();
  }

  auditPush(actor, action, announcementId, revision, requestId, now, summary) {
    this.audit.push({
      id: `${now.getTime()}-${this.audit.length}`,
      actor,
      action,
      announcementId,
      revision,
      timestamp: now.toISOString(),
      summary,
      requestId,
    });
  }
}
