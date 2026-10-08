package net.tare.decimenrx;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What arrived and where it went. The receiver page has no history of its own,
 * and the only trace of a finished save used to be a Toast that expired before
 * anyone could read the path — so the shell keeps the list.
 *
 * Entries are written when a save completes and read back from here, never
 * scraped from MediaStore: files this app owns are exactly the ones worth
 * listing, and no storage permission is needed to name them again.
 */
final class Saves {

    private static final String PREFS = "decimen-saves";
    private static final String KEY = "list";
    private static final int LIMIT = 40;

    private Saves() {
    }

    static void record(MainActivity activity, String name, String path, String uri, long size, String mime) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("name", name);
            entry.put("path", path == null ? "" : path);
            entry.put("uri", uri == null ? "" : uri);
            entry.put("size", size);
            entry.put("mime", mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
            entry.put("at", System.currentTimeMillis() / 1000L);

            JSONArray previous = read(activity);
            JSONArray next = new JSONArray();
            next.put(entry);
            for (int i = 0; i < previous.length() && next.length() < LIMIT; i++) {
                next.put(previous.opt(i));
            }
            prefs(activity).edit().putString(KEY, next.toString()).apply();
        } catch (JSONException broken) {
            // A lost history line is not a reason to fail a save that worked.
        }
    }

    static String[] rows(MainActivity activity) {
        JSONArray all = read(activity);
        String[] out = new String[all.length()];
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            out[i] = entry == null ? "损坏的记录"
                    : entry.optString("name") + " · " + human(entry.optLong("size"))
                            + " · " + when(entry.optLong("at"));
        }
        return out;
    }

    static void open(MainActivity activity, int index) {
        JSONObject entry = read(activity).optJSONObject(index);
        if (entry == null) return;
        String uri = entry.optString("uri");
        if (uri.isEmpty()) {
            // Below API 29 the file sits in this app's own directory. A file://
            // Uri handed to another app trips FileUriExposedException, so give the
            // path to the user instead of a crash.
            String path = entry.optString("path");
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Decimen", path));
            }
            activity.say("已复制路径：" + path);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(uri), entry.optString("mime", "application/octet-stream"));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException none) {
            activity.say("这台机器上没有能打开 " + entry.optString("name") + " 的应用");
        }
    }

    private static JSONArray read(MainActivity activity) {
        try {
            return new JSONArray(prefs(activity).getString(KEY, "[]"));
        } catch (JSONException firstRun) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(MainActivity activity) {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return format(kb, "KB");
        double mb = kb / 1024.0;
        if (mb < 1024) return format(mb, "MB");
        return format(mb / 1024.0, "GB");
    }

    private static String format(double value, String unit) {
        return String.format(Locale.getDefault(), "%.1f %s", value, unit);
    }

    private static String when(long epochSeconds) {
        if (epochSeconds <= 0) return "时间未知";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(epochSeconds * 1000L));
    }
}
