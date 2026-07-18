package io.github.trialiya.kb.advisor;

import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Логирует точный список сообщений, уходящих в модель на каждой итерации tool-calling цикла — то
 * есть ровно то, что реально попадает в тело запроса к LLM, после {@link
 * org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor} (подстановка истории) и
 * перед самим вызовом модели. Диагностический advisor — не меняет запрос/ответ.
 *
 * <p>Включается логгером {@code io.github.trialiya.kb.advisor.MessageLoggingAdvisor} на уровне
 * {@code DEBUG} (по умолчанию выключен, см. {@code application.yaml}).
 */
@Slf4j
public class MessageLoggingAdvisor implements StreamAdvisor {

    @Override
    public String getName() {
        return "messageLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        // Максимально близко к модели: после ToolPreparingAdvisor уже нечего менять, порядок
        // между двумя advisor-ами с одинаковым LOWEST_PRECEDENCE не важен — оба observation-only.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        if (log.isDebugEnabled()) {
            final String conversationId =
                    String.valueOf(request.context().getOrDefault(ChatMemory.CONVERSATION_ID, "?"));
            final String runId =
                    String.valueOf(
                            request.context().getOrDefault(ToolPreparingAdvisor.RUN_ID_PARAM, "?"));
            log.debug(
                    "LLM request conversationId={} runId={} messages=[\n{}\n]",
                    conversationId,
                    runId,
                    describe(request.prompt().getInstructions()));
        }
        return chain.nextStream(request);
    }

    private static String describe(List<Message> messages) {
        return messages.stream()
                .map(MessageLoggingAdvisor::describe)
                .collect(Collectors.joining("\n"));
    }

    private static String describe(Message message) {
        final String text = message.getText();
        final String textPreview =
                text == null
                        ? "null"
                        : "\"" + text.replace("\n", "\\n") + "\" (" + text.length() + " chars)";
        final StringBuilder sb =
                new StringBuilder("  ")
                        .append(message.getMessageType())
                        .append(": ")
                        .append(textPreview);
        if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            sb.append(" toolCalls=")
                    .append(
                            am.getToolCalls().stream()
                                    .map(tc -> tc.name() + "(" + tc.id() + ")")
                                    .collect(Collectors.joining(", ")));
        }
        if (message instanceof ToolResponseMessage trm) {
            sb.append(" toolResponses=")
                    .append(
                            trm.getResponses().stream()
                                    .map(r -> r.name() + "(" + r.id() + ")")
                                    .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }
}
