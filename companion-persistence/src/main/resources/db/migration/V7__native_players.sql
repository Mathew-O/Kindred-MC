ALTER TABLE creation_operations ADD COLUMN request_fingerprint TEXT;
CREATE TABLE native_players (
  companion_id TEXT PRIMARY KEY REFERENCES companions(id),
  controller_id TEXT NOT NULL,
  phase TEXT NOT NULL CHECK(phase IN ('ALLOCATED','ESTABLISHED','BLOCKED')),
  save_receipt TEXT,
  blocker TEXT NOT NULL DEFAULT '',
  revision INTEGER NOT NULL CHECK(revision >= 0),
  CHECK(phase <> 'ESTABLISHED' OR save_receipt IS NOT NULL)
);
