package com.local.sandclock;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Changes TV settings that need WRITE_SECURE_SETTINGS (granted once over adb):
 * keeps Sand Clock as the screensaver, and lowers the Mi TV backlight while it runs.
 * The original backlight is saved before any change and restored afterwards, on boot,
 * or the next time the app opens, so a crash or power cut can't leave it dimmed.
 */
final class SystemTweaks {
    private static final String BACKLIGHT_KEY = "picture_backlight";
    private static final String SAVED = "savedBacklight";

    static boolean canWrite(Context c) {
        return c.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static String component(Context c) {
        return new ComponentName(c, SandClockDream.class).flattenToString();
    }

    /** Re-selects Sand Clock as the screensaver if something (e.g. an update) changed it. */
    static void ensureScreensaver(Context c) {
        if (!canWrite(c) || !Prefs.load(c).keepScreensaver) return;
        try {
            String want = component(c);
            if (!want.equals(Settings.Secure.getString(c.getContentResolver(), "screensaver_components"))) {
                Settings.Secure.putString(c.getContentResolver(), "screensaver_components", want);
            }
            Settings.Secure.putInt(c.getContentResolver(), "screensaver_enabled", 1);
        } catch (RuntimeException ignored) {
            // Firmware refused; the settings screen shows the status.
        }
    }

    /** Sets the backlight to `fraction` of the user's own level. Saves that level first. */
    static void dimBacklight(Context c, float fraction) {
        if (!canWrite(c)) return;
        try {
            SharedPreferences sp = store(c);
            int original = sp.getInt(SAVED, -1);
            if (original < 0) {
                original = Settings.Global.getInt(c.getContentResolver(), BACKLIGHT_KEY, -1);
                if (original < 0) return; // this TV doesn't use the key
                sp.edit().putInt(SAVED, original).commit();
            }
            int target = Math.max(1, Math.round(original * fraction));
            Settings.Global.putInt(c.getContentResolver(), BACKLIGHT_KEY, target);
        } catch (RuntimeException ignored) {
        }
    }

    /** Puts the user's backlight level back, if we changed it. Safe to call any time. */
    static void restoreBacklight(Context c) {
        SharedPreferences sp = store(c);
        int original = sp.getInt(SAVED, -1);
        if (original < 0 || !canWrite(c)) return;
        try {
            Settings.Global.putInt(c.getContentResolver(), BACKLIGHT_KEY, original);
            sp.edit().remove(SAVED).commit();
        } catch (RuntimeException ignored) {
        }
    }

    /** Minutes of no remote input before the screensaver starts (the TV's own setting). */
    static int screensaverDelayMin(Context c) {
        try {
            return Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT) / 60_000;
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }

    static boolean canSetDelay(Context c) {
        return android.os.Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(c);
    }

    /** Sets the screensaver delay. Returns false if the TV refuses. */
    static boolean setScreensaverDelayMin(Context c, int minutes) {
        try {
            return Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A secure setting in minutes (-1 = never). */
    static int secureMinutes(Context c, String key) {
        try {
            int ms = Settings.Secure.getInt(c.getContentResolver(), key, -1);
            return ms < 0 ? -1 : ms / 60_000;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Sets a secure setting given in minutes (-1 = never). Needs the adb grant. */
    static boolean setSecureMinutes(Context c, String key, int minutes) {
        if (!canWrite(c)) return false;
        try {
            return Settings.Secure.putInt(c.getContentResolver(), key, minutes < 0 ? -1 : minutes * 60_000);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SharedPreferences store(Context c) {
        return c.getSharedPreferences("sandclock_restore", Context.MODE_PRIVATE);
    }
}
