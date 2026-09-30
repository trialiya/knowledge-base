package io.github.trialiya.kb.service.chat.memory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.trialiya.kb.config.ChatConfig;
import io.github.trialiya.kb.config.ChatModelRegistry;
import io.github.trialiya.kb.model.chat.spring.AssistantChatMessage;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.run.PendingMessageService;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.tools.ChatToolset;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

/**
 * Рассуждение модели от стрима до следующего запроса — на настоящем {@link OpenAiChatModel},
 * направленном на заглушку эндпоинта, и настоящей цепочке чата ({@link ChatConfig}).
 *
 * <p>Заглушкой здесь нельзя заменить ни модель, ни агрегатор: весь вопрос в том, в какой форме
 * Spring AI отдаёт рассуждение в чанках стрима и что из этого остаётся в собранном ответе. В 2.0.1
 * каждый чанк несёт нарастающий итог обращения, и собранный ответ получает его целиком — а
 * рассуждение, склеенное из чанков ещё раз, повторялось бы в истории много раз. Если Spring AI
 * поменяет форму, этот тест упадёт первым.
 */
class ReasoningStreamTest {

    private static final String CONV = "conv-reasoning";

    /** Первое обращение: рассуждение в двух чанках и вызов инструмента. */
    private static final String TOOL_CALL_STREAM =
            chunk("c1", "{\"role\":\"assistant\",\"reasoning_content\":\"Сначала \"}", null)
                    + chunk("c1", "{\"reasoning_content\":\"посмотрю время.\"}", null)
                    + chunk(
                            "c1",
                            "{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                                    + "\"function\":{\"name\":\"echo\","
                                    + "\"arguments\":\"{\\\"text\\\":\\\"hi\\\"}\"}}]}",
                            null)
                    + chunk("c1", "{}", "tool_calls")
                    + "data: [DONE]\n\n";

    /** Второе обращение, после ответа инструмента: рассуждение и текст в нескольких чанках. */
    private static final String ANSWER_STREAM =
            chunk("c2", "{\"role\":\"assistant\",\"reasoning_content\":\"Готово.\"}", null)
                    + chunk("c2", "{\"content\":\"Отв\"}", null)
                    + chunk("c2", "{\"content\":\"ет.\"}", null)
                    + chunk("c2", "{}", "stop")
                    + "data: [DONE]\n\n";

    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Message> history = Collections.synchronizedList(new ArrayList<>());
    private HttpServer endpoint;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void startEndpoint() throws IOException {
        endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", this::respond);
        endpoint.start();
        final OpenAiCommonProperties common = new OpenAiCommonProperties();
        common.setBaseUrl("http://127.0.0.1:" + endpoint.getAddress().getPort());
        common.setApiKey("sk-stub");
        common.setTimeout(Duration.ofSeconds(30));
        common.setMaxRetries(0);
        chatModel = ChatModelRegistry.buildDefaultModel(
                common, new OpenAiChatProperties(), ToolCallingManager.builder().build(), absent(), absent(), empty());
    }

    @AfterEach
    void stopEndpoint() {
        endpoint.stop(0);
    }

    @Test
    void eachCallStoresItsReasoningOnceAndGetsItBack() throws IOException {
        history.add(new UserMessage("Который час?"));

        client()
                .prompt()
                .system("SYS")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .options(OpenAiChatOptions.builder().streamUsage(true))
                .stream()
                .chatResponse()
                .blockLast();

        // В память легли оба ответа модели — каждый со своим рассуждением, ровно один раз.
        final List<AssistantMessage> answers = history.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .toList();
        assertThat(answers)
                .extracting(m -> m.getMetadata().get(AssistantChatMessage.REASONING_CONTENT))
                .containsExactly("Сначала посмотрю время.", "Готово.");
        assertThat(answers.getLast().getText()).isEqualTo("Ответ.");

        // Второй запрос вернул модели рассуждение её ответа с вызовом — как reasoning_content.
        assertThat(requests).hasSize(2);
        final JsonNode toolCallAnswer = Stream.of(
                        new ObjectMapper().readTree(requests.get(1)).get("messages"))
                .flatMap(messages ->
                        Stream.iterate(0, i -> i + 1).limit(messages.size()).map(messages::get))
                .filter(m -> m.has("tool_calls"))
                .findFirst()
                .orElseThrow();
        assertThat(toolCallAnswer.get("reasoning_content").asText()).isEqualTo("Сначала посмотрю время.");
    }

    private org.springframework.ai.chat.client.ChatClient client() {
        final ChatMemory memory = new ChatMemory() {
            @Override
            public void add(String conversationId, List<Message> messages) {
                history.addAll(messages);
            }

            @Override
            public List<Message> get(String conversationId) {
                return List.copyOf(history);
            }

            @Override
            public void clear(String conversationId) {
                history.clear();
            }
        };
        return new ChatConfig()
                .chatClient(
                        chatModel,
                        memory,
                        new ClassPathResource("prompt/sys.md"),
                        ToolCallingManager.builder().build(),
                        new ChatToolset(List.of(ToolCallbacks.from(new EchoTool())), List.of()),
                        mock(ChatEventService.class),
                        mock(PendingMessageService.class),
                        mock(RunRegistry.class));
    }

    /** Инструмент, который модель зовёт в первом обращении. */
    static class EchoTool {
        @Tool(name = "echo", description = "Echoes the text back")
        public String echo(String text) {
            return text;
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        final String request = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        requests.add(request);
        final byte[] response =
                (request.contains("\"role\":\"tool\"") ? ANSWER_STREAM : TOOL_CALL_STREAM).getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private static String chunk(String id, String delta, String finishReason) {
        return "data: {\"id\":\""
                + id
                + "\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":"
                + delta
                + ",\"finish_reason\":"
                + (finishReason == null ? "null" : "\"" + finishReason + "\"")
                + "}]}\n\n";
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        final ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(provider.getIfAvailable(any(Supplier.class)))
                .thenAnswer(call -> ((Supplier<T>) call.getArgument(0)).get());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty() {
        final ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call -> Stream.empty());
        return provider;
    }
}
