package io.github.hongguoweb.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 整个 App 就是一个全屏 WebView，打开内嵌 Node 在本机起的页面（http://127.0.0.1:38787）。
 * 页面、接口、解密都和 Docker 版是同一套代码，这里只补手机特有的东西：
 * 返回键、视频全屏横屏、选文件（导入备份）、保存文件（导出备份）。
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1001;

    private FrameLayout root;
    private WebView web;
    private TextView splash;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;
    private ValueCallback<Uri[]> fileCallback;
    private WebChromeClient chrome;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splash = new TextView(this);
        splash.setText("正在启动…");
        splash.setTextColor(Color.LTGRAY);
        splash.setTextSize(16);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(Color.BLACK);
        root.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 上次没能正常启动（闪退）：先把日志给用户看，再让用户选择继续。
        CrashLog.install(this);
        String report = CrashLog.beginLaunch(this);
        if (report != null) {
            showReport(report);
            return;
        }
        boot();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void boot() {

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // 封面图等直接从 https 外链加载，页面本身是本机 http。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.addJavascriptInterface(new Bridge(), "HongguoApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("127.0.0.1".equals(u.getHost())) return false;
                // 站外链接（如源的上游主页）交给系统浏览器。
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                splash.setVisibility(View.GONE);
                CrashLog.launchOk(MainActivity.this);
            }
        });

        chrome = new WebChromeClient() {
            // 页面里的 requestFullscreen()（播放器全屏按钮 / 双击）走这里：铺满屏幕。
            // 短剧多是竖版视频：只有视频本身是横版时才转横屏，且用 USER_LANDSCAPE（尊重系统的方向锁定，不跟着陀螺仪乱转）。
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullView != null) { callback.onCustomViewHidden(); return; }
                fullView = view;
                fullCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setFullscreenBars(true);
                web.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');return v&&v.videoWidth>v.videoHeight?1:0})()",
                        value -> {
                            if (fullView != null && "1".equals(value))
                                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
                        });
            }

            @Override
            public void onHideCustomView() {
                if (fullView == null) return;
                root.removeView(fullView);
                fullView = null;
                if (fullCallback != null) fullCallback.onCustomViewHidden();
                fullCallback = null;
                setFullscreenBars(false);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }

            // <input type="file">（导入备份、导入源文件）。
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        };
        web.setWebChromeClient(chrome);

        NodeRunner.ensureStarted(this);
        final String token = NodeRunner.token(this);
        new Thread(() -> {
            boolean ok = NodeRunner.waitReady(60_000);
            runOnUiThread(() -> {
                if (ok) {
                    web.loadUrl("http://127.0.0.1:" + NodeRunner.PORT + "/?at=" + token);
                } else {
                    String why = NodeRunner.failure();
                    splash.setText("本地服务没有启动成功\n" + (why == null ? "等待超时" : why) + "\n\n请完全退出 App 后重试");
                }
            });
        }, "wait-node").start();
    }

    /** 诊断页：显示上次失败的日志，可复制，可继续启动。 */
    private void showReport(String report) {
        splash.setVisibility(View.GONE);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.BLACK);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("上次没有正常启动，下面是日志。请点「复制日志」发给开发者。");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        box.addView(title);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        android.widget.Button copy = new android.widget.Button(this);
        copy.setText("复制日志");
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("hongguo log", report));
            android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show();
        });
        android.widget.Button go = new android.widget.Button(this);
        go.setText("继续启动");
        go.setOnClickListener(v -> {
            root.removeView(box);
            splash.setVisibility(View.VISIBLE);
            boot();
        });
        row.addView(copy);
        row.addView(go);
        box.addView(row);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView body = new TextView(this);
        body.setText(report);
        body.setTextColor(Color.LTGRAY);
        body.setTextSize(11);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        box.addView(scroll, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (fullView != null) {
            chrome.onHideCustomView();
            return;
        }
        // 先让页面自己关弹窗 / 播放器 / 详情页，页面没东西可关时退到后台（不杀进程，Node 继续在）。
        web.evaluateJavascript("(window.hongguoBack && window.hongguoBack()) ? 1 : 0", value -> {
            if (!"1".equals(value)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @SuppressWarnings("deprecation")
    private void setFullscreenBars(boolean on) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : 0);
        }
    }

    /** 给页面用的原生能力：window.HongguoApp.saveFile(name, text) 把备份写进「下载」目录。 */
    private class Bridge {
        @JavascriptInterface
        public boolean saveFile(String name, String text) {
            String safe = name == null ? "backup.json" : name.replaceAll("[^A-Za-z0-9._-]", "_");
            byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return false;
                        os.write(bytes);
                    }
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return false;
                    dir.mkdirs();
                    try (OutputStream os = new FileOutputStream(new File(dir, safe))) { os.write(bytes); }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
