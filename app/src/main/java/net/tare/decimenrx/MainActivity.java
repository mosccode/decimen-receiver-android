package net.tare.decimenrx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The whole shell: one WebView pinned to a local asset, plus the things a
 * browser gives for free and a bare WebView does not — a secure-context origin
 * for the camera, a way to get a blob: download onto disk, and a place to find
 * the files afterwards.
 *
 * The receiver page itself is the official build and is not restyled here, so
 * everything the phone needs that the page does not provide is native: a bottom
 * bar, a result line, a file list.
 */
public final class MainActivity extends Activity {

    /**
     * WebViewAssetLoader serves assets under this https origin, which Chromium
     * treats as a secure context. file:///android_asset is an opaque origin, and
     * an opaque origin gets no camera — that is the same rule that stops the
     * official single-file receiver from working when it is opened from disk.
     */
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String PROBE_URL = ORIGIN + "/assets/probe.html";
    private static final String RECEIVER_URL = ORIGIN + "/assets/decimen-receiver.html";
    private static final int CAMERA_REQUEST = 41;

    private WebView web;
    private TextView status;
    private String shim = "";
    private boolean onReceiver;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            status.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The transfer can take minutes and the page's own wake-lock call is
        // optional chaining that quietly does nothing in a WebView.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#070a11"));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        status.setTextColor(Color.parseColor("#dfe6f2"));
        status.setBackgroundColor(Color.parseColor("#1d2534"));
        int pad = dp(10);
        status.setPadding(pad, pad, pad, pad);
        status.setTextIsSelectable(true);
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#0b1018"));
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(tab("自检", () -> openProbe()));
        bar.addView(tab("已收文件", () -> showFiles()));
        bar.addView(tab("关于", () -> showAbout()));

        setContentView(root);

        shim = readAsset("save-shim.js");
        if (shim.isEmpty()) noteError("注入脚本缺失：收到文件将无法落盘");
        configureWebView();

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.CAMERA }, CAMERA_REQUEST);
        }
        web.loadUrl(RECEIVER_URL);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != CAMERA_REQUEST) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            noteError("没有相机权限就无法接收；在系统设置里允许后重进本页");
            return;
        }
        web.reload();
    }

    private TextView tab(String label, Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTextColor(Color.parseColor("#c9d4e6"));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(15), 0, dp(15));
        t.setOnClickListener(v -> action.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return t;
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                  // the receiver persists one setting
        s.setMediaPlaybackRequiresUserGesture(false);  // "Start camera" is a tap, not a media element
        s.setAllowFileAccess(false);                   // assets come through the loader
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportZoom(true);                        // on a phone, pinching to read small text is not optional

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.addJavascriptInterface(new SaveBridge(this), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || !url.startsWith(ORIGIN + "/assets/")) return;
                onReceiver = url.contains("/decimen-receiver.html");
                ui.removeCallbacks(hideStatus);
                status.setVisibility(View.GONE);
                if (!shim.isEmpty()) view.evaluateJavascript(shim, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                // Only the frame the user is looking at. A missing favicon or a
                // subresource the page's own CSP refused is noise, not a fault.
                if (request.isForMainFrame()) {
                    noteError("页面加载失败 " + error.getErrorCode() + " " + request.getUrl());
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean wantsCamera = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) wantsCamera = true;
                }
                boolean granted = checkSelfPermission(android.Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED;
                if (wantsCamera && granted) {
                    runOnUiThread(() -> request.grant(request.getResources()));
                } else {
                    // Always resolve the request: a page left waiting for an answer
                    // hangs with no clue what it asked for.
                    runOnUiThread(() -> request.deny());
                    if (wantsCamera) noteError("页面要相机，但系统权限还没给");
                }
            }

            @Override
            public void onConsoleMessage(String message, int line, String source) {
                // Deprecated since API 30, and still the one that fires: the
                // framework's newer console hook forwards here by default. It
                // carries no level, so report() only surfaces what actually reads
                // as an uncaught failure rather than painting the bar red over
                // routine page chatter.
                report("log", source, line, message);
            }
        });
    }

    private void report(String level, String source, int line, String message) {
        if (message == null) return;
        boolean uncaught = message.contains("Uncaught") || "error".equalsIgnoreCase(level);
        if (!uncaught) return;
        noteError((source == null ? "" : source.substring(lastSlash(source) + 1) + ":" + line + " ") + message);
    }

    /** A result the user can still read two minutes later, not a Toast that expires. */
    void say(String line) {
        showStatus(line, false);
    }

    void noteError(String line) {
        showStatus(line, true);
    }

    private void showStatus(String line, boolean fault) {
        runOnUiThread(() -> {
            ui.removeCallbacks(hideStatus);
            status.setText(line);
            status.setBackgroundColor(Color.parseColor(fault ? "#b3261e" : "#1d2534"));
            status.setTextColor(Color.parseColor(fault ? "#ffe4e6" : "#dfe6f2"));
            status.setVisibility(View.VISIBLE);
            // A fault stays until the user has read it; a routine result folds away.
            if (!fault) ui.postDelayed(hideStatus, 7000L);
        });
    }

    private void showFiles() {
        String[] rows = Saves.rows(this);
        if (rows.length == 0) {
            say("还没有收到过文件");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("已收文件")
                .setItems(rows, (dialog, which) -> Saves.open(this, which))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage("壳版本 " + versionName()
                        + "\n\n接收端是官方 v0.5.3 的单文件原件，CI 每次构建都按 SHA-256 校验它未被改动。"
                        + "\n\n本应用没有网络权限，断网由系统强制。"
                        + "\n\n上游 Decimen 采用 AGPL-3.0。")
                .setPositiveButton("关闭", null)
                .show();
    }

    private String versionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (Exception missing) {
            return "?";
        }
    }

    @Override
    public void onBackPressed() {
        // The self-check is a sub-page: back returns to the transfer, it does not
        // quit the app. On the receiver itself, back does what back does.
        if (!onReceiver) {
            openReceiver();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    void openProbe() {
        // One camera at a time. The receiver's own track has to be released before
        // the probe asks for one, or the probe's answer is a useless
        // NotReadableError. The page keeps its stream in a variable the shell
        // cannot reach, but every track is on a <video> element in the DOM.
        web.evaluateJavascript("window.__decimenRxEjectCamera && __decimenRxEjectCamera()",
                ignored -> web.loadUrl(PROBE_URL));
    }

    void openReceiver() {
        onReceiver = true;
        web.loadUrl(RECEIVER_URL);
    }

    private static int lastSlash(String url) {
        int slash = url.lastIndexOf('/');
        int query = url.indexOf('?');
        return query >= 0 && query < slash ? query : slash;
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

}
