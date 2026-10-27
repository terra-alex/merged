# Merged

**Your merged PRs, as glowing Nixie tubes on the back of your phone.**

Merged turns the small rear screen of the Xiaomi 17 Pro Max into a desk-counter-style instrument for your GitHub output: PRs merged today, lines added and removed, streaks, badges, and a colony of pixel cats. Every merge is an event: cats with flaming butts zoom out from under the camera, then the tubes catch fire. It runs on the stock dim always-on display, and draws 0 frames at rest.

<p align="center"><img src="docs/media/merge.gif" width="640" alt="A merge: pixel cats zoom across the rear screen, then the Nixie tubes catch fire"></p>

<p align="center"><b><a href="demo/merged-demo.mp4">▶ Watch the 68-second demo, with sound</a></b></p>

## Screens

Swipe left and right through eight views. Anything but Today returns there after 20 s, and the dim always-on face is always Today.

| | |
|---|---|
| <img src="docs/media/today.jpg" alt="Today"><br>**Today.** PRs merged today in two big tubes, lines added (neon) and removed (argon violet), and the rolling 7-day count. | <img src="docs/media/streak.jpg" alt="Streak"><br>**Streak.** Your workday streak (weekends never break it), your best, and an IN-13-style bargraph filling towards today's goal. |
| <img src="docs/media/week.jpg" alt="Week"><br>**Week.** A recap that replays day by day: PRs, lines, the best PR, goal days, badges. It plays by itself once a week from Friday 17:00. | <img src="docs/media/badges.jpg" alt="Badges"><br>**Badges.** Eight achievements drawn as Nixie cathode icons, each with its own unlock animation: Net Negative, Early Bird, 10K Day, Friday Ship, Speedrun, Goal Met, On a Roll, Century. |
| <img src="docs/media/cats.jpg" alt="Cats"><br>**Cats.** A sleeping pixel cat for every PR merged this week, sized by PR and coloured by repo. Tap and they fly round and go back to sleep. | <img src="docs/media/open.jpg" alt="Open PRs"><br>**Open PRs.** Status lights for your open PRs (ready, waiting, needs you), with an alert when one turns green or red. |

Plus **All-time** (lifetime count and a 7-day rail) and **Recent** (your last 5 merges as a departures board; tap one for its details).

## What happens when a PR merges

- **Cats, then fire.** One to five cats with rainbow trails and flaming butts roam the screen and burst into firework mini-cats. Then the camera island strikes, a burner rail lights, and the changed digits catch fire. The bigger the PR (S, M, L, XL), the bigger the moment. See `design/FX.md`.
- **The line banks show that PR's own +/−,** then roll up to the day's totals.
- **A buzz and a chime,** following your ringer: silent or DND, nothing; vibrate, a buzz; ringer on, a buzz and an 8-bit chime (a little "mew" for big ones). Off at night.
- **Showcases.** If that merge hits your daily goal, the screen cuts to Streak and the bargraph surges. A new badge plays its unlock. Then it's back to Today.
- **Line counts are honest.** Lockfiles, snapshots and built JS/CSS don't count.

## Touch

- **Swipe** for the next or previous view. **Tap** for the next view, or on Recent and Open rows, badges, cats or the recap, to interact.
- **Double-tap** replays the last merge.
- **Drag** and a cat flies out of the camera and chases your finger. Fling it and it bursts into fireworks.
- **Long-press** opens Setup on the front screen (GitHub sign-in, Shizuku, daily goal).

The rear screen dims 10 s after the last touch. Once it's dim, the first double-tap only wakes it.

## How it runs on the rear screen

- **Routing:** Merged starts on the front screen and moves its own task to the rear display through Shizuku (`docs/rear-screen-routing.md`).
- **Always-on:** it rides the stock rear doze, so it costs about the same as Xiaomi's own rear faces (`design/REAR-AOD.md`).
- **Staying responsive:** HyperOS freezes apps on the rear while the front screen is off. A one-frame translucent activity registers Merged as the rear's foreground app, which exempts it while the rear is on (`RearPokeActivity.kt`).

Design docs: `design/SPEC.md` (the instrument), `design/V2.md` (streaks, badges, cats, week, open PRs), `design/FX.md` (the merge celebration).

## Battery

Built to cost about as much as a stock rear-screen face, not a live wallpaper that redraws forever and holds the screen on.

