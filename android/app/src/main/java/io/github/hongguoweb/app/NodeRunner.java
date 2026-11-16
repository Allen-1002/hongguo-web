package io.github.hongguoweb.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 在 App 进程里运行内嵌 Node（nodejs-mobile），跑的就是仓库里的 server/index.mjs。
 * Node 一个进程只能启动一次，所以用静态标记防止 Activity 重建时重复启动。
 */
final class NodeRunner {
    static final String TAG = "hongguo";
    /** 固定端口：WebView 的 localStorage 按「主机+端口」隔离，端口变了本地设置就丢了。 */
    static final int PORT = 38787;

    private static volatile boolean started = false;
    private static volatile String failure = null;

    /** 原生库加载失败时不让 App 直接崩，把原因显示出来。 */
    private static final String loadError;

    static {
        String err = null;
        try {
            System.loadLibrary("node");
            System.loadLibrary("native-lib");
        } catch (Throwable e) {
            err = "加载原生库失败：" + e;
            Log.e(TAG, err, e);
        }
        loadError = err;
    }

    private static native int startNode(String[] args, String[] envs);

    private NodeRunner() {}

    static String failure() { return failure; }

    /** 本机访问口令：首次启动随机生成并保存，防止手机上其它 App 调用本地接口。 */
    static String token(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("node", Context.MODE_PRIVATE);
        String t = sp.getString("token", null);
        if (t == null) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            sp.edit().putString("token", t).apply();
        }
        return t;
    }

    static synchronized void ensureStarted(Context ctx) {
        if (started) return;
        started = true;
        if (loadError != null) { failure = loadError; return; }
        final Context app = ctx.getApplicationContext();
        final String token = token(app);
        // Node/V8 主线程给足 16 MB 栈（默认线程栈太小，深递归的 JSON/正则会崩）。
        Thread t = new Thread(null, () -> {
            try {
                File project = extractIfNeeded(app);
                File data = new File(app.getFilesDir(), "data");
                data.mkdirs();
                String[] args = {"node", new File(project, "server/mobile-boot.cjs").getAbsolutePath()};
                String[] envs = {
                        "MOBILE=1",
                        "HONGGUO_LOG_FILE=" + CrashLog.nodeLog(app).getAbsolutePath(),
                        "HOST=127.0.0.1",
                        "PORT=" + PORT,
                        "TOKEN=" + token,
                        "DATA_DIR=" + data.getAbsolutePath(),
                        "SOURCES_DIR=" + new File(project, "sources/builtin").getAbsolutePath(),
                        "TMPDIR=" + app.getCacheDir().getAbsolutePath(),
                        "HOME=" + app.getFilesDir().getAbsolutePath(),
                        "TZ=Asia/Shanghai",
                        "NODE_ENV=production",
                        // 手机上没有 ffmpeg：指向一个不存在的路径，VVC 转码档自动不出现。
                        "FFMPEG_PATH=/nonexistent/ffmpeg",
                        "MAX_STREAMS=4",
                        "SOURCE_UPDATE_START_DELAY_SECONDS=30",
                };
                int code = startNode(args, envs);
                failure = "Node 已退出（代码 " + code + "）";
                Log.e(TAG, failure);
            } catch (Throwable e) {
                failure = "启动失败：" + e;
                Log.e(TAG, "node start failed", e);
            }
        }, "node-main", 16L * 1024 * 1024);
        t.setDaemon(true);
        t.start();
    }

    /** 等本地服务就绪（/health 返回 200），最多 timeoutMs。 */
    static boolean waitReady(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (failure != null) return false;
            if (ping()) return true;
            try { Thread.sleep(200); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    static boolean ping() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(1000);
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** App 安装或升级后第一次启动时，把 assets/nodejs-project.zip 解压到私有目录。 */
    private static File extractIfNeeded(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "nodejs-project");
        PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        String stamp = pi.versionCode + "-" + pi.lastUpdateTime;
        SharedPreferences sp = ctx.getSharedPreferences("node", Context.MODE_PRIVATE);
        if (stamp.equals(sp.getString("extracted", "")) && new File(dir, "server/mobile-boot.cjs").exists()) return dir;

        deleteRecursive(dir);
        dir.mkdirs();
        String root = dir.getCanonicalPath() + File.separator;
        try (InputStream raw = ctx.getAssets().open("nodejs-project.zip");
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = new File(dir, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("bad zip entry " + e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        sp.edit().putString("extracted", stamp).apply();
        Log.i(TAG, "node project extracted: " + stamp);
        return dir;
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
