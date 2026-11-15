package com.local.sandclock;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks this app's GitHub releases for a newer version and installs it through Android's own
 * installer (which asks the viewer to confirm). Android only accepts the update if it is signed
 * with the same key as the installed app, so a tampered download can't install.
 * Network calls block: run them off the main thread.
 */
final class Updater {
    static final String ACTION_INSTALL_STATUS = "com.local.sandclock.INSTALL_STATUS";
    private static final Pattern APK = Pattern.compile("sand-clock-(\\d+)\\.apk");

    String latestName;
    int latestCode = -1;
    private String apkUrl;

    /** Looks up the latest release. Returns true if it is newer than this build. */
    boolean check() throws IOException {
        HttpURLConnection c = open("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) throw new IOException("no releases yet");
        if (code != 200) throw new IOException("GitHub replied " + code);
        try {
            JSONObject release = new JSONObject(readAll(c.getInputStream()));
            latestName = release.optString("tag_name", "?").replaceFirst("^v", "");
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                Matcher m = APK.matcher(a.optString("name"));
                if (m.matches()) {
                    latestCode = Integer.parseInt(m.group(1));
                    apkUrl = a.getString("browser_download_url");
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected reply from GitHub");
        } finally {
            c.disconnect();
        }
        if (apkUrl == null) throw new IOException("release has no app file");
        return latestCode > BuildConfig.VERSION_CODE;
    }

    /** Downloads the update and hands it to Android's installer, which asks to confirm. */
    void install(Context ctx) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            HttpURLConnection c = open(apkUrl);
            if (c.getResponseCode() != 200) throw new IOException("download failed (" + c.getResponseCode() + ")");
            InputStream in = c.getInputStream();
            OutputStream out = session.openWrite("base.apk", 0, -1);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            session.fsync(out);
            out.close();
            in.close();
            c.disconnect();
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(ctx, id, status, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SandClock-Updater");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
