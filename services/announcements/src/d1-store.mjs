// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { audienceHash } from "./sign.mjs";
import { HttpError } from "./errors.mjs";
import { PENDING, SIX_HOURS_MS, assertEventValue, assertInstant, assertPublishData, assertTranslationsValue, isToken, normalizeAnnouncementBody } from "./validation.mjs";

const FEED_ID = 1;
const EVENT_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
const AUDIT_RETENTION_MS = 180 * 24 * 60 * 60 * 1000;
const SIX_HOURS_DAYS = SIX_HOURS_MS / 86_400_000;

/** D1-backed store. All writes are committed through one D1 batch. */
export class D1Store {
  constructor(db, options = {}) {
    if (!db || typeof db.prepare !== "function" || typeof db.batch !== "function") {
      throw new Error("ANNOUNCEMENTS_DB binding is required");
    }
    this.db = db;
    this.keyId = String(options.keyId || "");
    this.signedSnapshots = new Map();
    this.feedState = { id: FEED_ID, sequence: 0, contentVersion: 0, keyId: this.keyId };
  }

  statement(sql, ...params) {
    return this.db.prepare(sql).bind(...params);
  }

  async first(sql, ...params) {
    return this.statement(sql, ...params).first();
  }

  async all(sql, ...params) {
    const result = await this.statement(sql, ...params).all();
    return result?.results || [];
  }

  async getFeedState() {
    const row = await this.first(
      "SELECT id, sequence, content_version, key_id, updated_at FROM feed_state WHERE id = ?",
      FEED_ID,
    );
    if (!row) throw new Error("feed_state is not initialized; apply migrations first");
    if (!row.key_id && this.keyId && Number(row.content_version) === 0) {
      await this.statement(
        "UPDATE feed_state SET key_id = ? WHERE id = ? AND content_version = 0 AND key_id = ''",
        this.keyId,
        FEED_ID,
      ).run();
      row.key_id = this.keyId;
    }
    if (this.keyId && row.key_id !== this.keyId) {
      throw new Error("configured announcement key id does not match D1 feed state");
    }
    this.feedState = {
      id: Number(row.id),
      sequence: Number(row.sequence),
      contentVersion: Number(row.content_version),
      keyId: row.key_id,
      updatedAt: row.updated_at,
    };
    return this.feedState;
  }

  async _beginMutation(requestId) {
    // Bind the configured public key id before the first content version is
    // published. A fresh migration seeds an empty key id because secrets are
    // deliberately absent from SQL.
    await this.getFeedState();
    const existing = await this.first(
      "SELECT response_json FROM admin_idempotency WHERE request_id = ?",
      requestId,
    );
    if (existing) {
      if (existing.response_json) return { response: parseJson(existing.response_json, "idempotency response") };
      throw new HttpError(409, "request is already in progress");
    }
    return { token: crypto.randomUUID() };
  }

  ownerMarker(requestId, token) {
    return {
      sql: "SELECT 1 FROM admin_idempotency WHERE request_id = ? AND operation_token = ? AND response_json IS NULL",
      params: [requestId, token],
    };
  }

  rowMarker(table, where, params, requestId, token) {
    const owner = this.ownerMarker(requestId, token);
    return {
      sql: `SELECT 1 FROM ${table} WHERE ${where} AND mutation_token = ? AND EXISTS (${owner.sql})`,
      params: [...params, token, ...owner.params],
    };
  }

