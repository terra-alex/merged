# Shizuku + rish — privileged shell from Termux without root

## What it gives us

`run-as <pkg> …` to read/write debuggable apps' private data dirs from inside Termux, plus `am`, `pm`, `dumpsys`, `settings` at shell-UID (2000).

## Setup

1. Install **Shizuku** from Play Store (or APKMirror): `moe.shizuku.privileged.api` (we use v13.5).
2. In Shizuku app, choose **"Start via wireless debugging"** (adb-mode; no root).
3. Pair via the OS Wireless Debugging dialog. Shizuku remembers the pairing.
4. In Termux:
   ```bash
   pkg install -y wget unzip
   mkdir -p ~/bin && cd ~/bin
   wget https://github.com/RikkaApps/Shizuku/raw/master/rish_shizuku.dex
   # rish wrapper script — see triage/patch/ondevice/rish in this repo
   chmod +x ~/bin/rish
   ```
5. Open Shizuku → Authorized applications → grant Termux.
6. Test: `rish id` should print `uid=2000(shell) …`.

## Quirks

### Binary stdout is filtered

`rish` pipes through Android's binder. Anything that puts raw bytes on stdout (e.g. `run-as PKG cat /path/to/file.db`) is dropped — you'll get 0 bytes.

**Workaround**: two-hop everything binary via `/sdcard/Download/`:

```bash
# pull
rish "run-as com.miui.weather2 cp /data/.../weather.db /sdcard/Download/x.db"
cp /sdcard/Download/x.db ./local.db
# push
cp ./local.db /sdcard/Download/x.db
rish "cp /sdcard/Download/x.db /data/local/tmp/x.db && chmod 644 /data/local/tmp/x.db"
rish "run-as com.miui.weather2 cp /data/local/tmp/x.db /data/.../weather.db"
```

### Termux home is unreachable from rish

`/data/data/com.termux/files/home` is mode `0700`, owned by Termux's app UID. `rish` runs as shell UID 2000 → can't read it. Same `/sdcard/Download/` bridge solves it (Termux has `MANAGE_EXTERNAL_STORAGE`).

### run-as needs debuggable

`run-as <pkg>` works only if the APK has `android:debuggable="true"`. We re-signed Mi Weather with that flag set. If GetApps ever updates the app back to the official non-debuggable build, you lose `run-as` and need to reflash `triage/patch/cn-weather-debuggable.apk`.

### stdin pipe, not -c

Shizuku rish reliably accepts a command via **stdin** rather than `rish -c "…"`. From Python:

```python
subprocess.run(["rish"], input=cmd + "\n", capture_output=True, text=True)
```

## Post-reboot ritual (Shizuku adb-mode dies on every reboot)

There is no fully-autonomous-on-boot path for adb-mode Shizuku without root. After each reboot:

1. Open the **Shizuku** app.
2. Tap **Start** under "Start via wireless debugging" — Shizuku remembers the previous pairing.
3. Tap **Allow** on the Termux auth prompt.

Total: ~20 seconds. The mi-weather-sync daemon polls every 30s and resumes automatically once Shizuku is alive.
