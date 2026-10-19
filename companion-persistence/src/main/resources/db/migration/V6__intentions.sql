CREATE TABLE companion_modes (
  companion_id TEXT PRIMARY KEY REFERENCES companions(id),
  mode TEXT NOT NULL DEFAULT 'COMPANION' CHECK(mode IN ('COMPANION', 'INDEPENDENT')),
  scope TEXT NOT NULL DEFAULT 'APPROVED' CHECK(scope IN ('HOME', 'APPROVED')),
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
INSERT INTO companion_modes(companion_id, revision) SELECT id, 0 FROM companions;
CREATE TABLE goals (
  goal_id TEXT PRIMARY KEY,
  companion_id TEXT NOT NULL REFERENCES companions(id),
  origin TEXT NOT NULL CHECK(origin IN ('OWNER', 'PERSONAL')),
  description TEXT NOT NULL,
  outcome TEXT NOT NULL,
  priority INTEGER NOT NULL CHECK(priority BETWEEN 0 AND 100),
  status TEXT NOT NULL CHECK(status IN ('OPEN', 'BLOCKED', 'DONE')),
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
CREATE TABLE memories (
  memory_id TEXT PRIMARY KEY,
  companion_id TEXT NOT NULL REFERENCES companions(id),
  kind TEXT NOT NULL CHECK(kind IN ('PREFERENCE', 'FACT', 'EPISODE')),
  text TEXT NOT NULL,
  provenance TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
CREATE TABLE activity_limits (
  companion_id TEXT PRIMARY KEY REFERENCES companions(id),
  cap_ms INTEGER NOT NULL CHECK(cap_ms > 0),
  offline_work INTEGER NOT NULL CHECK(offline_work IN (0, 1)),
  timezone TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
INSERT INTO activity_limits(companion_id, cap_ms, offline_work, timezone, revision)
SELECT id, 7200000, 0, 'UTC', 0 FROM companions;
