# BeeshTV Launcher

A calm home screen for Android TV and Google TV. Your apps, the time and weather where you are and
where your people are, and a landscape that changes with the time of day.

No ads, no rows of recommendations, no account.

![Home screen](docs/home.jpg)

| Other apps and inputs | All apps | Settings |
|---|---|---|
| ![Other apps and inputs](docs/sections.jpg) | ![All apps](docs/apps.jpg) | ![Settings](docs/settings.jpg) |

## Features

- **Clock on the right** of the menu bar, with the weather where the TV is and the date, and a world
  clock on the left with its flag and weather. The middle stays clear for the photo.
- **Your Apps** first, in the order you want, then **Other Apps** A–Z, then **Inputs**: Google TV,
  HDMI 1–4 and AV. Rename any input, or keep the names set in the TV's settings.
- **Every app** one tab over, in a full-screen A–Z list.
- **Dynamic wallpapers** from wallhaven.cc that follow the time of day (morning, day, evening, night),
  in three styles: landscapes, cityscapes or the current season.
- **Wi-Fi, Bluetooth, Inputs and Notifications** panels: see your network and nearby ones, which
  devices are connected, switch HDMI, and read or clear notifications without leaving Home.
- Small and quick: about 2 MB, plain Android, nothing running in the background except the
  notification listener.

## Install

You need a computer with `adb` on the same Wi-Fi as the TV (`brew install android-platform-tools` on
a Mac). Works on Android TV and Google TV running Android 11 or newer.

1. On the TV, open **Settings → System → About** and press OK on **Android TV OS build** seven times.
   Go back to **Settings → System → Developer options** and turn on **USB debugging**.
2. Find the TV's IP address in **Settings → Network & Internet** (select your Wi-Fi network).
3. Download this repo (or just `dist/BeeshTV-Launcher-1.0.apk` and `install.sh`) and run:

   ```bash
   ./install.sh 192.168.1.20
   ```

4. Accept **Allow debugging?** on the TV. Press Home.

To go back to the stock home screen: `./install.sh 192.168.1.20 --undo`.

### Installing just the APK

Copy `BeeshTV-Launcher-1.0.apk` to the TV (a USB stick, LocalSend, or a downloader app) and open it.
On first launch the launcher asks for what it needs, one at a time, and opens the TV's own screen for
each: location (for local weather; the time zone always comes from the TV), notification access (for
the bell), and to become the Home app. You can come back to this any time under
**Settings → Permissions**.

Google TV keeps its own home screen on the Home button even after you pick another one. Switching it
off needs a computer once (below), or use `install.sh`, which does everything.

### Doing it by hand

```bash
adb connect 192.168.1.20:5555
adb install BeeshTV-Launcher-1.0.apk
adb shell cmd package set-home-activity com.beeshtv.home/.MainActivity
```

Google TV has no setting for the default home app and takes the Home button back from any other
launcher, so the stock one has to be switched off. This only disables it; nothing is uninstalled:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Turn them back on with `adb shell pm enable <package>`.

The Wi-Fi panel shows network names only when the launcher has location permission and Location
is on, and the bell needs notification access. `install.sh` sets all three; by hand:

```bash
adb shell pm grant com.beeshtv.home android.permission.ACCESS_FINE_LOCATION
adb shell cmd location set-location-enabled true
adb shell cmd notification allow_listener com.beeshtv.home/com.beeshtv.home.NotificationsService
```

## Using it

| Remote | |
|---|---|
| OK | Open the app |
| Hold OK | On an app: move it, move it between Your Apps and Other Apps, uninstall it, app info. On an input: rename it |
| Menu | Inputs |
| Back | Back to Home |
| Left / Right in All apps | Jump to the previous / next letter |

**Settings** (the gear, top right) has the section backgrounds on or off, the wallpaper style and how
often it changes, the world clock city (or off), Permissions, and About.

## Build it yourself

No Gradle, just the SDK command-line tools:

```bash
brew install openjdk@17 android-commandlinetools
yes | JAVA_HOME=/opt/homebrew/opt/openjdk@17 sdkmanager \
  --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-34" "build-tools;34.0.0"

launcher/build.sh                 # builds dist/BeeshTV-Launcher-<version>.apk
launcher/build.sh 192.168.1.20    # builds and installs
```

The default Home apps, hidden apps and city list are at the top of
`launcher/src/com/beeshtv/home/Prefs.java`.

The APK is signed with `launcher/debug.keystore`, created on the first build (it isn't in the repo).
An update only installs over an older version signed with the same key, so if you build your own,
uninstall the release APK first.

## Clean up a TCL TV (optional)

TCL's Google TVs (the list was made on a TCL Smart TV Pro, Android 14) ship with a viewing tracker (Samba TV), analytics, promo pop-ups and an app that
installs sponsored apps on its own. `debloat.txt` lists them, one line each with what it does.
Comment out anything you use.

```bash
./backup.sh 192.168.1.20     # saves package lists and settings to backup/
./debloat.sh 192.168.1.20    # disables everything in debloat.txt
./restore.sh 192.168.1.20    # puts the TV back the way backup.sh found it
```

Everything is disabled, never uninstalled, so a restore is complete. For ad blocking across every
app, set Private DNS to AdGuard:

```bash
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier dns.adguard-dns.com
```

## Credits

- Photos are found through [wallhaven.cc](https://wallhaven.cc) and belong to their photographers.
- Location for the weather comes from the TV itself; without one, from [ipwho.is](https://ipwho.is).
- Weather data by [Open-Meteo.com](https://open-meteo.com) (CC BY 4.0).
- Typeface: [Schibsted Grotesk](https://github.com/schibsted/schibsted-grotesk) by Schibsted (SIL Open Font License).
- Menu bar icons are in the style of Google's Material Symbols (Apache 2.0).
- Input switching is adapted from [tv-home](https://github.com/mihirgupta0900/tv-home) (MIT).

Licences for bundled work are in `launcher/licenses/`.

## License

MIT, see [LICENSE](LICENSE). Made by [Bishwo Bhusal](https://github.com/bishwofic).
