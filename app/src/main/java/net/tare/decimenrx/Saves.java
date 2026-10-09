package net.tare.decimenrx;

import android.app.AlertDialog;
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
    private static final String PACKAGE = "application/vnd.android.package-archive";

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

    /** Where a row says the bytes live, on the screen and in the install hint. */
    static String where(JSONObject entry) {
        String path = entry.optString("path");
        if (!path.isEmpty()) return path;
        return entry.optString("uri").isEmpty() ? "位置未知" : "Download/Decimen";
    }

    /**
     * Re-derived rather than trusted: a line written before the shell learned to
     * carry the blob's type says octet-stream for a PNG, and then 相册 is not in
     * the list no matter what the file itself is.
     */
    static String usableType(JSONObject entry) {
        return SaveBridge.usable(entry.optString("mime"), entry.optString("name"));
    }

    /** Hand the file to another app. The page has no share path in a WebView. */
    static void share(MainActivity activity, JSONObject entry) {
        if (entry == null) return;
        String uri = entry.optString("uri");
        if (uri.isEmpty()) {
            copyPath(activity, entry);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(usableType(entry));
        intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(intent, entry.optString("name")));
        } catch (ActivityNotFoundException none) {
            activity.noteError("没有应用接收这份文件");
        } catch (RuntimeException refused) {
            activity.noteError("分享被系统拒绝：" + reason(refused));
        }
    }

    /**
     * Drop the record and its file. Called with the record the row was drawn
     * from, and located by its own uri/path — a position in the list is not
     * stable while transfers land.
     */
    static void remove(MainActivity activity, JSONObject entry) {
        JSONArray all = read(activity);
        int index = indexOf(all, entry);
        if (index < 0) {
            activity.say("这条记录已经不在列表里了");
            return;
        }
        JSONObject row = all.optJSONObject(index);
        String uri = row.optString("uri");
        String path = row.optString("path");
        boolean gone = true;
        try {
            if (!uri.isEmpty()) {
                gone = activity.getContentResolver().delete(Uri.parse(uri), null, null) > 0;
            } else if (!path.isEmpty()) {
                File file = new File(path);
                gone = !file.exists() || file.delete();
            }
        } catch (RuntimeException refused) {
            gone = false;
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            if (i != index) next.put(all.opt(i));
        }
        prefs(activity).edit().putString(KEY, next.toString()).apply();
        if (gone) {
            activity.say("已删除 " + row.optString("name"));
        } else {
            // The index is gone but the bytes are not: say so in the colour that
            // means "this needs doing about", because the file still fills storage.
            activity.noteError("记录去掉了，但文件没删掉：" + (uri.isEmpty() ? path : uri));
        }
    }

    private static int indexOf(JSONArray all, JSONObject want) {
        if (want == null) return -1;
        String uri = want.optString("uri");
        String path = want.optString("path");
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.optJSONObject(i);
            if (row == null) continue;
            if (!uri.isEmpty() && uri.equals(row.optString("uri"))) return i;
            if (!path.isEmpty() && path.equals(row.optString("path"))) return i;
        }
        return -1;
    }

    /**
     * 安装包不能像别的文件那样交出去：Android 8 起，调起安装器要求发起方在清单里声明
     * REQUEST_INSTALL_PACKAGES，而本应用刻意不申请——一个离线接收端不该有发起安装的
     * 能力。缺了它，系统会把请求静默吞掉，用户看到的就是「点了没反应」。所以这里说清
     * 文件在哪、去哪儿点，并给仍然想试一次的人留一颗按钮。
     */
    static void open(MainActivity activity, JSONObject entry) {
        if (entry == null) return;
        String mime = usableType(entry);
        String name = entry.optString("name");
        if (PACKAGE.equals(mime) || name.toLowerCase(Locale.US).endsWith(".apk")) {
            packageHint(activity, entry, mime);
            return;
        }
        launch(activity, entry, mime, false);
    }

    private static void packageHint(final MainActivity activity, final JSONObject entry, final String mime) {
        new AlertDialog.Builder(activity)
                .setTitle("这是安装包，本应用不代你调起安装")
                .setMessage(entry.optString("name") + " · " + human(entry.optLong("size"))
                        + "\n\n文件已经在 " + where(entry) + " 里。要装它，请在系统的「文件管理 → 下载 → Decimen」中点这个文件。"
                        + "本应用没有申请安装权限，由它调起安装器只会被系统悄悄拒绝。")
                .setPositiveButton("复制完整路径", (dialog, which) -> copyPath(activity, entry))
                .setNeutralButton("仍然试一次", (dialog, which) -> launch(activity, entry, mime, true))
                .setNegativeButton("知道了", null)
                .show();
    }

    private static void launch(MainActivity activity, JSONObject entry, String mime, boolean chooser) {
        String uri = entry.optString("uri");
        if (uri.isEmpty()) {
            copyPath(activity, entry);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(uri), mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (chooser) intent = Intent.createChooser(intent, entry.optString("name"));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException none) {
            if (!chooser) {
                // 没有默认处理程序不等于没有应用读得了它：让用户自己挑一个。
                launch(activity, entry, mime, true);
                return;
            }
            activity.noteError("这台机器上没有能打开 " + mime + " 的应用");
        } catch (RuntimeException refused) {
            // The failure the user reported was this one, silent.
            activity.noteError("系统拒绝了这次打开：" + reason(refused));
        }
    }

    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : " · " + message);
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
