package com.aireply.companion;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.aireply.companion.ai.AiConfig;
import com.aireply.companion.ai.AiEngine;
import com.aireply.companion.ai.AiLogger;
import com.aireply.companion.ai.ConvStore;

import java.util.Collections;

/**
 * Executes suggestion-notification actions. Deliberately a BroadcastReceiver
 * that NEVER launches an activity (Android 12+ notification-trampoline rule);
 * the Edit button opens EditReplyActivity through a PendingIntent directly.
 *
 * Actions:
 *   ACTION_SEND  - accept: dispatch the suggestion via WhatsApp's reply action
 *   ACTION_COPY  - copy the suggestion to the clipboard
 *   ACTION_RETRY - re-run the AI suggestion for the last incoming message
 */
public class ReplyReceiver extends BroadcastReceiver {

    public static final String ACTION_SEND = "com.aireply.companion.ACTION_SEND";
    public static final String ACTION_COPY = "com.aireply.companion.ACTION_COPY";
    public static final String ACTION_RETRY = "com.aireply.companion.ACTION_RETRY";
    public static final String EXTRA_TEXT = "text";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String convKey = intent.getStringExtra(SuggestionNotifier.EXTRA_CONV);
        String sender = intent.getStringExtra(SuggestionNotifier.EXTRA_SENDER);
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (convKey == null) return;

        try {
            if (ACTION_SEND.equals(action)) {
                handleSend(ctx, convKey, sender, text);
            } else if (ACTION_COPY.equals(action)) {
                handleCopy(ctx, text);
            } else if (ACTION_RETRY.equals(action)) {
                handleRetry(ctx, convKey, sender);
            }
        } catch (Throwable t) {
            AiLogger.e(ctx, "ReplyReceiver crashed (swallowed)", t);
        }
    }

    private void handleSend(Context ctx, String convKey, String sender, String text) {
        SuggestionNotifier.cancel(ctx, convKey); // remove our suggestion card
        if (text == null || text.trim().length() == 0) return;
        if (WhatsAppReply.send(ctx, convKey, text.trim())) {
            ConvStore.onReplySent(convKey, text.trim());
            AiLogger.i(ctx, "User accepted suggestion for " + sender);
            Toast.makeText(ctx, ctx.getString(R.string.sent_ok), Toast.LENGTH_SHORT).show();
        } else {
            AiLogger.w(ctx, "User accept failed - WhatsApp notification gone");
            Toast.makeText(ctx, ctx.getString(R.string.send_failed), Toast.LENGTH_LONG).show();
        }
    }

    private void handleCopy(Context ctx, String text) {
        if (text == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("AI reply", text));
                Toast.makeText(ctx, ctx.getString(R.string.copied), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            AiLogger.e(ctx, "Copy failed", t);
        }
    }

    private void handleRetry(final Context ctx, final String convKey, final String sender) {
        if (!AiConfig.isEnabled(ctx)) return;
        String last = ConvStore.lastIncoming(convKey);
        if (last == null || last.length() == 0) {
            AiLogger.w(ctx, "Retry: no stored message for this chat");
            return;
        }
        AiLogger.i(ctx, "Retrying suggestion for " + sender);
        AiEngine.suggest(ctx, convKey, last, new AiEngine.Callback() {
            @Override
            public void onSuggestions(String key, java.util.List<String> suggestions) {
                SuggestionNotifier.showSuggestions(ctx, convKey, sender, null, suggestions, false);
            }

            @Override
            public void onError(String key, String userMessage) {
                SuggestionNotifier.showSuggestions(ctx, convKey, sender, null,
                        Collections.singletonList(userMessage), true);
            }
        });
    }
}
