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

    /** Сообщений за вызов — то, что обещает описание инструмента. */
    static final int MAX_MESSAGES = 10;

    /**
     * Символов одного сообщения. Ответ ассистента со вставленным файлом бывает в сотни килобайт, а
     * за точным текстом приходят ради цитаты, не ради всего файла заново.
     */
    static final int MAX_MESSAGE_CHARS = 20_000;

    private final ChatMessageRepository chatMessageRepository;
    private final ContextItemService contextItemService;

    @Tool(name = "getOriginalMessages", description = """
            Retrieve full text of chat messages by their positions (e.g., [msg:5]). \
            Use when a summary references [msg:N] and you need the exact text. \
            Max 10 messages per call; a message over 20000 characters is cut.
            """, resultConverter = CompactToolResultConverter.class)
    public String getOriginalMessages(
            ToolContext context, @ToolParam(description = "Message positions to retrieve.") List<Long> positions) {
        requireNonEmpty(positions, "positions");
        if (positions.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("At most " + MAX_MESSAGES + " messages per call, got " + positions.size()
                    + " — split the positions into several calls.");
        }
        final String chatId = conversationId(context);
        log.debug("[{}] Fetching original messages positions: {}", chatId, positions);

        final List<String> lines =
                chatMessageRepository
                        .findChatMessagesByConversationIdAndPositionInOrderByCreatedAt(chatId, positions)
                        .stream()
                        .map(m -> "[msg:"
                                + m.getPosition()
                                + "] "
                                + m.getMessageType()
                                + ": <msg>\n"
                                // У ряда действия пользователя (git-команда,
                                // откат правок) текста нет вовсе: его содержимое —
                                // нотис, который собирается на чтении. Без этого
                                // «точный текст сообщения» возвращал бы пустоту
                                // там, где сводка сослалась на такой ряд.
                                + capped(eventTextOr(m))
                                // Приложенное к вопросу живёт в meta, а не в
                                // тексте: без этого «точный текст сообщения»
                                // молча терял бы упоминание вложения.
                                + contextItemService.render(chatId, m.getContextItems())
                                + "\n</msg>")
                        .collect(Collectors.toList());

        if (lines.isEmpty()) {
            return "No messages found for positions " + positions + " in this conversation.";
        }

        return String.join("\n", lines);
    }

    private static String capped(String text) {
        return text.length() <= MAX_MESSAGE_CHARS
                ? text
                : text.substring(0, MAX_MESSAGE_CHARS) + "\n... (message cut; total chars: " + text.length() + ")";
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
