CREATE TABLE IF NOT EXISTS design_revisions (
  design_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(revision >= 1),
  parent_revision INTEGER NOT NULL CHECK(parent_revision >= 0),
  world_id TEXT NOT NULL,
  dimension TEXT NOT NULL,
  observation_id TEXT NOT NULL,
  observation_revision INTEGER NOT NULL CHECK(observation_revision >= 0),
  geometry_sha256 TEXT NOT NULL,
  semantic_sha256 TEXT NOT NULL,
  generator_version TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('DRAFT', 'VALIDATING', 'VALIDATED', 'ACCEPTED', 'SUPERSEDED')),
  artifact_json TEXT NOT NULL,
  validation_json TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  PRIMARY KEY (design_id, revision)
);
CREATE INDEX IF NOT EXISTS design_revisions_semantic_idx ON design_revisions(semantic_sha256);
