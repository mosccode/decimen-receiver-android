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

import java.io.File;
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
            entry.put("mime", SaveBridge.usable(mime, name));
            entry.put("at", System.currentTimeMillis() / 1000L);

            JSONArray previous = read(activity);
            JSONArray next = new JSONArray();
            next.put(entry);
            for (int i = 0; i < previous.length() && next.length() < LIMIT; i++) {
                JSONObject old = previous.optJSONObject(i);
                if (old == null) continue;
                // The page's own Save link stays on screen after the shell has
                // auto-saved, so tapping it again is a legitimate second write to
                // a different path — but listing the same file twice is not.
                if (old.optLong("size") == size && old.optString("name").equals(name)) continue;
                next.put(old);
            }
            prefs(activity).edit().putString(KEY, next.toString()).apply();
        } catch (JSONException broken) {
            // A lost history line is not a reason to fail a save that worked.
        }
    }

    static JSONArray entries(MainActivity activity) {
        return read(activity);
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return format(kb, "KB");
        double mb = kb / 1024.0;
        if (mb < 1024) return format(mb, "MB");
        return format(mb / 1024.0, "GB");
    }

    static String when(long epochSeconds) {
        if (epochSeconds <= 0) return "时间未知";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(epochSeconds * 1000L));
    }

    /** Hand the file to another app. The page has no share path in a WebView. */
    static void share(MainActivity activity, int index) {
        JSONObject entry = read(activity).optJSONObject(index);
        if (entry == null) return;
        String uri = entry.optString("uri");
        if (uri.isEmpty()) {
            copyPath(activity, entry);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(SaveBridge.usable(entry.optString("mime"), entry.optString("name")));
        intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(intent, entry.optString("name")));
        } catch (ActivityNotFoundException none) {
            activity.say("没有应用接收这份文件");
        }
    }

    /** Drop the file and its line in the list; the list is the only index. */
    static void remove(MainActivity activity, int index) {
        JSONArray all = read(activity);
        JSONObject entry = all.optJSONObject(index);
        if (entry == null) return;
        String uri = entry.optString("uri");
        String path = entry.optString("path");
        boolean gone = true;
        if (!uri.isEmpty()) {
            gone = activity.getContentResolver().delete(Uri.parse(uri), null, null) > 0;
        } else if (!path.isEmpty()) {
            File file = new File(path);
            gone = !file.exists() || file.delete();
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            if (i != index) next.put(all.opt(i));
        }
        prefs(activity).edit().putString(KEY, next.toString()).apply();
        activity.say(gone ? "已删除 " + entry.optString("name")
                : "记录去掉了，但文件没删掉：" + (uri.isEmpty() ? path : uri));
    }

    static void open(MainActivity activity, int index) {
        JSONObject entry = read(activity).optJSONObject(index);
        if (entry == null) return;
        String uri = entry.optString("uri");
        if (uri.isEmpty()) {
            copyPath(activity, entry);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        // Re-derived rather than trusted: a line written before the shell learned
        // to carry the blob's type says octet-stream for a PNG, and then 相册 is
        // not in the list no matter what the file itself is.
        intent.setDataAndType(Uri.parse(uri),
                SaveBridge.usable(entry.optString("mime"), entry.optString("name")));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException none) {
            activity.say("这台机器上没有能打开 " + entry.optString("name") + " 的应用");
        }
    }

    /**
     * Below API 29 the file sits in this app's own directory. There is no content
     * Uri to grant, and a file:// Uri handed to another app trips
     * FileUriExposedException, so the path goes to the clipboard instead.
     */
    private static void copyPath(MainActivity activity, JSONObject entry) {
        String path = entry.optString("path");
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Decimen", path));
        }
        activity.say("已复制路径：" + path);
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

    private static String format(double value, String unit) {
        return String.format(Locale.getDefault(), "%.1f %s", value, unit);
    }
}
