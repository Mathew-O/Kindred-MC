CREATE TABLE companions (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  world_id TEXT NOT NULL,
  dimension TEXT NOT NULL,
  entity_id TEXT UNIQUE,
  lifecycle TEXT NOT NULL,
  intent_revision INTEGER NOT NULL CHECK(intent_revision >= 0),
  task_revision INTEGER NOT NULL CHECK(task_revision >= 0),
  stop_latched INTEGER NOT NULL CHECK(stop_latched IN (0, 1)),
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
CREATE TABLE creation_operations (
  operation_id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  companion_id TEXT NOT NULL REFERENCES companions(id)
);
CREATE TABLE budget_buckets (
  companion_id TEXT NOT NULL REFERENCES companions(id),
  start_ms INTEGER NOT NULL,
  end_ms INTEGER NOT NULL,
  local_date TEXT NOT NULL,
  timezone TEXT NOT NULL,
  charged_ms INTEGER NOT NULL CHECK(charged_ms >= 0),
  observed_ms INTEGER NOT NULL CHECK(observed_ms >= 0),
  sequence INTEGER NOT NULL CHECK(sequence >= 0),
  reservation_id TEXT,
  reserved_ms INTEGER,
  reserved_at_ms INTEGER,
  expires_at_ms INTEGER,
  PRIMARY KEY(companion_id, start_ms),
  CHECK(end_ms > start_ms),
  CHECK(observed_ms <= charged_ms),
  CHECK((reservation_id IS NULL AND reserved_ms IS NULL AND reserved_at_ms IS NULL AND expires_at_ms IS NULL)
     OR (reservation_id IS NOT NULL AND reserved_ms BETWEEN 1 AND 5000 AND reserved_at_ms IS NOT NULL AND expires_at_ms IS NOT NULL
         AND reserved_at_ms >= start_ms AND expires_at_ms <= end_ms AND expires_at_ms = reserved_at_ms + reserved_ms))
);
CREATE UNIQUE INDEX one_outstanding_reservation ON budget_buckets(companion_id) WHERE reservation_id IS NOT NULL;
CREATE TABLE journal_events (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  companion_id TEXT NOT NULL REFERENCES companions(id),
  observed_at_ms INTEGER NOT NULL,
  type TEXT NOT NULL,
  detail TEXT NOT NULL
);
CREATE INDEX journal_by_companion ON journal_events(companion_id, sequence);
