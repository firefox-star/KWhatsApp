package com.aireply.companion;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.aireply.companion.ai.AiConfig;
import com.aireply.companion.ai.AiEngine;
import com.aireply.companion.ai.AiLogger;
import com.aireply.companion.ai.ConvStore;
import com.aireply.companion.ai.Providers;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The message analysis hook.
 *
 * Lifecycle:
 *   Android binds this service once the user grants "Notification access"
 *   -> every WhatsApp message notification arrives here
 *   -> sender + text extracted (MessagingStyle, with extras fallback)
 *   -> deduplicated (WhatsApp re-posts the same notification on each update)
 *   -> stored in ConvStore (per-chat context)
 *   -> AiEngine.suggest() calls the user-configured AI API on a worker thread
 *   -> {@link SuggestionNotifier} shows the result with Send / Edit / Copy
 *      actions, or {@link WhatsAppReply#send} directly in auto-send mode.
 *
 * This service NEVER crashes the host: everything is wrapped, failures are
 * logged (AiMods-style tag) and surfaced as user-facing error notifications.
 */
public class SuggestionListener extends NotificationListenerService {

    /** Packages whose message notifications are analyzed. */
    private static final String[] TRACKED = {
            "com.whatsapp",       // WhatsApp
            "com.gbwhatsapp",     // GBWhatsApp (same RemoteInput mechanics)
            "com.whatsapp.w4b"    // WhatsApp Business
    };

    /** convKey -> last processed signature, to skip notification re-posts. */
    private static final ConcurrentHashMap<String, String> LAST_SEEN =
            new ConcurrentHashMap<String, String>();

    private static final int MAX_WATCHED = 60;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        AiLogger.i(this, "Notification listener connected - watching for messages");
        SuggestionListener.self = this;
    }

    @Override
    public void onListenerDisconnected() {
        AiLogger.w(this, "Notification listener disconnected");
        if (SuggestionListener.self == this) SuggestionListener.self = null;
        super.onListenerDisconnected();
    }

    private static SuggestionListener self;

    /** @return live listener instance, or null when Android has not bound us yet. */
    public static SuggestionListener active() {
        return self;
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Throwable t) {
            // Never let a bad notification kill the hook.
            AiLogger.e(this, "onNotificationPosted crashed (swallowed)", t);
        }
    }

    private void handle(StatusBarNotification sbn) {
        String pkg = sbn == null ? null : sbn.getPackageName();
        if (!isTracked(pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        // ---- extract sender + text ------------------------------------
        CharSequence title = null;
        CharSequence text = null;
        String signature = null;

        // MessagingStyle carries the richest data. Public API path:
        // Builder.recoverBuilder().getStyle() (API 24+); below that we rely
        // on the plain extras, which WhatsApp fills on old versions.
        if (Build.VERSION.SDK_INT >= 24) {
            Notification.MessagingStyle style = null;
            try {
                Notification.Builder rb = Notification.Builder.recoverBuilder(this, n);
                if (rb != null && rb.getStyle() instanceof Notification.MessagingStyle) {
                    style = (Notification.MessagingStyle) rb.getStyle();
                }
            } catch (Throwable ignored) {
            }

            if (style != null) {
                List<Notification.MessagingStyle.Message> msgs = style.getMessages();
                if (msgs != null && !msgs.isEmpty()) {
                    Notification.MessagingStyle.Message last = msgs.get(msgs.size() - 1);
                    text = last.getText();
                    CharSequence senderName = null;
                    if (Build.VERSION.SDK_INT >= 28) {
                        Person sender = last.getSenderPerson();
                        if (sender != null && sender.getName() != null) {
                            senderName = sender.getName();
                        }
                    } else {
                        senderName = last.getSender();
                    }
                    if (senderName != null && senderName.length() > 0) {
                        title = senderName;
                    } else {
                        title = style.getConversationTitle();
                    }
                    signature = msgs.size() + "|" + String.valueOf(text);
                }
            }
        }
        if (text == null || text.length() == 0) {
            Bundle ex = n.extras;
            title = ex.getCharSequence(Notification.EXTRA_TITLE);
            text = ex.getCharSequence(Notification.EXTRA_TEXT);
            signature = "x|" + String.valueOf(title) + "|" + String.valueOf(text);
        } else if (title == null && n.extras != null) {
            // Style gave us the message but no sender (individual chats
            // sometimes) - fall back to the conversation title from extras.
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        }
        if (text == null) return;
        // Ticker fallback ("Sender: message") still used by some builds.
        if (text.length() == 0 && n.tickerText != null) text = n.tickerText;
        String body = text.toString().trim();
        if (body.length() == 0) return;
        // Skip trivial system-ish texts
        String lower = body.toLowerCase();
        if (lower.equals("typing…") || lower.equals("typing...") || lower.startsWith("recording audio")
                || lower.contains("new messages") || lower.startsWith("unread messages")) {
            return;
        }

        String t0 = title == null ? "Unknown" : title.toString().trim();
        final String chatTitle = t0.length() == 0 ? "Unknown" : t0;
        final String convKey = pkg + "|" + chatTitle;

        // ---- dedupe ----------------------------------------------------
        String prev = LAST_SEEN.put(convKey, signature);
        if (signature.equals(prev)) return; // same notification re-posted
        // Bound the dedupe map.
        if (LAST_SEEN.size() > MAX_WATCHED) {
            for (String k : LAST_SEEN.keySet()) {
                LAST_SEEN.remove(k);
                break;
            }
        }

        final Context ctx = getApplicationContext();
        AiLogger.d(ctx, "Incoming message conv=" + chatTitle + " (" + pkg + ") "
                + (body.length() <= 50 ? body : body.substring(0, 50) + "…"));

        // Remember WhatsApp's reply action so Send/Edit can answer via it.
        WhatsAppReply.remember(convKey, sbn);

        if (!AiConfig.isEnabled(ctx)) return;
        if (!AiConfig.isConfigured(ctx)) {
            AiLogger.w(ctx, "AI enabled but not configured - open AI Settings");
            return;
        }

        // Conversation context for the model.
        ConvStore.onIncoming(convKey, body);

        // Auto mode: send the first suggestion immediately.
        if (AiConfig.isAutoSend(ctx)) {
            autoSend(ctx, convKey, chatTitle, body);
            return;
        }
        if (!AiConfig.isAutoSuggest(ctx)) return; // manual-only mode

        AiEngine.suggest(ctx, convKey, body, new AiEngine.Callback() {
            @Override
            public void onSuggestions(String convKey, List<String> suggestions) {
                SuggestionNotifier.showSuggestions(ctx, convKey, chatTitle, body, suggestions, false);
            }

            @Override
            public void onError(String convKey, String userMessage) {
                AiLogger.e(ctx, "Suggest failed: " + userMessage);
                SuggestionNotifier.showSuggestions(ctx, convKey, chatTitle, body,
                        java.util.Collections.singletonList(userMessage), true);
            }
        });
    }

    /** Auto-send mode: first suggestion goes out without confirmation. */
    private void autoSend(final Context ctx, final String convKey, final String chatTitle, final String body) {
        AiEngine.suggest(ctx, convKey, body, new AiEngine.Callback() {
            @Override
            public void onSuggestions(String key, List<String> suggestions) {
                if (suggestions.isEmpty()) return;
                String reply = suggestions.get(0);
                if (WhatsAppReply.send(ctx, convKey, reply)) {
                    ConvStore.onReplySent(convKey, reply);
                    SuggestionNotifier.showAutoSent(ctx, convKey, chatTitle, reply);
                } else {
                    SuggestionNotifier.showSuggestions(ctx, convKey, chatTitle, body,
                            suggestions, false);
                }
            }

            @Override
            public void onError(String key, String userMessage) {
                AiLogger.e(ctx, "Auto-send suggest failed: " + userMessage);
                SuggestionNotifier.showSuggestions(ctx, convKey, chatTitle, body,
                        java.util.Collections.singletonList(userMessage), true);
            }
        });
    }

    private static boolean isTracked(String pkg) {
        if (pkg == null) return false;
        for (String p : TRACKED) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }
}