  async _commitMutation(requestId, token, statements, response, options = {}) {
    const responseText = JSON.stringify(response);
    const marker = options.marker;
    const markerSql = marker ? ` AND EXISTS (${marker.sql})` : "";
    const markerParams = marker?.params || [];
    const final = options.feedVersion
      ? this.statement(
          `UPDATE admin_idempotency
              SET response_json = json_set(?, '$.feedVersion',
                (SELECT content_version FROM feed_state WHERE id = ?))
            WHERE request_id = ? AND operation_token = ? AND response_json IS NULL${markerSql}`,
          responseText,
          FEED_ID,
          requestId,
          token,
          ...markerParams,
        )
      : this.statement(
          `UPDATE admin_idempotency SET response_json = ?
            WHERE request_id = ? AND operation_token = ? AND response_json IS NULL${markerSql}`,
          responseText,
          requestId,
          token,
          ...markerParams,
        );
    const batch = [
      this.statement(
        "INSERT OR IGNORE INTO admin_idempotency (request_id, operation_token, response_json, created_at) VALUES (?, ?, NULL, ?)",
        requestId,
        token,
        options.createdAt || new Date().toISOString(),
      ),
      ...statements,
      final,
      ...(options.cleanup || []),
    ];
    try {
      await this.db.batch(batch);
    } catch (error) {
      const existing = await this.first(
        "SELECT response_json FROM admin_idempotency WHERE request_id = ?",
        requestId,
      );
      if (existing?.response_json) return parseJson(existing.response_json, "idempotency response");
      if (/unique|constraint|conflict/i.test(String(error?.message || error))) {
        throw new HttpError(409, "mutation conflicted with another writer");
      }
      throw error;
    }
    const committed = await this.first(
      "SELECT response_json FROM admin_idempotency WHERE request_id = ? AND operation_token = ?",
      requestId,
      token,
    );
    if (!committed?.response_json) throw new HttpError(409, "request conflicted with another writer");
    return parseJson(committed.response_json, "idempotency response");
  }

