-- E-18b: how a player types (proper, casual, gamer, chaotic), set by its controller, NULL until set, when it types as its
-- personality has it. Read through ChatStyle.parse, so an unknown value reads as none.
ALTER TABLE social_profiles ADD COLUMN chat_style TEXT;
-- E-18b: each person's own yes or no to swearing, dark jokes and flirting. NULL (or 'default') leaves it to the server.
ALTER TABLE listener_preferences ADD COLUMN swearing TEXT CHECK(swearing IS NULL OR swearing IN ('default', 'on', 'off'));
ALTER TABLE listener_preferences ADD COLUMN dark_humor TEXT CHECK(dark_humor IS NULL OR dark_humor IN ('default', 'on', 'off'));
ALTER TABLE listener_preferences ADD COLUMN flirting TEXT CHECK(flirting IS NULL OR flirting IN ('default', 'on', 'off'));
