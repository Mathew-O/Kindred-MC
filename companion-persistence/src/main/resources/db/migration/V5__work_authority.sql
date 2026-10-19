CREATE TABLE work_setups (
  companion_id TEXT PRIMARY KEY REFERENCES companions(id),
  dimension TEXT NOT NULL,
  home_x INTEGER, home_y INTEGER, home_z INTEGER,
  area_min_x INTEGER, area_min_z INTEGER, area_max_x INTEGER, area_max_z INTEGER,
  storage_x INTEGER, storage_y INTEGER, storage_z INTEGER, storage_block TEXT, storage_version INTEGER,
  patch_min_x INTEGER, patch_min_y INTEGER, patch_min_z INTEGER,
  patch_max_x INTEGER, patch_max_y INTEGER, patch_max_z INTEGER, patch_resource TEXT,
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
INSERT INTO work_setups(companion_id, dimension, revision) SELECT id, dimension, 0 FROM companions;
CREATE TABLE action_ledger (
  action_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL,
  companion_id TEXT NOT NULL REFERENCES companions(id),
  epoch TEXT NOT NULL,
  intent_revision INTEGER NOT NULL CHECK(intent_revision >= 0),
  task_revision INTEGER NOT NULL CHECK(task_revision >= 0),
  kind TEXT NOT NULL,
  preconditions TEXT,
  state TEXT NOT NULL CHECK(state IN ('PREPARED', 'DISPATCHED', 'VERIFIED', 'FAILED', 'CANCELLED', 'UNCERTAIN')),
  before_evidence TEXT,
  after_evidence TEXT,
  outcome TEXT NOT NULL CHECK(outcome IN ('NONE', 'SUCCESS', 'FAILURE', 'UNKNOWN')),
  detail TEXT,
  revision INTEGER NOT NULL CHECK(revision >= 0),
  prepared_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);
CREATE INDEX action_ledger_companion_state ON action_ledger(companion_id, state);
CREATE TABLE skill_checkpoints (
  companion_id TEXT PRIMARY KEY REFERENCES companions(id),
  skill_id TEXT NOT NULL,
  task_id TEXT NOT NULL,
  task_revision INTEGER NOT NULL CHECK(task_revision >= 0),
  arguments TEXT NOT NULL,
  progress TEXT NOT NULL,
  at_ms INTEGER NOT NULL,
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
