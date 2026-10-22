CREATE TABLE IF NOT EXISTS design_placement_actions (
  design_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  action_id TEXT NOT NULL,
  x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
  block_id TEXT NOT NULL, side TEXT NOT NULL, dependency_digest TEXT NOT NULL,
  PRIMARY KEY (design_id, revision, action_id),
  FOREIGN KEY (design_id, revision) REFERENCES design_revisions(design_id, revision)
);
