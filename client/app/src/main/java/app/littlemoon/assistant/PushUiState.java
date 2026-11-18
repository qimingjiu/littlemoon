package app.littlemoon.assistant;

/** Lets the push receiver avoid interrupting the conversation which is on screen. */
public final class PushUiState {
    private static volatile String visible;

    private PushUiState() {}

    public static void visibleConversation(String conversationId) {
        visible = conversationId;
    }

    static boolean isVisible(String conversationId) {
        return conversationId != null && conversationId.equals(visible);
    }
}
