# Routing arbitrary apps onto the Xiaomi 17 Pro Max rear screen

How we got Merged onto display 1 from a third-party app with no root, no MRSS
runtime dependency. Lessons extracted from decompiling Mi Rear Screen
Switcher (`com.tgwgroup.MiRearScreenSwitcher`, v3.2.0).

## TL;DR

```sh
# 1. uid=shell, via Shizuku
am force-stop com.xiaomi.subscreencenter
service call activity_task 50 i32 <yourTaskId> i32 1
```

That's it. The activity must already exist on display 0; this moves it onto
display 1 and stops the launcher that would otherwise reclaim the display.

> **Update (HyperOS OS3.0.319, Oct 2026): use `am display move-stack <taskId> 1`
> instead of `service call activity_task 50`.** The OTA renumbered
> `IActivityTaskManager`: 50 is now `resizeTask` and `moveRootTaskToDisplay` is
> 51. `service call … 50` still returns `Result: Parcel(00000000)` and exit 0,
> but nothing moves — a silent failure.
> `am display move-stack` reaches the same server method by name, so it survives
> renumbering. To read the current code from a build:
> `adb pull /system/framework/framework.jar`, unzip `classes*.dex`, then
> `dexdump -f classes.dex | grep -A4 "TRANSACTION_moveRootTaskToDisplay'"`.
> `RearRouter.kt` uses the `am` form.

## Why the obvious approaches don't work

| Approach | What happens |
|---|---|
| `am start --display 1 -n co.tryterra.merged/.MeterActivity` | MIUI `ActivityStarterImpl` rejects with `"should not allow app = <pkg> show on rear display"`. Hardcoded package allowlist in system_server. |
| `IActivityTaskManager.moveRootTaskToDisplay(taskId, 1)` via reflection (with Shizuku binder) | Call returns OK, but the activity's window stays on display 0; the activity transitions to STOPPED on display 1 with no surface. Logcat shows `Found window <YourActivity> in display 0, but it should belong to display 1` and an orphan window blocks front-screen input. |
| `IActivityManager.moveTaskToFront(taskId, 0, opts)` with `ActivityOptions.setLaunchDisplayId(1)` | HyperOS 3 signature is `(IApplicationThread, String, int, int, Bundle)` — needs a real `IApplicationThread`, can't pass null. Even if we built one, MIUI's policy code re-runs the package check on `moveTaskToFront`. |
| `Activity.startActivity(intent, options)` with `setLaunchDisplayId(1)` from our own app | Same package-check rejection as `am start --display 1`. |

The only third-party-friendly path is the raw binder transaction below — it
bypasses both the package check and the API-version churn.

## The mechanism MRSS actually uses

Pulled from
`/data/app/com.tgwgroup.MiRearScreenSwitcher-…/base.apk →
com/tgwgroup/MiRearScreenSwitcher/TaskService.java`:

```java
public boolean moveTaskToDisplay(int taskId, int displayId) {
    Process p = new ProcessBuilder(
        "sh", "-c",
        "service call activity_task 50 i32 " + taskId + " i32 " + displayId
    ).start();
    return p.waitFor() == 0;
}
```

- `activity_task` is the system service name (registered by
  `ActivityTaskManagerService`).
- Transaction `50` corresponds to `moveRootTaskToDisplay(int rootTaskId, int
  displayId)` in `IActivityTaskManager.aidl`. **The numeric code is what's
  stable across MIUI builds; the method name and reflective signature are
  not.**
- `i32 X` puts a 32-bit int onto the binder transaction parcel.
- The process must run as **uid=2000 (shell)** — which has the
  `INTERNAL_SYSTEM_WINDOW` and matching SELinux context. uid=10xxx app
  processes get `SecurityException`.

## Why SubScreenLauncher has to be killed first

`com.xiaomi.subscreencenter/.SubScreenLauncher` is registered as
`category.SECONDARY_HOME` for display 1 (MIUI's "rear-screen launcher"). If
it's alive, the move succeeds but our task becomes invisible behind it —
SubScreenLauncher stays top, our activity goes to STOPPED.

MRSS's fix (from `TaskService.disableSubScreenLauncher()`):

```java
new ProcessBuilder("sh", "-c", "am force-stop com.xiaomi.subscreencenter")
    .start().waitFor();
```

After force-stop, display 1 has no `SECONDARY_HOME` activity → our moved task
gets surfaced + resumed. SubScreenLauncher will start back up when display 1
next powers down/up; MRSS optionally calls
`am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher` to
restore it sooner.

## The full MRSS architecture (for the curious)

MRSS doesn't shell out from its main process — it uses a **Shizuku
UserService**:

```java
// SwitchToRearTileService.java
private final Shizuku.UserServiceArgs serviceArgs =
    new Shizuku.UserServiceArgs(new ComponentName(
        "com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
        .daemon(false)
        .processNameSuffix("task_service")
        .debuggable(false)
        .version(1);

Shizuku.bindUserService(serviceArgs, taskServiceConnection);
```

Shizuku forks a process owned by `uid=shell` from its own binder host, loads
`TaskService` into it, and binds it to the host app over an AIDL
`ITaskService` interface (the AIDL stub is regenerated in
`com/tgwgroup/MiRearScreenSwitcher/ITaskService.java` — transaction codes 2–22
for the public methods, 16777115 for `destroy`).

This is overkill for a single move call — `Shizuku.newProcess` (which Merged
uses) runs a one-shot subprocess and is plenty for our needs.

`ITaskService` methods worth knowing about for future rear-screen tricks:

| Tx | Method | Notes |
|---|---|---|
| 4 | `moveTaskToDisplay(taskId, displayId)` | The `service call activity_task 50` above |
| 7 | `disableSubScreenLauncher()` | `am force-stop com.xiaomi.subscreencenter` |
| 8 | `enableSubScreenLauncher()` | `am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher` |
| 14 | `setRearDpi(dpi)` | `wm density <dpi> -d 1` |
| 15 | `resetRearDpi()` | `wm density reset -d 1` |
| 16 | `takeRearScreenshot()` | `screencap -d <uniqueId> -p` |
| 18 | `getForegroundAppOnDisplay(displayId)` | parses `am stack list` |
| 19 | `setDisplayRotation(displayId, rotation)` | `wm user-rotation -d <id> lock <rot>` |

All of them are wrappers around shell commands; none use reflection. That's a
useful design pattern for HyperOS automation in general — **prefer
`service call <svc> <tx>` over the reflective AIDL stub**, because:

- Transaction numbers are stable across minor MIUI updates; reflective method
  signatures aren't.
- No hidden-API exemption (`HiddenApiBypass`) needed.
- No `IApplicationThread` headaches.

## How Merged uses this

`android/app/src/main/kotlin/co/tryterra/merged/RearRouter.kt` runs
`am force-stop com.xiaomi.subscreencenter` then `am display move-stack <taskId> 1`
via `Shizuku.newProcess` (reflected because it's package-private in Shizuku 13).
`MeterActivity` schedules a 250 ms-delayed `RearRouter.moveToRear(this)` so the
task is fully attached to WMS before the move.

Total user interaction to put Merged on the rear screen: **tap the icon once.**
First run: also grant Shizuku permission on the setup screen (once per install).
