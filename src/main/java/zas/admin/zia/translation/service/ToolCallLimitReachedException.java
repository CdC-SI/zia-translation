package zas.admin.zia.translation.service;

/**
 * The model kept calling a tool past its cap instead of answering. Spring AI then ends the tool loop with a
 * placeholder answer (the limit message or the last tool result), which must not be used as the translation.
 */
public class ToolCallLimitReachedException extends RuntimeException {

    public ToolCallLimitReachedException(String message) {
        super(message);
    }
}
