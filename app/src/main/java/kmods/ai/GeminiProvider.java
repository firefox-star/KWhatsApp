package kmods.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Provider for Google's Gemini API (generativelanguage.googleapis.com).
 * Free-tier friendly: works with a plain AI Studio API key.
 *
 * Expected user Base URL examples:
 *   https://generativelanguage.googleapis.com/v1beta        (recommended)
 *   https://generativelanguage.googleapis.com/v1
 *
 * Model examples: gemini-1.5-flash, gemini-2.0-flash, gemini-1.5-flash-8b
 * The API key is appended to the query string (Gemini's standard auth mode)
 * and is NEVER logged (see AiHttpClient.safeUrl).
 */
public class GeminiProvider implements AiProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int maxTokens;

    public GeminiProvider(String baseUrl, String model, String apiKey, int maxTokens) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
    }

    public static boolean matches(String baseUrl) {
        return baseUrl != null && baseUrl.contains("generativelanguage.googleapis.com");
    }

    @Override
    public String getName() {
        return "Google Gemini";
    }

    @Override
    public String complete(String systemPrompt, List<ChatMessage> history, String incoming) throws AiException {
        if (apiKey == null || apiKey.length() == 0) {
            throw new AiException("Gemini needs an API key - set it in AI Settings");
        }
        String version = baseUrl.contains("/v1beta") ? "v1beta" : "v1";
        String endpoint = "https://generativelanguage.googleapis.com/" + version
                + "/models/" + urlEncode(model) + ":generateContent?key=" + apiKey;

        JSONObject body = new JSONObject();
        try {
            JSONObject sys = new JSONObject();
            JSONArray sysParts = new JSONArray();
            JSONObject sysPart = new JSONObject();
            sysPart.put("text", systemPrompt);
            sysParts.put(sysPart);
            sys.put("parts", sysParts);
            body.put("systemInstruction", sys);

            JSONArray contents = new JSONArray();
            if (history != null) {
                for (int i = 0; i < history.size(); i++) {
                    ChatMessage m = history.get(i);
                    contents.put(contentBlock(m.role, m.content));
                }
            }
            contents.put(contentBlock("user", incoming));
            body.put("contents", contents);

            JSONObject gen = new JSONObject();
            gen.put("maxOutputTokens", maxTokens);
            gen.put("temperature", 0.7);
            body.put("generationConfig", gen);
        } catch (Throwable t) {
            throw new AiException("Failed to build request", t);
        }

        AiHttpClient.Response resp = AiHttpClient.postJson(endpoint, null, body, null);
        if (!resp.isOk()) {
            throw AiHttpClient.httpError(resp.code, resp.body, getName());
        }
        return parse(resp.body);
    }

    /** Gemini roles are "user" / "model" (our "assistant" maps to "model"). */
    private static JSONObject contentBlock(String role, String text) throws Throwable {
        JSONObject c = new JSONObject();
        c.put("role", "assistant".equals(role) ? "model" : "user");
        JSONArray parts = new JSONArray();
        JSONObject p = new JSONObject();
        p.put("text", text);
        parts.put(p);
        c.put("parts", parts);
        return c;
    }

    static String parse(String responseBody) throws AiException {
        try {
            JSONObject root = new JSONObject(responseBody);
            if (root.has("error")) {
                JSONObject err = root.optJSONObject("error");
                String msg = err == null ? "API error" : err.optString("message", "API error");
                throw new AiException("API error: " + msg, "error=" + responseBody);
            }
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject c0 = candidates.getJSONObject(0);
                JSONObject content = c0.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null && parts.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length(); i++) {
                            String t = parts.getJSONObject(i).optString("text", "");
                            if (t.length() > 0) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(t);
                            }
                        }
                        if (sb.length() > 0) return sb.toString().trim();
                    }
                }
            }
            throw new AiException("API returned an empty completion", "body=" + responseBody);
        } catch (AiException e) {
            throw e;
        } catch (Throwable t) {
            throw new AiException("Could not understand the API response", t);
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s == null ? "" : s;
        }
    }
}
