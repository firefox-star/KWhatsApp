package com.aireply.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.List;

/**
 * Builds and posts the suggestion notification the user interacts with:
 *
 *   "Suggested reply to Jane"                 (heads-up)
 *   Message: "Are we still on for tomorrow?"
 *   Suggested reply:
 *   • Yes, see you at 5!
 *   • Yes! Should I bring anything?
 *
 *   [ Send reply ]  [ Edit ]  [ Copy ]
 *
 * - Send reply  = ACCEPT: sends the first suggestion via WhatsApp's own
 *                 reply action (RemoteInput).
 * - Edit        = opens {@link EditReplyActivity} with ALL candidates.
 * - Copy        = copies the first suggestion to the clipboard.
 * - Ignore      = swipe the notification away (auto-cancel).
 *
 * Failures use the same id with a Retry action instead.
 */
public final class SuggestionNotifier {

    public static final String CH_SUGGESTIONS = "ai_suggestions";
    public static final String CH_STATUS = "ai_status";

    public static final String EXTRA_CONV = "conv";
    public static final String EXTRA_SENDER = "sender";

    private SuggestionNotifier() {
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel sugg = new NotificationChannel(CH_SUGGESTIONS,
                ctx.getString(R.string.sugg_channel), NotificationManager.IMPORTANCE_HIGH);
        sugg.setDescription(ctx.getString(R.string.sugg_channel_desc));
        nm.createNotificationChannel(sugg);
        NotificationChannel status = new NotificationChannel(CH_STATUS,
                ctx.getString(R.string.status_channel), NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(status);
    }

    /** Stable notification id per conversation so updates replace, not pile up. */
    public static int idFor(String convKey) {
        return ("sugg|" + convKey).hashCode();
    }

    public static void cancel(Context ctx, String convKey) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(idFor(convKey));
    }

    /** Shows a suggestion (or failure) notification. Safe from any thread. */
    public static void showSuggestions(Context ctx, String convKey, CharSequence sender,
                                       CharSequence original, List<String> suggestions, boolean isError) {
        ensureChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String senderName = sender == null ? ctx.getString(R.string.app_name) : sender.toString();
        String first = suggestions.isEmpty() ? "" : suggestions.get(0);

        String shortText = original == null ? "" : original.toString();
        if (shortText.length() > 120) shortText = shortText.substring(0, 120) + "…";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CH_SUGGESTIONS)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentTitle(isError
                        ? ctx.getString(R.string.err_title)
                        : ctx.getString(R.string.sugg_for, senderName))
                .setContentText(first.isEmpty() ? shortText : first)
                .setStyle(new Notification.BigTextStyle()
                        .bigText((original == null || original.length() == 0 ? "" : "Message: " + shortText + "\n\n")
                                + (isError ? first : "Suggested reply:\n" + join(suggestions))))
                .setContentIntent(editPending(ctx, convKey, senderName, original, suggestions, 11));

        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_HIGH);
        }

        if (!isError && !first.isEmpty()) {
            b.addAction(action(ctx, ReplyReceiver.ACTION_SEND, "Send reply",
                    convKey, senderName, first, 1));
            b.addAction(action(ctx, ReplyReceiver.ACTION_COPY, "Copy",
                    convKey, senderName, first, 2));
        } else {
            b.addAction(action(ctx, ReplyReceiver.ACTION_RETRY, "Retry",
                    convKey, senderName, "", 3));
        }
        b.addAction(editAction(ctx, convKey, senderName, original, suggestions));

        try {
            nm.notify(idFor(convKey), b.build());
        } catch (Throwable t) {
            com.aireply.companion.ai.AiLogger.e(ctx, "notify failed", t);
        }
    }

    /** Low-key confirmation used after auto-send. */
    public static void showAutoSent(Context ctx, String convKey, CharSequence sender, String text) {
        ensureChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CH_STATUS)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat)
                .setAutoCancel(true)
                .setContentTitle(ctx.getString(R.string.auto_sent_title))
                .setContentText("To " + sender + ": " + text);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        try {
            nm.notify(("autosent|" + convKey).hashCode(), b.build());
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- parts

    private static Notification.Action action(Context ctx, String action, String title,
                                              String convKey, String sender, String text, int req) {
        Intent i = new Intent(ctx, ReplyReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_CONV, convKey)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(ReplyReceiver.EXTRA_TEXT, text);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat),
                title, pi).build();
    }

    private static Notification.Action editAction(Context ctx, String convKey, String sender,
                                                  CharSequence original, List<String> suggestions) {
        PendingIntent pi = editPending(ctx, convKey, sender, original, suggestions, 10);
        return new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat),
                "Edit", pi).build();
    }

    private static PendingIntent editPending(Context ctx, String convKey, String sender,
                                             CharSequence original, List<String> suggestions, int req) {
        Intent i = new Intent(ctx, EditReplyActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_CONV, convKey)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EditReplyActivity.EXTRA_ORIGINAL, original == null ? "" : original.toString())
                .putExtra(EditReplyActivity.EXTRA_SUGGESTIONS, suggestions.toArray(new String[0]));
        return PendingIntent.getActivity(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append("• ").append(l.get(i));
        }
        return sb.toString();
    }
}
