package com.aireply.companion;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.aireply.companion.ai.AiConfig;
import com.aireply.companion.ai.AiEngine;
import com.aireply.companion.ai.AiLogger;
import com.aireply.companion.ai.CryptoStore;
import com.aireply.companion.ai.ConvStore;

/**
 * AI Settings - every requirement field lives here:
 *   • AI API Base URL   (user-provided endpoint, HTTPS enforced)
 *   • AI Model Name     (used verbatim in every request)
 *   • API Key/Token     (encrypted via Android Keystore before persisting)
 *   • AI on/off toggle  (master switch) + auto-analyze / auto-send toggles
 *   • Provider type     (auto-detect / OpenAI-compatible / Gemini)
 *   • Max tokens + system prompt for tuning
 *
 * "Test connection" saves first, then performs a real round-trip so the
 * user immediately sees whether their free API endpoint works.
 */
public class AiSettingsActivity extends Activity {

    private Switch swEnabled, swAutoSuggest, swAutoSend, swDebug;
    private EditText etBaseUrl, etModel, etApiKey, etMaxTokens, etSystemPrompt;
    private Spinner spProvider;
    private TextView tvTestResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swEnabled = findViewById(R.id.sw_enabled);
        swAutoSuggest = findViewById(R.id.sw_auto_suggest);
        swAutoSend = findViewById(R.id.sw_auto_send);
        swDebug = findViewById(R.id.sw_debug);
        etBaseUrl = findViewById(R.id.et_base_url);
        etModel = findViewById(R.id.et_model);
        etApiKey = findViewById(R.id.et_api_key);
        etMaxTokens = findViewById(R.id.et_max_tokens);
        etSystemPrompt = findViewById(R.id.et_system_prompt);
        spProvider = findViewById(R.id.sp_provider);
        tvTestResult = findViewById(R.id.test_result);

        spProvider.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Auto-detect from URL", "OpenAI-compatible", "Google Gemini"}));

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(true);
            }
        });

        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!save(true)) return;
                runTest();
            }
        });

        load();
    }

    private void load() {
        SharedPreferences p = getSharedPreferences(AiConfig.PREFS, 0);
        swEnabled.setChecked(p.getBoolean("ai_enabled", false));
        swAutoSuggest.setChecked(p.getBoolean("ai_auto_suggest", true));
        swAutoSend.setChecked(p.getBoolean("ai_auto_send", false));
        swDebug.setChecked(p.getBoolean("ai_debug_log", false));

        etBaseUrl.setText(p.getString("ai_base_url", ""));
        etModel.setText(p.getString("ai_model", ""));

        // Show the DECRYPTED key so re-saving does not double-encrypt; it is
        // masked by the password field anyway.
        etApiKey.setText(AiConfig.getApiKey(this));

        String prov = AiConfig.getProvider(this);
        int idx = AiConfig.PROVIDER_GEMINI.equals(prov) ? 2
                : AiConfig.PROVIDER_OPENAI.equals(prov) ? 1 : 0;
        spProvider.setSelection(idx);

        etMaxTokens.setText(p.getString("ai_max_tokens", "200"));
        etSystemPrompt.setText(p.getString("ai_system_prompt", AiConfig.DEFAULT_SYSTEM_PROMPT));
    }

    /** @return true when settings were persisted (validation passed). */
    private boolean save(boolean toast) {
        String url = etBaseUrl.getText().toString().trim();
        String model = etModel.getText().toString().trim();

        // HTTPS enforcement happens at save time, not only at request time.
        String urlError = AiConfig.validateBaseUrl(url);
        if (urlError != null) {
            tvTestResult.setVisibility(View.VISIBLE);
            tvTestResult.setText("Not saved: " + urlError);
            Toast.makeText(this, urlError, Toast.LENGTH_LONG).show();
            return false;
        }
        if (TextUtils.isEmpty(model)) {
            Toast.makeText(this, "Model name is required", Toast.LENGTH_LONG).show();
            return false;
        }

        SharedPreferences.Editor e = getSharedPreferences(AiConfig.PREFS, 0).edit();
        e.putBoolean("ai_enabled", swEnabled.isChecked());
        e.putBoolean("ai_auto_suggest", swAutoSuggest.isChecked());
        e.putBoolean("ai_auto_send", swAutoSend.isChecked());
        e.putBoolean("ai_debug_log", swDebug.isChecked());
        e.putString("ai_base_url", url);
        e.putString("ai_model", model);
        // The key is the only secret: store the ENCRYPTED blob, never plaintext.
        e.putString("ai_api_key", CryptoStore.encrypt(this, etApiKey.getText().toString()));
        int provIdx = spProvider.getSelectedItemPosition();
        e.putString("ai_provider", provIdx == 2 ? AiConfig.PROVIDER_GEMINI
                : provIdx == 1 ? AiConfig.PROVIDER_OPENAI : AiConfig.PROVIDER_AUTO);
        e.putString("ai_max_tokens", etMaxTokens.getText().toString().trim());
        e.putString("ai_system_prompt", etSystemPrompt.getText().toString().trim());
        e.apply();

        AiLogger.i(this, "AI settings saved (model=" + model + ", provider=" + provIdx + ")");
        if (toast) Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void runTest() {
        tvTestResult.setVisibility(View.VISIBLE);
        tvTestResult.setText("Testing…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = AiEngine.testConnectionSync(AiSettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvTestResult.setText(result);
                    }
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        // Silent save so users do not lose a typed key by accident.
        save(false);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // Safety valve for the in-memory context (keeps nothing on disk).
        if (isFinishing() && !AiConfig.isEnabled(this)) {
            ConvStore.clear();
        }
        super.onDestroy();
    }
}
