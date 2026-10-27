# Rear-screen always-on (dim AOD) for a third-party app

Researched 2026-10-23/24 on the Xiaomi 17 Pro Max, HyperOS OS3.0.319 (Android 16). It's feasible without root, at about the same power as the stock face.

## How the stock face stays on dimly

There's no special always-on surface. The stock face (`SubScreenLauncher`) is an ordinary activity on display 1. What keeps it visible is a **windowless doze dream**:

1. The rear's user-activity timeout is `Settings.System subscreen_display_time` = 10000 ms. Because `Settings.Secure rear_doze_always_on=1`, the DIM step is skipped and group 1 goes straight to dozing.
2. `PowerManagerService.handleRearSandman` starts `config_subDozeComponent` = `com.xiaomi.subscreencenter/.doze.DozeService`. It's a DreamService with `setWindowless(true)`.
3. DozeService holds the panel ON at doze brightness for 6 s, then switches to **DOZE_SUSPEND**: the panel self-refreshes and there's no vsync. 10 s + 6 s ≈ the 17 s we saw.
4. `DisplayContentStubImpl.shouldReleaseSleepToken()` (Xiaomi) releases display 1's "Display-off" sleep token in any doze state. So **whatever activity is on top of display 1 stays RESUMED and visible**: the stock face, or Merged. Verified: Merged was RESUMED and `mVisible=true` for minutes in DOZE_SUSPEND at backlight 0.0216 (≈35 nits).
5. Doze brightness is set by system_server from the rear light sensor (1–35 nits, versus ≈87 awake). The app can't set it.
6. Frames drawn during DOZE_SUSPEND are dropped. DozeService itself pushes updates with `newWakeLock(128 /*DRAW_WAKE_LOCK*/, tag, displayId=1).acquire(300)`, which lifts the group to DOZE while held.

## Why the rear goes black (it does for the stock face too)

DozeService sets the state to OFF when:
- the rear pocket sensor (type 33171227) reads near;
- the ambient light level is 1–2 (immediately);
- the light level is 3 for 5 minutes;
- the phone has been unlocked for 90 s (`SubScreenAutoOffAlarmTimeout`). This rule is controlled by `Settings.Secure subscreen_aod_auto_off_enable`: unset means on, `0` disables it.

In OFF, display 1 takes a sleep token and the top activity becomes STOPPED / `isSleeping=true`.

Gotchas:
- **Force-stopping `com.xiaomi.subscreencenter` while the rear is dozing kills the dream**, so the rear goes OFF. Merged does this once per move to the rear and never again.
- While charging with developer option "Stay awake" (`stay_on_while_plugged_in=15`), group 1 never times out, so the rear stays fully ON at ≈87 nits. Judge always-on on battery, or after `dumpsys battery unplug`.
- While the front screen is ON, the Qualcomm HWC keeps the rear in DOZE instead of DOZE_SUSPEND ("Skip Display(5) setPowerMode(DOZE_SUSPEND)"), so frames still compose.
- Shell (uid 2000, so Shizuku) holds DEVICE_POWER, WRITE_SECURE_SETTINGS and CONTROL_DISPLAY_BRIGHTNESS. `service call power 20 i32 1 i64 <uptimeMs> i32 0 i32 0` (goToSleepWithDisplayId) drops the rear straight into the stock doze.

## What Merged does (MeterActivity / MeterView)

- Stays on display 1 and lets the stock doze do the always-on. There's no wake lock to keep it awake and no `FLAG_KEEP_SCREEN_ON`; the rear group ignores it anyway.
- When `Display.state` is DOZE_SUSPEND or OFF ("still"):
  - no moments, rolls or strike-ins, since the panel can't show them;
  - the numbers snap to the latest values;
  - one frame is pushed with an app-level DRAW wake lock: `newWakeLock(0x80, "merged:push").acquire(500)`, then `invalidate()` after 50 ms. This needs only `WAKE_LOCK`.
- Pushes also happen in `onResume` while dozing, to cover DozeService's 300 ms draw window when the rear comes back from OFF and the process is still thawing, and when the ±3 px burn-in drift moves.
- Polling is unchanged: it runs while RESUMED. HyperOS Greeze freezes Merged while the front is off, so counts refresh whenever you use the phone.

## Staying unfrozen while the rear is in use (touch)

HyperOS's freezer (Greeze, `com.miui.server.greeze`) freezes background apps. With the front screen off, an app on the rear counts as background.
- **What happens:** Greeze thaws the app when display 1 turns on (`Display_On`), then refreezes it 5 s later (`from system`, `persist.sys.gz.fztimeout`), even mid-gesture.
- **Why touches stall:** touches don't thaw it, so they queue for up to 5 s and trigger an input ANR. The user sees "it crashed when I threw a second cat".
- **The exemption:** `PolicyMaker.isAllowFreeze` keeps an app unfrozen (reason `subScreen`) while display 1 is ON **and** the app is `GreezeManagerService.mSubScreenUid`.
- **How the slot is set:** `EventReceiver` sets `mSubScreenUid` from `ProcessManager` foreground-info changes whose `mForegroundDisplayId == 1`. Those changes only fire when the foreground **package** changes (`ActivityTaskManagerServiceImpl` / `ForegroundInfoManager`).
- **How Merged loses it:**
  - Merged launches on display 0 and is moved, so its own resume isn't a package change.
  - The `force-stop` of `com.xiaomi.subscreencenter` restarts SubScreenLauncher, which takes the slot (uid 10213).
  - Merged stays RESUMED through doze, so it never gets the slot back.

