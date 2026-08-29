-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
-- SPDX-License-Identifier: AGPL-3.0-only
-- Durable CAS markers, audit snapshots, and request idempotency.

ALTER TABLE announcements ADD COLUMN mutation_token TEXT;
ALTER TABLE announcement_revisions ADD COLUMN updated_at TEXT NOT NULL DEFAULT '';
ALTER TABLE announcement_revisions ADD COLUMN mutation_token TEXT;
ALTER TABLE install_state ADD COLUMN mutation_token TEXT;
ALTER TABLE event_dedup ADD COLUMN mutation_token TEXT;
ALTER TABLE admin_audit_log ADD COLUMN before_json TEXT;
ALTER TABLE admin_audit_log ADD COLUMN after_json TEXT;
ALTER TABLE feed_state ADD COLUMN updated_at TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS admin_idempotency (
  request_id TEXT PRIMARY KEY,
  operation_token TEXT NOT NULL,
  response_json TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS announcement_revisions_status_idx
  ON announcement_revisions(revision_status, announcement_id);
CREATE INDEX IF NOT EXISTS admin_audit_timestamp_idx
  ON admin_audit_log(timestamp DESC, id DESC);
CREATE INDEX IF NOT EXISTS event_dedup_received_idx
  ON event_dedup(received_at);
CREATE INDEX IF NOT EXISTS receipt_first_at_idx
  ON announcement_receipts(first_at);

CREATE TRIGGER IF NOT EXISTS announcement_status_insert_guard
BEFORE INSERT ON announcements
WHEN NEW.status NOT IN ('draft', 'scheduled', 'published', 'withdrawn', 'archived')
BEGIN
  SELECT RAISE(ABORT, 'invalid announcement status');
END;

CREATE TRIGGER IF NOT EXISTS announcement_status_update_guard
BEFORE UPDATE OF status ON announcements
WHEN NEW.status NOT IN ('draft', 'scheduled', 'published', 'withdrawn', 'archived')
BEGIN
  SELECT RAISE(ABORT, 'invalid announcement status');
END;

CREATE TRIGGER IF NOT EXISTS revision_status_insert_guard
BEFORE INSERT ON announcement_revisions
WHEN NEW.revision_status NOT IN ('draft', 'scheduled', 'published', 'superseded')
BEGIN
  SELECT RAISE(ABORT, 'invalid revision status');
END;

CREATE TRIGGER IF NOT EXISTS revision_status_update_guard
BEFORE UPDATE OF revision_status ON announcement_revisions
WHEN NEW.revision_status NOT IN ('draft', 'scheduled', 'published', 'superseded')
BEGIN
  SELECT RAISE(ABORT, 'invalid revision status');
END;
