# Sand Clock (Android TV screensaver)

Pixel-art sand clock for Android TV, built as a low-power screensaver. One grain falls every
second of the day (86,400 by midnight) and piles up on the floor. The clock's letters are open-top
containers: sand pours in and fills them, and when a character changes it lets its sand go. At
midnight the floor opens and the screen empties.

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
keytool -genkeypair -keystore sandclock.keystore -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD \
  -alias sandclock -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Sand Clock"
echo "storePassword=YOUR_PASSWORD" > keystore.properties   # not in git
```

Without it the build is signed with a throwaway debug key, which can't update an install signed
with a different key (uninstall first).

## Releases and updates from the TV

Push a version tag and GitHub builds, signs and publishes the app:

```bash
git tag v1.2 && git push origin v1.2
```

On the TV: Sand Clock settings → **Updates** → ▶ to check, ▶ again to install, then confirm.
Android only installs it if it is signed with the same key, and asks once to allow
"install unknown apps" for Sand Clock.

The release workflow needs two repository secrets (Settings → Secrets and variables → Actions):
`SANDCLOCK_KEYSTORE_B64` (the keystore, base64) and `SANDCLOCK_KEY_PASSWORD` (its password).

The version code is the number of commits, so every build counts as newer. **Never rewrite
`main`'s history** (squash or force-push): the count could go down and the TV would refuse updates.

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

To see it right away, open the app and choose **Run clock now** (any button except volume closes it).

## Settings (open "Sand Clock" from the apps row)

Everything works with ◀ ▶ (no OK button needed); ▶ also opens the action rows.

| Setting | Options |
|---|---|
| Start screensaver after | 1–60 min (the TV's own setting; needs the `appops` grant) |
| Turn TV off after no use | Never, 30 min – 8 h (Android `sleep_timeout`) |
| Off after hours without a button | Off, 4–24 h (Android `attentive_timeout`) |
| Style | Black sand (default: backlight zones under the sand switch fully off), Dark sand (faint grain texture), Glowing sand (lit sand on black) |
| Grains fall at | 15–85 % across the top, 5 % steps. The physics is tuned per position so the screen fills at midnight; nearer the edge a pile can't hold a full day |
| Stream sway | Off, Gentle, Wide: the stream drifts back and forth (widest after midnight, none by the next midnight) so sand lands on every letter |
| Colour | Sand, Mono, Amber, Red |
| Brightness | Low, Medium, High (dims the drawn pixels) |
| Clock | 12-hour / 24-hour |
| Falling sand | Gentle (15 fps), Smooth (30 fps), Off (sand lands once a minute) |
| Night schedule | On/Off, start and end in 30-minute steps |
| At night show | Black screen, Dim clock |
| Lower backlight at night | drops the backlight further during night hours |
| Midnight reset during night | On: the midnight pour-out still plays, dimmed, if night mode covers midnight |
| Evening warmth | A Malaysian city (or Singapore): colours warm and dim from sunset, worked out offline |
| Backlight while showing | Unchanged, 75 %, 50 %, 25 % of your backlight while the screensaver shows; restored afterwards |
| Keep as screensaver | Re-selects Sand Clock if a firmware update resets the screensaver |
| Updates | ▶ checks GitHub for a newer release, ▶ again installs it |

Dismissing the screensaver (any button) returns to whatever was on before; the app's own screens
close when the screensaver starts.

## Power tips (mini LED)

- Set the TV's **local dimming** to High. On a black background the dark zones then switch off.
- Lower the TV's own **backlight** setting. That matters more than anything drawn on screen.
- For real power off overnight, use the TV's scheduled power-off if it has one, or **Turn TV off after no use**. The app's night mode only blacks out the screen.
- If the screensaver seems late: Android keeps the screen on for 10 minutes when you press buttons just as it dims ("undim detector"). There is no menu for it; `adb shell device_config put attention_manager_service keep_screen_on_enabled false` turns it off (may reset after a reboot).

## Uninstall

```bash
adb uninstall com.local.sandclock
```
