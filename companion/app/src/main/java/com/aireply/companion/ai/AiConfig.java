package com.aireply.companion.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Typed access to the AI Settings the user configures in
 * AI Settings (persisted in the app's own private prefs).
 *
 * Preference keys:
 *   ai_enabled       - master on/off toggle
 *   ai_auto_suggest  - analyze incoming messages automatically
 *   ai_auto_send     - send the first suggestion without confirmation
 *   ai_debug_log     - verbose in-memory logging
 *   ai_provider      - "auto" | "openai" | "gemini"
 *   ai_base_url      - user-provided API endpoint (e.g. https://api.groq.com/openai/v1)
 *   ai_model         - model name (e.g. llama-3.1-8b-instant, gpt-4o-mini, gemini-1.5-flash)
 *   ai_api_key       - ENCRYPTED API key blob (see CryptoStore)
 *   ai_max_tokens    - max tokens per completion
 *   ai_system_prompt - persona/instructions for the suggester
 */
public final class AiConfig {

    public static final String PREFS = "aireply_prefs";

    public static final String PROVIDER_AUTO = "auto";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_GEMINI = "gemini";

    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful reply assistant inside a chat app. "
                    + "Given a short chat transcript, suggest natural, polite replies the user could send. "
                    + "Reply in the same language as the conversation. "
                    + "Return ONLY up to 3 candidate replies, each on its own line. "
                    + "No numbering, no quotes, no explanations, no emoji spam. "
                    + "Each reply must be short (under 120 characters).";

    private AiConfig() {
    }

    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0);
    }

    public static boolean isEnabled(Context ctx) {
        return p(ctx).getBoolean("ai_enabled", false);
    }

    public static boolean isAutoSuggest(Context ctx) {
        return p(ctx).getBoolean("ai_auto_suggest", true);
    }

    /** Auto-send mode: send the first suggestion immediately (no confirmation). */
    public static boolean isAutoSend(Context ctx) {
        return p(ctx).getBoolean("ai_auto_send", false);
    }

    public static boolean isDebugLog(Context ctx) {
        return p(ctx).getBoolean("ai_debug_log", false);
    }

    public static String getProvider(Context ctx) {
        String v = p(ctx).getString("ai_provider", PROVIDER_AUTO);
        return v == null || v.length() == 0 ? PROVIDER_AUTO : v;
    }

    /** @return normalized base URL (no trailing slash) or "" when unset. */
    public static String getBaseUrl(Context ctx) {
        String v = p(ctx).getString("ai_base_url", "");
        if (v == null) v = "";
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    public static String getModel(Context ctx) {
        String v = p(ctx).getString("ai_model", "");
        return v == null ? "" : v.trim();
    }

    /** @return the DECRYPTED API key (never logged). */
    public static String getApiKey(Context ctx) {
        String blob = p(ctx).getString("ai_api_key", "");
        if (blob == null) blob = "";
        return CryptoStore.decrypt(ctx, blob);
    }

    public static int getMaxTokens(Context ctx) {
        try {
            int v = Integer.parseInt(p(ctx).getString("ai_max_tokens", "200").trim());
            if (v < 16) v = 16;
            if (v > 2048) v = 2048;
            return v;
        } catch (Throwable t) {
            return 200;
        }
    }

    public static String getSystemPrompt(Context ctx) {
        String v = p(ctx).getString("ai_system_prompt", DEFAULT_SYSTEM_PROMPT);
        if (v == null || v.trim().length() == 0) return DEFAULT_SYSTEM_PROMPT;
        return v.trim();
    }

    /** Minimal sanity check used before making network calls. */
    public static boolean isConfigured(Context ctx) {
        return !TextUtils.isEmpty(getBaseUrl(ctx)) && !TextUtils.isEmpty(getModel(ctx));
    }

    /**
     * Enforces HTTPS for every remote host. Plain HTTP is only accepted for
     * loopback / private-network addresses (local LLM servers such as Ollama
     * or LM Studio running on the same device or LAN), where TLS is not
     * available anyway. This keeps the security requirement: "all API calls
     * are made securely (HTTPS)" while still allowing local free APIs.
     *
     * @return null when valid, otherwise a human-readable error message.
     */
    public static String validateBaseUrl(String url) {
        if (TextUtils.isEmpty(url)) return "Base URL is required";
        String u = url.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.startsWith("https://")) return null;
        if (u.startsWith("http://")) {
            String host = u.substring("http://".length());
            int slash = host.indexOf('/');
            if (slash >= 0) host = host.substring(0, slash);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            host = host.toLowerCase();
            boolean local = "localhost".equals(host)
                    || "127.0.0.1".equals(host)
                    || "0.0.0.0".equals(host)
                    || "[::1]".equals(host)
                    || host.startsWith("10.")
                    || host.startsWith("192.168.")
                    || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
            if (local) return null; // local test server, TLS impossible - allowed
            return "HTTPS is required for remote APIs (http:// only allowed for localhost/LAN)";
        }
        return "URL must start with https:// (or http:// for a localhost test server)";
    }
}
