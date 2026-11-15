-- F-34c2a (with Session A's agreement, 2026-11-15): refused attempts on a faction's land (territory.refused) are kept
-- thirty game days, as settled action history is (F-31b1), then pruned after a check that agreed, with one
-- territory.pruned tally a game day written in the same transaction. The delete trigger is recreated by
-- SqliteCompanionStore: as at schema 14, and a territory.refused event once it is past the bound by its own time, and every
-- other kind stays append-only.
-- The index finds the refusals past the bound by time.
CREATE INDEX world_events_refused_by_time ON world_events(real_time_ms) WHERE kind = 'territory.refused'
