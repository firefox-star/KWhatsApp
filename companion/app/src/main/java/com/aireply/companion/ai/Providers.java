package com.aireply.companion.ai;

import android.content.Context;

/**
 * Provider registry / factory. This is where new AI providers plug in -
 * the rest of the app only ever talks to {@link AiProvider}.
 */
public final class Providers {

    private Providers() {
    }

    /**
     * Resolves the provider to use based on the "ai_provider" setting.
     *
     * @throws AiException with a user-facing message when config is incomplete/invalid
     */
    public static AiProvider resolve(Context ctx) throws AiException {
        String baseUrl = AiConfig.getBaseUrl(ctx);
        String model = AiConfig.getModel(ctx);
        String apiKey = AiConfig.getApiKey(ctx); // decrypted
        String selection = AiConfig.getProvider(ctx);

        if (baseUrl.length() == 0 || model.length() == 0) {
            throw new AiException("AI not configured - set Base URL and Model in AI Settings");
        }
        String urlError = AiConfig.validateBaseUrl(baseUrl);
        if (urlError != null) {
            throw new AiException("Insecure/invalid API URL: " + urlError, urlError + " url=" + baseUrl);
        }

        String effective = selection;
        if (AiConfig.PROVIDER_AUTO.equals(selection)) {
            effective = GeminiProvider.matches(baseUrl)
                    ? AiConfig.PROVIDER_GEMINI
                    : AiConfig.PROVIDER_OPENAI;
            AiLogger.d(ctx, "Provider auto-detect: " + effective);
        }

        if (AiConfig.PROVIDER_GEMINI.equals(effective)) {
            return new GeminiProvider(baseUrl, model, apiKey, AiConfig.getMaxTokens(ctx));
        }
        if (AiConfig.PROVIDER_OPENAI.equals(effective)) {
            return new OpenAiCompatibleProvider(baseUrl, model, apiKey, AiConfig.getMaxTokens(ctx));
        }
        // Unknown selection value -> fall back to OpenAI-compatible
        AiLogger.w(ctx, "Unknown ai_provider '" + selection + "', falling back to OpenAI-compatible");
        return new OpenAiCompatibleProvider(baseUrl, model, apiKey, AiConfig.getMaxTokens(ctx));
    }
}
