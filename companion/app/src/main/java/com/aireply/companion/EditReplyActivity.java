package com.aireply.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.aireply.companion.ai.AiLogger;
import com.aireply.companion.ai.ConvStore;

import java.util.Arrays;

/**
 * The EDIT step of accept / edit / ignore.
 *
 * Opened from the suggestion notification ("Edit" action or tapping the
 * card). Shows the original message, an editable reply box pre-filled with
 * the first AI candidate, tap-to-use chips for the remaining candidates,
 * and Send / Copy / Cancel buttons.
 *
 * Send dispatches through WhatsApp's own RemoteInput reply action
 * ({@link WhatsAppReply#send}) - the official mechanism, no WhatsApp code
 * is touched.
 */
public class EditReplyActivity extends Activity {

    public static final String EXTRA_ORIGINAL = "original";
    public static final String EXTRA_SUGGESTIONS = "suggestions";

    private String convKey;
    private String sender;
    private String original;
    private String[] suggestions;
    private EditText etReply;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(true);

        Intent intent = getIntent();
        convKey = intent.getStringExtra(SuggestionNotifier.EXTRA_CONV);
        sender = intent.getStringExtra(SuggestionNotifier.EXTRA_SENDER);
        original = intent.getStringExtra(EXTRA_ORIGINAL);
        String[] sugg = intent.getStringArrayExtra(EXTRA_SUGGESTIONS);
        suggestions = sugg == null ? new String[0] : sugg;

        setContentView(R.layout.activity_edit);
        getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        TextView tvOriginal = findViewById(R.id.tv_original);
        TextView title = findViewById(R.id.tv_title);
        title.setText(getString(R.string.sugg_for, sender == null ? "" : sender));
        tvOriginal.setText((sender == null ? "" : sender + ": ") + (original == null ? "" : original));

        etReply = findViewById(R.id.et_reply);
        if (suggestions.length > 0) {
            etReply.setText(suggestions[0]);
            etReply.setSelection(0, suggestions[0].length());
        }

        // Tap-to-use alternative candidates
        LinearLayout alt = findViewById(R.id.alt_container);
        alt.removeAllViews();
        for (int i = 1; i < suggestions.length; i++) {
            final String candidate = suggestions[i];
            TextView chip = new TextView(this);
            chip.setText("↪ " + candidate);
            chip.setTextColor(0xFF25D366);
            chip.setTextSize(13);
            chip.setPadding(dp(8), dp(8), dp(8), dp(8));
            chip.setBackgroundResource(android.R.color.transparent);
            chip.setGravity(Gravity.START);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etReply.setText(candidate);
                    etReply.setSelection(candidate.length());
                }
            });
            alt.addView(chip);
        }

        findViewById(R.id.btn_send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendReply();
            }
        });
        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyReply();
            }
        });
        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void sendReply() {
        String text = etReply.getText().toString().trim();
        if (text.length() == 0) return;
        SuggestionNotifier.cancel(this, convKey);
        if (WhatsAppReply.send(this, convKey, text)) {
            ConvStore.onReplySent(convKey, text);
            AiLogger.i(this, "User edited + sent suggestion for " + sender);
            Toast.makeText(this, R.string.sent_ok, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.send_failed, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void copyReply() {
        try {
            String text = etReply.getText().toString().trim();
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI reply", text));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            AiLogger.e(this, "Copy failed", t);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