  auditStatement(actor, action, announcementId, revision, requestId, now, summary, marker, before, after) {
    const values = [
      crypto.randomUUID(),
      actor,
      action,
      announcementId,
      revision == null ? null : revision,
      now.toISOString(),
      summary,
      requestId,
      before == null ? null : JSON.stringify(before),
      after == null ? null : JSON.stringify(after),
    ];
    if (!marker) {
      return this.statement(
        `INSERT INTO admin_audit_log
          (id, actor, action, announcement_id, revision, timestamp, summary, request_id, before_json, after_json)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        ...values,
      );
    }
    return this.statement(
      `INSERT INTO admin_audit_log
        (id, actor, action, announcement_id, revision, timestamp, summary, request_id, before_json, after_json)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        WHERE EXISTS (${marker.sql})`,
      ...values,
      ...marker.params,
    );
  }

  async announcement(id) {
    const row = await this.first(
      "SELECT id, current_published_revision, status, created_at, updated_at FROM announcements WHERE id = ?",
      id,
    );
    if (!row) throw new HttpError(404, "announcement not found");
    return row;
  }

  async pending(id) {
    const row = await this.first(
      `SELECT announcement_id, revision, revision_status, body_json, rollout_salt, updated_at
         FROM announcement_revisions
        WHERE announcement_id = ? AND revision_status IN ('draft', 'scheduled')
        ORDER BY revision DESC LIMIT 1`,
      id,
    );
    if (!row) throw new HttpError(409, "no draft or scheduled revision");
    return row;
  }

  async translations(id, revision) {
    const rows = await this.all(
      `SELECT locale, title, summary, body_markdown
         FROM announcement_translations
        WHERE announcement_id = ? AND revision = ? ORDER BY locale`,
      id,
      revision,
    );
    return Object.fromEntries(rows.map((row) => [row.locale, {
      locale: row.locale,
      title: row.title,
      summary: row.summary,
      bodyMarkdown: row.body_markdown,
    }]));
  }

  async maxRevision(id) {
    const row = await this.first(
      "SELECT COALESCE(MAX(revision), 0) AS max_revision FROM announcement_revisions WHERE announcement_id = ?",
      id,
    );
    return Number(row?.max_revision || 0);
  }

  validatedInput(input) {
    if (!input || typeof input !== "object" || Array.isArray(input)) throw new HttpError(400, "request body must be an object");
    const body = normalizeAnnouncementBody(input, { strict: true });
    assertTranslationsValue(input.translations);
    return { body, translations: input.translations };
  }

  translationStatements(id, revision, translations, marker) {
    return Object.entries(translations).map(([locale, value]) => {
      if (!marker) {
        return this.statement(
          `INSERT INTO announcement_translations
            (announcement_id, revision, locale, title, summary, body_markdown)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (announcement_id, revision, locale) DO UPDATE SET
              title = excluded.title, summary = excluded.summary, body_markdown = excluded.body_markdown`,
          id,
          revision,
          locale,
          value.title,
          value.summary,
          value.bodyMarkdown,
        );
      }
      return this.statement(
        `INSERT INTO announcement_translations
          (announcement_id, revision, locale, title, summary, body_markdown)
          SELECT ?, ?, ?, ?, ?, ?
          WHERE EXISTS (${marker.sql})
          ON CONFLICT (announcement_id, revision, locale) DO UPDATE SET
            title = excluded.title, summary = excluded.summary, body_markdown = excluded.body_markdown`,
        id,
        revision,
        locale,
        value.title,
        value.summary,
        value.bodyMarkdown,
        ...marker.params,
      );
    });
  }

  async createAnnouncement(input, actor, requestId, now) {
    const begun = await this._beginMutation(requestId);
    if (begun.response) return begun.response;
    const id = input?.id;
    if (!isToken(id)) throw new HttpError(400, "invalid announcement id");
    if (await this.first("SELECT id FROM announcements WHERE id = ?", id)) throw new HttpError(409, "announcement already exists");
    const { body, translations } = this.validatedInput(input);
    const created = now.toISOString();
    const owner = this.ownerMarker(requestId, begun.token);
    const marker = this.rowMarker("announcements", "id = ?", [id], requestId, begun.token);
    const statements = [
      this.statement(
        `INSERT INTO announcements (id, current_published_revision, status, created_at, updated_at, mutation_token)
         SELECT ?, NULL, 'draft', ?, ?, ? WHERE EXISTS (${owner.sql})`,
        id,
        created,
        created,
        begun.token,
        ...owner.params,
      ),
      this.statement(
        `INSERT INTO announcement_revisions
          (announcement_id, revision, revision_status, body_json, rollout_salt, updated_at, mutation_token)
         SELECT ?, 1, 'draft', ?, ?, ?, NULL WHERE EXISTS (${marker.sql})`,
        id,
        JSON.stringify(body),
        body.target.rolloutSalt,
        created,
        ...marker.params,
      ),
      ...this.translationStatements(id, 1, translations, marker),
      this.auditStatement(actor, "create", id, 1, requestId, now, "created draft revision 1", marker, null, body),
    ];
    return this._commitMutation(requestId, begun.token, statements, { id, revision: 1, status: "draft" }, {
      createdAt: created,
      marker,
      cleanup: [this.statement(
        "UPDATE announcements SET mutation_token = NULL WHERE id = ? AND mutation_token = ?",
        id,
        begun.token,
      )],
    });
  }

  async patchDraft(id, input, actor, requestId, now, expectedRevision) {
    const begun = await this._beginMutation(requestId);
    if (begun.response) return begun.response;
    if (!input || typeof input !== "object" || Array.isArray(input)) throw new HttpError(400, "request body must be an object");
    if (!Number.isInteger(expectedRevision)) throw new HttpError(409, "expectedRevision is required");
    const pending = await this.pending(id);
    if (pending.revision !== expectedRevision) throw new HttpError(409, "revision conflict");
    if (pending.revision_status !== "draft") throw new HttpError(409, "only draft revisions can be patched");
    const current = parseJson(pending.body_json, "announcement body");
    const merged = { ...current, ...(input || {}) };
    const hasTranslations = Object.prototype.hasOwnProperty.call(input || {}, "translations");
    const body = normalizeAnnouncementBody(merged, { strict: true });
    const translations = hasTranslations ? (this.validatedInput(merged).translations) : null;
    const updated = now.toISOString();
    const marker = this.rowMarker("announcement_revisions", "announcement_id = ? AND revision = ?", [id, pending.revision], requestId, begun.token);
    const statements = [
      this.statement(
        `UPDATE announcement_revisions SET mutation_token = ?
          WHERE announcement_id = ? AND revision = ? AND revision_status = 'draft' AND mutation_token IS NULL
            AND EXISTS (${this.ownerMarker(requestId, begun.token).sql})`,
        begun.token,
        id,
        pending.revision,
        ...this.ownerMarker(requestId, begun.token).params,
      ),
      this.statement(
        `UPDATE announcement_revisions SET body_json = ?, rollout_salt = ?, updated_at = ?
          WHERE announcement_id = ? AND revision = ? AND mutation_token = ?`,
        JSON.stringify(body),
        body.target.rolloutSalt,
        updated,
        id,
        pending.revision,
        begun.token,
      ),
    ];
    if (translations) {
      statements.push(this.statement(
        `DELETE FROM announcement_translations WHERE announcement_id = ? AND revision = ? AND EXISTS (${marker.sql})`,
        id,
        pending.revision,
        ...marker.params,
      ));
      statements.push(...this.translationStatements(id, pending.revision, translations, marker));
    }
    statements.push(
      this.statement(
        `UPDATE announcements SET updated_at = ? WHERE id = ? AND EXISTS (${marker.sql})`,
        updated,
        id,
        ...marker.params,
      ),
      this.auditStatement(actor, "patch", id, pending.revision, requestId, now, "patched draft", marker, current, body),
    );
    return this._commitMutation(requestId, begun.token, statements, { id, revision: pending.revision, status: "draft" }, {
      createdAt: updated,
      marker,
      cleanup: [this.statement(
        "UPDATE announcement_revisions SET mutation_token = NULL WHERE announcement_id = ? AND revision = ? AND mutation_token = ?",
        id,
        pending.revision,
        begun.token,
      )],
    });
  }

  async addRevision(id, input, actor, requestId, now) {
    const begun = await this._beginMutation(requestId);
    if (begun.response) return begun.response;
    const announcement = await this.announcement(id);
    const pending = await this.first(
      "SELECT revision FROM announcement_revisions WHERE announcement_id = ? AND revision_status IN ('draft', 'scheduled') LIMIT 1",
      id,
    );
    if (pending) throw new HttpError(409, "a draft or scheduled revision already exists");
    const { body, translations } = this.validatedInput(input);
    const revision = (await this.maxRevision(id)) + 1;
    const updated = now.toISOString();
    const owner = this.ownerMarker(requestId, begun.token);
    const statements = [
      this.statement(
        `INSERT INTO announcement_revisions
          (announcement_id, revision, revision_status, body_json, rollout_salt, updated_at, mutation_token)
         SELECT ?, ?, 'draft', ?, ?, ?, NULL WHERE EXISTS (${owner.sql})`,
        id,
        revision,
        JSON.stringify(body),
        body.target.rolloutSalt,
        updated,
        ...owner.params,
      ),
      ...this.translationStatements(id, revision, translations, owner),
      this.statement(
        `UPDATE announcements SET updated_at = ? WHERE id = ? AND EXISTS (${owner.sql})`,
        updated,
        id,
        ...owner.params,
      ),
      this.auditStatement(actor, "revise", id, revision, requestId, now, `created draft revision ${revision}; published pointer ${announcement.current_published_revision}`, owner, null, body),
    ];
    return this._commitMutation(requestId, begun.token, statements, { id, revision, status: announcement.status }, {
      createdAt: updated,
      marker: owner,
    });
  }

  async schedule(id, startsAt, actor, requestId, now, expectedRevision) {
    const begun = await this._beginMutation(requestId);
    if (begun.response) return begun.response;
    if (!Number.isInteger(expectedRevision)) throw new HttpError(409, "expectedRevision is required");
    const pending = await this.pending(id);
    if (pending.revision !== expectedRevision) throw new HttpError(409, "revision conflict");
    assertInstant(startsAt, "startsAt");
    const current = parseJson(pending.body_json, "announcement body");
    const body = normalizeAnnouncementBody({ ...current, startsAt }, { strict: true });
    const updated = now.toISOString();
    const marker = this.rowMarker("announcement_revisions", "announcement_id = ? AND revision = ?", [id, pending.revision], requestId, begun.token);
    const owner = this.ownerMarker(requestId, begun.token);
    const statements = [
      this.statement(
        `UPDATE announcement_revisions SET mutation_token = ?
          WHERE announcement_id = ? AND revision = ? AND revision_status IN ('draft', 'scheduled')
            AND mutation_token IS NULL AND EXISTS (${owner.sql})`,
        begun.token,
        id,
        pending.revision,
        ...owner.params,
      ),
      this.statement(
        `UPDATE announcement_revisions SET body_json = ?, rollout_salt = ?, revision_status = 'scheduled', updated_at = ?
          WHERE announcement_id = ? AND revision = ? AND mutation_token = ?`,
        JSON.stringify(body),
        body.target.rolloutSalt,
        updated,
        id,
        pending.revision,
        begun.token,
      ),
      this.auditStatement(actor, "schedule", id, pending.revision, requestId, now, `scheduled for ${startsAt}`, marker, current, body),
    ];
    return this._commitMutation(requestId, begun.token, statements, { id, revision: pending.revision, status: "scheduled" }, {
      createdAt: updated,
      marker,
      cleanup: [this.statement(
        "UPDATE announcement_revisions SET mutation_token = NULL WHERE announcement_id = ? AND revision = ? AND mutation_token = ?",
        id,
        pending.revision,
        begun.token,
      )],
    });
  }

  async publish(id, actor, requestId, now, expectedRevision) {
    const begun = await this._beginMutation(requestId);
    if (begun.response) return begun.response;
    if (!Number.isInteger(expectedRevision)) throw new HttpError(409, "expectedRevision is required");
    const announcement = await this.announcement(id);
    const pending = await this.pending(id);
    if (pending.revision !== expectedRevision) throw new HttpError(409, "revision conflict");
    if (["withdrawn", "archived"].includes(announcement.status)) throw new HttpError(409, "announcement is not publishable");
    if (announcement.current_published_revision != null && pending.revision <= announcement.current_published_revision) {
      throw new HttpError(409, "cannot publish a smaller revision over a larger one");
    }
    const current = parseJson(pending.body_json, "announcement body");
    const body = normalizeAnnouncementBody(current, { strict: true });
    if (!body.publishedAt) body.publishedAt = now.toISOString();
    const translations = await this.translations(id, pending.revision);
    assertPublishData(body, translations);
    const updated = now.toISOString();
    const owner = this.ownerMarker(requestId, begun.token);
    const revisionMarker = this.rowMarker("announcement_revisions", "announcement_id = ? AND revision = ?", [id, pending.revision], requestId, begun.token);
    const announcementMarker = this.rowMarker("announcements", "id = ?", [id], requestId, begun.token);
    const statements = [
      this.statement(
        `UPDATE announcement_revisions SET mutation_token = ?
          WHERE announcement_id = ? AND revision = ? AND revision_status IN ('draft', 'scheduled')
            AND mutation_token IS NULL AND EXISTS (${owner.sql})
            AND EXISTS (
              SELECT 1 FROM announcements a WHERE a.id = ? AND a.status NOT IN ('withdrawn', 'archived')
                AND (a.current_published_revision IS NULL OR a.current_published_revision < ?)
            )`,
        begun.token,
        id,
        pending.revision,
        ...owner.params,
        id,
        pending.revision,
      ),
      this.statement(
        `UPDATE announcement_revisions SET revision_status = 'superseded'
          WHERE announcement_id = ? AND revision_status = 'published' AND EXISTS (${revisionMarker.sql})`,
        id,
        ...revisionMarker.params,
      ),
      this.statement(
        `UPDATE announcement_revisions SET revision_status = 'published', body_json = ?, rollout_salt = ?, updated_at = ?
          WHERE announcement_id = ? AND revision = ? AND mutation_token = ?`,
        JSON.stringify(body),
        body.target.rolloutSalt,
        updated,
        id,
        pending.revision,
        begun.token,
      ),
      this.statement(
        `UPDATE announcements SET current_published_revision = ?, status = 'published', updated_at = ?, mutation_token = ?
          WHERE id = ? AND mutation_token IS NULL
            AND (current_published_revision IS NULL OR current_published_revision < ?)
            AND EXISTS (${revisionMarker.sql})`,
        pending.revision,
        updated,
        begun.token,
        id,
        pending.revision,
        ...revisionMarker.params,
      ),
      this.statement(
        `UPDATE feed_state SET sequence = sequence + 1, content_version = content_version + 1, updated_at = ?
          WHERE id = ? AND EXISTS (${announcementMarker.sql})`,
        updated,
        FEED_ID,
        ...announcementMarker.params,
      ),
      this.auditStatement(actor, "publish", id, pending.revision, requestId, now, `published revision ${pending.revision}`, announcementMarker, announcement, { ...body, revision: pending.revision }),
    ];
    return this._commitMutation(requestId, begun.token, statements, { id, revision: pending.revision, status: "published", feedVersion: null }, {
      createdAt: updated,
      marker: announcementMarker,
      feedVersion: true,
      cleanup: [
        this.statement("UPDATE announcements SET mutation_token = NULL WHERE id = ? AND mutation_token = ?", id, begun.token),
        this.statement("UPDATE announcement_revisions SET mutation_token = NULL WHERE announcement_id = ? AND revision = ? AND mutation_token = ?", id, pending.revision, begun.token),
      ],
    });
  }

  async statusChange(id, status, actor, requestId, now, expectedRevision, action = status) {
    const begun = await this._beginMutation(requestId);
    if (begun.response) return begun.response;
    if (!Number.isInteger(expectedRevision)) throw new HttpError(409, "expectedRevision is required");
    const announcement = await this.announcement(id);
    if (announcement.current_published_revision !== expectedRevision) throw new HttpError(409, "revision conflict");
    if (announcement.status === status) throw new HttpError(409, `announcement is already ${status}`);
    if (status === "withdrawn" && announcement.status === "archived") throw new HttpError(409, "archived announcement cannot be withdrawn");
    const updated = now.toISOString();
    const owner = this.ownerMarker(requestId, begun.token);
    const marker = this.rowMarker("announcements", "id = ?", [id], requestId, begun.token);
    const statements = [
      this.statement(
        `UPDATE announcements SET mutation_token = ?
          WHERE id = ? AND current_published_revision = ? AND status NOT IN ('archived', ?)
            AND mutation_token IS NULL AND EXISTS (${owner.sql})`,
        begun.token,
        id,
        expectedRevision,
        status,
        ...owner.params,
      ),
      this.statement(
        "UPDATE announcements SET status = ?, updated_at = ? WHERE id = ? AND mutation_token = ?",
        status,
        updated,
        id,
        begun.token,
      ),
      this.statement(
        `UPDATE feed_state SET sequence = sequence + 1, content_version = content_version + 1, updated_at = ?
          WHERE id = ? AND EXISTS (${marker.sql})`,
        updated,
        FEED_ID,
        ...marker.params,
      ),
      this.auditStatement(actor, action, id, expectedRevision, requestId, now, `${status} announcement`, marker, announcement, { ...announcement, status, updated_at: updated }),
    ];
    return this._commitMutation(requestId, begun.token, statements, { id, status, feedVersion: null }, {
      createdAt: updated,
      marker,
      feedVersion: true,
      cleanup: [this.statement("UPDATE announcements SET mutation_token = NULL WHERE id = ? AND mutation_token = ?", id, begun.token)],
    });
  }

  withdraw(id, actor, requestId, now, expectedRevision) {
    return this.statusChange(id, "withdrawn", actor, requestId, now, expectedRevision, "withdraw");
  }

  archive(id, actor, requestId, now, expectedRevision) {
    return this.statusChange(id, "archived", actor, requestId, now, expectedRevision, "archive");
  }

  async listAdmin() {
    const announcements = await this.all(
      "SELECT id, current_published_revision, status, created_at, updated_at FROM announcements ORDER BY updated_at DESC, id",
    );
    const revisions = await this.all(
      "SELECT announcement_id, revision, revision_status FROM announcement_revisions ORDER BY announcement_id, revision",
    );
    const grouped = new Map();
    for (const row of revisions) {
      if (!grouped.has(row.announcement_id)) grouped.set(row.announcement_id, []);
      grouped.get(row.announcement_id).push(row);
    }
    return announcements.map((row) => {
      const revs = grouped.get(row.id) || [];
      const pending = revs.find((revision) => PENDING.has(revision.revision_status));
      return {
        ...mapAnnouncement(row),
        pendingRevision: pending ? Number(pending.revision) : null,
        revisions: revs.map((revision) => ({ revision: Number(revision.revision), revisionStatus: revision.revision_status })),
      };
    });
  }

  async getAdmin(id) {
    const announcement = await this.announcement(id);
    const revisions = await this.all(
      `SELECT announcement_id, revision, revision_status, body_json, rollout_salt, updated_at
         FROM announcement_revisions WHERE announcement_id = ? ORDER BY revision`,
      id,
    );
    return {
      ...mapAnnouncement(announcement),
      revisions: await Promise.all(revisions.map(async (row) => ({
        announcementId: row.announcement_id,
        revision: Number(row.revision),
        revisionStatus: row.revision_status,
        bodyJson: row.body_json,
        rolloutSalt: row.rollout_salt,
        updatedAt: row.updated_at,
        body: parseJson(row.body_json, "announcement body"),
        translations: await this.translations(id, Number(row.revision)),
      }))),
    };
  }

  async publicRows(now) {
    const withdrawn = await this.all(
      "SELECT id, current_published_revision AS revision FROM announcements WHERE status = 'withdrawn' AND current_published_revision IS NOT NULL",
    );
    const rows = await this.all(
      `SELECT a.id, a.current_published_revision, a.status,
              r.revision, r.revision_status, r.body_json, r.rollout_salt
         FROM announcements a JOIN announcement_revisions r
           ON r.announcement_id = a.id AND r.revision = a.current_published_revision
        WHERE a.status = 'published' AND r.revision_status = 'published'`,
    );
    const candidates = [];
    for (const row of rows) {
      const body = parseJson(row.body_json, "announcement body");
      if (body.startsAt && Date.parse(body.startsAt) > now.getTime()) continue;
      if (body.endsAt && Date.parse(body.endsAt) <= now.getTime()) continue;
      candidates.push({
        announcement: { id: row.id, currentPublishedRevision: Number(row.current_published_revision), status: row.status },
        rev: { announcementId: row.id, revision: Number(row.revision), revisionStatus: row.revision_status, bodyJson: row.body_json, rolloutSalt: row.rollout_salt },
        body,
        translations: await this.translations(row.id, Number(row.revision)),
      });
    }
    return {
      withdrawn: withdrawn.map((row) => ({ id: row.id, revision: Number(row.revision) })),
      candidates,
    };
  }

  async promoteDue(now) {
    const due = await this.all(
      `SELECT r.announcement_id, r.revision
         FROM announcement_revisions r JOIN announcements a ON a.id = r.announcement_id
        WHERE r.revision_status = 'scheduled' AND a.status NOT IN ('withdrawn', 'archived')
          AND json_extract(r.body_json, '$.startsAt') IS NOT NULL
          AND julianday(json_extract(r.body_json, '$.startsAt')) <= julianday(?)
        ORDER BY r.announcement_id, r.revision`,
      now.toISOString(),
    );
    for (const row of due) {
      try {
        await this.publish(row.announcement_id, "scheduler", `scheduler:${row.announcement_id}:${row.revision}`, now, Number(row.revision));
      } catch (error) {
        if (!(error instanceof HttpError) || ![400, 409].includes(error.status)) throw error;
      }
    }
  }

  async recordEvents(events, now) {
    const result = [];
    for (const event of events) result.push(await this.recordEvent(event, now));
    return result;
  }

  async recordEvent(event, now) {
    assertEventValue(event);
    const installIdHash = event.installIdHash || audienceHash(event.installId);
    if (!/^[0-9a-f]{64}$/.test(installIdHash)) throw new HttpError(400, "invalid installId hash");
    if (await this.first("SELECT event_id FROM event_dedup WHERE event_id = ?", event.eventId)) {
      return { eventId: event.eventId, duplicate: true };
    }
    const token = crypto.randomUUID();
    const received = now.toISOString();
    const actionKey = event.type === "action_clicked" ? String(event.actionId || "") : "";
    const announcementId = String(event.announcementId || "");
    const revision = Number(event.revision || 0);
    const dedup = { sql: "SELECT 1 FROM event_dedup WHERE event_id = ? AND mutation_token = ?", params: [event.eventId, token] };
    const statements = [
      this.statement(
        "INSERT OR IGNORE INTO event_dedup (event_id, received_at, mutation_token) VALUES (?, ?, ?)",
        event.eventId,
        received,
        token,
      ),
      this.statement(
        `INSERT INTO install_state
          (install_id_hash, platform, channel, version_code, locale, first_seen_at, last_active_at, last_counted_activity_at, mutation_token)
         SELECT ?, ?, ?, ?, ?, ?, ?, NULL, NULL
          WHERE EXISTS (${dedup.sql})
          ON CONFLICT (install_id_hash) DO NOTHING`,
        installIdHash,
        event.platform || "all",
        event.channel || "all",
        Number(event.versionCode || 0),
        String(event.locale || "default"),
        received,
        received,
        ...dedup.params,
      ),
      this.statement(
        `UPDATE install_state SET platform = ?, channel = ?, version_code = ?, locale = ?, last_active_at = ?,
          last_counted_activity_at = CASE WHEN ? = 'app_active' AND
            (last_counted_activity_at IS NULL OR julianday(?) - julianday(last_counted_activity_at) >= ?)
            THEN ? ELSE last_counted_activity_at END,
          mutation_token = CASE WHEN ? = 'app_active' AND
            (last_counted_activity_at IS NULL OR julianday(?) - julianday(last_counted_activity_at) >= ?)
            THEN ? ELSE NULL END
        WHERE install_id_hash = ? AND EXISTS (${dedup.sql})`,
        event.platform || "all",
        event.channel || "all",
        Number(event.versionCode || 0),
        String(event.locale || "default"),
        received,
        event.type,
        received,
        SIX_HOURS_DAYS,
        received,
        event.type,
        received,
        SIX_HOURS_DAYS,
        token,
        installIdHash,
        ...dedup.params,
      ),
      this.statement(
        `INSERT INTO announcement_receipts
          (install_id_hash, announcement_id, revision, event_type, action_key, first_at, last_at, count)
         SELECT ?, ?, ?, ?, ?, ?, ?, 1
          WHERE EXISTS (${dedup.sql})
            AND (? <> 'app_active' OR EXISTS (SELECT 1 FROM install_state WHERE install_id_hash = ? AND mutation_token = ?))
         ON CONFLICT (install_id_hash, announcement_id, revision, event_type, action_key) DO UPDATE SET
           last_at = CASE WHEN excluded.last_at > last_at THEN excluded.last_at ELSE last_at END,
           count = count + 1`,
        installIdHash,
        announcementId,
        revision,
        event.type,
        actionKey,
        received,
        received,
        ...dedup.params,
        event.type,
        installIdHash,
        token,
      ),
      this.statement("UPDATE event_dedup SET mutation_token = NULL WHERE event_id = ? AND mutation_token = ?", event.eventId, token),
      this.statement("UPDATE install_state SET mutation_token = NULL WHERE install_id_hash = ? AND mutation_token = ?", installIdHash, token),
    ];
    try {
      await this.db.batch(statements);
    } catch (error) {
      if (/unique|constraint|conflict/i.test(String(error?.message || error))) {
        const duplicate = await this.first("SELECT event_id FROM event_dedup WHERE event_id = ?", event.eventId);
        if (duplicate) return { eventId: event.eventId, duplicate: true };
      }
      throw error;
    }
    return { eventId: event.eventId, duplicate: false };
  }

  async stats(now = new Date()) {
    const installs = await this.first("SELECT COUNT(*) AS count FROM install_state");
    const receipts = await this.first("SELECT COUNT(*) AS count FROM announcement_receipts");
    const active = await this.first(
      `SELECT
        COUNT(DISTINCT CASE WHEN julianday(?) - julianday(last_active_at) <= 1 THEN install_id_hash END) AS active_24h,
        COUNT(DISTINCT CASE WHEN julianday(?) - julianday(last_active_at) <= 7 THEN install_id_hash END) AS active_7d,
        COUNT(DISTINCT CASE WHEN julianday(?) - julianday(last_active_at) <= 30 THEN install_id_hash END) AS active_30d
       FROM install_state`,
      now.toISOString(),
      now.toISOString(),
      now.toISOString(),
    );
    return {
      consentedInstalls: Number(installs?.count || 0),
      receiptRows: Number(receipts?.count || 0),
      active24h: Number(active?.active_24h || 0),
      active7d: Number(active?.active_7d || 0),
      active30d: Number(active?.active_30d || 0),
    };
  }

  async listAudit() {
    return this.all(
      `SELECT id, actor, action, announcement_id AS announcementId, revision,
              timestamp, summary, request_id AS requestId
         FROM admin_audit_log ORDER BY timestamp DESC, id DESC LIMIT 500`,
    );
  }

  async cleanup(now = new Date()) {
    const eventCutoff = new Date(now.getTime() - EVENT_RETENTION_MS).toISOString();
    const auditCutoff = new Date(now.getTime() - AUDIT_RETENTION_MS).toISOString();
    await this.db.batch([
      this.statement("DELETE FROM event_dedup WHERE received_at < ?", eventCutoff),
      this.statement("DELETE FROM announcement_receipts WHERE first_at < ?", eventCutoff),
      this.statement("DELETE FROM admin_audit_log WHERE timestamp < ?", auditCutoff),
      this.statement("DELETE FROM admin_idempotency WHERE created_at < ?", auditCutoff),
    ]);
    this.signedSnapshots.clear();
  }
}

function parseJson(value, label) {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error(`${label} is corrupt`);
  }
}

function mapAnnouncement(row) {
  return {
    id: row.id,
    currentPublishedRevision: row.current_published_revision == null ? null : Number(row.current_published_revision),
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}
