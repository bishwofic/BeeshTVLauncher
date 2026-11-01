#!/usr/bin/env bash
# Put the TV back exactly as it was in a backup made with backup.sh.
#   ./restore.sh 192.168.1.20                      from the oldest backup in backup/
#   ./restore.sh 192.168.1.20 backup/<timestamp>   from a specific backup
#   ./restore.sh 192.168.1.20 --stock              and also bring back the stock Google TV launcher
# What it does:
#   1. Re-enables every package that is disabled now but wasn't in the backup (one pm call each).
#   2. Restores Private DNS, Location and the accessibility-service list to their backed-up values.
#   3. Sets the backed-up home app as default again, removes BEESH Launcher, presses Home.
# Nothing was ever uninstalled, so nothing needs reinstalling.
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:-${ANDROID_SERIAL:-}}"
[ -n "$TV" ] || { echo "Usage: ./restore.sh <tv-ip> [backup-folder] [--stock]"; exit 1; }
[[ "$TV" == *:* ]] || TV="$TV:5555"
export ANDROID_SERIAL="$TV"
shift || true

STOCK=0; B=""
for a in "$@"; do
  case "$a" in --stock) STOCK=1 ;; *) B="$a" ;; esac
done
[ -n "$B" ] || B="$(ls -d backup/*/ | sort | head -1)"
B="${B%/}"
[ -f "$B/packages-disabled.txt" ] || { echo "No backup at $B"; exit 1; }
echo "Restoring from $B"

adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
adb wait-for-device

strip() { tr -d '\r' | sed 's/^package://' | sort -u; }

# 1. Packages
now="$(adb shell pm list packages -d | strip)"
before="$(strip < "$B/packages-disabled.txt")"
to_enable="$(comm -23 <(echo "$now") <(echo "$before") | sed '/^$/d')"
if [ "$STOCK" = 1 ]; then
  to_enable="$(printf '%s\ncom.google.android.apps.tv.launcherx\ncom.google.android.tungsten.setupwraith\n' "$to_enable" | sed '/^$/d' | sort -u)"
fi
for p in $to_enable; do
  printf '  enable %-44s ' "$p"; adb shell pm enable "$p" | tr -d '\r'
done

# 2. Settings
setting() {  # ns key  -> value from backup, or empty when it wasn't set
  grep -E "^$2=" "$B/settings-$1.txt" | head -1 | cut -d= -f2- | tr -d '\r' || true
}
for key in private_dns_mode private_dns_specifier; do
  v="$(setting global $key)"
  if [ -z "$v" ] || [ "$v" = null ]; then adb shell settings delete global $key >/dev/null
  else adb shell settings put global $key "$v"; fi
done
echo "  private DNS restored ($(setting global private_dns_mode || true))"
if [ "$(setting secure location_mode)" = 0 ]; then
  adb shell cmd location set-location-enabled false
  echo "  location turned off again"
fi
acc="$(setting secure enabled_accessibility_services)"
if [ -n "$acc" ] && [ "$acc" != null ]; then
  adb shell settings put secure enabled_accessibility_services "'$acc'"
  echo "  accessibility services restored"
fi

# 3. Home screen
if [ "$STOCK" = 1 ]; then
  home="com.google.android.apps.tv.launcherx/.home.HomeActivity"
else
  home="$(tr -d '\r' < "$B/home-resolve.txt" | grep '/' | tail -1 | xargs)"
fi
adb shell cmd package set-home-activity --user 0 "$home"
adb uninstall com.beeshtv.home >/dev/null 2>&1 && echo "  BeeshTV launcher removed" || true
adb shell cmd package set-home-activity --user 0 "$home"
adb shell am force-stop com.android.tv.settings
adb shell input keyevent KEYCODE_HOME
echo "Restored. Home is now $home"
