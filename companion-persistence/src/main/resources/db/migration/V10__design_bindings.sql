CREATE TABLE IF NOT EXISTS design_bindings (
  design_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  project_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('DRAFT', 'VALIDATED', 'ACCEPTED', 'SUPERSEDED')),
  validation_sha256 TEXT NOT NULL,
  version INTEGER NOT NULL CHECK(version >= 0),
  updated_at_ms INTEGER NOT NULL,
  PRIMARY KEY (design_id, revision),
  FOREIGN KEY (design_id, revision) REFERENCES design_revisions(design_id, revision)
);
