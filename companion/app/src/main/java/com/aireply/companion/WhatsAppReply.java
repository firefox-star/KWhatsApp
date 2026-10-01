package com.aireply.companion;

import android.app.Notification;
import android.app.RemoteInput;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends replies THROUGH WhatsApp's own notification reply action
 * (RemoteInput) - the same mechanism Wear OS and "AutoResponder"-style
 * apps use. We never touch WhatsApp's code or data; we simply fill in
 * the reply text on the reply button WhatsApp itself put on its
 * notification, which is the official, supported way to answer a
 * message without opening the app.
 *
 * Memory map: convKey -> the most recent WhatsApp StatusBarNotification
 * for that chat that carried a reply action. Entries are refreshed on
 * every notification post and evicted FIFO (a dismissed/old PendingIntent
 * may stop working anyway - callers must handle send failures).
 */
public final class WhatsAppReply {

    private static final int MAX = 12;

    private static final LinkedHashMap<String, Notification.Action> ACTIONS =
            new LinkedHashMap<String, Notification.Action>();

    private WhatsAppReply() {
    }

    /** Called from the listener when a WhatsApp notification arrives. */
    public static synchronized void remember(String convKey, StatusBarNotification sbn) {
        if (convKey == null || sbn == null) return;
        Notification.Action found = findReplyAction(sbn.getNotification());
        if (found == null) return;
        ACTIONS.remove(convKey);
        ACTIONS.put(convKey, found);
        while (ACTIONS.size() > MAX) {
            Iterator<String> it = ACTIONS.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    public static synchronized boolean canReply(String convKey) {
        return convKey != null && ACTIONS.containsKey(convKey);
    }

    public static synchronized void forgetAll() {
        ACTIONS.clear();
    }

    /**
     * Fills WhatsApp's own RemoteInput reply action with {@code text} and
     * fires it. Must be called on a background-safe path (it is fast, but
     * callers may still prefer a worker thread).
     *
     * @return true when the reply intent was dispatched successfully.
     */
    public static boolean send(Context ctx, String convKey, CharSequence text) {
        Notification.Action act;
        synchronized (WhatsAppReply.class) {
            act = ACTIONS.get(convKey);
        }
        if (act == null) {
            com.aireply.companion.ai.AiLogger.w(ctx, "send: no remembered reply action for conv");
            return false;
        }
        RemoteInput[] remoteInputs = act.getRemoteInputs();
        if (remoteInputs == null || remoteInputs.length == 0) {
            com.aireply.companion.ai.AiLogger.w(ctx, "send: remembered action has no RemoteInput");
            return false;
        }
        try {
            Bundle results = new Bundle();
            for (RemoteInput ri : remoteInputs) {
                results.putCharSequence(ri.getResultKey(), text);
            }
            Intent localIntent = new Intent();
            RemoteInput.addResultsToIntent(remoteInputs, localIntent, results);
            act.actionIntent.send(ctx, 0, localIntent);
            com.aireply.companion.ai.AiLogger.i(ctx, "Reply dispatched via WhatsApp RemoteInput ("
                    + text.length() + " chars)");
            return true;
        } catch (Throwable t) {
            com.aireply.companion.ai.AiLogger.e(ctx, "Reply dispatch failed "
                    + "(WhatsApp notification dismissed? reopen the chat once)", t);
            return false;
        }
    }

    /** Finds the first action carrying a direct-reply RemoteInput. */
    private static Notification.Action findReplyAction(Notification n) {
        if (n == null || n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            try {
                if (a != null && a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) {
                    return a;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
