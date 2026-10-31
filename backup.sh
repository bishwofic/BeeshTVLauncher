#!/usr/bin/env bash
# Save the TV's current state before changing anything: package lists, all settings, the Home app,
# memory use and a screenshot. restore.sh puts the TV back to a backup made here.
#   ./backup.sh 192.168.1.20
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:-${ANDROID_SERIAL:-}}"
[ -n "$TV" ] || { echo "Usage: ./backup.sh <tv-ip>"; exit 1; }
[[ "$TV" == *:* ]] || TV="$TV:5555"
export ANDROID_SERIAL="$TV"
adb connect "$TV" >/dev/null
adb wait-for-device

B="backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$B"
adb shell pm list packages -f -U > "$B/packages-all.txt"
adb shell pm list packages -d > "$B/packages-disabled.txt"
adb shell pm list packages -e > "$B/packages-enabled.txt"
adb shell pm list packages -3 > "$B/packages-thirdparty.txt"
adb shell pm list packages -s > "$B/packages-system.txt"
for ns in global secure system; do adb shell settings list $ns > "$B/settings-$ns.txt"; done
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$B/home-resolve.txt"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$B/home-candidates.txt"
adb shell getprop > "$B/getprop.txt"
adb shell dumpsys meminfo > "$B/meminfo.txt"
adb exec-out screencap -p > "$B/screen.png"
echo "Saved $B"
