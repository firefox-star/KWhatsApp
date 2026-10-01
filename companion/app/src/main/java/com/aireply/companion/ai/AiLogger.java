package com.aireply.companion.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Central debug logging for the AI engine.
 *
 * - Always mirrors to logcat under the "AiReply" tag (filter with:
 *       adb logcat -s AiReply
 * ).
 * - Optionally keeps the last {@link #MAX_LINES} entries in memory so the
 *   user can open "Debug Logs" from the app without a PC.
 * - Verbose in-memory logging is gated by the "ai_debug_log" preference so
 *   normal users pay zero overhead.
 */
public final class AiLogger {

    public static final String TAG = "AiReply";
    private static final int MAX_LINES = 200;

    private static final ArrayDeque<String> BUFFER = new ArrayDeque<String>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private AiLogger() {
    }

    private static boolean verbose(Context ctx) {
        if (ctx == null) return false;
        try {
            SharedPreferences p = ctx.getSharedPreferences(AiConfig.PREFS, 0);
            return p.getBoolean("ai_debug_log", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Debug-level log. Recorded in the in-memory buffer only when verbose logging is on. */
    public static void d(Context ctx, String msg) {
        Log.d(TAG, msg);
        if (verbose(ctx)) record("D", msg, null);
    }

    public static void d(Context ctx, String msg, Throwable t) {
        Log.d(TAG, msg, t);
        if (verbose(ctx)) record("D", msg, t);
    }

    /** Info log - always recorded in buffer (low volume: config changes, test results). */
    public static void i(Context ctx, String msg) {
        Log.i(TAG, msg);
        record("I", msg, null);
    }

    /** Warning - always recorded. */
    public static void w(Context ctx, String msg) {
        Log.w(TAG, msg);
        record("W", msg, null);
    }

    /** Error - always recorded (API failures etc.). */
    public static void e(Context ctx, String msg) {
        Log.e(TAG, msg);
        record("E", msg, null);
    }

    /** Error with throwable - always recorded. */
    public static void e(Context ctx, String msg, Throwable t) {
        Log.e(TAG, msg, t);
        record("E", msg, t);
    }

    private static synchronized void record(String level, String msg, Throwable t) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(TS.format(new Date())).append(' ').append(level).append(' ').append(msg);
            if (t != null) {
                sb.append(" :: ").append(t.getClass().getSimpleName());
                String m = t.getMessage();
                if (m != null) sb.append(": ").append(m);
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < st.length && i < 6; i++) {
                    sb.append("\n    at ").append(st[i].toString());
                }
            }
            if (BUFFER.size() >= MAX_LINES) BUFFER.pollFirst();
            BUFFER.addLast(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** @return snapshot of the in-memory log buffer (oldest first), for the viewer. */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : BUFFER) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (sb.length() == 0) {
            return "No AI log entries yet.\n\nEnable 'Debug logging' in AI Settings, use the AI features, then come back.";
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        BUFFER.clear();
    }
}
