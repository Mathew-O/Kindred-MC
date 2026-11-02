-- E-18a: a player's expression preset (plain, dry, playful), set by its controller, NULL until set, when it sounds by its
-- humour as before. Read through Expression.parse, so an unknown value reads as none.
ALTER TABLE social_profiles ADD COLUMN expression TEXT;
-- E-19a: how each person has asked the AI players to talk to them, one row per person, deleted on request.
CREATE TABLE listener_preferences (
  person_id TEXT PRIMARY KEY,
  unasked TEXT NOT NULL CHECK(unasked IN ('fewer', 'usual', 'more')),
  no_teasing INTEGER NOT NULL CHECK(no_teasing IN (0, 1)),
  no_jokes INTEGER NOT NULL CHECK(no_jokes IN (0, 1)),
  revision INTEGER NOT NULL CHECK(revision >= 0)
);
