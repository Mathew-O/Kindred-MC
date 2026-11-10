# f29e-on-4h: runner notes

- All 10 seeds exit 0 and ran 288000 ticks, at 75 to 150 ticks a second with 3 servers (`run.py` --parallel 3, main at 659cc0f; build cd56501).
- Ask timing is the campaign's one-minute wait (`PortalRunCampaign.RESCUE_AFTER_TICKS = 1_200`), as in f29e-on's notes.
- Replies: 289 asks and 289 replies (195 plans; refused on arrival: 69 "it is no longer where it asked from", 21 "the stuck spell is over", 4 malformed). 0 timeouts.
- 48 "rescue: not asked (the rates or the day's budgets)" lines. The team's token median is 87126 [26070 to 100418] against the bridge's 100000 a day in all.
