# Merged: UX pass

*Implemented in v3 (October 2026): everything below except #5 and #12 (cut), with #3 folded into #4. One change found on the device: the rear's window is read from HyperOS (`subscreen_display_time`) and is unlimited while "Stay awake" keeps a charging phone on. And the double-tap below doesn't survive the device: HyperOS sleeps the awake rear on a double-tap, so replay is a tap on Today's count.*

The instrument works. Tubes, fire and cats read at a glance, and idle stays at 0 fps. The problems come from the rear's 10-second awake window and from the panel usually facing away from you.
**The always-on face can show wrong data.** After midnight it shows yesterday's count labelled TODAY, with the live lamp lit. Most merges celebrate at doze brightness with nobody watching, and almost every showcase runs past the dim and gets deleted.
**Touch is unpredictable.** A slow swipe spawns a cat. Any two quick taps on any screen replay a merge. Input is dead for 5–23 s per celebration. A hidden long-press opens Setup on the side facing the desk.
**Labels contradict each other.** CATS says "122 THIS WEEK" while WEEK says 33. OPEN's header says 105 while its tubes sum to 44.
**Plan:** wave 1 makes the face honest and touch predictable (all small fixes, no new art). Wave 2 fits every merge into a 10-second budget and keeps a list of what you missed. Wave 3 makes the cats one character and adds records and last-week comparisons to existing screens. Still 8 screens. Of the 20 additions: 2 kept, 15 modified, 1 merged, 2 cut.

## Fix first (current app)

Fixes are F1–F31. Additions keep your numbers #1–#20.

