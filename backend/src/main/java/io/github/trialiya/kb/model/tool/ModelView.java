package io.github.trialiya.kb.model.tool;

/**
 * A tool result whose text for the model is not the result itself: the model gets {@link
 * #forModel()}, while the whole result is kept for the call's detail view.
 *
 * <p>{@code CompactToolResultConverter} serialises the view into the protocol response — the text
 * the model reads now and on every later request, since the chat history replays exactly that —
 * and hands the whole result's JSON to {@code RecordingToolCallback}. From the run's collector it
 * goes to a table of its own, {@code tool_call_full_result} ({@code ToolCallService#keepFullResults}),
 * never into {@code chat_message.tool_data}: nothing that builds a prompt can reach it.
 */
public interface ModelView {

    /** What the model is shown instead of this result; serialised the same way the result would be. */
    Object forModel();
}
