package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.requireNonEmpty;
import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.PromptNotices;
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
    private final ContextItemService contextItemService;

    @Tool(
            name = "getOriginalMessages",
            description =
                    """
            Retrieve full text of chat messages by their positions (e.g., [msg:5]). \
            Use when a summary references [msg:N] and you need the exact text. \
            Max 10 messages per call.
            """,
            resultConverter = CompactToolResultConverter.class)
    public String getOriginalMessages(
            ToolContext context,
            @ToolParam(description = "Message positions to retrieve.") List<Long> positions) {
        requireNonEmpty(positions, "positions");
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
                                                // У ряда действия пользователя (git-команда,
                                                // откат правок) текста нет вовсе: его содержимое —
                                                // нотис, который собирается на чтении. Без этого
                                                // «точный текст сообщения» возвращал бы пустоту
                                                // там, где сводка сослалась на такой ряд.
                                                + eventTextOr(m)
                                                // Приложенное к вопросу живёт в meta, а не в
                                                // тексте: без этого «точный текст сообщения»
                                                // молча терял бы упоминание вложения.
                                                + contextItemService.render(
                                                        chatId, m.getContextItems())
                                                + "\n</msg>")
                        .collect(Collectors.toList());

        if (lines.isEmpty()) {
            return "No messages found for positions " + positions + " in this conversation.";
        }

        return String.join("\n", lines);
    }

    /**
     * Текст ряда: собранный на чтении нотис у ряда действия пользователя, собственный текст у
     * остальных.
     */
    private static String eventTextOr(ChatMessageEntity message) {
        final String notice = PromptNotices.eventNotice(message.getMeta());
        return notice.isEmpty() ? message.getText() : notice;
    }
}
