package kmods.ai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;

/**
 * Static facade CALLED FROM PATCHED WHATSAPP SMALI (see tools/AiMod/README.md).
 * Keeps every hook method tiny, side-effect-free on failure, and safe to call
 * from any thread so the smali patches stay one-liners.
 *
 * Hook surface:
 *
 *   1) AiReplies.attachToConversation(Activity)
 *      Call at the END of com.whatsapp.Conversation.onCreate (after
 *      setContentView). Finds the entry EditText ("entry" resource id),
 *      reads the chat jid from the intent extra "jid", and installs the
 *      smart-reply bar. When "AI > Auto suggest" is on, the latest incoming
 *      message is analyzed immediately (read from WhatsApp's messages DB -
 *      no further smali changes needed).
 *
 *   2) AiReplies.onIncomingMessage(Context, String jid, String senderJid, String body)
 *      OPTIONAL real-time hook (message-store insert). Fires an analysis as
 *      soon as a text message lands while the chat is open.
 *
 *   3) AiReplies.onConversationDestroy()
 *      Optional cleanup hook (Conversation.onDestroy) - the bar also cleans
 *      itself up automatically via its view hierarchy.
 */
public final class AiReplies {

    private AiReplies() {
    }

    /** @return true when the jid looks like a group chat ("...@g.us"). */
    public static boolean isGroupJid(String jid) {
        return jid != null && jid.endsWith("@g.us");
    }

    /**
     * HOOK 1 - install the smart reply bar into the open conversation.
     * Never throws; logs under tag AiMods.
     */
    public static void attachToConversation(final Activity conversation) {
        try {
            if (conversation == null || conversation.isFinishing()) return;
            if (!AiConfig.isEnabled(conversation)) return;
            String jid = extractJid(conversation);
            if (jid == null || jid.length() == 0) {
                AiLogger.w(conversation, "attachToConversation: could not resolve jid");
                return;
            }
            final String fJid = jid;
            final EditText entry = findEntryBox(conversation);
            // Attach on next frame so WhatsApp has fully laid out its view tree.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        SmartReplyBar.attach(conversation, entry, fJid, isGroupJid(fJid));
                    } catch (Throwable t) {
                        AiLogger.e(conversation, "attachToConversation failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            AiLogger.e(conversation, "attachToConversation outer failure", t);
        }
    }

    /**
     * HOOK 2 (optional) - real-time analysis of a freshly received text message.
     *
     * @param ctx       any context (Conversation / Application)
     * @param jid       chat jid, e.g. "15551234567@s.whatsapp.net" or "12-34@g.us"
     * @param senderJid sender's jid inside groups ("" in 1:1 chats); not sent to the API
     * @param body      the text body of the incoming message
     */
    public static void onIncomingMessage(Context ctx, String jid, String senderJid, String body) {
        try {
            if (ctx == null || body == null || body.trim().length() == 0) return;
            if (!AiConfig.isEnabled(ctx) || !AiConfig.isAutoSuggest(ctx)) return;
            // Only worth it when that exact chat is open and shows our bar.
            String attached = SmartReplyBar.attachedJid();
            if (attached == null || !attached.equals(jid)) return;
            AiLogger.d(ctx, "Real-time hook: incoming message in open chat");
            AiEngine.suggest(ctx.getApplicationContext(), jid, isGroupJid(jid), body,
                    new AiEngine.Callback() {
                        public void onSuggestions(String forJid, java.util.List<String> suggestions) {
                            SmartReplyBar.deliverSuggestions(forJid, suggestions);
                        }

                        public void onError(String forJid, String userMessage) {
                            SmartReplyBar.deliverError(forJid, userMessage);
                        }
                    });
        } catch (Throwable t) {
            AiLogger.e(ctx, "onIncomingMessage failed", t);
        }
    }

    /** HOOK 3 (optional) - drop the bar when the conversation screen goes away. */
    public static void onConversationDestroy() {
        try {
            SmartReplyBar.detach();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ internals

    /** Reads the chat jid from the Conversation intent ("jid" extra). */
    private static String extractJid(Activity conversation) {
        try {
            Intent i = conversation.getIntent();
            if (i != null) {
                String jid = i.getStringExtra("jid");
                if (jid != null && jid.length() > 0) return jid;
            }
        } catch (Throwable t) {
            AiLogger.d(conversation, "extractJid from intent failed: " + t.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Finds WhatsApp's message input EditText by its well-known resource name
     * ("entry"). Returns null when the id cannot be resolved - the bar then
     * falls back to clipboard-based suggestions instead of crashing.
     */
    private static EditText findEntryBox(Activity conversation) {
        try {
            int id = kmods.Utils.resID("entry", "id");
            if (id != 0) {
                View v = conversation.findViewById(id);
                if (v instanceof EditText) return (EditText) v;
                if (v != null) {
                    AiLogger.d(conversation, "entry id found but not an EditText: "
                            + v.getClass().getName());
                }
            } else {
                AiLogger.d(conversation, "resource id 'entry' not found in this base");
            }
        } catch (Throwable t) {
            AiLogger.d(conversation, "findEntryBox failed: " + t.getClass().getSimpleName());
        }
        return null;
    }
}
