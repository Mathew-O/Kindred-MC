-- F-11f: the local hours an AI player may be active in, in its accounting timezone. Both NULL is all day.
ALTER TABLE activity_limits ADD COLUMN window_start TEXT;
ALTER TABLE activity_limits ADD COLUMN window_end TEXT;
