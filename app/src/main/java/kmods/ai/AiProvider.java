package kmods.ai;

import java.util.List;

/**
 * Modular AI provider abstraction - different AI APIs (OpenAI-compatible,
 * Google Gemini, future providers) plug in here.
 *
 * A provider only has to be able to turn
 *     (system instructions, chat history, latest incoming message)
 * into a plain-text completion. Everything else (settings UI, history
 * collection, suggestion parsing, reply bar UI) is provider-independent.
 *
 * To add a new provider:
 *   1. implement this interface,
 *   2. register it in {@link Providers#resolve}.
 */
public interface AiProvider {

    /** Human-readable name shown in logs ("OpenAI-compatible", "Google Gemini", ...). */
    String getName();

    /**
     * Blocking call - MUST be executed on a background thread.
     *
     * @param systemPrompt  instructions for the model (persona + output format)
     * @param history       recent chat turns, oldest first, already role-tagged
     * @param incoming      the latest incoming message to reply to
     * @return raw model completion text (never null; may be single or multi-line)
     * @throws AiException on any failure - carries a user-facing message
     */
    String complete(String systemPrompt, List<ChatMessage> history, String incoming) throws AiException;
}
