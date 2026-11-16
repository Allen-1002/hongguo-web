package io.github.hongguoweb.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * 闪退排查：没有 adb 也能看到原因。
 * - Node 的 stdout/stderr 由 native-lib 同时写进 node.log（每次启动前把上一次的挪成 node.prev.log）；
 * - Java 未捕获异常写进 crash.txt；
 * - 启动时写一个 starting 标记，页面加载成功后删掉。下次启动若标记还在，说明上次没起来，就把日志显示出来。
 */
final class CrashLog {
    private CrashLog() {}

    static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "logs");
        d.mkdirs();
        return d;
    }

    static File nodeLog(Context ctx) { return new File(dir(ctx), "node.log"); }

    static void install(Context ctx) {
        final File crash = new File(dir(ctx), "crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream os = new FileOutputStream(crash, true)) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                os.write(("线程 " + t.getName() + "\n" + sw + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** 启动前调用：返回上次失败的诊断文本（没有失败返回 null），并轮转日志、写下本次的 starting 标记。 */
    static String beginLaunch(Context ctx) {
        File d = dir(ctx);
        File marker = new File(d, "starting");
        File crash = new File(d, "crash.txt");
        File log = nodeLog(ctx);
        File prevLog = new File(d, "node.prev.log");
        String report = null;
        if (marker.exists() || crash.exists()) {
            StringBuilder sb = new StringBuilder();
            sb.append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
              .append("，Android ").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
              .append("ABI：").append(String.join(",", Build.SUPPORTED_ABIS)).append("\n")
              .append("App：").append(BuildConfigInfo.version(ctx)).append("\n\n");
            if (crash.exists()) sb.append("== Java 异常 ==\n").append(tail(crash, 6000)).append("\n");
            sb.append("== Node 日志（最后部分）==\n").append(log.exists() ? tail(log, 8000) : "（没有日志：Node 可能没来得及启动）").append("\n");
            report = sb.toString();
        }
        //noinspection ResultOfMethodCallIgnored
        crash.delete();
        //noinspection ResultOfMethodCallIgnored
        prevLog.delete();
        //noinspection ResultOfMethodCallIgnored
        log.renameTo(prevLog);
        try { new FileOutputStream(marker).close(); } catch (Exception ignored) {}
        return report;
    }

    /** 页面加载成功后调用。 */
    static void launchOk(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(ctx), "starting").delete();
    }

    static String tail(File f, int max) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - max);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "（读日志失败：" + e + "）";
        }
    }

    /** 不依赖生成的 BuildConfig，直接读包信息。 */
    static final class BuildConfigInfo {
        static String version(Context ctx) {
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                return pi.versionName + " (" + pi.versionCode + ")";
            } catch (Exception e) {
                return "?";
            }
        }
    }
}
