package kmods.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads recent conversation history from WhatsApp's own messages database
 * (the same SQLiteOpenHelper the existing KMods group-counter feature uses,
 * provided at runtime via kmods.Utils.SetDB).
 *
 * This means the AI hook does NOT need access to in-memory message objects -
 * any smali call-site that knows the current jid can request suggestions.
 *
 * Table: messages (WhatsApp 2.17.87 schema)
 *   key_remote_jid  - chat id ("number@s.whatsapp.net" / "id@g.us")
 *   remote_resource - sender jid inside groups ("" in 1:1 chats)
 *   key_from_me     - 1 when the message was sent BY me
 *   data            - text body of text messages (null/empty for media)
 *   timestamp       - epoch millis
 */
public final class MessageHistory {

    private static final int MAX_TURNS = 10;
    private static final int MAX_CHARS_PER_MSG = 400;

    private MessageHistory() {
    }

    /**
     * @return recent turns for the chat, oldest first, role-tagged
     *         ("user" = from them, "assistant" = from me); empty list on any failure.
     */
    public static List<ChatMessage> recent(SQLiteOpenHelper db, String jid, boolean isGroup) {
        List<ChatMessage> out = new ArrayList<ChatMessage>();
        if (db == null || jid == null || jid.length() == 0) return out;
        Cursor c = null;
        try {
            c = db.getReadableDatabase().rawQuery(
                    "SELECT key_from_me, remote_resource, data FROM messages "
                            + "WHERE key_remote_jid=? AND data IS NOT NULL AND data != '' "
                            + "ORDER BY timestamp DESC LIMIT ?",
                    new String[]{jid, String.valueOf(MAX_TURNS)});
            while (c.moveToNext()) {
                boolean fromMe = c.getInt(0) == 1;
                String sender = c.getString(1);
                String body = c.getString(2);
                if (body == null) continue;
                body = body.trim();
                if (body.length() == 0) continue;
                if (body.length() > MAX_CHARS_PER_MSG) body = body.substring(0, MAX_CHARS_PER_MSG) + "...";
                if (fromMe) {
                    out.add(new ChatMessage("assistant", body));
                } else if (isGroup && sender != null && sender.length() > 0) {
                    String who = sender;
                    int at = who.indexOf('@');
                    if (at > 0) who = who.substring(0, at);
                    out.add(new ChatMessage("user", who + ": " + body));
                } else {
                    out.add(new ChatMessage("user", body));
                }
            }
        } catch (Throwable t) {
            AiLogger.e(null, "MessageHistory read failed: " + t.getClass().getSimpleName(), t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        java.util.Collections.reverse(out); // oldest first
        return out;
    }
}
