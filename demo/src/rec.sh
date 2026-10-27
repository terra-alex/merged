#!/bin/zsh
# rec.sh NAME SECONDS 'shell steps run during the take' — records the phone's rear display (60 fps, high bitrate)
# (REAR_DISPLAY = the rear panel's physical display id: `adb shell dumpsys SurfaceFlinger --display-id`)
# while keeping both screens awake (front on the lock screen keeps HyperOS from freezing Merged), then pulls the mp4.
set -u
NAME=$1; SECS=$2; STEPS=${3:-}
D=${0:A:h}/raw
mkdir -p $D
p() { adb "$@"; } # set ANDROID_SERIAL if more than one device is attached
b() { p shell am broadcast -f 32 -a co.tryterra.merged.DEBUG -p co.tryterra.merged "$@" >/dev/null; }
tap() { p shell input -d 1 tap "$@"; }
p shell input keyevent KEYCODE_WAKEUP
p shell input -d 1 keyevent KEYCODE_WAKEUP
sleep 0.6
p shell rm -f /sdcard/take.mp4
p shell screenrecord --display-id ${REAR_DISPLAY:-4630946457447247252} --bit-rate 24000000 --time-limit $SECS /sdcard/take.mp4 &
REC=$!
( for i in $(seq 1 $((SECS / 3))); do sleep 3; p shell input -d 1 keyevent KEYCODE_WAKEUP; p shell input keyevent KEYCODE_WAKEUP; done ) &
KEEP=$!
sleep 0.8
eval "$STEPS"
wait $REC
kill $KEEP 2>/dev/null
sleep 1
p pull /sdcard/take.mp4 $D/$NAME.mp4 >/dev/null && ffprobe -v error -count_frames -select_streams v -show_entries stream=nb_read_frames -of csv=p=0 $D/$NAME.mp4 | xargs echo "$NAME frames:"