| # | Where | Problem | Fix | Effort |
|---|---|---|---|---|
| F1 | TODAY doze face, rear wake | The face can't show it's out of date. With the front off the app is frozen, so the last frame stays up for hours with the live lamp lit, and after midnight it shows yesterday's count under TODAY. A locked wake often never polls: the poke is skipped under the keyguard, and the uptime Handler doesn't advance in deep sleep. | On the rear's STATE_ON edge, call `markStale()` (it rolls the day), then `refresh()` if `lastFetchMs` is more than 60 s old, locked or not. Draw an as-of `HH:MM` just left of the lamp (x≈560–630, y 508) in #8A7360. It only changes when a poll lands, so it costs 0 extra frames. `dozing()` sets the label to `PRs MERGED · TUE`. At night the stamp can be up to 15 min old, because night polling is every 15 min. | S |
| F2 | Every screen by day: swipe vs pet | A swipe still moving 350 ms after touchdown becomes a pet drag. A cat flies out and bursts, and the screen doesn't change. Index-finger swipes on the back of the phone often take that long. | A pause decides. Start the pet only if the finger rested ≥250 ms before crossing the touch slop. Anything that moves straight away is judged at UP as a swipe (\|dx\|>90, \|dx\|>1.5\|dy\|), however long it takes. TICK when the pet spawns. The verb is "HOLD, THEN DRAG". | S |
| F3 | Merge found while the front screen is in use | This is the most common case. With the front on, the rear sits in DOZE, so `isStill()` is false and the whole celebration renders at 60 fps at doze brightness with nobody watching. The buzz makes you flip the phone, your first double-tap only wakes the rear, and you arrive at the tail end with input locked. | Play live only if the rear is STATE_ON at detection. Otherwise buzz and chime, settle the numbers at once, and park the event and its shows. On the next STATE_ON edge, run `replay()` if the merge is under 2 min old, otherwise #8's peek. This also saves 5–11 s of 60 fps rendering per unseen merge. | S |
| F4 | After a merge: showcases vs the 10 s window | The chain outlasts the window: a goal merge plus a badge takes ≈16 s from detection, a Friday adds the recap for ≈23 s, and detection itself lands ≈4 s after the wake. `dozing()` → `settle()` then clears the queue. The badge was already saved silently. `recapWeek` is written when the recap starts, so a cut recap is gone for the week. Night drops everything. | Budget per section 6: at most one show per merge, picked by rarity, each ≤3 s and only if it ends before the deadline. Replace `shows.clear()` with a persisted unseen ledger: an unplayed badge shows NEW on its cell, and an unplayed recap stays due. Write `recapWeek` when the show ends. | M |
| F5 | CATS vs WEEK vs TODAY labels | CATS' "122 THIS WEEK" is the rolling 7 days, while WEEK's 33 runs Monday to now. TODAY calls the same 122 "7 DAYS". The demo caption repeats the mix-up. | Make CATS the calendar week: filter `snap.prs` to `mergedAtMs ≥ startOf(Monday)` before `pile()`, so the header reads `CAT COLONY · 33 THIS WEEK`. TODAY's `7 DAYS` becomes `LAST 7 DAYS`. Fix the demo caption. I chose this over just renaming the header because it matches WEEK, and a smaller pile early in the week gives #1 bigger cats to poke. | S |
| F6 | During a moment and the showcase chain | Every touch is dropped, with no sign, for 5–11 s per moment plus 3–7 s per show. A poll can cut away from an open card into an alert or the recap. Frustrated tapping keeps the panel lit while the app ignores it. | Touch always wins (section 4 layers). During a show: a tap skips to TODAY, a swipe ends the chain and moves. During a moment: a swipe settles the numbers and moves, a tap blinks the lamp (until #10 lets it pop a cat). Alerts and the recap wait 3 s after the last touch and never start while a card is open. No long-press while busy. | S |
| F7 | Taps everywhere: 300 ms wait, global double-tap | Every tap lags 300 ms. Any two taps within 300 ms, on any screen, run `replay()`: poking cats, tapping two badges, opening and closing a card, or a habitual wake double-tap. That locks you into a 5–11 s celebration. | Double-tap replay on TODAY only. The other 7 screens act at ACTION_UP with no wait. | S |
| F8 | Long-press opens Setup | An 800 ms still finger anywhere, even mid-celebration, opens Setup on the face-down front, with nothing on the rear to say so. Rest-then-drag fires both the pet and Setup. While locked, Setup turns up at the next unlock. Once signed in it is the only way into Setup. | Raise it to 1.5 s. On fire: LONG_PRESS haptic plus `SETUP · ON YOUR PHONE` (or `UNLOCK YOUR PHONE FOR SETUP`) for 3 s, reverted with `at()`. No pet after `longFired`, none while busy. Add a static "Setup" launcher shortcut; check that it can target the non-exported SetupActivity. | S |
| F9 | OPEN rows | The meta line is `REPO · #N · STATUS`, ellipsized at x 372, so the status is cut on all 5 rows. Red covers three different statuses, so the jewel can't tell them apart. | Put the status first, in short words: `FAILING · #2729 · API` (READY, AUTO, FAILING, CONFLICT, CHANGES, RUNNING, WAITING, DRAFT). About 307 of the 324 px slot. | S |
| F10 | OPEN header vs its tubes | The header says `OPEN PRs · 105`, but the tubes sum to 44 (only the 50 newest, minus drafts). NEEDS YOU is argon, which means "deletions" on every other screen. | Header = the tube sum (`OPEN PRs · 44`). Add a footer `+6 DRAFTS · +55 OLDER`. Draw NEEDS YOU in `Gas.NEON` at lvl 1. | S |
| F11 | OPEN counts | The counts look like controls, but a tap goes to the next screen (which is TODAY). Only 5 rows are ever listed, so 8 of the 10 NEEDS YOU PRs and all 31 WAITING can't be reached. | A tap on a count band (x>392, ~264×158 px) filters the rows to that colour, with the header reading `NEEDS YOU · 10`. Tap again for the next 5; after the last page the filter clears. The header strip still goes next. | S |
| F12 | What a tap means per screen | A tap means next, open, wake or replay-recap depending on the screen. WEEK is a dead end, so BADGES, CATS and OPEN can only be reached by swiping. | One rule: a tap on a target acts on it, a tap anywhere else goes next. WEEK's tap goes next (entering it already replays the recap). CATS is the one screen where a tap never navigates, and its hint says so. | S |
| F13 | Day strips on STREAK, WEEK, LONG | A filled dot means "merged" on STREAK but "goal met" on WEEK. An orange letter means "today" on LONG and STREAK but "had merges" on WEEK. LONG runs W…T (rolling), the others M…S. | One grammar: orange letter = today only, filled dot = merged, ring = goal met. WEEK uses `dayPaint` for past letters and STREAK's 6 px dot and 11 px ring. Drop STREAK's week jewels, since WEEK owns the strip. LONG's rail is replaced in wave 3 (#18). | S |
| F14 | Text on RECENT/OPEN rows, detail cards, badge names | The text that carries information is 0.7–0.85 mm tall. Row meta uses LABEL #5E4C3E (≈2.6:1), which drops to ≈1.3:1 after the 0.4 night/stale dim. Cards leave ~190 px empty. | detail 18→22, title 21→24, focus 24→28, badge names 15→18. Row meta moves to a 19 px paint in #8A7360 (≈4.7:1) with 0.08 em tracking. Silk/LABEL stays for screen headers only. Rows still fit the 86 px pitch. | S |
| F15 | Catch-up poll on a wake | The merged search (~4 s), the open search (~4 s) and the generated-file check all run before `publish()`, so 8–9 s of the 10 s window is gone before the cats appear. | Publish right after the merged search plus the generated check (`withOpen = false`). Then run the open search on the same executor and publish again; that second publish only rolls. Needs the open query as its own call. | S |
| F16 | After a reboot (Shizuku not running) | The meter sits on the front with no explanation. "Start Merged" later resumes that front task and never moves it, because the move only runs in `onCreate`. "Grant" does nothing while Shizuku is off. | In `onResume`: if on display 0, RearRouter is READY and no move is pending, post `moveToRear`. In Setup, the button becomes "Open Shizuku" while it's off (add a `<queries>` entry). | S |
| F17 | Getting home, knowing where you are | No position cue, and the only one-step home is a double-tap replay. The documented 20 s return doesn't happen: with the front off a card vanishes at 10 s, and with the front on the dim screen stays. | Go home when the rear leaves STATE_ON: front off → `settle()`, front on → `goHome()`. Delete `armReturn` and its mentions (README:13, SPEC:318, V2:13). Position comes from #14's pips. | S |
| F18 | STREAK | Today's count shows 3 times (plus on TODAY). The TODAY tubes outshine BEST. Matching your best streak isn't marked. BEST really means "since install". | The TODAY bank becomes `TO GO` (goal − today), then `OVER GOAL` with the surplus. When streak = best: `BEST STREAK · NOW` in eventPaint with bestBank at lvl 1. Otherwise `BEST · SINCE <first history day>`. | S |
| F19 | RECENT highlighted row | The glowing title runs into THIS PR's `+`. | Ellipsize titles at 330 px and meta at 356. | S |
| F20 | WEEK summary | `GOAL 1 / 2 DAYS` on a Tuesday reads as a miss, though today isn't over. | Count today in `workdaysSoFar` only once it meets the goal or the day ends. | S |
| F21 | CATS at night | A tap does nothing and nothing acknowledges it. | Show `CATS ASLEEP TILL 07:00` in the header for 2 s. Once #1 ships, show the tag only. | S |
| F22 | First run | Start is active before sign-in and just bounces back. After Start, Setup finishes and you land on the home screen with no hint to look at the back. "Token saved ✓" doesn't show the login. The first fetch takes 10–20 s. | Disable Start until a token exists. Keep Setup open after Start, reading `On the back screen ✓ — flip the phone and double-tap it`, plus the gesture legend. Publish before the backfill (F15). | S |
| F23 | Signed out, token expired, offline | A 401 wipes the token and blanks the whole face, doze included, although the stats are still true. Taps are dead (busy while `shown == null`). Offline on a first run shows blank tubes and no message. | #15's precedence order. On AuthError, keep the snapshot cooled under `TOKEN EXPIRED · TAP FOR SETUP`. Any tap opens Setup, with the rear confirmation from F8. `markStale()` with no snapshot shows `NO CONNECTION · RETRYING`. Return early from `signedOut()` if already signed out. | S |
| F24 | Screen order | ALL-TIME, which changes least, is one step from the doze face; RECENT is two. | TODAY → RECENT → STREAK → WEEK → ALL-TIME → BADGES → CATS → OPEN. Update SPEC §14 and V2. | S |
| F25 | When the buzz and chime land | While you're watching, they fire at detection. The climax 2–5 s later (island strike, digits, XL flashover) is silent. | Only when the rear is STATE_ON at detection: mew at launch, and `at(t0) { buzz(tier) }` at the strike, skipped on replays. When parked, keep the immediate buzz; it's what makes you flip the phone. | S |
| F26 | Sound and haptic salience | S merges, the most frequent event, get a full 45 ms pulse plus a chime. Badge, century and READY get nothing. | S: 15 ms tick at amp ~70, no chime. M: merge_small. L/XL: merge_big. Badge unlock: sparkle sting plus CONFIRM. Goal surge: CONFIRM only, since the goal merge is already bumped to L with merge_big. | S |
| F27 | S-tier timing | One cat roams 1.8 s, so the digit lands 2.1–2.6 s after detection, and the most-seen animation goes stale first. | Your call; I recommend it, and it also frees budget: `lead = 0` for S in `playNext`, so the digit lands in ~315 ms. | S |
| F28 | CATS tap-to-wake | A casual poke launches 16 cats with fire and drips for ~3.6 s, close to merge size. | Home flights are rainbow only with half-length trails: no tongues, flames or drips. At most 6 cats, 1.2–1.6 s, no loop-the-loops. | S |
| F29 | Pet release | Any slow release is relaunched at 700 px/s and bursts within 0.7 s, so you can't set the pet down. | Below ~700 px/s it flies home into the island with a TICK. Faster, it bursts with POP. Tune the threshold on the device. | S |
| F30 | Detail cards | They cut in and out hard; everything else strikes or crossfades. | Strike the header and title in with `strikeCurve()` over 220 ms. | S |
| F31 | CATS colony looks | About 55 of 60 cats are lemon, because the repo hashes collide. | Repo-rank coat (#6): the top 3 repos this week get 3 frostings, the rest a 4th. One map, shared by `pile()` and the flyby. | S |

## Screens after the additions

Still 8 screens and no 9th: records move onto ALL-TIME. The order runs back in time to the left of TODAY and to pending work on its right.

| # | Screen | One job | What changes |
|---|---|---|---|
| 1 | TODAY | What did I ship today? (the doze face) | As-of stamp; `PRs MERGED · TUE` while dozing; `LAST 7 DAYS`; hero-label precedence (#15); #8's peek cat; the only screen with double-tap; its `BEST` label means beating BEST DAY (#18). No pips. |
| 2 | RECENT | What just landed? | Moves up from 3rd; bigger text with information-tier meta; titles ellipsized at 330; taps act at UP; card strike-in; hint `TAP A ROW`. |
| 3 | STREAK | Am I on track today? | `TO GO` / `OVER GOAL` replaces the TODAY bank; `BEST STREAK · NOW` / `BEST · SINCE …`; week jewels removed; #9's rocket in the goal surge only. |
| 4 | WEEK | How is this week going (Monday to now)? | The only Mon–Sun strip, in the shared mark grammar; fixed goal denominator; #20's last-week counts plus `+6 VS LAST WEEK`; tap goes next; the recap plays on entry, or opens an idle Friday wake. |
| 5 | ALL-TIME | The odometer and my records | The rolling 7-day rail becomes one row of records (#18): BEST DAY · BEST WEEK · BIGGEST PR · FASTEST MERGE, labelled `SINCE <date>`. The milestone roll-over happens inside CENTURY's XL moment. |
| 6 | BADGES | What I've earned, what's next | #17's progress; 18 px names; NEW on unplayed unlocks, which then play in their cell when you visit; hint `TAP A BADGE`. |
| 7 | CATS | This week as cats | Calendar week (matches WEEK); repo-rank coats; poke, tag and card (#1); rub (#2); laser (#11); arrivals on visit (#3+#4); READY kittens on a shelf (#7), next to OPEN in the loop; calmer wake-all; night label. |
| 8 | OPEN | What's about to land, what needs me | Status-first rows; header = tube sum, plus a drafts/older footer; NEEDS YOU in neon; counts filter and page; merged ids filtered out; `RESCUED` status word and #19's show. |

Rules every screen follows:
- **One word per time window.** TODAY · LAST 7 DAYS (rolling) · THIS WEEK (Monday to now) · SINCE `<date>` (from history) · ALL-TIME (GitHub lifetime). Every new number names its window in these words.
- **One mark, one meaning.** Orange letter = today. Filled dot = merged that day. Ring = goal met. Green/amber/red jewel = open-PR status only. Neon = PRs and additions. Argon = deletions. EVENT colour = something just happened (tag, NEW, record). The current-screen pip is a neon core, not the amber jewel.
- **Two text tiers.** Ornament is silk 17 px LABEL, for screen headers only. Information is ≥19 px at ≥4.5:1 (#8A7360 or brighter), ≤0.08 em tracking, and dims less than ornament at night or when stale. Row meta, card lines, tags, progress and the doze stamp are all information.
- **Header = `NAME · the one number it explains`.** A transient tag or hint may replace the suffix for up to 4 s. Nothing else gets appended: no mood, no rare count, no rescue score, no legend.

Not added: a RECORDS screen (it lives on ALL-TIME), and ia's READY / NEEDS YOU jewels on the doze face. The as-of stamp takes that slot, OPEN is one swipe away, and open data can be 5 min old.

## Gesture grammar

**Layers, checked top-down (first match wins):**

| Layer | Tap | Swipe | Everything else |
|---|---|---|---|
| DOZE (rear dim) | HyperOS: the first double-tap only wakes the rear; the face is TODAY | — | — |
| MOMENT (flyby, fire, roll; always on TODAY) | Within ~90 px of a flying cat: POP (#10). Elsewhere: lamp blink. | Settle the numbers, then move | Double-tap, hold and long-press ignored |
| SHOWCASE (goal, badge, rescue, alert) | Skip the chain → TODAY | End the chain, move one screen from the show's screen | Ignored |
| CARD (row, badge or cat card) | Anywhere: close | Leave the screen | Ignored |
| SIGNED OUT / TOKEN EXPIRED / NO DATA | Any tap: Setup on the front, plus a rear label | Moves if there's cached data | — |
| NIGHT (quiet hours) | Screen rules. CATS: tag plus a static `!`, no hop; wake-all shows `CATS ASLEEP TILL 07:00` for 2 s. | Normal | Hold-then-drag falls back to swipe; no pet, laser or flight |
| SCREEN | Table below | | |

**Screen × gesture:**

| Screen | Tap | Double-tap | Swipe | Hold → drag | Long-press (1.5 s still) |
|---|---|---|---|---|---|
| TODAY | Next (waits 300 ms for a second tap). While #8's cat peeks, a tap at x<160, y>400 replays at once. | Replay the last merge | Prev / next | Pet cat; a slow release flies home, a fling bursts into fireworks | Setup |
| RECENT | Row (x<392) → card; elsewhere → next | — | Prev / next | Pet | Setup |
| STREAK | Next | — | Prev / next | Pet | Setup |
| WEEK | Next (arriving already replays the recap) | — | Prev / next | Pet | Setup |
| ALL-TIME | Next | — | Prev / next | Pet | Setup |
| BADGES | Badge → card; header or bottom strip → next | — | Prev / next | Pet | Setup |
| CATS | Header strip (y<52) → wake all. Nearest cat or kitten within 60 px → poke plus tag. The same one again while tagged → its card. Nothing in range → wake all. Never navigates. | — (two taps = two pokes) | One-way → prev / next. Moving at once and reversing within 70 px of a cat = RUB (purr). | Laser dot; the 3 newest cats chase it and fly home on release | Setup |
| OPEN | Row → card. Count (x>392) → filter to that colour; again → next 5; past the end → clear. Header strip → next. | — | Prev / next | Pet | Setup |

**The rules behind it:**
1. **A pause decides.**
   - Moving within 250 ms of touchdown is judged at UP: one-way with \|dx\|>90 and \|dx\|>1.5\|dy\| is a swipe, at any speed or duration. On CATS, a first reversal within 70 px of the cat under the finger is a rub.
   - Still ≥250 ms, then moving, is hold-drag (TICK on spawn).
   - Still 1.5 s with no movement is Setup.
   - No movement before UP is a tap.

   So swipe vs drag and rub vs laser are split by the pause, swipe vs rub by the reversal, and drag vs long-press by moving before 1.5 s.
2. **A tap acts on what's under it.** It acts at UP. With no target it goes to the next screen, except on CATS, where it wakes the colony. Double-tap exists only on TODAY, so only TODAY waits 300 ms.
3. **Touch always wins.** The layers replace the single `busy()` gate. A swipe always leaves, every tap gets a response, and nothing is dropped silently. Shows not caused by a merge wait 3 s after the last touch and never start over a card.
4. **Every touch answers within a frame, and everything cleans up before doze.** An accepted touch gets a TICK or a visual change. A refused one gets the lamp blink, which can only happen during a moment, always on TODAY. A touch that opens something on the front gets LONG_PRESS plus `SETUP · ON YOUR PHONE`. Tags, hints and labels revert via `at()` within 4 s. The app goes home when the rear leaves STATE_ON.

Cut from the grammar: shake (#12), a vertical fling home (the dim already sends you home), a tap zone on the lamp (#15), tappable pips (#14). Rejected: ia's "taps never navigate", because on the back of the phone a tap is the easiest way round the loop. Also rejected: ia's "no rear long-press". With feedback, 1.5 s and the pause rule it can't collide with anything, and it's the fallback if the launcher shortcut can't reach SetupActivity.

## The cats as one character

**Personality:** sleepy workshop cats, proud of what you shipped. They are asleep by default, never animate unprompted and never ask for anything (no hungry or sulking cats). They answer in proportion to how you touch them, and fire only appears with shipping.

**Poses.** Shapes do the work, because on an S sleeper one sprite px is ~1.9 stage px and faces don't read:

| Pose | Status | Used by |
|---|---|---|
| SLEEP | exists | The pile |
| HEAD-UP | new | Cats new since your last visit (#4), neighbours of an arrival (#3) |
| SIT | new | A poked cat after its hop (#1), READY kittens at 0.45× (#7) |
| FLY (2 frames) | exists | Flyby, wake-all, pet, laser chasers, arrivals, goal-surge rocket |

That is 4 poses × 4 frostings = 16 bitmaps, baked in `colony()`. Rare and seasonal coats are palette swaps of the same bitmaps. Dropped: YAWN, STRETCH, `^ ^` eyes, belly-up, ear and tail flicks, a sitting-up HUNGRY cat.

**Layer precedence per cat** (one item per layer, top wins):
1. **Glyph above the head:** `!` (just poked) > heart (being rubbed, at most 4 alive) > z (asleep). The z hides while a glyph shows.
2. **Coat:** rare gold crust or ghost at α .55 (0.5 %, decided by the PR id) > seasonal (pumpkin, Oct 25–31) > repo rank (top 3 repos this week get 3 frostings, the rest a 4th). One map per snapshot, so a cat wears its repo's colour on the pile and in the flyby.
3. **No head overlays.** At ~1 mm they don't read, and #1's tag names the repo, trait and rarity.

Status lamps belong only to open-PR kittens and sit on the shelf under them, never on a sprite. Header text never touches a sprite. Fire (flaming butt, tongues, drips) appears only on a merge flyby, the goal-surge rocket and a fling you caused. Every other flight is rainbow only, with a half-length trail.

**Sound palette.** Everything goes through one `quiet()` gate: ringer, DND and quiet hours.

| Event | Sound | When |
|---|---|---|
| S merge | none | Tick haptic only |
| M merge | merge_small | At the strike when you're watching, at detection when parked |
| L / XL merge | merge_big | Same |
| Flyby launch | Mew at the tier's pitch | Only when watching |
| Badge unlock | Sparkle sting (render `sparkle()` from make_sfx.py to a new raw file) | At the strike frame |
| Rescue (#19) | merge_small, rising C6→G6 | Same chime family |
| Poke (#1) | Mew | First poke per cat per visit; at most one mew per 2 s |
| Goal surge, rub, pop, land, laser, wake-all, replay | silent | Haptics carry them |

The mew is the existing synth at rate [1.5, 1.2, 1.0, 0.8] by tier, detuned ±3 % from the PR id. Raise SoundPool `maxStreams` from 1 to 2, and never start a mew while a chime plays. There is no audio purr.

**Haptic vocabulary.** One `feel(kind)`, rate-limited to 1 per 120 ms:

| Kind | Spec | When |
|---|---|---|
| TICK | `performHapticFeedback(CLOCK_TICK)`, follows the system setting | Tap accepted, card open/close, swipe, pet spawn, poke, peek tap |
| PURR | Waveform 0/14/26 ms, amp ≤60 | While a rub is claimed (night included) |
| POP | 20 ms, amp 255 | A burst you caused: flyby pop, pet fling |
| LAND | 25 ms, amp 60/100/140/180 by tier | Once per landing event, even when 6 cats land |
| CONFIRM | `performHapticFeedback(CONFIRM)` | Goal surge, badge unlock, rescue strike |
| LONG_PRESS | `performHapticFeedback(LONG_PRESS)` | Setup opened on the front |
| MERGE | Existing tiered pattern; S = 15 ms at amp ~70 | Real merges only, never replays |

Check on the device that HyperOS lets USAGE_TOUCH vibrations through; `buzz()`'s comment says it drops notification-tagged ones.

**Frequency sets loudness.**
- More than 5 times a day (S merges, pokes, swipes, taps): tick, no sound.
- Daily (M/L merges, goal): chime plus tiered buzz.
- Weekly or rarer (badge, century, XL, rescue): sting plus fire.

Touch responses are acknowledged within 100 ms. Transitions take under 400 ms, character responses at most 1.5 s, colony flights at most 2 s. Only merges run longer.

## Merge moment timing

**Deadline:** D = max(rear STATE_ON edge, last touch) + 9.5 s. Hard-code the 10 s `subscreen_display_time` as a calibration constant. Don't extend it through Shizuku `userActivity`. A live sequence starts at S0, which is detection when you're watching, or the STATE_ON edge for a parked replay.

| Slot | Starts | Max length | Notes |
|---|---|---|---|
| Wake → detection | W | ~4 s on a catch-up poll (merged search only, after F15); 0 for a parked replay | #8's peek fills this wait when a missed merge is already known |
| Flyby | S0 | Natural length (S ~2.0, M ~2.9, L ~3.5, XL ≤5.2 s). ≤3.0 s when a show follows. Shrinks to fit, floor 1.2 s, otherwise skipped. S: skipped (F27, recommended). | Mew at launch, only when watching |
| Strike + fire + hero | t0 | Hero lands at t0 + 0.3 s | MERGE haptic and chime here when watching |
| Numbers (banks roll) | t0 | S 0.6 · M 2.9 · L/XL 4.0 s | Never cut |
| Label hold | End of numbers | 0.8 s when a show follows, otherwise until min(t0 + 6 s, D) | Carries the news: `GOAL MET · 10/10`, `NEW RECORD · BEST DAY 31`, `PR #2,500 · ALL-TIME` |
| One show | End of hold | Badge 3.0 · goal surge 2.5 (with #9) · rescue 2.5 · alert 3.0 s | Picked by rarity: badge > goal > rescue > alert. Starts only if it ends before D. |
| Home | End of show | 1 frame | TODAY, labels reverted |
| D | Wake or last touch + 9.5 s | — | `settle()`; anything unplayed goes to the ledger |
| Dim | Wake or last touch + 10 s | — | Front on: `goHome()`. Front off: `dozing()`. |
| Doze | ~16 s after wake | — | DOZE_SUSPEND: TODAY, as-of stamp, `PRs MERGED · TUE` |

**What gives way near the deadline**, first to last:
1. The label hold shrinks to 0.8 s.
2. Open alert: parked, and replayed only if it's still true.
3. Rescue: parked.
4. The flyby shrinks to 1.2 s.
5. Goal surge: dropped. The label says `GOAL MET`, and STREAK shows it.
6. The flyby is skipped.
7. Badge unlock: parked, with NEW on its cell; it plays from the peek tap or on a BADGES visit.
8. The numbers are never cut.

**Never in a merge chain:**
- **Recap:** only opens an idle wake with ≥7.5 s left (Friday from 17:00, or the weekend). `recapWeek` is written when it finishes.
- **Arrival (#3):** plays on the next CATS visit.
- **Records and milestones (#18):** shown as label-hold text, and the CENTURY XL moment.
- **At night:** no flyby and no shows; anything earned goes to the ledger and plays on the first wake after quiet hours.

**Parking and the ledger.** A moment that couldn't play while the rear was STATE_ON gets its buzz at detection, its numbers settled at once, and a ledger entry (persisted in prefs `state`). On the next STATE_ON edge: under 2 min old, it auto-replays through `replay()` with the full 9.5 s. Older (until midnight), you get #8's peek. Tapping the peek replays the merge and then the parked shows. The touch resets D, so they get a fresh window. An item is marked seen only when it finishes while the rear is STATE_ON, or when you visit its screen.

**Worked examples:**
- **Goal-hitting L merge plus a badge, replayed on wake.** Today: 3.5 s flyby + 6.2 s + 3.4 s goal + 3.3 s badge = 16.4 s, cut at ~10 s, badge lost. New: 1.7 s flyby + 4.0 s numbers + 0.8 s hold (`GOAL MET · 10/10`) + 3.0 s badge = 9.5 s.
- **The same merge found by a catch-up poll at W+4 s.** 5.5 s are left. No flyby, 4.0 s numbers, 0.8 s hold. The badge is parked and shows NEW until it plays.
- **M merge with nothing queued, watching.** 2.9 s flyby + 6 s moment = 8.9 s.

## The 20 additions, revised

| # | Idea | Verdict | What changes |
|---|---|---|---|
| 1 | Tap a cat, meet its PR | modify | Acts at UP. Hit the nearest centre within 60 px, at y ≥ 52; the header strip wakes all. I chose 60 over the skeptic's 45 because a fingertip is 130–150 px, nearest-centre picks one cat anyway, and the tag says which. Hop: a 380 ms parabola on the sleeper plus `!` and TICK, no fire. Tag in eventPaint in the header (`API · #482 · +46 −55 · 2H AGO`), reverted in 4 s. The same cat again opens a card that replaces the colony. Mew only on the first poke. No repo-mate ear flicks. Skip hit-tests while flyers are airborne. Night: tag plus a static `!`. |
| 2 | Stroke a cat, it purrs | modify | Called RUB. Claimed at the first direction reversal within 70 px of the cat under DOWN, with the finger moving within 250 ms; a one-way move is a swipe. Hearts in the glyph slot (one per ~150 px rubbed, at most 4), plus PURR. Drop the `^ ^` eyes, tail flick, belly-up and audio purr. Night: haptic only. |
| 3 | The new cat comes home | merge → #4 | Not a post-merge show. The chain has no time left, and the flyby has just shown that cat; feel wanted it first in the chain, and this overrules it. In the flyby, the PR's own cat dives back into the island instead of bursting (M and up). On the next swipe into CATS, up to 3 unseen cats fly out of the island (or off #7's shelf) and curl up, with one LAND. |
| 4 | Colony stirs on visit | modify | Driven by data: only cats merged since your last CATS visit react (arrivals from #3, then up to 6 HEAD-UP swaps via `at()`). Nothing new means nothing moves; random stirs get stale by week 2. Only from `nextScreen()`. Drop YAWN, STRETCH and the drifting z (it steps once). |
| 5 | Colony moods and a food bowl | cut | The bowl repeats STREAK's bargraph at 3×1.4 mm. HUNGRY and SULKING are guilt moods. Party hats fight the coat layer. Feel and gestures wanted the bowl as goal progress; "one fact, one home" wins. |
| 6 | Cats wear their PR | modify | Coat only: repo rank (F31), rare gold or ghost at 0.5 %, pumpkin coat Oct 25–31. No outfits, witch hats, legend or `RARE N`; #1's tag names the trait and rarity. Feel wanted 3 rare head overlays; at ~1 mm they don't read. |
| 7 | Open PRs as kittens on the shelf | modify | READY and auto-merge PRs only, at most 5, in a static SIT pose at 0.45×. They go in the empty band at y 56–110 (the pile tops out at y≈165 today), with a green `drawJewel` r4 on the shelf under each. Five keeps it a shelf, not a second OPEN board; feel said 3, glance said 8. Filter out merged ids, which also fixes OPEN's stale row. A tap shows a tag, a second tap opens OPEN's card. When it merges, the kitten is the one that arrives (#3). |
| 8 | A cat tells you what you missed | modify | Fed by the ledger. It triggers when a moment didn't play while the rear was STATE_ON, which covers the front-on case. Under 2 min old: auto-replay. Older: a ~3 s peek with `TAP THE CAT · REPLAY`, started while the wake poll is still running. A tap at x<160, y>400 replays the merge and then the parked shows. It does not summon the pet: the peek is about the missed merge, and `petDown` would leave `petHeld` stuck. TICK, no sound, label reverted before doze. |
| 9 | Hamster cat in the goal bar | modify | Only in the goal surge: the cat rockets out of the right end with fire, then a puff, with no second firework. No runner on ordinary fills and no resting sleeper. Cats appear on instrument screens only at earned moments, and the fill plays on every visit. Nothing at night. The surge is capped at 2.5 s. |
| 10 | Pop the flyby cats | keep | Hit-test at DOWN with a radius ≥90 px, against the pose at `downAt` and `downAt − 50 ms`. Use a separate cut time; don't shrink `dur`. POP haptic. A clean sweep needs ≥2 cats. Schedule `play()` as one `at()` step, so a sweep pulls the strike to ~250 ms after the last pop and leaves no dead air. Misses blink the lamp. |
| 11 | Laser dot for the colony | modify | Hold-then-drag anywhere on CATS starts it, in place of the pet there. The 3 newest cats chase it, rainbow only with short trails, each with its own history ring. Pounce: a hop plus LAND. Release: they fly home. Drop the yarn ball. Off at night. |
| 12 | Shake and tilt the cat jar | cut | Shake repeats wake-all and can't be done with the phone face-down on a desk. Flipping the phone over looks like a shake. The sensor can outlive a Greeze freeze. ia's easter-egg version is cut for the same reasons. |
| 13 | Feels like a real instrument | modify | Ship the instant tap first: taps act at UP off TODAY, and double-tap works on TODAY only. `feel(kind)` as in section 5. Transition: power down tubes and labels in ≤150 ms (don't redraw the old screen; `leave()` has already emptied it), then a 220 ms strike whose wave follows the swipe direction. Card strike-in (F30). The lamp blink is only for refused taps during a moment. |
| 14 | Hints that teach, then vanish | modify | Pips: r3 at a 16 px pitch, y ≤ 568. Neon core marks the current screen, not amber. None on TODAY, and they're never tap targets. No "news" pips; the peek carries news, and amber already has three meanings. Hints use the gesture table's verbs. The CATS hint replaces the count suffix until learned, then becomes `TAP HERE · WAKE ALL`. The replay hint lives in #8's peek, not on TODAY. Hints only while STATE_ON, never during a moment or show. The first-run legend goes in the front Setup. |
| 15 | Tells you what's wrong | modify | Label precedence: SIGNED OUT > TOKEN EXPIRED > OFFLINE (no cache) > WARMING > STALE > LIVE. AuthError keeps the snapshot cooled. Any tap in those states opens Setup, with the rear confirmation. Setup: "Open Shizuku" with `<queries>`, a `viewer{login}` check off the main thread, `Signed in as @login ✓`. Cut the QS tile (it can't start Shizuku after a reboot) and the lamp card (a corner zone would steal TODAY's tap; the as-of stamp and the label cover it). |
| 16 | Settings worth having | modify | Bigger text by default instead of a switch (F14). Quiet hours become the single source for `isNight()`, `pollMs()` and parked replays, cached rather than read each frame. Drop the chime/buzz toggles (ringer and DND cover them), the loop filter (it breaks pips and shows), the return delay (it has no effect, because the rear goes home at dim) and the Large-text switch. |
| 17 | Badges show how close you are | keep | Progress line on the card. On the shelf, only under locked badges and LABEL-dim; earned badges keep `×N` in neon. `EARNED THIS RUN` for ON_A_ROLL. Skip PRs with `createdAtMs == 0`. `NEXT CHANCE · FRI FROM 5 PM` on the card only. |
| 18 | Records and milestones | modify | No 9th screen and no new show. ALL-TIME's rolling rail becomes one row of small tubes: BEST DAY (2) · BEST WEEK (3) · BIGGEST PR (4) · FASTEST MERGE in minutes (3), 12 tubes where the rail holds 14, each labelled `SINCE <date>`. BEST STREAK stays on STREAK, and TODAY's `BEST` means beating BEST DAY. A broken record goes into the moment's label hold, held back for the first 4 weeks. The milestone odometer folds into CENTURY's XL moment. No year-to-date count. Gestures' flip face is rejected: it's a hidden mode on a tap that otherwise means next. Glance's "scatter them across screens" is rejected: each record needs one home. |
| 19 | Green-light rescue | modify | Trigger on checks FAILURE/ERROR → SUCCESS for ids in `redSeen`. Fast path: a `nodes(ids:)` query for those ids (~1 point). Suppress `alertShow` for that PR. An alert-class show, ≤2.5 s, only while STATE_ON; otherwise parked, and dropped if the PR goes red again or merges. No header counter; `RESCUED` is the row's status word. The paw is #8's peek sprite, the ding is merge_small rising, plus CONFIRM. |
| 20 | Ghost digits: you vs last week | modify | No ghost digit in the hero tubes. In the day strip, unrevealed columns show last week's same-day count in a third style, then this week's count strikes over it (350 ms per day, unchanged). `+6 VS LAST WEEK`, compared to the same weekday and written without ▲, goes on its own line at y≈540. |

## Build order

**Wave 1: an honest face and predictable touch** (all S, no new art)
- F1: freshness on wake, as-of stamp, day-name doze label.
- The gesture core, which is the grammar minus the cat gestures: F2, F6, F7, F8, F12, F29, and #13's instant-tap half.
- Home on dim, delete `armReturn` (F17). Screen reorder (F24).
- Labels and legibility: F5, F9, F10, F11, F13, F14 (#16's size bump), F18, F19, F20, F21.
- Plumbing and Setup: F15, F16, F22, F23 with #15's rear states and Setup diagnosis, plus the launcher Setup shortcut.

**Wave 2: the 10-second budget and the feel**
- F3 parking, F4 one-show budget, and the unseen ledger (NEW on badge cells, recap stays due, `recapWeek` written at the end).
- #8's peek, built on the ledger.
- `feel(kind)` and `quiet()`: F25, F26, and F27 if you agree.
- #10 pop the flyby cats, the input layer for the moment.
- #13 transitions (direction-aware) and F30 card strike-in.
- #14 pips and hints, #17 badge progress, F28 calmer wake-all, #16 quiet hours.

**Wave 3: the cats as one character, then new data on existing screens**
- Shared bakes (HEAD-UP, SIT), `Sleeper.pr`, the repo-rank coat (F31) plus #6's rare and seasonal coats.
- #1 poke, tag and card → #3+#4 arrivals on visit → #2 rub → #11 laser.
- #7 READY kittens (needs #1's tag and the arrivals). #9 surge rocket.
- #20 last-week counts on WEEK → #18 records row in place of ALL-TIME's rail, the TODAY `BEST` meaning, the milestone in CENTURY.
- #19 rescue (uses the ledger's parking and #8's paw sprite).
