package net.tare.decimenrx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
 * The receiver page stays the official build, byte for byte. What it looks like
 * is the shell's business, so its layout is rewritten at runtime by
 * receiver-ui.css and the affordances it lacks are driven through its own
 * elements by receiver-ui.js. Everything a WebView cannot do from inside the
 * page is native here: three screens, a control strip, and a file list that can
 * open, share and delete.
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

    private static final int SCREEN_RECEIVE = 0;
    private static final int SCREEN_FILES = 1;
    private static final int SCREEN_SETTINGS = 2;
    private static final int SCREEN_PROBE = 3;

    private static final int CONTROL_BAR_DP = 50;
    private static final int NAV_BAR_DP = 52;

    private static final String INK = "#070a11";
    private static final String INK_SOFT = "#0b1018";
    private static final String LINE = "#1d2534";
    private static final String TEXT = "#dfe6f2";
    private static final String DIM = "#8b98ae";
    private static final String ACCENT = "#58c8ff";
    private static final String FAULT = "#b3261e";

    private WebView web;
    private TextView status;
    private TextView pauseAction;
    private TextView torchAction;
    private TextView resetAction;
    private LinearLayout controlBar;
    private LinearLayout fileList;
    private LinearLayout settingsList;
    private View filesPanel;
    private View settingsPanel;
    private final TextView[] navTabs = new TextView[3];

    private String shim = "";
    private String pageCss = "";
    private String pageDriver = "";
    private boolean onReceiver;
    private int screen = SCREEN_RECEIVE;
    private boolean paused;
    private boolean torchOn;
    private JSONObject pageSettings;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean foreground = true;
    private long lastRecovery;
    private String pendingNote;
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            status.setVisibility(View.GONE);
        }
    };
    private final Runnable pollPage = new Runnable() {
        @Override
        public void run() {
            if (screen != SCREEN_RECEIVE || !onReceiver || !foreground) return;
            evaluate("__decimenRxUi && __decimenRxUi.state()", raw -> {
                JSONObject state = parse(raw);
                if (state != null) applyState(state);
            });
            ui.postDelayed(this, 1200L);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The transfer can take minutes and the page's own wake-lock call is
        // optional chaining that quietly does nothing in a WebView.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int bars = dp(CONTROL_BAR_DP + NAV_BAR_DP);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(INK));

        web = new WebView(this);
        FrameLayout.LayoutParams webParams = fullFrame(bars);
        root.addView(web, webParams);

        filesPanel = panel("已收文件", true);
        root.addView(filesPanel, fullFrame(dp(NAV_BAR_DP)));
        settingsPanel = panel("设置", false);
        root.addView(settingsPanel, fullFrame(dp(NAV_BAR_DP)));

        // The bars sit in one stack pinned to the bottom. Their combined height is
        // what the WebView is inset by, and that inset is a constant: the page
        // lays its preview out against 100dvh, so resizing the WebView would
        // resize the camera picture mid-transfer.

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams stackParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        stackParams.gravity = Gravity.BOTTOM;
        root.addView(stack, stackParams);

        controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.HORIZONTAL);
        controlBar.setBackgroundColor(Color.parseColor(INK_SOFT));
        controlBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        stack.addView(controlBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(CONTROL_BAR_DP)));
        pauseAction = control("暂停", () -> togglePause());
        torchAction = control("手电筒", () -> toggleTorch());
        resetAction = control("重置", () -> reset());
        controlBar.addView(pauseAction, controlParams());
        controlBar.addView(torchAction, controlParams());
        controlBar.addView(resetAction, controlParams());

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(Color.parseColor(INK_SOFT));
        stack.addView(nav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(NAV_BAR_DP)));
        nav.addView(navTab(0, "接收", () -> show(SCREEN_RECEIVE)));
        nav.addView(navTab(1, "文件", () -> show(SCREEN_FILES)));
        nav.addView(navTab(2, "设置", () -> show(SCREEN_SETTINGS)));

        // Added last so a save confirmation is never buried under a panel.
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        status.setTextColor(Color.parseColor(TEXT));
        status.setBackgroundColor(Color.parseColor(LINE));
        int pad = dp(10);
        status.setPadding(pad, pad, pad, pad);
        status.setTextIsSelectable(true);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.BOTTOM;
        statusParams.bottomMargin = bars;
        root.addView(status, statusParams);

        setContentView(root);

        shim = readAsset("save-shim.js");
        pageCss = readAsset("receiver-ui.css");
        pageDriver = readAsset("receiver-ui.js");
        if (shim.isEmpty()) noteError("注入脚本缺失：收到文件将无法落盘");
        if (pageCss.isEmpty() || pageDriver.isEmpty()) noteError("界面脚本缺失：接收页将保持网页排版");
        configureWebView();

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.CAMERA }, CAMERA_REQUEST);
        }
        web.loadUrl(RECEIVER_URL);
        show(SCREEN_RECEIVE);
    }

    /**
     * Android takes the camera away from a backgrounded app and gives it to
     * whatever came forward, and the page never hears about it — it listens to
     * no visibility event at all, so its frame loop just stops being called and
     * the preview freezes on the last frame. Freezing the renderer here keeps the
     * page from spinning against a dead stream, and the resume half hands both
     * back to the watchdog, which is what actually brings the picture up again.
     */
    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        ui.removeCallbacks(pollPage);
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        foreground = true;
        if (screen == SCREEN_RECEIVE && onReceiver) ui.postDelayed(pollPage, 400L);
    }

    private FrameLayout.LayoutParams fullFrame(int bottomMargin) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    // ------------------------------------------------------------------ 屏幕

    private void show(int which) {
        screen = which;
        filesPanel.setVisibility(which == SCREEN_FILES ? View.VISIBLE : View.GONE);
        settingsPanel.setVisibility(which == SCREEN_SETTINGS ? View.VISIBLE : View.GONE);
        // INVISIBLE keeps the strip's space instead of collapsing it, and an
        // invisible view takes no touch, so the panel below reaches all the way
        // down and reads through the gap.
        controlBar.setVisibility(which == SCREEN_RECEIVE ? View.VISIBLE : View.INVISIBLE);
        for (int i = 0; i < navTabs.length; i++) {
            styleTab(navTabs[i], which == i || (which == SCREEN_PROBE && i == 2));
        }
        ui.removeCallbacks(pollPage);

        if (which == SCREEN_RECEIVE) {
            if (!onReceiver) openReceiver();
            // 进屏即开镜头。合成点击未必被 Chromium 当作真实手势：那时页面自己的
            // 错误路径会把启动按钮留在屏上（样式已把它放大成整屏），一次轻触即开。
            evaluate("__decimenRxUi && __decimenRxUi.start()", null);
            ui.postDelayed(pollPage, 400L);
            return;
        }
        if (which == SCREEN_FILES) {
            rebuildFiles();
            return;
        }
        if (which == SCREEN_SETTINGS) {
            if (!onReceiver) openReceiver();
            pullSettings();
        }
    }

    private void pullSettings() {
        evaluate("__decimenRxUi && __decimenRxUi.settings()", raw -> {
            pageSettings = parse(raw);
            rebuildSettings();
        });
    }

    private TextView navTab(int index, String label, Runnable action) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tab.setGravity(Gravity.CENTER);
        tab.setOnClickListener(view -> action.run());
        tab.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        navTabs[index] = tab;
        return tab;
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setTextColor(Color.parseColor(active ? ACCENT : "#c9d4e6"));
        tab.getPaint().setFakeBoldText(active);
        tab.invalidate();
    }

    private TextView control(String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTextColor(Color.parseColor(TEXT));
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(view -> action.run());
        paintControl(button, false, true);
        return button;
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    /**
     * Three states, and the grey one is really inert: a disabled view takes no
     * click, so a control that cannot do anything yet cannot be tapped by
     * mistake. That is the difference between a control strip and a decoration.
     */
    private void paintControl(TextView button, boolean active, boolean enabled) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(9));
        shape.setColor(Color.parseColor(active ? "#16321f" : "#131a26"));
        shape.setStroke(dp(1), Color.parseColor(enabled ? (active ? "#2f6f4f" : LINE) : "#141a24"));
        button.setBackground(shape);
        button.setTextColor(Color.parseColor(enabled ? (active ? "#b9f0cf" : TEXT) : "#4b5566"));
        button.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ 驱动

    private void togglePause() {
        final int next = paused ? 0 : 1;
        evaluate("__decimenRxUi && __decimenRxUi.setPaused(" + next + ")", raw -> {
            paused = "true".equals(raw);
            pauseAction.setText(paused ? "继续" : "暂停");
            paintControl(pauseAction, paused, true);
        });
    }

    private void toggleTorch() {
        evaluate("__decimenRxUi && __decimenRxUi.torch()", raw -> {
            if (!"true".equals(raw)) {
                notice("这颗镜头不支持补光");
                return;
            }
            torchOn = !torchOn;
            paintControl(torchAction, torchOn, true);
        });
    }

    /** The page's own 「接收另一个文件」 is a location.reload(); so is this. */
    private void reset() {
        paused = false;
        torchOn = false;
        // A deliberate reset gets its own chance to recover on its own.
        lastRecovery = 0L;
        if (onReceiver) web.reload();
        else openReceiver();
    }

    private void applyState(JSONObject state) {
        if (state.optBoolean("stalled")) {
            recoverStall();
            return;
        }
        boolean running = state.optBoolean("running");
        paused = state.optBoolean("paused");
        torchOn = state.optBoolean("torchOn");
        pauseAction.setText(paused ? "继续" : "暂停");
        paintControl(pauseAction, paused, running);
        paintControl(torchAction, torchOn, state.optBoolean("torch"));
    }

    /**
     * The watchdog inside the page already tried the cheap repair (push playback
     * again); a frame counter that still has not moved means the stream is gone.
     * Reopening the receiver is the only restart the page supports from here, and
     * it is the same path 重置 takes — so say so on the bar, because a transfer in
     * progress really was lost.
     */
    private void recoverStall() {
        long now = SystemClock.uptimeMillis();
        if (now - lastRecovery < 20000L) {
            ui.removeCallbacks(pollPage);
            paintControl(pauseAction, false, false);
            paintControl(torchAction, false, false);
            noteError("重新打开接收之后画面仍然没有帧：镜头多半还被别的应用占着，关掉它再按重置");
            return;
        }
        lastRecovery = now;
        pendingNote = "刚才退到后台时镜头被系统收回，壳已重新打开接收；那一次未传完的要重发";
        web.reload();
    }

    private void evaluate(String script, PageValue callback) {
        final PageValue sink = callback;
        runOnUiThread(() -> web.evaluateJavascript(script, raw -> {
            if (sink != null) sink.call(raw);
        }));
    }

    private interface PageValue {
        void call(String raw);
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException notObject) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 文件屏

    private ScrollView panel(String title, boolean intoFiles) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(INK));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(14), dp(14), dp(14), dp(20));
        scroll.addView(column);

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        head.setTextColor(Color.parseColor(TEXT));
        head.setPadding(0, 0, 0, dp(6));
        column.addView(head);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        column.addView(list);
        if (intoFiles) fileList = list;
        else settingsList = list;
        return scroll;
    }

    private void rebuildFiles() {
        JSONArray all = Saves.entries(this);
        fileList.removeAllViews();
        if (all.length() == 0) {
            fileList.addView(line("还没有收到过文件", DIM, 15f));
            return;
        }
        for (int i = 0; i < all.length(); i++) {
            final JSONObject entry = all.optJSONObject(i);
            if (entry == null) continue;
            if (fileList.getChildCount() > 0) fileList.addView(divider());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(11), 0, dp(11));
            row.addView(line(entry.optString("name"), TEXT, 16f));
            row.addView(line(Saves.human(entry.optLong("size")) + " · "
                    + Saves.when(entry.optLong("at")) + " · " + Saves.where(entry), DIM, 12f));

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(7), 0, 0);
            // The record itself travels to the handler, never its row number: a
            // file can land at any moment (the page auto-saves), and each arrival
            // pushes every existing row one slot down. Acting on a position would
            // then delete a different file than the one whose 删除 was tapped.
            actions.addView(small("打开", () -> Saves.open(MainActivity.this, entry)));
            actions.addView(small("分享", () -> Saves.share(MainActivity.this, entry)));
            actions.addView(small("删除", () -> confirmDelete(entry)));
            row.addView(actions);
            fileList.addView(row);
        }
    }

    /** Deleting throws the file away for good, so it asks first. */
    private void confirmDelete(JSONObject entry) {
        new AlertDialog.Builder(this)
                .setTitle("删掉这个文件？")
                .setMessage(entry.optString("name") + " · " + Saves.human(entry.optLong("size")))
                .setPositiveButton("删除", (dialog, which) -> {
                    Saves.remove(MainActivity.this, entry);
                    rebuildFiles();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Called from the save bridge: a finished file must not arrive to a stale list. */
    void fileLanded() {
        if (screen != SCREEN_FILES) return;
        runOnUiThread(this::rebuildFiles);
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(Color.parseColor(LINE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.topMargin = dp(4);
        line.setLayoutParams(params);
        return line;
    }

    private TextView line(String text, String color, float sp) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        row.setTextColor(Color.parseColor(color));
        return row;
    }

    private TextView small(String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTextColor(Color.parseColor(TEXT));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setOnClickListener(view -> action.run());
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(9));
        shape.setColor(Color.parseColor("#131a26"));
        shape.setStroke(dp(1), Color.parseColor(LINE));
        button.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    // ------------------------------------------------------------------ 设置屏

    private void rebuildSettings() {
        settingsList.removeAllViews();
        JSONObject page = pageSettings;
        if (page == null) {
            settingsList.addView(setting("采集参数", "未启动", () -> show(SCREEN_RECEIVE)));
        } else {
            settingsList.addView(section("摄像头与解码"));
            settingsList.addView(setting("镜头", labelFor(page, "camera", "cameraValue"),
                    picker(page, "camera", "cameraValue", "镜头")));
            settingsList.addView(setting("采集宽度", page.optString("widthValue"),
                    picker(page, "width", "widthValue", "采集宽度")));
            settingsList.addView(setting("采集帧率", page.optString("capfpsValue"),
                    picker(page, "capfps", "capfpsValue", "采集帧率")));
            settingsList.addView(setting("解码线程", page.optString("workersValue"),
                    picker(page, "workers", "workersValue", "解码线程")));
            final boolean autoshow = page.optBoolean("autoshow");
            settingsList.addView(setting("收到即展示", autoshow ? "开" : "关",
                    () -> writeSetting("autoshow", autoshow ? "false" : "true")));
        }
        // 记下来的参数归壳所有，所以「回到官方默认」也只有壳能给：清掉自己那组键，
        // 再让页面重新加载一次 —— 它自己的默认值（宽度 1280、帧率 60、线程推到硬件
        // 上限、镜头 auto）就是这么长出来的，不需要在这里抄一份。
        settingsList.addView(setting("恢复默认参数", "↺", () -> restoreDefaults()));
        if (page != null) {
            String actual = page.optString("actual");
            if (!actual.isEmpty()) {
                settingsList.addView(section("当前生效"));
                settingsList.addView(line(actual, DIM, 13f));
            }
        }
        settingsList.addView(section("诊断"));
        settingsList.addView(setting("摄像头自检", "›", () -> openProbe()));
        settingsList.addView(setting("关于", versionName(), () -> showAbout()));
    }

    private void restoreDefaults() {
        evaluate("__decimenRxUi && __decimenRxUi.clearPrefs()", ignored -> {
            pendingNote = "采集参数已恢复默认，镜头重新按自动选择打开";
            // onPageFinished pulls the settings screen again once the page is back.
            reset();
        });
    }

    private TextView section(String label) {
        TextView head = line(label, ACCENT, 13f);
        head.setPadding(0, dp(16), 0, dp(4));
        head.getPaint().setFakeBoldText(true);
        return head;
    }

    private View setting(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(13));
        TextView name = line(label, TEXT, 15f);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);
        TextView current = line(value, DIM, 14f);
        current.setPadding(dp(10), 0, 0, 0);
        row.addView(current);
        if (action != null) {
            row.setOnClickListener(view -> action.run());
            row.setClickable(true);
        }
        return row;
    }

    /**
     * The page owns these values, so setting one means writing its own
     * &lt;select&gt; and dispatching the change its handlers already listen
     * for — the live-apply path stays theirs.
     */
    private Runnable picker(final JSONObject page, final String key, final String valueKey,
            final String title) {
        return () -> {
            JSONArray options = page.optJSONArray(key);
            if (options == null || options.length() == 0) return;
            final String[] labels = new String[options.length()];
            final String[] values = new String[options.length()];
            int checked = 0;
            String current = page.optString(valueKey);
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                labels[i] = option.optString("t");
                values[i] = option.optString("v");
                if (values[i].equals(current)) checked = i;
            }
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        dialog.dismiss();
                        writeSetting(key, js(values[which]));
                    })
                    .setNegativeButton("取消", null)
                    .show();
        };
    }

    private void writeSetting(String key, String jsValue) {
        evaluate("__decimenRxUi.setSetting(" + js(key) + ", " + jsValue + ")", raw -> {
            if ("false".equals(raw)) noteError("页面拒绝了这个值");
            pullSettings();
        });
    }

    private String labelFor(JSONObject page, String key, String valueKey) {
        JSONArray options = page.optJSONArray(key);
        String current = page.optString(valueKey);
        if (options == null) return current.isEmpty() ? "默认" : current;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && option.optString("v").equals(current)) return option.optString("t");
        }
        return current.isEmpty() ? "默认" : current;
    }

    // ------------------------------------------------------- 页面、权限与桥

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

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                  // the page keeps its language and its auto-show switch here
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
                // A reload wipes the bar, so a reason that belongs to the reload
                // itself has to be carried across it — otherwise the picture
                // restarts and the user never learns why.
                if (pendingNote != null) {
                    String carried = pendingNote;
                    pendingNote = null;
                    notice(carried);
                }
                if (shim.isEmpty()) return;
                view.evaluateJavascript(shim, null);
                if (!onReceiver) return;
                view.evaluateJavascript(styleSnippet(), null);
                view.evaluateJavascript(pageDriver, null);
                // A reload really did clear the page, so clear the strip's own
                // answer too: waiting for the next poll leaves 「继续」 sitting
                // there on a camera that is already running again.
                paused = false;
                torchOn = false;
                pauseAction.setText("暂停");
                paintControl(pauseAction, false, false);
                paintControl(torchAction, false, false);
                ui.removeCallbacks(pollPage);
                ui.postDelayed(pollPage, 600L);
                if (screen == SCREEN_SETTINGS) pullSettings();
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

    /** Appended last, so it wins the cascade against the page's own sheet. */
    private String styleSnippet() {
        return "(function(){if(document.getElementById('decimen-rx-ui'))return;"
                + "var s=document.createElement('style');s.id='decimen-rx-ui';"
                + "s.textContent=" + js(pageCss) + ";"
                + "document.head.appendChild(s);})();";
    }

    /**
     * A JS string literal for a value that came from a file or from the page.
     * JSONObject.quote is not used because its signature differs between the
     * reference org.json and the android.jar one this compiles against.
     */
    private static String js(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }

    private void report(String level, String source, int line, String message) {
        if (message == null) return;
        boolean uncaught = message.contains("Uncaught") || "error".equalsIgnoreCase(level);
        if (!uncaught) return;
        noteError((source == null ? "" : source.substring(lastSlash(source) + 1) + ":" + line + " ") + message);
    }

    /** A routine result: neutral, and it folds itself away. */
    void say(String line) {
        showStatus(line, false, 7000L);
    }

    /**
     * Something worth knowing but with nothing to act on — the picture restarted
     * itself, this lens has no flash. Neutral, and it leaves on its own: a bar
     * that never clears stops being a notice and becomes wallpaper.
     */
    void notice(String line) {
        showStatus(line, false, 12000L);
    }

    /** Only a real fault stays up, because only a fault needs doing about. */
    void noteError(String line) {
        showStatus(line, true, 0L);
    }

    private void showStatus(String line, boolean fault, long hold) {
        runOnUiThread(() -> {
            ui.removeCallbacks(hideStatus);
            status.setText(line);
            status.setBackgroundColor(Color.parseColor(fault ? FAULT : LINE));
            status.setTextColor(Color.parseColor(fault ? "#ffe4e6" : TEXT));
            status.setVisibility(View.VISIBLE);
            if (hold > 0L) ui.postDelayed(hideStatus, hold);
        });
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage("壳版本 " + versionName()
                        + "\n\n接收端是官方 v0.5.3 的单文件原件，CI 每次构建都按 SHA-256 校验它未被改动。"
                        + "它的排版由壳在运行时改写，协议代码一行没有替换。"
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
        // Three screens share one window: back walks in to the transfer, and only
        // a second press on the transfer screen leaves the app.
        if (screen == SCREEN_PROBE) {
            openReceiver();
            show(SCREEN_SETTINGS);
            return;
        }
        if (screen != SCREEN_RECEIVE) {
            show(SCREEN_RECEIVE);
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
        screen = SCREEN_PROBE;
        filesPanel.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
        controlBar.setVisibility(View.INVISIBLE);
        for (int i = 0; i < navTabs.length; i++) styleTab(navTabs[i], i == 2);
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
