package com.local.sandclock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Closes one of the app's own screens when the screensaver starts, so dismissing the
 * screensaver returns to the home screen (or whatever was open before), not to these screens.
 */
final class CloseOnScreensaver extends BroadcastReceiver {
    private final Activity activity;

    private CloseOnScreensaver(Activity activity) { this.activity = activity; }

    static CloseOnScreensaver register(Activity activity) {
        CloseOnScreensaver r = new CloseOnScreensaver(activity);
        IntentFilter f = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(r, f);
        }
        return r;
    }

    void unregister() {
        try { activity.unregisterReceiver(this); } catch (IllegalArgumentException ignored) { }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        activity.finish();
    }
}
