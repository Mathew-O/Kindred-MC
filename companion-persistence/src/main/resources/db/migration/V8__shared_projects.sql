CREATE TABLE IF NOT EXISTS shared_projects (
  project_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('OPEN', 'IN_PROGRESS', 'BLOCKED', 'COMPLETED', 'CANCELLED')),
  revision INTEGER NOT NULL CHECK(revision >= 0),
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS shared_tasks (
  task_id TEXT PRIMARY KEY,
  project_id TEXT NOT NULL REFERENCES shared_projects(project_id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  skill TEXT NOT NULL,
  arguments_json TEXT NOT NULL DEFAULT '{}',
  dependencies_json TEXT NOT NULL DEFAULT '[]',
  assigned_player_id TEXT,
  status TEXT NOT NULL CHECK(status IN ('OPEN', 'CLAIMED', 'IN_PROGRESS', 'COMPLETED', 'REVOKED', 'FAILED')),
  claimed_at_tick INTEGER NOT NULL DEFAULT 0,
  lease_duration_ticks INTEGER NOT NULL DEFAULT 0,
  completion_evidence TEXT,
  revision INTEGER NOT NULL CHECK(revision >= 0),
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS stock_reservations (
  reservation_id TEXT PRIMARY KEY,
  task_id TEXT REFERENCES shared_tasks(task_id) ON DELETE SET NULL,
  project_id TEXT REFERENCES shared_projects(project_id) ON DELETE CASCADE,
  player_id TEXT,
  item_id TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity > 0),
  destination TEXT,
  status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'FULFILLED', 'RELEASED')),
  revision INTEGER NOT NULL CHECK(revision >= 0),
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);
