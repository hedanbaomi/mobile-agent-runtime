-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
-- SPDX-License-Identifier: AGPL-3.0-only
-- Canonical schema for the independent announcements Worker+D1 service.

CREATE TABLE IF NOT EXISTS announcements (
  id TEXT PRIMARY KEY,
  current_published_revision INTEGER,
  status TEXT NOT NULL CHECK (status IN ('draft', 'scheduled', 'published', 'withdrawn', 'archived')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  mutation_token TEXT
);

CREATE TABLE IF NOT EXISTS announcement_revisions (
  announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL CHECK (revision > 0),
  revision_status TEXT NOT NULL CHECK (revision_status IN ('draft', 'scheduled', 'published', 'superseded')),
  body_json TEXT NOT NULL,
  rollout_salt TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  mutation_token TEXT,
  PRIMARY KEY (announcement_id, revision)
);

CREATE UNIQUE INDEX IF NOT EXISTS announcement_pending_revision
  ON announcement_revisions(announcement_id)
  WHERE revision_status IN ('draft', 'scheduled');

CREATE TABLE IF NOT EXISTS announcement_translations (
  announcement_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  locale TEXT NOT NULL,
  title TEXT NOT NULL,
  summary TEXT NOT NULL,
  body_markdown TEXT NOT NULL,
  PRIMARY KEY (announcement_id, revision, locale),
  FOREIGN KEY (announcement_id, revision)
    REFERENCES announcement_revisions(announcement_id, revision) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS install_state (
  install_id_hash TEXT PRIMARY KEY,
  platform TEXT NOT NULL,
  channel TEXT NOT NULL,
  version_code INTEGER NOT NULL,
  locale TEXT NOT NULL,
  first_seen_at TEXT NOT NULL,
  last_active_at TEXT NOT NULL,
  last_counted_activity_at TEXT,
  mutation_token TEXT
);

CREATE TABLE IF NOT EXISTS announcement_receipts (
  install_id_hash TEXT NOT NULL,
  announcement_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  action_key TEXT NOT NULL,
  first_at TEXT NOT NULL,
  last_at TEXT NOT NULL,
  count INTEGER NOT NULL CHECK (count > 0),
  PRIMARY KEY (install_id_hash, announcement_id, revision, event_type, action_key)
);

CREATE TABLE IF NOT EXISTS event_dedup (
  event_id TEXT PRIMARY KEY,
  received_at TEXT NOT NULL,
  mutation_token TEXT
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
  id TEXT PRIMARY KEY,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  announcement_id TEXT,
  revision INTEGER,
  timestamp TEXT NOT NULL,
  summary TEXT NOT NULL,
  request_id TEXT NOT NULL,
  before_json TEXT,
  after_json TEXT
);

CREATE TABLE IF NOT EXISTS admin_idempotency (
  request_id TEXT PRIMARY KEY,
  operation_token TEXT NOT NULL,
  response_json TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS feed_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  sequence INTEGER NOT NULL,
  content_version INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT ''
);

INSERT OR IGNORE INTO feed_state (id, sequence, content_version, key_id, updated_at)
VALUES (1, 0, 0, '', '');

CREATE INDEX IF NOT EXISTS announcement_revisions_status_idx
  ON announcement_revisions(revision_status, announcement_id);
CREATE INDEX IF NOT EXISTS admin_audit_timestamp_idx
  ON admin_audit_log(timestamp DESC, id DESC);
CREATE INDEX IF NOT EXISTS event_dedup_received_idx
  ON event_dedup(received_at);
CREATE INDEX IF NOT EXISTS receipt_first_at_idx
  ON announcement_receipts(first_at);
