package io.github.trialiya.kb.advisor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Обмер запроса, уходящего в модель, — после {@link
 * org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor} (подстановка истории) и
 * перед самим вызовом. Диагностический advisor: запрос и ответ он не меняет.
 *
 * <p>Включается логгером {@code io.github.trialiya.kb.advisor.MessageLoggingAdvisor} на уровне
 * {@code DEBUG} (по умолчанию выключен, см. {@code application.yaml}).
 *
 * <p>Умеет и стрим, и синхронный вызов — не про запас: чат стримит, а раунд {@code /compact}
 * ({@code CompactService}) ходит к модели синхронно, и написан этот advisor ровно ради того, чтобы
 * два таких запроса можно было положить рядом. Сжатие обязано попасть в кэш промпта чата, а это
 * вопрос «с какого места начала запросы разошлись».
 *
 * <p><b>Отвечает на него накопительный хэш.</b> У каждой строки лога стоит {@code prefix} — CRC32
 * всего, что уехало модели до конца этой строки включительно, начиная со схем инструментов. Кэш у
 * провайдера префиксный, поэтому сравнение двух запросов сводится к одному проходу сверху: первая
 * строка, где {@code prefix} разошёлся, и есть место, с которого кэш перестал засчитываться.
 * Разошедшийся {@code tools} значит, что до истории дело и не дошло.
 *
 * <p><b>Целиком тексты не печатаются.</b> Одно окно чата — это десятки тысяч токенов, и дамп такого
 * лога нечитаем и дорог. Вместо него у строки есть длина в символах и первые {@value #PREVIEW}
 * символов текста: длины хватает, чтобы увидеть, ЧТО изменилось, а хэша — чтобы увидеть, ГДЕ.
 *
 * <p><b>Вес инструментов виден отдельно</b>, и это не педантизм: у длинного хода протокольная часть
 * весит на порядок больше самих реплик, а в {@code getText()} её нет вовсе — ни аргументов вызова,
 * ни результата. Строка ASSISTANT показывает вес аргументов своих вызовов, строка TOOL — вес
 * ответов, и итог по запросу считает их наравне с текстом.
 */
@Slf4j
public class MessageLoggingAdvisor implements StreamAdvisor, CallAdvisor {

    /** Сколько символов текста показывать — на опознание строки хватает. */
    static final int PREVIEW = 100;

    @Override
    public String getName() {
        return "messageLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        // Максимально близко к модели, но НА ШАГ раньше неё. Цепочку замыкает адвайзер самого
        // вызова, и стоит он на LOWEST_PRECEDENCE: встань мы туда же, ничью решал бы порядок
        // складывания, и в цепочке без ToolCallingAdvisor — а это ровно раунд /compact, который
        // его отключает, — терминальный адвайзер оказывался бы первым и логировать было бы уже
        // некому. Порядок с TokenUsageAdvisor не важен, оба только наблюдают.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        return chain.nextStream(request);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);
        return chain.nextCall(request);
    }

    private static void logRequest(ChatClientRequest request) {
        log.atDebug()
                .setMessage("LLM request conversationId={} runId={}\n{}")
                .addArgument(() -> request.context().getOrDefault(ChatMemory.CONVERSATION_ID, "?"))
                .addArgument(() -> request.context().getOrDefault(AdvisorParams.RUN_ID_PARAM, "?"))
                .addArgument(() -> describe(request))
                .log();
    }

    /**
     * Тело лога: строка про инструменты, затем по строке на сообщение, затем итог. Хэш — один на
     * весь обход, и в этом весь смысл: каждая строка печатает его текущее значение, то есть хэш
     * всего префикса запроса до неё включительно.
     */
    private static String describe(ChatClientRequest request) {
        final Prefix prefix = new Prefix();
        final StringBuilder out = new StringBuilder();
        long chars = tools(out, prefix, toolCallbacks(request));

        final List<Message> messages = request.prompt().getInstructions();
        for (int i = 0; i < messages.size(); i++) {
            chars += message(out, prefix, i + 1, messages.get(i));
        }
        return out.append(String.format("  total %3d messages %8d chars", messages.size(), chars))
                .toString();
    }

    /**
     * Схемы инструментов — начало кэшируемого префикса, поэтому и хэш начинается с них. Считаются
     * так же, как уедут: имя, описание и JSON-схема аргументов каждого.
     *
     * @return вес блока в символах
     */
    private static long tools(StringBuilder out, Prefix prefix, List<ToolCallback> callbacks) {
        long chars = 0;
        for (ToolCallback callback : callbacks) {
            chars +=
                    prefix.add(callback.getToolDefinition().name())
                            + prefix.add(callback.getToolDefinition().description())
                            + prefix.add(callback.getToolDefinition().inputSchema());
        }
        out.append(String.format("  tools %3d schemas %8d chars", callbacks.size(), chars))
                .append(", prefix ")
                .append(prefix)
                .append('\n');
        return chars;
    }

    /** Одно сообщение: номер, роль, вес, хэш префикса и начало текста. */
    private static long message(StringBuilder out, Prefix prefix, int number, Message message) {
        final String text = message.getText() == null ? "" : message.getText();
        // Роль идёт в хэш, но не в вес: она у сообщения не полезная нагрузка, а «чей это ряд», и
        // прибавка постоянной длины к каждой строке только мешала бы сравнивать веса глазами.
        prefix.add(message.getMessageType().getValue());
        final long textChars = prefix.add(text);
        final Protocol protocol = protocol(prefix, message);
        final long chars = textChars + protocol.chars();
        // Веса выровнены в колонку: два запроса кладут рядом и читают глазами.
        out.append(String.format("  %3d %-9s %8d chars", number, message.getMessageType(), chars))
                .append(protocol.rendered())
                .append(", prefix ")
                .append(prefix)
                .append("  ")
                .append(preview(text))
                .append('\n');
        return chars;
    }

    /** Протокольная часть сообщения: как её показать и сколько она весит. */
    private record Protocol(String rendered, long chars) {

        private static final Protocol NONE = new Protocol("", 0);
    }

    /**
     * Вызовы инструментов у ASSISTANT, ответы у TOOL: имена с {@code callId} и вес полезной
     * нагрузки. У остальных сообщений её нет.
     *
     * <p>В хэш она идёт здесь же: аргументы вызова и его результат — такая же часть префикса, как
     * текст, и расхождение в них обнуляет кэш ровно так же.
     */
    private static Protocol protocol(Prefix prefix, Message message) {
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            final StringBuilder out = new StringBuilder(" calls=");
            long chars = 0;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                prefix.add(call.id());
                prefix.add(call.name());
                final long payload = prefix.add(call.arguments());
                chars += payload;
                out.append(part(call.name(), call.id(), payload));
            }
            return new Protocol(out.toString().stripTrailing(), chars);
        }
        if (message instanceof ToolResponseMessage responses) {
            final StringBuilder out = new StringBuilder(" responses=");
            long chars = 0;
            for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
                prefix.add(response.id());
                prefix.add(response.name());
                final long payload = prefix.add(response.responseData());
                chars += payload;
                out.append(part(response.name(), response.id(), payload));
            }
            return new Protocol(out.toString().stripTrailing(), chars);
        }
        return Protocol.NONE;
    }

    private static String part(String name, String callId, long chars) {
        return name + "(" + callId + ", " + chars + " chars) ";
    }

    /** Схемы, которые уедут модели; пусто — запрос без инструментов (или опции не те). */
    private static List<ToolCallback> toolCallbacks(ChatClientRequest request) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) {
            return List.of();
        }
        final @Nullable List<ToolCallback> callbacks = options.getToolCallbacks();
        return callbacks == null ? List.of() : callbacks;
    }

    /** Начало текста одной строкой; перевод строки экранируется, чтобы не разорвать запись лога. */
    private static String preview(String text) {
        final String head = text.length() <= PREVIEW ? text : text.substring(0, PREVIEW) + "…";
        return '"' + head.replace("\n", "\\n") + '"';
    }

    /**
     * Накопительный хэш префикса: всё, что скормлено, в порядке скармливания. CRC32, а не
     * криптографический дайджест, — задача не «не подделать», а «увидеть, что байты разошлись», и
     * четырёх байт на глаз хватает при десятках строк.
     */
    private static final class Prefix {

        private final CRC32 crc = new CRC32();

        /**
         * Добавляет кусок в хэш.
         *
         * @return его длина в символах — считать её отдельным проходом незачем
         */
        private long add(@Nullable String part) {
            if (part == null) {
                return 0;
            }
            crc.update(part.getBytes(StandardCharsets.UTF_8));
            return part.length();
        }

        @Override
        public String toString() {
            return String.format("%08x", crc.getValue());
        }
    }
}
