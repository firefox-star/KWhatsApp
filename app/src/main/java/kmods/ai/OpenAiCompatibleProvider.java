package kmods.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider for ANY OpenAI-compatible /chat/completions endpoint.
 * Works with: OpenAI, Groq, OpenRouter, Together, DeepSeek, Mistral,
 * Z.ai (GLM), BigModel, Fireworks, Perplexity, llama.cpp server,
 * Ollama (/v1), LM Studio, LM Studio local, vLLM, and many free tiers.
 *
 * Expected user Base URL examples (the mod appends /chat/completions):
 *   https://api.openai.com/v1
 *   https://api.groq.com/openai/v1
 *   https://openrouter.ai/api/v1
 *   https://api.z.ai/api/paas/v4
 *   http://127.0.0.1:11434/v1                (Ollama, local)
 *
 * If the user pastes a URL that ALREADY ends with /chat/completions it is
 * used as-is (so both ".../v1" and full-endpoint styles work).
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int maxTokens;

    public OpenAiCompatibleProvider(String baseUrl, String model, String apiKey, int maxTokens) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
    }

    /** Auto-detection: any https endpoint that is not Google's Gemini host is treated as OpenAI-style. */
    public static boolean matches(String baseUrl) {
        return baseUrl != null && !baseUrl.contains("generativelanguage.googleapis.com");
    }

    @Override
    public String getName() {
        return "OpenAI-compatible";
    }

    @Override
    public String complete(String systemPrompt, List<ChatMessage> history, String incoming) throws AiException {
        String endpoint = buildEndpoint();
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.put(sys);
            if (history != null) {
                for (int i = 0; i < history.size(); i++) {
                    ChatMessage m = history.get(i);
                    JSONObject jm = new JSONObject();
                    jm.put("role", m.role);
                    jm.put("content", m.content);
                    messages.put(jm);
                }
            }
            JSONObject last = new JSONObject();
            last.put("role", "user");
            last.put("content", incoming);
            messages.put(last);
            body.put("messages", messages);
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.7);
            body.put("stream", false);
        } catch (Throwable t) {
            throw new AiException("Failed to build request", t);
        }

        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (apiKey != null && apiKey.length() > 0) {
            headers.put("Authorization", "Bearer " + apiKey);
        }

        AiHttpClient.Response resp = AiHttpClient.postJson(endpoint, headers, body, null);
        if (!resp.isOk()) {
            throw AiHttpClient.httpError(resp.code, resp.body, getName());
        }
        return parse(resp.body);
    }

    /** Accepts ".../v1" (appends) or ".../v1/chat/completions" (used as-is). */
    private String buildEndpoint() {
        if (baseUrl.endsWith("/chat/completions")) return baseUrl;
        return baseUrl + "/chat/completions";
    }

    static String parse(String responseBody) throws AiException {
        try {
            JSONObject root = new JSONObject(responseBody);
            if (root.has("error")) {
                JSONObject err = root.optJSONObject("error");
                String msg = err == null ? root.optString("error") : err.optString("message", "API error");
                throw new AiException("API error: " + msg, "error=" + responseBody);
            }
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject first = choices.getJSONObject(0);
                JSONObject msgObj = first.optJSONObject("message");
                if (msgObj != null) {
                    String content = msgObj.optString("content", null);
                    if (content != null && content.trim().length() > 0) return content.trim();
                }
                // Some servers use the legacy "text" field
                String text = first.optString("text", null);
                if (text != null && text.trim().length() > 0) return text.trim();
            }
            throw new AiException("API returned an empty completion", "body=" + responseBody);
        } catch (AiException e) {
            throw e;
        } catch (Throwable t) {
            throw new AiException("Could not understand the API response", t);
        }
    }
}
