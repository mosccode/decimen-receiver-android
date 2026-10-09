package net.tare.decimenrx;

import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * The only things the page may ask the device for: write bytes to storage, put
 * text on the clipboard, open the receiver.
 *
 * The file name is chosen by whatever is in front of the camera, so it arrives
 * here as hostile input — the sender could name a file "../../x" or a control
 * character. It is reduced to a basename before it reaches the filesystem.
 */
public final class SaveBridge {

    private static final String GENERIC = "application/octet-stream";

    private final MainActivity activity;

    private String session;
    private OutputStream out;
    private Uri mediaUri;
    private File plainFile;
    private String name = "";
    private String mime = GENERIC;
    private String display = "";
    private String savedUri = "";
    private String savedPath = "";
    private long written;
    private long expected = -1;

    SaveBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public synchronized void saveBegin(String session, String rawName, String mime, long sizeHint) {
        discard();
        this.session = session == null ? "" : session;
        name = safeName(rawName);
        this.mime = usable(mime, name);
        savedUri = "";
        savedPath = "";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, this.mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Decimen");
                mediaUri = activity.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (mediaUri == null) throw new IOException("MediaStore 拒绝了写入请求");
                out = activity.getContentResolver().openOutputStream(mediaUri, "w");
                if (out == null) throw new IOException("MediaStore 打不开写入流");
                savedUri = mediaUri.toString();
                display = "Download/Decimen/" + name;
            } else {
                File root = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (root == null) throw new IOException("这台设备没有可写的外部存储");
                File dir = new File(root, "Decimen");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("建不出 " + dir.getPath());
                plainFile = unique(dir, name);
                out = new FileOutputStream(plainFile);
                savedPath = plainFile.getAbsolutePath();
                display = savedPath;
            }
            written = 0;
            expected = sizeHint;
        } catch (Throwable failure) {
            discard();
            activity.noteError(activity.getString(R.string.save_failed) + "：" + failure);
        }
    }

    @JavascriptInterface
    public synchronized void saveChunk(String session, String base64) {
        if (out == null || !sameSession(session)) return;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            out.write(bytes);
            written += bytes.length;
        } catch (Throwable failure) {
            discard();
            activity.noteError(activity.getString(R.string.save_failed) + "：" + failure);
        }
    }

    @JavascriptInterface
    public synchronized void saveEnd(String session) {
        if (out == null || !sameSession(session)) return;
        long size = written;
        long wanted = expected;
        try {
            out.flush();
            out.close();
        } catch (Throwable failure) {
            discard();
            activity.noteError(activity.getString(R.string.save_failed) + "：" + failure);
            return;
        }
        out = null;

        if (wanted >= 0 && wanted != size) {
            // The page counted the bytes it handed over; a shortfall means the last
            // chunk never arrived. Keep it and it looks like a received file — and
            // the container's own check would only fail later, far from here.
            discard();
            activity.noteError("只收到 " + size + "/" + wanted + " 字节，这份没有保留");
            return;
        }

        // Stop pointing at the destination: the next saveBegin() opens by discarding
        // whatever this one still refers to, and a finished file must not be on that
        // list — it would be deleted as debris of the transfer after it.
        this.session = null;
        mediaUri = null;
        plainFile = null;
        written = 0;
        expected = -1;

        activity.say("已保存 " + display + " · " + size + " 字节");
        Saves.record(activity, name, savedPath, savedUri, size, mime);
        activity.fileLanded();
    }

    @JavascriptInterface
    public void saveFailed(String why) {
        discard();
        activity.noteError("页面没能把文件交出来：" + why);
    }

    @JavascriptInterface
    public void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Decimen", text == null ? "" : text));
        activity.say("已复制，粘贴给助手即可");
    }

    @JavascriptInterface
    public void openReceiver() {
        activity.runOnUiThread(() -> activity.openReceiver());
    }

    /** Lets the self-check page name the build it is reporting from. */
    @JavascriptInterface
    public String shellVersion() {
        try {
            String name = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (Throwable unknown) {
            return "?";
        }
    }

    private boolean sameSession(String other) {
        return session != null && session.equals(other);
    }

    /** A half-written file that looks like a received one is worse than none. */
    private void discard() {
        if (out != null) {
            try {
                out.close();
            } catch (Throwable ignored) {
            }
            out = null;
        }
        if (mediaUri != null) {
            activity.getContentResolver().delete(mediaUri, null, null);
            mediaUri = null;
        }
        if (plainFile != null && plainFile.exists() && !plainFile.delete()) {
            plainFile.deleteOnExit();
        }
        plainFile = null;
        session = null;
        savedUri = "";
        savedPath = "";
        written = 0;
        expected = -1;
    }

    private static File unique(File dir, String name) {
        File candidate = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String tail = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; candidate.exists() && n < 1000; n++) {
            candidate = new File(dir, stem + " (" + n + ")" + tail);
        }
        return candidate;
    }

    /**
     * The type a file is filed under decides which apps the system will offer for
     * it: an image stored as octet-stream never reaches 相册, however obvious its
     * name is. So take the page's word when it is a real type, and otherwise fall
     * back to the extension — a blob the sender never labelled comes back with no
     * type at all, and a mislabelled one says octet-stream.
     */
    static String usable(String mime, String name) {
        String value = mime == null ? "" : mime.trim();
        int parameter = value.indexOf(';');
        if (parameter >= 0) value = value.substring(0, parameter).trim();
        if (value.matches("[\\w.!#$&^+\\-.]+/[\\w.!#$&^+\\-.]+") && !GENERIC.equals(value)) return value;
        int dot = name.lastIndexOf('.');
        String guessed = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                name.substring(dot + 1).toLowerCase(Locale.US));
        return guessed == null || guessed.isEmpty() ? GENERIC : guessed;
    }

    static String safeName(String raw) {        String value = raw == null ? "" : raw.trim();
        int cut = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (cut >= 0) value = value.substring(cut + 1);
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < value.length() && kept.length() < 120; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            kept.append("/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String name = kept.toString().trim();
        while (name.startsWith(".")) name = name.substring(1);
        return name.isEmpty() ? "decimen.bin" : name;
    }
}
