package kmods.ai;

/**
 * One chat turn used to build the AI request context.
 */
public class ChatMessage {
    /** OpenAI-style role: "user" (incoming / from the other side) or "assistant" (sent by me). */
    public final String role;
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content == null ? "" : content;
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
