-- F-31 (the action fold, from F-33's month-long cost check): settled action history older than three game days is
-- compacted into one action.folded summary per action, by the ordered writer. The delete trigger is recreated by
-- SqliteCompanionStore to allow deleting action events alone, and every other kind stays append-only. The index finds an
-- action's events by its id.
CREATE INDEX world_events_by_kind_subject ON world_events(kind, subject, id);
