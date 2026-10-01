package com.aireply.companion.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation context, keyed per chat (conversation key =
 * source app + chat title).
 *
 * Replaces the mod's MessageHistory (which read WhatsApp's database -
 * impossible from a separate app). The listener feeds incoming messages in;
 * the engine reads a snapshot for the AI request; sent replies are fed back
 * as "assistant" turns so the model keeps context over the conversation.
 *
 * Also remembers the last incoming text per chat so "Retry" can re-run the
 * suggestion, and caps memory with fixed-size deques.
 */
public final class ConvStore {

    private static final int MAX_TURNS = 12;
    private static final int MAX_CONVS = 40;
    private static final int MAX_TEXT = 2000;

    private static final ConcurrentHashMap<String, ArrayDeque<ChatMessage>> CONVS =
            new ConcurrentHashMap<String, ArrayDeque<ChatMessage>>();
    private static final ConcurrentHashMap<String, String> LAST_INCOMING =
            new ConcurrentHashMap<String, String>();

    private ConvStore() {
    }

    /** Records an incoming message from the other side. */
    public static void onIncoming(String convKey, String text) {
        if (convKey == null || text == null || text.trim().length() == 0) return;
        LAST_INCOMING.put(convKey, text.trim());
        add(convKey, new ChatMessage("user", clip(text)));
    }

    /** Records a reply that was actually sent (or chosen) by the user. */
    public static void onReplySent(String convKey, String text) {
        if (convKey == null || text == null || text.trim().length() == 0) return;
        add(convKey, new ChatMessage("assistant", clip(text)));
    }

    /** @return recent turns for the chat, oldest first (defensive copy). */
    public static List<ChatMessage> recent(String convKey) {
        List<ChatMessage> out = new ArrayList<ChatMessage>();
        ArrayDeque<ChatMessage> d = CONVS.get(convKey);
        if (d == null) return out;
        synchronized (d) {
            Iterator<ChatMessage> it = d.iterator();
            while (it.hasNext()) {
                ChatMessage m = it.next();
                out.add(new ChatMessage(m.role, m.content));
            }
        }
        return out;
    }

    /** @return the last incoming message stored for the chat, or null. */
    public static String lastIncoming(String convKey) {
        return LAST_INCOMING.get(convKey);
    }

    public static void clear() {
        CONVS.clear();
        LAST_INCOMING.clear();
    }

    private static void add(String convKey, ChatMessage m) {
        ArrayDeque<ChatMessage> d = CONVS.get(convKey);
        if (d == null) {
            d = new ArrayDeque<ChatMessage>();
            ArrayDeque<ChatMessage> prev = CONVS.putIfAbsent(convKey, d);
            if (prev != null) d = prev;
        }
        synchronized (d) {
            d.addLast(m);
            while (d.size() > MAX_TURNS) d.pollFirst();
        }
        // Keep the map bounded (notifications from many chats over days).
        if (CONVS.size() > MAX_CONVS) {
            String oldest = null;
            for (String k : CONVS.keySet()) {
                oldest = k;
                break;
            }
            if (oldest != null) {
                CONVS.remove(oldest);
                LAST_INCOMING.remove(oldest);
            }
        }
    }

    private static String clip(String s) {
        String t = s.trim();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    /** Compact view used for logs. */
    public static String stats() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ArrayDeque<ChatMessage>> e : CONVS.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            synchronized (e.getValue()) {
                sb.append(safe(e.getKey())).append('=').append(e.getValue().size());
            }
        }
        return sb.length() == 0 ? "(no active chats)" : sb.toString();
    }

    private static String safe(String s) {
        if (s == null) return "?";
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
}