- **Idle draws 0 frames.** Frames only run while a tube is animating.
- **Merge celebration** (pixel cats with flaming butts, then fire; `design/FX.md`): 60 fps only while a merge plays (about 4 s for S up to 10 s for XL), then back to 0. No cats at night, and nothing while the rear is dozing.
- **Ambient redraws:** a ±3px burn-in drift (checked every 5 min, and pushed to the dozing panel only when it actually moves) and a tube sweep every 15 min, which runs only while the rear is awake.
- **Refresh rate:** it requests the panel's slowest mode at rest and about 60Hz only while animating. HyperOS ignores this on the rear panel (it stays at 120Hz), so this only matters on the front screen.
- **Always-on:** Merged uses the stock rear doze. About 16 s after the rear wakes, Xiaomi's DozeService dims the panel into self-refresh (DOZE_SUSPEND). Merged stays on top, so its last frame shows at the light-sensor doze brightness (1–35 nits), the same way the stock face does. While dozing it doesn't animate. Number changes reach the panel through a 500 ms DRAW wake lock push. The rear still goes black on the stock rules: pocket, dark room, or 90 s after unlocking. See `design/REAR-AOD.md`.
- **Network:** it polls GitHub only while visible (HyperOS freezes Merged while the phone is idle, so it catches up when you next use the phone):
  - every 2 min normally;
  - every 15 min from 23:00 to 07:00;
  - every 45 s while a merge looks imminent: a PR with auto-merge on, or one that went ready in the last day.

  Each poll is one GraphQL call (about 1 point of the 5000/h budget). The open-PR search is a second call, made at most every 5 min. The generated-file check runs once per new PR. The streak history is backfilled week by week, only until the streak's first missed workday.
- **Buzz and chime:** when a fresh merge lands, it vibrates (1–3 pulses by size; XL adds a long one) and, with the ringer on, plays a short 8-bit chime (`design/sfx/make_sfx.py`). Silent mode or Do Not Disturb: nothing. Vibrate mode: buzz only. Never at night. HyperOS drops app vibrations tagged as notifications, so Merged checks the ringer itself.

To check idle cost: `adb shell dumpsys gfxinfo co.tryterra.merged reset; sleep 60; adb shell dumpsys gfxinfo co.tryterra.merged | grep 'Total frames'`. This should print 0 when no merge happened.

## Data

`GitHub.kt` runs a single GraphQL search for `author:@me is:pr is:merged merged:>=<6 days ago, local midnight>`, plus `issueCount` for year-to-date and lifetime. A PR counts as fresh if its id wasn't in the previous poll (`seen`, in prefs) and it merged less than 6h ago.
- **First run:** nothing celebrates.
- **More than 3 fresh, or a poll gap of 30 min or more:** one combined moment plays.

## Sign-in

Setup (on the front screen) offers two ways:

1. **Sign in with GitHub** uses Device Flow. It needs an OAuth App client id at build time. Create the app at github.com/settings/applications/new: any homepage and callback URL will do, and you must tick **Enable Device Flow**. Then add `githubClientId=Iv1.xxxx` (or `Ov23…`) to `android/local.properties` or `~/.gradle/gradle.properties`. The scope is `repo`, the only OAuth scope that lets search see private-repo PRs. If an org restricts OAuth apps, an owner has to approve the app for that org.
2. **Paste a token:** a classic PAT with `repo` scope. This works with no OAuth app at all.

The token is stored AES-GCM-sealed with an Android Keystore key (`Vault.kt`).

## Build, test, install

Unit tests (streaks, badge rules, open-PR status, generated-file filter, cat pile layout): `./gradlew :app:testDebugUnitTest`.

```sh
cd android
./gradlew assembleDebug        # or assembleRelease (debug-signed, no adb test hooks)
```

**Emulator** (an AVD `rear976` at 976×596; drive it with debug-build hooks, which only `adb shell` can reach):

```sh
B='adb shell am broadcast -f 32 -a co.tryterra.merged.DEBUG -p co.tryterra.merged'
$B --ei cutout 296                 # pretend the camera covers the left 296px
$B --es token "$(gh auth token)"   # real data
$B --es demo big|quiet|zero|off    # canned days
$B --ei merge 50|500|2000|6000     # fake a merge: S / M / L / XL (shown over real data until the next poll)
$B --es show goal|badge:all|badge:EARLY_BIRD|open|recap|cats|screen:STREAK   # play a showcase / open a screen
$B --ei goal 3                     # daily goal
$B --es badges reset               # forget earned badges
```

**Phone:** `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`. If your ROM blocks `adb install` (some HyperOS CN builds do), use `scripts/install-apk-hyperos.sh <apk>`. Then:

1. Start Shizuku. It runs in adb mode, so restart it after every reboot (`docs/shizuku-rish.md`).
2. Open Merged and grant Shizuku.
3. Sign in.
4. Tap **Start**. It launches on the front screen and moves itself to the rear (`docs/rear-screen-routing.md`).


## Repo layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, one Canvas-drawn view, no UI toolkit, no dependencies beyond Shizuku) |
| `design/` | Specs, mockups, the effects prototype and the chime generator |
| `docs/` | Rear-screen routing and Shizuku notes, README media |
| `demo/` | The demo video, and `demo/src/` with the scripts that made it (phone capture, graphics, the synthesised soundtrack, the edit) |
| `scripts/` | APK install helper for ROMs that block `adb install` |

## Credits

- Fonts: Big Shoulders Display and Space Grotesk, under the SIL Open Font License (`android/app/src/main/assets/fonts/`).
- The pixel cats are original artwork, a homage to Nyan Cat. The chimes, soundtrack and sound effects are synthesised from scratch (`design/sfx/`, `demo/src/make_audio.py`).

## License

MIT, see `LICENSE`. The bundled fonts keep their own SIL Open Font License.
