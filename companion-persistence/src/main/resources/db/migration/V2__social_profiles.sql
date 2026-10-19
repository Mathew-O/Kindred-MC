CREATE TABLE social_profiles (
  companion_id TEXT PRIMARY KEY REFERENCES companions(id),
  initiative INTEGER NOT NULL CHECK(initiative BETWEEN 0 AND 100),
  caution INTEGER NOT NULL CHECK(caution BETWEEN 0 AND 100),
  curiosity INTEGER NOT NULL CHECK(curiosity BETWEEN 0 AND 100),
  sociability INTEGER NOT NULL CHECK(sociability BETWEEN 0 AND 100),
  humor INTEGER NOT NULL CHECK(humor BETWEEN 0 AND 100),
  personality_revision INTEGER NOT NULL CHECK(personality_revision >= 0),
  ambient_enabled INTEGER NOT NULL CHECK(ambient_enabled IN (0, 1)),
  public_conversation INTEGER NOT NULL CHECK(public_conversation IN (0, 1)),
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
INSERT INTO social_profiles SELECT id, 50, 50, 50, 50, 50, 0, 1, 0, 0 FROM companions;
