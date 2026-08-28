-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
-- SPDX-License-Identifier: AGPL-3.0-only
CREATE TABLE IF NOT EXISTS announcements (
  id TEXT PRIMARY KEY,
  current_published_revision INTEGER,
  status TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS announcement_revisions (
  announcement_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  revision_status TEXT NOT NULL,
  body_json TEXT NOT NULL,
  rollout_salt TEXT NOT NULL,
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
  PRIMARY KEY (announcement_id, revision, locale)
);

CREATE TABLE IF NOT EXISTS install_state (
  install_id_hash TEXT PRIMARY KEY,
  platform TEXT NOT NULL,
  channel TEXT NOT NULL,
  version_code INTEGER NOT NULL,
  locale TEXT NOT NULL,
  first_seen_at TEXT NOT NULL,
  last_active_at TEXT NOT NULL,
  last_counted_activity_at TEXT
);

CREATE TABLE IF NOT EXISTS announcement_receipts (
  install_id_hash TEXT NOT NULL,
  announcement_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  action_key TEXT NOT NULL,
  first_at TEXT NOT NULL,
  last_at TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (install_id_hash, announcement_id, revision, event_type, action_key)
);

CREATE TABLE IF NOT EXISTS event_dedup (
  event_id TEXT PRIMARY KEY,
  received_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
  id TEXT PRIMARY KEY,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  announcement_id TEXT,
  revision INTEGER,
  timestamp TEXT NOT NULL,
  summary TEXT NOT NULL,
  request_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS feed_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  sequence INTEGER NOT NULL,
  content_version INTEGER NOT NULL,
  key_id TEXT NOT NULL
);
