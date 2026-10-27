#!/usr/bin/env bash
# Install an APK on a HyperOS 3 CN device, bypassing INSTALL_FAILED_USER_RESTRICTED.
#
# The CN ROM blocks `adb install` from USB. We push to /sdcard/Download/ then
# fire a VIEW intent so the on-device installer (which doesn't share that gate)
# handles it. The user taps the grey "Install" button on the resulting
# "Couldn't find ICP registration info" dialog.
#
# Usage: ./install-apk-hyperos.sh path/to/app.apk
set -euo pipefail

APK="${1:?usage: $0 path/to/app.apk}"
[ -f "$APK" ] || { echo "no such file: $APK" >&2; exit 1; }

BASE="$(basename "$APK")"
REMOTE="/sdcard/Download/$BASE"

echo "→ pushing $APK to $REMOTE"
adb push "$APK" "$REMOTE"

echo "→ launching on-device installer (tap Install on the dialog)"
adb shell am start \
  -a android.intent.action.VIEW \
  -d "file://$REMOTE" \
  -t application/vnd.android.package-archive