Fix (`RearPokeActivity`):
- **The poke:** a translucent activity that resumes for one frame on the rear. That's a foreground change to Merged on display 1, so `setSubScreenUid(merged)`.
- **Launch rules:** HyperOS (`ActivityStarterImpl.handleSubScreen`) only lets an activity start on the rear if it has `<meta-data android:name="miui.rear.policy" android:value="1"/>`, **and** the keyguard is unlocked, unless the package is on a fixed system allowlist. So the poke runs only while unlocked: 0.5 s and 4 s after the move, and when the rear comes back on after a quiet gap.
- **It sticks:** once registered, the slot survives locking and rear doze/wake. Verified with the phone locked, the rear dimmed then woken, and 7 swipes: 0 freezes, all delivered.
- **Battery is unchanged:** while the rear is dim (not ON), the exemption doesn't apply, so Greeze still freezes Merged.

Also: while the rear is dim, HyperOS swallows touches until a double-tap wakes it (`secure double_tap_to_wake=1`), a double-tap on the awake rear puts it to sleep (`goToSleep`, reason `sleep_button`, power group 1), and it dims 10 s after the last touch.

## Power

- **Versus the stock face:** about the same. It's the same panel state (DOZE_SUSPEND self-refresh), at the same light-sensor brightness, and the CPU is free to suspend (`mHoldingDisplaySuspendBlocker=false` with the front off). The differences are lit-pixel count (the Nixie frame averages 0.08 luminance, which is dark) and ≈0.5 s in DOZE per push.
- **Outside figure:** Mi Back Screen uses the same mechanism on a 17 Pro Max and reports about 1.5 mAh/h (≈0.5%/day), pushing once a minute. It's a reported number; we couldn't read battery current here (shell has no access, CURRENT_NOW=0).
- **Rejected:** brute-force keep-awake. MRSS sends `input -d 1 keyevent KEYCODE_WAKEUP` every 100 ms plus SCREEN_BRIGHT, which runs the rear at ≈87 nits, 120 Hz, with the CPU busy: more than 10× the doze cost.

## Alternatives considered

| Option | Verdict |
|---|---|
| Keep awake but dim (setTemporaryBrightness on display 1, window `screenBrightness`, SCREEN_DIM on display 1, longer `subscreen_display_time`) | Rejected. The panel is still awake at 120 Hz and holds the suspend blocker. Xiaomi also turns DIM into BRIGHT for group 1 when `rear_doze_always_on=1`. |
| Our own doze dream for group 1 | Not possible without root. `config_subDozeComponent` can only be overridden by an RRO, the per-group startDream is a LocalService, and cmd/IDreamManager only act on group 0. |
| Native MAML rear face that reads counts from a Merged ContentProvider | Closest to stock and doesn't need Shizuku afterwards. But it uses undocumented ThemeManager paths, the Nixie art would have to be rebuilt in MAML, and there are reports the face stops updating in deep doze. Fallback only. |
| Rear notification card (`miui.rear.rvAOD` RemoteViews) | Restricted to an allow-list in subscreencenter, so it needs LSPosed. |

## Caveats

- **Not truly always on.** The OFF rules (pocket, dark room, 90 s unlocked) apply exactly as they do to the stock face. Only the 90 s rule can be changed from shell, and that's a global setting.
- **Breaks easily with OTAs.** It relies on Xiaomi internals: `config_subDozeComponent`, `shouldReleaseSleepToken`, the DozeService rules and the setting keys.
- **Reboots.** Shizuku runs in adb mode and dies on reboot. Merged then can't move back to the rear until Shizuku is restarted; the doze and DRAW lock don't need Shizuku.
- **Burn-in.** The drift only reaches the panel through pushes, and nothing is pushed while Greeze has Merged frozen.

## Evidence and sources

Raw evidence (logcat, dumpsys samples, decompiled DozeService / PowerManagerService / DisplayContentStubImpl / DozeBrightnessStrategyImpl, MRSS jadx output) was kept in that session's scratchpad: `research/{stock-dim,fw,mrss,experimenter,web}`.

- https://github.com/totallynotkasai/back-screen-wallpaper (Mi Back Screen: DRAW-lock push, ≈1.5 mAh/h)
- https://github.com/RongShangs/bright/blob/main/docs/2.0-hook-analysis.md (DozeService OFF rules, LSPosed)
- https://github.com/lhly/MiRearScreenSwitcher, https://github.com/896kb/ai-usage-companion (keep-awake cost)
- https://github.com/skyswords7/rear-daily, https://github.com/Jawiiki/UriRoute-For-Xiaomi-17-Rear-Screen (MAML rear faces)
- https://github.com/killerprojecte/REAREye (rear notification cards)
- AOSP `PowerManager.java` (DRAW_WAKE_LOCK = 0x80)
