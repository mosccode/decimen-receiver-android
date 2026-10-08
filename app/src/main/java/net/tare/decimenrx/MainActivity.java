package net.tare.decimenrx;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
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
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The whole shell: one WebView pinned to a local asset, plus the two things a
 * browser gives for free and a bare WebView does not — a secure-context origin
 * for the camera, and a way to get a blob: download onto disk.
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
    private TextView banner;
    private String shim = "";
    private boolean onReceiver;
    private int bannerLines;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The transfer can take minutes and the page's own wake-lock call is
        // optional chaining that quietly does nothing in a WebView.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#070a11"));

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        banner = new TextView(this);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        banner.setTextColor(Color.parseColor("#ffe4e6"));
        banner.setBackgroundColor(Color.parseColor("#b3261e"));
        int pad = (int) (getResources().getDisplayMetrics().density * 10f);
        banner.setPadding(pad, pad, pad, pad);
        banner.setTextIsSelectable(true);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);

        shim = readAsset("save-shim.js");
        if (shim.isEmpty()) note("注入脚本缺失：收到文件将无法落盘");
        configureWebView();

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.CAMERA }, CAMERA_REQUEST);
            // The probe still loads: reporting "permission denied" on screen is a
            // result, and it is the one this screen exists to produce.
        }
        web.loadUrl(PROBE_URL);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == CAMERA_REQUEST) web.loadUrl(PROBE_URL);
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
        s.setSupportZoom(false);

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
                banner.setVisibility(View.GONE);
                bannerLines = 0;
                if (!shim.isEmpty()) view.evaluateJavascript(shim, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                // Only the frame the user is looking at. A missing favicon or a
                // subresource the page's own CSP refused is noise, not a fault.
                if (request.isForMainFrame()) {
                    note("页面加载失败 " + error.getErrorCode() + " " + request.getUrl());
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
                    runOnUiThread(() -> request.deny());
                    showToast(getString(R.string.camera_needed));
                }
            }

            @Override
            public void onConsoleMessage(String message, int line, String source) {
                // Deprecated since API 30, and still the one that fires everywhere:
                // the framework's newer console hook forwards here by default. It
                // carries no level, so report() only surfaces what actually reads
                // as an uncaught failure rather than painting the screen red over
                // routine page chatter.
                report("log", source, line, message);
            }
        });
    }

    private void report(String level, String source, int line, String message) {
        if (message == null) return;
        boolean uncaught = message.contains("Uncaught") || "error".equalsIgnoreCase(level);
        if (!uncaught) return;
        note((source == null ? "" : source.substring(lastSlash(source) + 1) + ":" + line + " ") + message);
    }

    /** Keeps at most three distinct lines on screen — the user has to read this. */
    private void note(String line) {
        runOnUiThread(() -> {
            CharSequence current = banner.getText();
            if (current.toString().contains(line)) return;
            if (bannerLines >= 3) return;
            banner.append(bannerLines == 0 ? "" : "\n");
            banner.append(line);
            bannerLines++;
            banner.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onBackPressed() {
        if (onReceiver) {
            onReceiver = false;
            web.loadUrl(PROBE_URL);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    void openReceiver() {
        onReceiver = true;
        web.loadUrl(RECEIVER_URL);
    }

    void showToast(String text) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show());
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
