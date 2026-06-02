package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Slf4j
@AllArgsConstructor
public class MessageLookupFunction {

    private final ChatMessageRepository chatMessageRepository;

    @Tool(
            name = "getOriginalMessages",
            description =
                    """
            Возвращает полный текст исходных сообщений чата по их позициям. \
            Используй когда summary ссылается на [msg:N] и нужен точный текст. \
            Не более 10 сообщений за вызов.
            """,
            resultConverter = CompactToolResultConverter.class)
    public String getOriginalMessages(
            ToolContext context,
            @ToolParam(description = "Message position to retrieve)") List<Long> positions) {

        final String chatId = conversationId(context);
        log.info("[{}] Fetching original messages positions: {}", chatId, positions);

        final List<String> lines =
                chatMessageRepository
                        .findChatMessagesByConversationIdAndPositionInOrderByCreatedAt(
                                chatId, positions)
                        .stream()
                        .map(
                                m ->
                                        "[msg:"
                                                + m.getPosition()
                                                + "] "
                                                + m.getMessageType()
                                                + ": <msg>\n"
                                                + m.getText()
                                                + "\n</msg>")
                        .collect(Collectors.toList());

        if (lines.isEmpty()) {
            return "No messages found for positions " + positions + " in this conversation.";
        }

        return String.join("\n", lines);
    }
}
