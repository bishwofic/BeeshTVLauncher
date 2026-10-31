#!/usr/bin/env bash
# Disable every package in a debloat list for user 0 (nothing is uninstalled). Safe to re-run, for
# example after a firmware update turns something back on. Undo with ./restore.sh.
#   ./debloat.sh 192.168.1.20               uses debloat.txt
#   ./debloat.sh 192.168.1.20 my-list.txt
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:-${ANDROID_SERIAL:-}}"
[ -n "$TV" ] || { echo "Usage: ./debloat.sh <tv-ip> [list]"; exit 1; }
[[ "$TV" == *:* ]] || TV="$TV:5555"
export ANDROID_SERIAL="$TV"
adb connect "$TV" >/dev/null
adb wait-for-device
# One pm call per command: chained pm calls can stop after the first.
for p in $(grep -vE '^\s*(#|$)' "${2:-debloat.txt}" | awk '{print $1}'); do
  printf '  %-36s ' "$p"; adb shell pm disable-user --user 0 "$p" | tr -d '\r'
done
