package kmods.ai;

import android.content.Context;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.widget.Toast;

/**
 * Binds the AI Mods preference screen to runtime behavior:
 *  - live summaries (current URL / model / provider visible at a glance)
 *  - HTTPS validation when the Base URL is saved
 *  - value sanitization for max tokens
 *  - password-style API key handling (via {@link ApiKeyPreference})
 */
public final class AiSettingsUi {

    private AiSettingsUi() {
    }

    /** Called from kmods.Settings.onCreate after addPreferencesFromResource. */
    public static void register(final PreferenceActivity activity) {
        try {
            bindBaseUrl(activity);
            bindModel(activity);
            bindProvider(activity);
            bindSystemPrompt(activity);
            bindMaxTokens(activity);
            // API key summary is handled inside ApiKeyPreference itself.
        } catch (Throwable t) {
            AiLogger.e(activity, "AiSettingsUi.register failed", t);
        }
    }

    private static Preference find(PreferenceActivity act, String key) {
        try {
            return act.findPreference(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void bindBaseUrl(final PreferenceActivity act) {
        final Preference p = find(act, "ai_base_url");
        if (p == null) return;
        p.setSummary(currentBaseUrlSummary(act));
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                String url = String.valueOf(newValue).trim();
                String error = AiConfig.validateBaseUrl(url);
                if (error != null) {
                    Toast.makeText(act, "Rejected: " + error, Toast.LENGTH_LONG).show();
                    AiLogger.w(act, "Base URL rejected: " + error);
                    return false;
                }
                pref.setSummary(url.length() == 0
                        ? "Your own endpoint, e.g. https://api.groq.com/openai/v1"
                        : url);
                AiLogger.i(act, "Base URL saved (https-validated)");
                return true;
            }
        });
    }

    private static void bindModel(final PreferenceActivity act) {
        Preference p = find(act, "ai_model");
        if (p == null) return;
        if (p instanceof EditTextPreference) {
            String cur = ((EditTextPreference) p).getText();
            p.setSummary(cur == null || cur.trim().length() == 0
                    ? "Model the API should use, e.g. llama-3.1-8b-instant"
                    : cur.trim());
        }
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                String v = String.valueOf(newValue).trim();
                pref.setSummary(v.length() == 0
                        ? "Model the API should use, e.g. llama-3.1-8b-instant"
                        : v);
                return true;
            }
        });
    }

    private static void bindProvider(final PreferenceActivity act) {
        Preference p = find(act, "ai_provider");
        if (p == null) return;
        if (p instanceof ListPreference) {
            ListPreference lp = (ListPreference) p;
            CharSequence entry = lp.getEntry();
            p.setSummary(entry == null ? "Auto-detect from URL" : entry.toString());
        }
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                if (pref instanceof ListPreference) {
                    ListPreference lp = (ListPreference) pref;
                    int idx = lp.findIndexOfValue(String.valueOf(newValue));
                    pref.setSummary(idx >= 0 ? lp.getEntries()[idx] : String.valueOf(newValue));
                }
                return true;
            }
        });
    }

    private static void bindSystemPrompt(final PreferenceActivity act) {
        Preference p = find(act, "ai_system_prompt");
        if (p == null) return;
        p.setSummary(currentPromptSummary(act));
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                pref.setSummary(summarize(String.valueOf(newValue)));
                return true;
            }
        });
    }

    private static void bindMaxTokens(final PreferenceActivity act) {
        Preference p = find(act, "ai_max_tokens");
        if (p == null) return;
        p.setSummary(currentTokensSummary(act));
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                String v = String.valueOf(newValue).trim();
                if (v.length() > 0) {
                    try {
                        int n = Integer.parseInt(v);
                        if (n < 16 || n > 2048) {
                            Toast.makeText(act, "Value clamped to 16..2048", Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(act, "Numbers only (16..2048)", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
                pref.setSummary(v.length() == 0 ? "Upper bound for each completion (default 200)" : v + " tokens");
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- summaries

    private static String summarize(String v) {
        if (v == null || v.trim().length() == 0) {
            return "Optional custom persona/instructions for the reply assistant";
        }
        String t = v.trim().replace("\n", " ");
        return t.length() <= 80 ? t : t.substring(0, 80) + "...";
    }

    private static String currentBaseUrlSummary(Context ctx) {
        String cur = AiConfig.getBaseUrl(ctx);
        return cur.length() == 0
                ? "Your own endpoint, e.g. https://api.groq.com/openai/v1"
                : cur;
    }

    private static String currentPromptSummary(Context ctx) {
        return summarize(AiConfig.getSystemPrompt(ctx).equals(AiConfig.DEFAULT_SYSTEM_PROMPT) ? "" : AiConfig.getSystemPrompt(ctx));
    }

    private static String currentTokensSummary(Context ctx) {
        String cur = ctx.getSharedPreferences(AiConfig.PREFS, 0).getString("ai_max_tokens", "");
        return cur == null || cur.trim().length() == 0
                ? "Upper bound for each completion (default 200)"
                : cur.trim() + " tokens";
    }
}
