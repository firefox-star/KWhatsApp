package kmods.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The "smart reply" bar injected above the message input box in the
 * Conversation screen. Renders up to 3 AI-generated reply suggestions as
 * horizontally scrollable chips.
 *
 * User actions (requirement: accept, edit or ignore):
 *  - TAP a chip           -> ACCEPT : put the text into the WhatsApp input box;
 *                                      the user stays in full control of sending.
 *  - LONG-PRESS a chip    -> popup menu: "Edit suggestion" (editor dialog,
 *                                      inserts the edited text on OK),
 *                                      "Copy text", "Ignore this suggestion".
 *  - TAP the refresh icon -> regenerate suggestions.
 *  - TAP the close icon   -> ignore all (dismiss the bar).
 *
 * States: loading ("AI is thinking..."), error chip (tap = retry), suggestions.
 * The bar is 100% programmatic (no XML resource) to keep the mod-merge
 * workflow free of extra resource collisions. All view callbacks are anonymous
 * inner classes (project targets old Java style - no lambdas).
 */
public class SmartReplyBar extends LinearLayout {

    private static final int COLOR_BG = 0xE6141C24;      // dark translucent panel
    private static final int COLOR_CHIP = 0xFF25313B;    // chip background
    private static final int COLOR_CHIP_TXT = 0xFFF2F4F7;
    private static final int COLOR_MUTED = 0xFF9AA6B0;
    private static final int COLOR_ACCENT = 0xFF25D366;   // WhatsApp-ish green
    private static final int COLOR_ERROR = 0xFFE53935;
    private static final int MAX_SUGGESTIONS = 3;

    /** Currently attached bar (only one conversation is visible at a time). */
    private static WeakReference<SmartReplyBar> sAttached;

    private final String jid;
    private final boolean isGroup;
    private final WeakReference<EditText> inputRef;
    private final LinearLayout chipsRow;
    private final TextView header;

    private String lastIncoming = null;
    private List<String> currentSuggestions = null;

    private SmartReplyBar(Context ctx, String jid, boolean isGroup, EditText input) {
        super(ctx);
        this.jid = jid;
        this.isGroup = isGroup;
        this.inputRef = new WeakReference<EditText>(input);

        setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG);
        bg.setCornerRadius(dp(12));
        setBackgroundDrawable(bg);
        int pad = dp(6);
        setPadding(pad, pad, pad, pad);

        // ---- header row: "AI replies" + refresh + close ---------------------
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        header = new TextView(ctx);
        header.setText("AI replies");
        header.setTextColor(COLOR_ACCENT);
        header.setTextSize(11);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hp.leftMargin = dp(4);
        headerRow.addView(header, hp);

