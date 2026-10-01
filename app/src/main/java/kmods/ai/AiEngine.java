package kmods.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates one "suggest replies" round-trip:
 *
 *   hook (smali) -> AiReplies.onIncomingMessage / SmartReplyBar refresh
 *       -> AiEngine.suggest(jid, incoming)
 *           -> MessageHistory (WhatsApp DB)
 *           -> Providers.resolve (modular AI provider)
 *               -> HTTPS request (AiHttpClient)
 *           -> parse into <=3 short suggestion lines
 *       -> callback on the MAIN thread -> SmartReplyBar chips
 *
 * Guarantees:
 * - never blocks the UI thread
 * - one in-flight request per chat (jid); a newer request supersedes an older one
 * - every failure lands in a user-facing message + the AiLogger
 */
public final class AiEngine {

    /** Delivered on the main thread by {@link AiEngine}. */
    public interface Callback {
        /** Up to 3 short reply suggestions. */
        void onSuggestions(String jid, List<String> suggestions);

        /** @param userMessage friendly text; technical detail is already in AiLogger. */
        void onError(String jid, String userMessage);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** jid -> in-flight future, so a chat can cancel/supersede its request. */
    private static final ConcurrentHashMap<String, Future<?>> IN_FLIGHT = new ConcurrentHashMap<String, Future<?>>();
    /** jid -> generation counter; stale generations are dropped. */
    private static final ConcurrentHashMap<String, AtomicInteger> GENERATION = new ConcurrentHashMap<String, AtomicInteger>();

    private AiEngine() {
    }

    /**
     * Requests reply suggestions for {@code incoming} in chat {@code jid}.
     * Chat history is read from WhatsApp's messages DB on the worker thread.
     * Safe to call from any thread; failures are reported through the callback.
     */
    public static void suggest(final Context ctx, final String jid, final boolean isGroup,
                               final String incoming, final Callback callback) {
        suggest(ctx, jid, isGroup, incoming, null, callback);
    }

    /**
     * Same as {@link #suggest} with an explicit history snapshot. When
     * {@code preloadedHistory} is null the history is loaded from the DB
     * inside the worker task (no races - the snapshot travels with the request).
     */
    public static void suggest(final Context ctx, final String jid, final boolean isGroup,
                               final String incoming, final List<ChatMessage> preloadedHistory,
                               final Callback callback) {
        if (ctx == null || jid == null || incoming == null || callback == null) return;
        if (!AiConfig.isEnabled(ctx)) {
            AiLogger.d(ctx, "AI disabled - ignoring suggest request");
            return;
        }
        final String trimmed = incoming.trim();
        if (trimmed.length() == 0) return;
        final List<ChatMessage> snapshot = preloadedHistory;

        final AtomicInteger genRef;
        AtomicInteger existing = GENERATION.get(jid);
        if (existing == null) {
            existing = new AtomicInteger(0);
            AtomicInteger prev = GENERATION.putIfAbsent(jid, existing);
            if (prev != null) existing = prev;
        }
        genRef = existing;
        final int myGen = genRef.incrementAndGet();

        Future<?> old = IN_FLIGHT.get(jid);
        if (old != null) {
            old.cancel(true); // supersede - the old result would only confuse the user
        }

        final long startedAt = System.currentTimeMillis();
        AiLogger.d(ctx, "Suggest start jid=" + shortJid(jid) + " gen=" + myGen
                + " incoming=" + preview(trimmed));

        Future<?> f = EXECUTOR.submit(new Runnable() {
            public void run() {
                Result r = runOnce(ctx.getApplicationContext(), jid, isGroup, trimmed, snapshot);
                long ms = System.currentTimeMillis() - startedAt;
                AiLogger.d(ctx, "Suggest done jid=" + shortJid(jid) + " gen=" + myGen
                        + " ok=" + (r.error == null) + " in " + ms + "ms");
                final Result result = r;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (genRef.get() != myGen) {
                            AiLogger.d(ctx, "Dropping stale result gen=" + myGen);
                            return;
                        }
                        IN_FLIGHT.remove(jid);
                        if (result.error != null) {
                            callback.onError(jid, result.error);
                        } else {
                            callback.onSuggestions(jid, result.suggestions);
                        }
                    }
                });
            }
        });
        IN_FLIGHT.put(jid, f);
    }



    /** Small result holder. */
    private static class Result {
        List<String> suggestions;
        String error;
    }

    private static Result runOnce(Context appCtx, String jid, boolean isGroup, String incoming,
                                  List<ChatMessage> snapshot) {
        Result r = new Result();
        try {
            AiProvider provider = Providers.resolve(appCtx);
            List<ChatMessage> history = snapshot;
            if (history == null) {
                history = MessageHistory.recent(kmods.Utils.db(), jid, isGroup);
            }
            String system = AiConfig.getSystemPrompt(appCtx);
            String raw = provider.complete(system, history, incoming);
            r.suggestions = toSuggestions(raw);
            if (r.suggestions.isEmpty()) {
                r.error = "AI returned no usable suggestion";
            }
        } catch (AiException e) {
            r.error = e.userMessage;
        } catch (Throwable t) {
            AiLogger.e(appCtx, "suggest: unexpected failure", t);
            r.error = "AI error: " + t.getClass().getSimpleName();
        }
        return r;
    }

    /**
     * Splits the model output into candidate replies:
     * one suggestion per line, no numbering/bullets, trimmed, max 3, max 160 chars.
     */
    public static List<String> toSuggestions(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length && out.size() < 3; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            // strip common list markers / numbering / quotes the model may add
            line = line.replaceFirst("^(?:[-*\u2022]|\\d+[.)])\\s*", "");
            line = line.replaceFirst("^\"(.*)\"$", "$1");
            line = line.trim();
            if (line.length() == 0) continue;
            if (line.length() > 160) line = line.substring(0, 160);
            // skip meta lines like "Here are three suggestions:"
            String lower = line.toLowerCase();
            if (lower.startsWith("here ") || lower.startsWith("sure,") || lower.startsWith("suggestions:")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /** Quick settings sanity check + real network test used by "Test AI Connection". */
    public static String testConnectionSync(final Context ctx) {
        try {
            AiProvider provider = Providers.resolve(ctx);
            long t0 = System.currentTimeMillis();
            String raw = provider.complete(
                    "You are a connection tester. Answer with exactly: OK",
                    new ArrayList<ChatMessage>(), "Say OK");
            long ms = System.currentTimeMillis() - t0;
            AiLogger.i(ctx, "Test OK via " + provider.getName() + " in " + ms + "ms: " + preview(raw));
            return "Connected to " + provider.getName() + " (" + AiConfig.getModel(ctx) + ") in " + ms + "ms";
        } catch (AiException e) {
            AiLogger.e(ctx, "Test failed: " + e.getMessage());
            return "FAILED: " + e.userMessage;
        } catch (Throwable t) {
            AiLogger.e(ctx, "Test crashed", t);
            return "FAILED: " + t.getClass().getSimpleName();
        }
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    private static String shortJid(String jid) {
        if (jid == null) return "";
        int at = jid.indexOf('@');
        return at > 0 ? jid.substring(0, at) : jid;
    }
}
