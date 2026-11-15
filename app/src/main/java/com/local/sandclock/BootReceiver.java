package com.local.sandclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot or app update: undo any leftover dimming and re-select the screensaver. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        android.util.Log.i("SandClock", "boot/update: " + intent.getAction());
        SystemTweaks.restoreBacklight(context);
        SystemTweaks.ensureScreensaver(context);
    }
}
