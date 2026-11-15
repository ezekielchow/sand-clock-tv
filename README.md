# Sand Clock (Android TV screensaver)

Pixel-art sand clock: one grain falls every second of the day (86,400 by midnight), piling up on the ground and on the clock's digits. When the minute changes, the sand on the digits falls off. At midnight the floor opens and the screen empties.

## Build

```bash
./build.sh        # uses Docker; no local Android SDK needed
# -> app/build/outputs/apk/release/app-release.apk
```

### Signing key

Android only installs an update over an app signed with the same key. Builds use
`sandclock.keystore` in the project root if it exists (it is not in the repo). Keep your own copy
safe, or create one once with:

```bash
keytool -genkeypair -keystore sandclock.keystore -storepass sandclock -keypass sandclock \
  -alias sandclock -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Sand Clock"
```

Without it the build is signed with a throwaway debug key, which can't update an install signed
with a different key (uninstall first).

## Install on the TV

1. On the TV, open **Settings → Device Preferences → About** and click **Build** 7 times. Then turn on **Developer options → USB debugging / Network debugging**.
2. Find the TV's IP address (**Settings → Network**).
3. From this computer:

```bash
adb connect <TV-IP>:5555          # accept the prompt on the TV
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Make it the screensaver

Google TV / Mi TV often hides third-party screensavers in the menu, so set it with adb:

```bash
adb shell settings put secure screensaver_components com.local.sandclock/.SandClockDream
adb shell settings put secure screensaver_enabled 1
adb shell settings put system screen_off_timeout 300000   # start after 5 min idle
```

Let the app re-select itself after firmware updates and lower the Mi TV backlight while it shows (restored when it closes):

```bash
adb shell pm grant com.local.sandclock android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.local.sandclock WRITE_SETTINGS allow   # lets the app set the screensaver delay
```

Stop the TV from switching itself off after the screensaver starts:

```bash
adb shell settings put secure sleep_timeout -1
adb shell settings put secure attentive_timeout -1        # Android 11+
```

Also turn off any **Auto power off** / **Sleep timer** in the TV's own power settings.

To test right away: `adb shell am start -n com.android.systemui/.Somnambulator` (or open the app and choose **Run clock now**).

## Settings (open "Sand Clock" from the apps row)

| Setting | Options |
|---|---|
| Style | Black sand (default: backlight zones under the sand switch fully off), Dark sand (faint grain texture), Glowing sand (lit sand on black) |
| Grains fall at | 0–100% across the top of the screen, 5% steps (◀ ▶) |
| Colour | Sand, Mono, Amber, Red |
| Brightness | Low, Medium, High (dims the drawn pixels) |
| Clock | 24-hour / 12-hour |
| Falling sand | Gentle (8 fps), Smooth (30 fps), Off (redraws once a minute) |
| Night schedule | On/Off, start and end in 30-minute steps |
| At night show | Black screen, Dim clock |
| Lower backlight at night | asks the system to drop the backlight |
| Flip at midnight during night | On (default): if night mode covers midnight, the glass still flips, dimmed, for 4 seconds |

## Power tips (mini LED)

- Set the TV's **local dimming** to High. On a black background the dark zones then switch off.
- Lower the TV's own **backlight** setting. That matters more than anything drawn on screen.
- For real power off overnight, use the TV's scheduled power-off if it has one. The app's night mode only blacks out the screen.

## Uninstall

```bash
adb uninstall com.local.sandclock
```