        TextView refresh = iconBtn(ctx, "\u21BB"); // clockwise open circle arrow (regenerate)
        refresh.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                requestSuggestions();
            }
        });
        headerRow.addView(refresh);

        TextView close = iconBtn(ctx, "\u2715"); // X (ignore all)
        close.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                ignoreAll();
            }
        });
        headerRow.addView(close);

        addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- chips row inside a horizontal scroller --------------------------
        HorizontalScrollView scroller = new HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        chipsRow = new LinearLayout(ctx);
        chipsRow.setOrientation(HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsRow.setPadding(0, dp(2), 0, dp(2));
        scroller.addView(chipsRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ attach

    /**
     * Attaches a bar above {@code input} (WhatsApp's message entry EditText).
     * Any previously attached bar is removed first. No-op when the input view
     * or its parent cannot host the bar - everything is logged, never thrown.
     *
     * @param input the conversation entry EditText (may be null -> tap-to-insert
     *              falls back to clipboard copy)
     */
    public static void attach(Activity activity, EditText input, String jid, boolean isGroup) {
        try {
            detach();
            if (activity == null || jid == null || jid.length() == 0) return;
            if (!AiConfig.isEnabled(activity)) {
                AiLogger.d(activity, "AI disabled - SmartReplyBar not attached");
                return;
            }
            ViewGroup host = null;
            View anchor = null;
            if (input != null && input.getParent() instanceof ViewGroup) {
                host = (ViewGroup) input.getParent();
                anchor = input;
            } else {
                View content = activity.findViewById(android.R.id.content);
                if (content instanceof ViewGroup) host = (ViewGroup) content;
            }
            if (host == null) {
                AiLogger.w(activity, "SmartReplyBar.attach: no host view found");
                return;
            }
            SmartReplyBar bar = new SmartReplyBar(activity, jid, isGroup, input);
            ViewGroup.LayoutParams params;
            int index;
            if (host instanceof LinearLayout) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(8), dp(4), dp(8), dp(4));
                params = lp;
                index = anchor != null ? Math.max(0, host.indexOfChild(anchor)) : 0;
            } else {
                params = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                index = 0;
            }
            try {
                host.addView(bar, index, params);
            } catch (Throwable fallback) {
                host.addView(bar, params);
            }
            sAttached = new WeakReference<SmartReplyBar>(bar);
            String shortJid = jid.contains("@") ? jid.substring(0, jid.indexOf('@')) : jid;
            AiLogger.d(activity, "SmartReplyBar attached jid=" + shortJid);
            // Initial load: analyze the latest incoming message of this chat.
            if (AiConfig.isAutoSuggest(activity)) {
                bar.requestSuggestions();
            }
        } catch (Throwable t) {
            AiLogger.e(activity, "SmartReplyBar.attach failed", t);
        }
    }

    /** Removes the currently attached bar, if any. Safe from any thread. */
    public static void detach() {
        final SmartReplyBar bar = sAttached == null ? null : sAttached.get();
        sAttached = null;
        if (bar != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        ViewGroup p = (ViewGroup) bar.getParent();
                        if (p != null) p.removeView(bar);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    /** @return the attached bar's jid, or null. Used to route async results. */
    public static String attachedJid() {
        SmartReplyBar bar = sAttached == null ? null : sAttached.get();
        return bar == null ? null : bar.jid;
    }

    /** Main-thread delivery point used by {@link AiReplies}. */
    static void deliverSuggestions(String forJid, List<String> suggestions) {
        SmartReplyBar bar = sAttached == null ? null : sAttached.get();
        if (bar == null) return;
        if (forJid != null && !forJid.equals(bar.jid)) return; // user switched chats meanwhile
        bar.showSuggestions(suggestions);
    }

    /** Main-thread delivery point for errors used by {@link AiReplies}. */
    static void deliverError(String forJid, String userMessage) {
        SmartReplyBar bar = sAttached == null ? null : sAttached.get();
        if (bar == null) return;
        if (forJid != null && !forJid.equals(bar.jid)) return;
        bar.showError(userMessage);
    }

    // ------------------------------------------------------------------ states

    private void showLoading() {
        header.setText("AI is thinking...");
        header.setTextColor(COLOR_MUTED);
        chipsRow.removeAllViews();
        TextView loading = chip("...", false);
        loading.setOnClickListener(null);
        loading.setOnLongClickListener(null);
        chipsRow.addView(loading);
    }

    private void showSuggestions(List<String> suggestions) {
        this.currentSuggestions = suggestions;
        header.setText("AI replies");
        header.setTextColor(COLOR_ACCENT);
        chipsRow.removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) {
            showError("No suggestions available");
            return;
        }
        for (int i = 0; i < suggestions.size() && i < MAX_SUGGESTIONS; i++) {
            final String text = suggestions.get(i);
            TextView chip = chip(text, true);
            chip.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    acceptSuggestion(text);
                }
            });
            chip.setOnLongClickListener(new OnLongClickListener() {
                public boolean onLongClick(View v) {
                    openChipMenu(text);
                    return true;
                }
            });
            chipsRow.addView(chip);
        }
    }

    private void showError(final String message) {
        header.setText("AI error");
        header.setTextColor(COLOR_ERROR);
        chipsRow.removeAllViews();
        TextView err = chip(message, false);
        err.setTextColor(0xFFFFD9D6);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x40E53935);
        bg.setCornerRadius(dp(18));
        bg.setStroke(1, 0x88E53935);
        err.setBackgroundDrawable(bg);
        err.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(getContext(), "Retrying AI request...", Toast.LENGTH_SHORT).show();
                requestSuggestions();
            }
        });
        chipsRow.addView(err);
    }

    private void ignoreAll() {
        lastIncoming = null;
        currentSuggestions = null;
        try {
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
        } catch (Throwable ignored) {
        }
        AiLogger.d(getContext(), "SmartReplyBar dismissed by user");
    }

    // ------------------------------------------------------------------ actions

    private void requestSuggestions() {
        Context ctx = getContext();
        if (!AiConfig.isEnabled(ctx)) {
            Toast.makeText(ctx, "AI is disabled in KMods > AI Mods", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AiConfig.isConfigured(ctx)) {
            Toast.makeText(ctx, "Set Base URL + Model in KMods > AI Mods first", Toast.LENGTH_LONG).show();
            return;
        }
        showLoading();
        String incoming = lastIncoming;
        if (incoming == null || incoming.length() == 0) {
            // No real-time hook fired - take the newest incoming text message from the DB.
            List<ChatMessage> hist = MessageHistory.recent(kmods.Utils.db(), jid, isGroup);
            String latest = null;
            for (int i = hist.size() - 1; i >= 0; i--) {
                ChatMessage m = hist.get(i);
                if ("user".equals(m.role)) {
                    latest = m.content;
                    break;
                }
            }
            if (latest != null && latest.length() > 0) incoming = latest;
        }
        if (incoming == null || incoming.length() == 0) {
            showError("No incoming message found to reply to");
            return;
        }
        lastIncoming = incoming;
        AiEngine.suggest(ctx, jid, isGroup, incoming, new AiEngine.Callback() {
            public void onSuggestions(String forJid, List<String> suggestions) {
                deliverSuggestions(forJid, suggestions);
            }

            public void onError(String forJid, String userMessage) {
                deliverError(forJid, userMessage);
            }
        });
    }

    /** ACCEPT: insert into WhatsApp's input box (user still sends manually). */
    private void acceptSuggestion(String text) {
        EditText input = inputRef.get();
        if (input != null) {
            try {
                input.setText(text);
                input.requestFocus();
                input.setSelection(text.length());
                AiLogger.d(getContext(), "Suggestion accepted: " + text);
                return;
            } catch (Throwable t) {
                AiLogger.e(getContext(), "Insert into input failed", t);
            }
        }
        copyToClipboard(text);
    }

    private void openChipMenu(final String text) {
        PopupMenu pm = new PopupMenu(getContext(), chipsRow);
        pm.getMenu().add("Edit suggestion");
        pm.getMenu().add("Copy text");
        pm.getMenu().add("Ignore this suggestion");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String title = String.valueOf(item.getTitle());
                if ("Edit suggestion".equals(title)) {
                    editSuggestion(text);
                } else if ("Copy text".equals(title)) {
                    copyToClipboard(text);
                } else if ("Ignore this suggestion".equals(title)) {
                    if (currentSuggestions != null) currentSuggestions.remove(text);
                    showSuggestions(currentSuggestions);
                }
                return true;
            }
        });
        pm.show();
    }

    /** EDIT: small editor dialog; inserts the edited text on OK. */
    private void editSuggestion(final String original) {
        Context ctx = getContext();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Edit AI suggestion");
        final EditText editor = new EditText(ctx);
        editor.setText(original);
        editor.setSelection(original.length());
        editor.setSingleLine(false);
        editor.setMinLines(1);
        editor.setMaxLines(4);
        int pad = dp(16);
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(editor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setView(wrap);
        b.setPositiveButton("Insert", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                String value = editor.getText().toString().trim();
                if (value.length() > 0) acceptSuggestion(value);
            }
        });
        b.setNeutralButton("Copy", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                String value = editor.getText().toString().trim();
                if (value.length() > 0) copyToClipboard(value);
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("AI reply", text));
            Toast.makeText(getContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show();
            AiLogger.d(getContext(), "Suggestion copied: " + text);
        } catch (Throwable t) {
            Toast.makeText(getContext(), "Copy failed", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ widgets

    private TextView chip(String text, boolean active) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(active ? COLOR_CHIP_TXT : COLOR_MUTED);
        tv.setTextSize(13);
        tv.setMaxLines(1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CHIP);
        bg.setCornerRadius(dp(18));
        tv.setBackgroundDrawable(bg);
        int hp = dp(12);
        int vp = dp(7);
        tv.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView iconBtn(Context ctx, String glyph) {
        TextView t = new TextView(ctx);
        t.setText(glyph);
        t.setTextColor(COLOR_MUTED);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10), dp(2), dp(6), dp(2));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static int dp(int v) {
        return Math.round(v * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }
}
