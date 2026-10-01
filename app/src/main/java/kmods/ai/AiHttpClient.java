package kmods.ai;

import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Minimal HTTP client used by all AI providers.
 *
 * - Built on HttpURLConnection: no extra dependencies, so the mod-merge
 *   workflow stays simple (no extra dex files to inject).
 * - HTTPS enforced by {@link AiConfig#validateBaseUrl} before every call;
 *   TLS defaults of the platform are used (no custom TrustManager, so
 *   certificate validation is NOT weakened).
 * - Gzip/deflate handled transparently by the platform.
 * - Every request/response is (optionally) logged via {@link AiLogger}.
 */
public final class AiHttpClient {

    public static class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean isOk() {
            return code >= 200 && code < 300;
        }
    }

    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 45000;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** User agent helps some API gateways; also makes traffic identifiable in the user's own logs. */
    private static final String USER_AGENT =
            "KWhatsApp-AI/" + kmods.Utils.modVersion() + " (Android " + Build.VERSION.RELEASE + ")";

    private AiHttpClient() {
    }

    /**
     * POSTs a JSON document and returns the parsed response.
     *
     * @param url     absolute https:// URL (validated by caller)
     * @param headers extra headers (e.g. Authorization)
     * @param jsonBody request payload
     * @param ctx     context for debug logging (may be null)
     */
    public static Response postJson(String url, Map<String, String> headers, JSONObject jsonBody, android.content.Context ctx)
            throws AiException {
        AiLogger.d(ctx, "HTTP POST " + safeUrl(url));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            if (conn instanceof HttpsURLConnection) {
                // Keep default TLS verification (strict). Explicitly no trust-all.
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (headers != null) {
                Iterator<Map.Entry<String, String>> it = headers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, String> h = it.next();
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            byte[] payload = jsonBody.toString().getBytes(UTF8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                closeQuietly(os);
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(is);
            AiLogger.d(ctx, "HTTP " + code + " (" + body.length() + " bytes)");
            return new Response(code, body);
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            AiLogger.e(ctx, "HTTP POST failed: " + safeUrl(url) + " :: " + reason, e);
            throw new AiException("Network error - check your internet connection and the API URL", e);
        } catch (Throwable t) {
            AiLogger.e(ctx, "HTTP POST unexpected error: " + safeUrl(url), t);
            throw new AiException("Unexpected network error", t);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Converts HTTP error codes into friendly user messages shared by all providers. */
    public static AiException httpError(int code, String body, String providerName) {
        String snippet = body == null ? "" : body.trim();
        if (snippet.length() > 300) snippet = snippet.substring(0, 300);
        switch (code) {
            case 401:
            case 403:
                return new AiException(providerName + " rejected the API key (HTTP " + code + ")",
                        "HTTP " + code + " body=" + snippet);
            case 404:
                return new AiException("API URL or model not found (HTTP 404) - check Base URL and model name",
                        "HTTP 404 body=" + snippet);
            case 429:
                return new AiException(providerName + " rate limit reached (HTTP 429) - try again later",
                        "HTTP 429 body=" + snippet);
            default:
                return new AiException(providerName + " error (HTTP " + code + ")",
                        "HTTP " + code + " body=" + snippet);
        }
    }

    /** Strips query strings (which may contain keys, e.g. Gemini ?key=...) before logging. */
    public static String safeUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?<hidden>" : url;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, UTF8));
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > 1_000_000) break; // hard cap - never trust huge error bodies
            }
        } finally {
            closeQuietly(is);
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) {
        }
    }
}
