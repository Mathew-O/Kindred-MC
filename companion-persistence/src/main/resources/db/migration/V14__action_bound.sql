-- F-31b1 (the action bound, Session A's decision 2026-11-14): settled action history is kept 30 game days on the fold's
-- clock. Past that, an action's ledger row and its action.folded summary are pruned together, after a check that agreed,
-- with one action.pruned tally a game day written in the same transaction. The delete trigger is recreated by
-- SqliteCompanionStore: an action event may be deleted, and an action.folded summary once its ledger row is gone, and
-- every other kind stays append-only.
-- The index finds the summaries past the bound by time.
CREATE INDEX world_events_folded_by_time ON world_events(real_time_ms) WHERE kind = 'action.folded';
