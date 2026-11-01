#!/usr/bin/env bash
# Install BEESH TV Suite on an Android TV / Google TV over the network: BEESH Launcher as the Home app
# (the APK in dist/; the stock home screen is switched off and every permission the launcher uses is
# granted), BEESH Browser from its newest GitHub release as the TV's browser, and the TV's Private DNS
# set to AdGuard (blocks ads and trackers for every app).
#
#   ./install.sh 192.168.1.20               the whole suite
#   ./install.sh 192.168.1.20 --no-browser  just the launcher
#   ./install.sh 192.168.1.20 --undo        give Home back to the stock launcher, remove BeeshTV and
#                                           set Private DNS back to automatic (BEESH Browser stays)
#
# Turn on the TV's developer options and USB / network debugging first, and accept the
# "Allow debugging?" prompt on the TV the first time.
set -euo pipefail
cd "$(dirname "$0")"

TV="${1:-${ANDROID_SERIAL:-}}"
[ -n "$TV" ] || { echo "Usage: ./install.sh <tv-ip> [--undo]"; exit 1; }
[[ "$TV" == *:* ]] || TV="$TV:5555"
export ANDROID_SERIAL="$TV"
PKG=com.beeshtv.home
# Stock home apps that take the Home button back if left enabled.
STOCK="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.tvlauncher"
GUARD=$PKG/$PKG.HomeGuard
DNS=dns.adguard-dns.com
BROWSER=com.beeshtv.browser
BROWSER_REPO=bishwofic/BeeshTVBrowser

# The accessibility services that are on, without BeeshTV's, separated by ':'.
others() {
  adb shell settings get secure enabled_accessibility_services | tr -d '\r' | tr ':' '\n' \
    | grep -v -e '^null$' -e '^$' -e "^$GUARD$" | paste -sd: - || true
}

adb connect "$TV" >/dev/null
adb wait-for-device

if [ "${2:-}" = "--undo" ]; then
  for p in $STOCK; do adb shell pm enable "$p" >/dev/null 2>&1 || true; done
  adb shell settings put secure enabled_accessibility_services "$(others)"
  adb shell settings put global private_dns_mode opportunistic
  adb uninstall $PKG >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_HOME
  echo "Removed. The stock launcher is back and Private DNS is automatic again."
  exit 0
fi

APK="$(ls -t dist/BeeshTV-Launcher-*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo "No APK in dist/. Run launcher/build.sh first."; exit 1; }
echo "Installing $APK on $TV"
adb install -r --no-incremental "$APK"
# Let the TV's settings turn on accessibility and notification access for a sideloaded app, and let
# TCL TVs start BeeshTV's services (TCL blocks that by default and sets it back on every restart, so
# on those TVs switching off the stock home screen below is what keeps Home on BeeshTV).
adb shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow >/dev/null 2>&1 || true
adb shell appops set $PKG AUTO_START allow >/dev/null 2>&1 || true
# Lets the launcher install its own updates from GitHub (the TV still asks to confirm each one).
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
# The Wi-Fi panel can only see network names with location permission and Location on.
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
# The Bluetooth panel's paired devices (Android 12 and newer ask for this separately).
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT >/dev/null 2>&1 || true
adb shell cmd location set-location-enabled true >/dev/null 2>&1 || true
# Lets the bell in the menu bar list and clear notifications. Revoking first makes Android connect
# the listener again after an update.
adb shell cmd notification disallow_listener $PKG/$PKG.NotificationsService >/dev/null 2>&1 || true
adb shell cmd notification allow_listener $PKG/$PKG.NotificationsService >/dev/null 2>&1 || true
adb shell cmd role add-role-holder --user 0 android.app.role.HOME $PKG >/dev/null 2>&1 || true
adb shell cmd package set-home-activity --user 0 $PKG/.MainActivity
# Keeps Home on BeeshTV should a TV update switch the stock home screen back on. Added to, not in place
# of, any accessibility services already on.
EXISTING="$(others)"
adb shell settings put secure enabled_accessibility_services "${EXISTING:+$EXISTING:}$GUARD"
adb shell settings put secure accessibility_enabled 1
# AdGuard DNS for the whole TV: blocks ads and trackers in every app.
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier $DNS
# One pm call per command: chained pm calls can stop after the first.
for p in $STOCK; do adb shell pm disable-user --user 0 "$p" >/dev/null 2>&1 || true; done
adb shell cmd package set-home-activity --user 0 $PKG/.MainActivity
# A stale home task from the old launcher can make Home open behind the current app.
adb shell am force-stop com.android.tv.settings

# BEESH Browser, the rest of BEESH TV Suite: its newest release, made the TV's browser, with the
# microphone for voice typing and permission to install its own updates.
if [ "${2:-}" != "--no-browser" ]; then
  URL="$(curl -fsSL "https://api.github.com/repos/$BROWSER_REPO/releases/latest" 2>/dev/null \
    | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | sed 's/.*"\(http[^"]*\)"$/\1/' || true)"
  TMP="$(mktemp -d)"
  if [ -n "$URL" ] && curl -fsSL -o "$TMP/browser.apk" "$URL"; then
    echo "Installing BEESH Browser ($(basename "$URL"))"
    if adb install -r --no-incremental "$TMP/browser.apk"; then
      adb shell appops set $BROWSER REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
      adb shell pm grant $BROWSER android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
      adb shell cmd role add-role-holder --user 0 android.app.role.BROWSER $BROWSER >/dev/null 2>&1 || true
    else
      echo "BEESH Browser didn't install (a copy signed with another key may be on the TV; uninstall it first)."
    fi
  else
    echo "Couldn't download BEESH Browser from GitHub; skipped. Install it later from the launcher's setup."
  fi
  rm -rf "$TMP"
fi
# Updating stops the launcher, and Android brings back whatever app was underneath (often the last one
# you used). Open the launcher directly, once it's ready, instead of pressing Home.
sleep 1
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.MainActivity >/dev/null
echo "Done. Press Home on the remote."
