// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { HttpError } from "./errors.mjs";
import {
  assertAnnouncementBody,
  assertEventValue,
  assertInstant,
  assertPublishData,
  assertTranslationsValue,
  normalizeAnnouncementBody,
} from "./validation.mjs";

export { HttpError } from "./errors.mjs";

const PENDING = new Set(["draft", "scheduled"]);
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
    this.signedSnapshots = new Map();
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
    this.assertTranslations(input.translations);
    this.normalizeBody(input, { strict: true });
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
    if (input.translations) this.assertTranslations(input.translations);
    this.normalizeBody(merged, { strict: true });
    pending.bodyJson = JSON.stringify(this.normalizeBody(merged, { strict: true }));
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
    this.assertTranslations(input.translations);
    this.normalizeBody(input, { strict: true });
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
    this.assertInstant(startsAt, "startsAt");
    const body = JSON.parse(pending.bodyJson);
    body.startsAt = startsAt;
    pending.bodyJson = JSON.stringify(this.normalizeBody(body, { strict: true }));
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
    this.publishPending(id, pending, actor, requestId, now, "publish");
    const announcement = this.requireAnnouncement(id);
    const result = { id, revision: pending.revision, status: "published", feedVersion: this.feedState.contentVersion };
    this.idempotency.set(requestId, result);
    return result;
  }

  publishPending(id, pending, actor, requestId, now, action) {
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
    pending.bodyJson = JSON.stringify(this.normalizeBody(body, { strict: true }));
    announcement.currentPublishedRevision = pending.revision;
    announcement.status = "published";
    this.bumpFeed(now);
    this.touch(id, now);
    this.auditPush(actor, action, id, pending.revision, requestId, now, `published revision ${pending.revision}; feed ${this.feedState.contentVersion}`);
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

  promoteDue(now) {
    const due = [...this.revisions.values()].filter((rev) => {
      if (rev.revisionStatus !== "scheduled") return false;
      const announcement = this.announcements.get(rev.announcementId);
      if (!announcement || announcement.status === "withdrawn" || announcement.status === "archived") return false;
      const body = JSON.parse(rev.bodyJson);
      const starts = body.startsAt ? Date.parse(body.startsAt) : Number.NaN;
      return Number.isFinite(starts) && starts <= now.getTime();
    });
    for (const rev of due) {
      if (rev.revisionStatus !== "scheduled") continue;
      this.publishPending(rev.announcementId, rev, "scheduler", `schedule:${rev.announcementId}:${rev.revision}:${now.toISOString()}`, now, "schedule-promote");
    }
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
      const dimensions = {
        platform: event.platform || "all",
        channel: event.channel || "all",
        versionCode: Number.isInteger(event.versionCode) ? event.versionCode : 0,
        locale: event.locale || "default",
      };
      if (event.type === "app_active") {
        const state = this.installState.get(installIdHash) || {
          installIdHash,
          ...dimensions,
          firstSeenAt: now.toISOString(),
          lastActiveAt: now.toISOString(),
          lastCountedActivityAt: null,
        };
        Object.assign(state, dimensions);
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
          ...dimensions,
          firstSeenAt: now.toISOString(),
          lastActiveAt: now.toISOString(),
          lastCountedActivityAt: null,
        };
        Object.assign(state, dimensions);
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

  stats(now = new Date()) {
    const installs = this.installState.size;
    const receipts = [...this.receipts.values()].length;
    const nowMs = now.getTime();
    const activeIn = (windowMs) => [...this.installState.values()].filter((state) => {
      const lastActive = Date.parse(state.lastActiveAt);
      return Number.isFinite(lastActive) && lastActive <= nowMs && nowMs - lastActive <= windowMs;
    });
    const dauRows = activeIn(24 * 60 * 60 * 1000);
    const wauRows = activeIn(7 * 24 * 60 * 60 * 1000);
    const mauRows = activeIn(30 * 24 * 60 * 60 * 1000);
    const distinctEventInstalls = (eventType) => new Set(
      [...this.receipts.values()]
        .filter((receipt) => receipt.eventType === eventType)
        .map((receipt) => receipt.installIdHash),
    ).size;
    const distribution = (rows, property, normalize = (value) => value) => {
      const counts = new Map();
      for (const row of rows) {
        const value = normalize(row[property]);
        counts.set(value, (counts.get(value) || 0) + 1);
      }
      return [...counts.entries()]
        .map(([value, count]) => ({ [property]: value, count }))
        .sort((left, right) => property === "versionCode"
          ? left[property] - right[property]
          : String(left[property]).localeCompare(String(right[property])));
    };
    return {
      consentedInstalls: installs,
      receiptRows: receipts,
      installSeen: distinctEventInstalls("install_seen"),
      appActive: distinctEventInstalls("app_active"),
      dau: dauRows.length,
      wau: wauRows.length,
      mau: mauRows.length,
      // Keep the existing names as compatibility aliases for callers that
      // already consume the 24h/7d/30d counters.
      active24h: dauRows.length,
      active7d: wauRows.length,
      active30d: mauRows.length,
      byVersion: distribution(mauRows, "versionCode", (value) => Number(value || 0)),
      byChannel: distribution(mauRows, "channel", (value) => value || "all"),
      byPlatform: distribution(mauRows, "platform", (value) => value || "all"),
    };
  }

  writeRevision(announcementId, revision, revisionStatus, input, now) {
    const body = this.normalizeBody(input, { strict: true });
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
    this.assertTranslations(translations);
    for (const [locale, value] of Object.entries(translations)) {
      this.translations.set(`${announcementId}:${revision}:${locale}`, {
        locale,
        title: value.title,
        summary: value.summary,
        bodyMarkdown: value.bodyMarkdown,
      });
    }
  }

  assertTranslations(translations) {
    assertTranslationsValue(translations);
  }

  translationMap(announcementId, revision) {
    const map = {};
    for (const [key, value] of this.translations.entries()) {
      if (key.startsWith(`${announcementId}:${revision}:`)) map[value.locale] = value;
    }
    return map;
  }

  normalizeBody(input, options = {}) {
    return normalizeAnnouncementBody(input, options);
  }

  assertBody(body) {
    assertAnnouncementBody(body);
  }

  assertInstant(value, field) {
    assertInstant(value, field);
  }

  validatePublish(id, pending) {
    const translations = this.translationMap(id, pending.revision);
    const body = this.normalizeBody(JSON.parse(pending.bodyJson), { strict: true });
    pending.bodyJson = JSON.stringify(body);
    assertPublishData(body, translations);
  }

  assertEvent(event) {
    assertEventValue(event);
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
    this.signedSnapshots.clear();
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
