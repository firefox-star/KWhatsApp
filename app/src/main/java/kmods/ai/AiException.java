package kmods.ai;

/**
 * Provider-level failure carrying a USER-FACING message plus the technical
 * detail that goes to the debug log.
 */
public class AiException extends Exception {

    /** Short, friendly message suitable for a Toast / reply-bar error chip. */
    public final String userMessage;

    public AiException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    public AiException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public AiException(String userMessage, String technicalDetail) {
        super(technicalDetail);
        this.userMessage = userMessage;
    }
}
