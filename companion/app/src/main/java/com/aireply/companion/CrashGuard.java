package com.aireply.companion;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Global crash recorder - the app's black box.
 *
 * Any uncaught exception is appended to files/crash-log.txt before the
 * system takes the process down, so a "the app keeps stopping" report can
 * always be diagnosed from inside the app (Debug Logs screen) without adb.
 *
 * The previous default handler still runs afterwards: the Android crash
 * dialog and process death behave exactly as the system expects.
 */
public final class CrashGuard implements Thread.UncaughtExceptionHandler {

    private static final String FILE = "crash-log.txt";
    private static final long MAX_BYTES = 64 * 1024; // keep only the newest tail

    private final Thread.UncaughtExceptionHandler previous;
    private final Context app;

    private CrashGuard(Thread.UncaughtExceptionHandler previous, Context app) {
        this.previous = previous;
        this.app = app;
    }

    /** Install once from {@link App#onCreate()}. Safe to call repeatedly. */
    public static void install(Context app) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashGuard) return; // already installed
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashGuard(current, app.getApplicationContext()));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            append(report(thread, throwable));
        } catch (Throwable ignored) {
            // Never throw from the last-resort handler.
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        }
    }

    private static String report(Thread thread, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        return "----- CRASH " + when + " (thread: " + thread.getName() + ") -----\n"
                + sw.toString() + "\n";
    }

    private void append(String text) {
        FileOutputStream out = null;
        try {
            File f = new File(app.getFilesDir(), FILE);
            // Trim: when the file grows past the cap, start fresh so the
            // newest crashes always survive.
            if (f.length() > MAX_BYTES) f.delete();
            out = new FileOutputStream(f, true);
            out.write(text.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** @return true when at least one crash was recorded and not cleared. */
    public static boolean hasCrashLog(Context ctx) {
        try {
            return new File(ctx.getFilesDir(), FILE).length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Full crash log content, or an empty string when there is none. */
    public static String readCrashLog(Context ctx) {
        FileInputStream in = null;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists() || f.length() == 0) return "";
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Delete the stored crash log (used by the Clear button in Debug Logs). */
    public static void clear(Context ctx) {
        try {
            new File(ctx.getFilesDir(), FILE).delete();
        } catch (Throwable ignored) {
        }
    }
}
