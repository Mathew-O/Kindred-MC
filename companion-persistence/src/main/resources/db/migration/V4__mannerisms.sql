CREATE TABLE mannerism_profiles (
  companion_id TEXT PRIMARY KEY REFERENCES companions(id),
  sprintjump TEXT NOT NULL DEFAULT 'AUTO' CHECK(sprintjump IN ('AUTO', 'ON', 'OFF')),
  lively TEXT NOT NULL DEFAULT 'AUTO' CHECK(lively IN ('AUTO', 'ON', 'OFF')),
  curiousglance TEXT NOT NULL DEFAULT 'AUTO' CHECK(curiousglance IN ('AUTO', 'ON', 'OFF')),
  steady TEXT NOT NULL DEFAULT 'AUTO' CHECK(steady IN ('AUTO', 'ON', 'OFF')),
  policy_version INTEGER NOT NULL CHECK(policy_version >= 1),
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
INSERT INTO mannerism_profiles SELECT id, 'AUTO', 'AUTO', 'AUTO', 'AUTO', 1, 0 FROM companions;
