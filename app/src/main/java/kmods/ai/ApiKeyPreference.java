package kmods.ai;

import android.content.Context;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;

/**
 * API key / auth token preference.
 *
 * - Renders the input as a password field (dots) on screen and in the
 *   clipboard-free dialog, so nobody shoulder-surfs the key.
 * - PERSISTS THE ENCRYPTED BLOB ONLY (see {@link CryptoStore}) - the plaintext
 *   key never touches SharedPreferences, backups or logs.
 * - Summary shows only the last 4 characters.
 */
public class ApiKeyPreference extends EditTextPreference {

    public ApiKeyPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ApiKeyPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ApiKeyPreference(Context context) {
        super(context);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            String plain = getEditText().getText().toString().trim();
            if (callChangeListener(plain)) {
                String blob = plain.length() == 0 ? "" : CryptoStore.encrypt(getContext(), plain);
                setText(blob); // persists the ENCRYPTED value into com.whatsapp_preferences
                setSummary(buildSummary(blob));
                notifyChanged();
                AiLogger.i(getContext(), "API key updated (stored encrypted, "
                        + (plain.length() == 0 ? "cleared" : plain.length() + " chars)"));
            }
        }
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        // super put the stored (encrypted) value into the field - replace with plaintext.
        EditText field = (EditText) view.findViewById(android.R.id.edit);
        if (field == null && view instanceof EditText) field = (EditText) view;
        if (field != null) {
            String plain = CryptoStore.decrypt(getContext(), getText());
            field.setText(plain);
            field.setSelection(plain.length());
            field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
    }

    @Override
    public CharSequence getSummary() {
        return buildSummary(getText());
    }

    private String buildSummary(String blob) {
        if (blob == null || blob.length() == 0) {
            return "Not set - paste your API key / token";
        }
        String plain = CryptoStore.decrypt(getContext(), blob);
        if (plain.length() == 0) {
            return "Set (could not verify - re-enter if requests fail)";
        }
        String tail = plain.length() > 4 ? plain.substring(plain.length() - 4) : plain;
        return "Stored securely (encrypted) - ends with: " + tail;
    }
}
