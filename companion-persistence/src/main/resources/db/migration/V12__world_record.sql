-- F-31: the World Record, one append-only table of events, written only by the ordered writer. Never updated or deleted
-- (the triggers are added with this schema, from SqliteCompanionStore), and a correction is another event. tick is -1 where
-- the writer has no game clock. data is EventData's key=value&key=value with % & = CR LF percent-escaped.
CREATE TABLE world_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tick INTEGER NOT NULL CHECK(tick >= -1),
  real_time_ms INTEGER NOT NULL CHECK(real_time_ms >= 0),
  world TEXT NOT NULL,
  kind TEXT NOT NULL,
  actor TEXT,
  subject TEXT NOT NULL,
  place TEXT NOT NULL,
  data TEXT NOT NULL,
  how_known TEXT NOT NULL CHECK(how_known IN ('recorded', 'told', 'claimed'))
);
CREATE INDEX world_events_by_kind ON world_events(kind, id);
-- The journal and the action ledger become kinds of event: what they already hold is written once, in order.
INSERT INTO world_events(tick, real_time_ms, world, kind, actor, subject, place, data, how_known)
SELECT -1, observed_at_ms, '', 'journal', companion_id, substr(type, 1, 256), '',
       'sequence=' || sequence || '&detail=' || replace(replace(replace(replace(replace(substr(detail, 1, 4000), '%', '%25'), '&', '%26'), '=', '%3D'), char(13), '%0D'), char(10), '%0A'),
       'recorded'
FROM journal_events ORDER BY sequence;
INSERT INTO world_events(tick, real_time_ms, world, kind, actor, subject, place, data, how_known)
SELECT -1, updated_at_ms, '', 'action', companion_id, action_id, '',
       'state=' || state || '&revision=' || revision || '&kind=' || replace(replace(replace(replace(replace(kind, '%', '%25'), '&', '%26'), '=', '%3D'), char(13), '%0D'), char(10), '%0A') || '&outcome=' || outcome || '&migrated=1',
       'recorded'
FROM action_ledger ORDER BY prepared_at_ms, action_id;
