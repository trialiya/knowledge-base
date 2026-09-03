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
import io.github.trialiya.kb.config.model.SystemPromptProperties;
import io.github.trialiya.kb.functions.TopicFunction;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.run.PendingMessageService;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import io.github.trialiya.kb.service.chat.skill.SkillService;
import io.github.trialiya.kb.tools.ChatToolset;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Запрос {@code /compact} обязан начинаться ровно тем же, чем начинается обычный запрос чата:
 * провайдер отдаёт по ставке кэша совпадающее начало и считает его от первого байта, а сжатие — тот
 * самый вызов, который везёт весь контекст целиком (см. {@link CompactService}). Разойдись начало
 * хоть на одном инструменте, самая дорогая операция чата платила бы за весь контекст по полной
 * ставке — и заметить это по логам было бы нечем: запрос уходит и отвечается как обычно.
 *
 * <p>Поэтому сравниваются не объекты промпта, а тела HTTP-запросов: у обоих путей на этом тесте
 * настоящий {@link OpenAiChatModel}, направленный на локальную заглушку эндпоинта. Так в сравнение
 * попадает и сериализация — JSON-схемы инструментов, форма протокольных сообщений, поле {@code
 * model}, — то есть ровно то, что видит провайдер, а не то, из чего мы это собирали.
 *
 * <p>Настоящие здесь и оба клиента: чатовый собирает {@code ChatConfig#chatClient} (со своей
 * цепочкой адвайзеров и памятью), сжатие — сам {@link CompactService}. Копии сборки в тесте не
 * доказывали бы ничего: разойтись могут именно они.
 */
class CompactPromptCacheTest {

    private static final String CONV = "conv-1";

    /**
     * Проект и режим чата: у обоих запросов они обязаны быть одни — это подстановки {@code sys.md}.
     */
    private static final String PROJECT = "kb";

    private static final String MODE = "Отвечай кратко.";

    /** Ответ заглушки на обычный вызов — им кончается раунд сжатия. */
    private static final String COMPLETION =
            """
            {"id":"c1","object":"chat.completion","created":1,"model":"stub-model",\
            "choices":[{"index":0,"finish_reason":"stop",\
            "message":{"role":"assistant","content":"## Overview\\ncompacted"}}]}""";

    /** Ответ заглушки на стрим — им кончается прогон чата. */
    private static final String STREAM =
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"stub-model\",\"choices\":[{\"index\":0,"
                    + "\"finish_reason\":\"stop\",\"delta\":{\"role\":\"assistant\","
                    + "\"content\":\"answer\"}}]}\n\n"
                    + "data: [DONE]\n\n";

    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final ObjectMapper json = new ObjectMapper();

    private HttpServer endpoint;
    private OpenAiChatModel chatModel;
    private SystemPromptService systemPrompts;
    private ChatToolset toolset;

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
        chatModel =
                ChatModelRegistry.buildDefaultModel(
                        common,
                        new OpenAiChatProperties(),
                        ToolCallingManager.builder().build(),
                        absent(),
                        absent(),
                        empty());
        systemPrompts = systemPrompts();
        // Настоящий инструмент, а не заглушка: в тела запросов уезжает его JSON-схема, и сравнение
        // должно ловить расхождение именно в ней.
        toolset =
                new ChatToolset(
                        Stream.of(ToolCallbacks.from(new TopicFunction(chatTopicRepository())))
                                .<ToolCallback>map(RecordingToolCallback::new)
                                .toList(),
                        List.of());
    }

    @AfterEach
    void stopEndpoint() {
        endpoint.stop(0);
    }

    /**
     * Прогон чата и раунд сжатия по одному и тому же окну: у запросов обязаны совпасть системное
     * сообщение, набор инструментов, модель и вся история — разойтись им позволено ровно на
     * последнем сообщении, том самом, которое просит сжать.
     */
    @Test
    void theCompactionRequestRepeatsTheChatRequestUpToItsLastMessage() {
        final List<PromptRow> window = turns(2);
        // Вопрос пользователя у прогона и команда /compact у сжатия стоят на одном месте — в
        // конце. Историю до него оба берут одну и ту же.
        final List<Message> chatHistory =
                Stream.concat(
                                window.stream().map(PromptRow::toMessage),
                                Stream.of(new UserMessage("question 2")))
                        .toList();

        chatRun(chatHistory);
        compactRound(window);

        final JsonNode chat = body(0);
        final JsonNode compact = body(1);
        final JsonNode chatMessages = chat.get("messages");
        final JsonNode compactMessages = compact.get("messages");
        // Сначала — что сравнивать вообще есть что: равенство двух отсутствующих полей выполнялось
        // бы и на пустых запросах, и тест молчал бы ровно в том случае, ради которого написан.
        assertThat(chat.get("model").asText()).isNotBlank();
        assertThat(chat.get("tools").size()).isEqualTo(toolset.all().length);
        // Системное сообщение, окно и вопрос в конце.
        assertThat(chatMessages.size()).isEqualTo(window.size() + 2);
        assertThat(chatMessages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(chatMessages.get(0).get("content").asText()).contains(MODE).contains("SKILLS");

        // Полями запросы расходятся только там, где расхождение к кэшу отношения не имеет: прогон
        // читает ответ стримом, сжатие обычным вызовом. Сравнение именно списком, а не полем за
        // полем: параметр, добавленный в один из двух запросов и забытый во втором, — это тот же
        // сдвиг начала, и увидеть его надо здесь, а не по счёту за месяц.
        assertThat(differingFields(chat, compact))
                .containsExactlyInAnyOrder("messages", "stream", "stream_options");

        assertThat(compact.get("model")).isEqualTo(chat.get("model"));
        assertThat(compact.get("tools")).isEqualTo(chat.get("tools"));
        assertThat(compactMessages.size()).isEqualTo(chatMessages.size());
        assertThat(allButLast(compactMessages)).isEqualTo(allButLast(chatMessages));
        // И последним сообщением сжатие всё-таки отличается — иначе сравнение выше проходило бы
        // и на двух одинаковых запросах, которых в природе не бывает.
        assertThat(last(compactMessages)).isNotEqualTo(last(chatMessages));
        assertThat(last(compactMessages).get("content").asText()).contains("COMPACTOR HANDBOOK");
    }

    /**
     * Прогон чата — тем же клиентом и тем же запросом, каким его собирает {@code ChatRunService}.
     */
    private void chatRun(List<Message> history) {
        final ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(CONV)).thenReturn(history);
        final ChatClient client =
                new ChatConfig()
                        .chatClient(
                                chatModel,
                                memory,
                                new ClassPathResource("prompt/sys.md"),
                                ToolCallingManager.builder().build(),
                                toolset,
                                mock(ChatEventService.class),
                                mock(PendingMessageService.class),
                                mock(RunRegistry.class));
        client
                .prompt()
                .system(sp -> sp.params(systemPrompts.placeholders(false, PROJECT, MODE)))
                // Своего .user(...) у прогона нет: вопрос уже в истории, его подмешивает advisor
                // памяти (см. ChatRunService).
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONV))
                .options(OpenAiChatOptions.builder().streamUsage(true))
                .stream()
                .chatResponse()
                .blockLast();
    }

    /** Раунд сжатия — настоящим сервисом: запрос собирает он сам. */
    private void compactRound(List<PromptRow> window) {
        final ChatMessageRepository messages = mock(ChatMessageRepository.class);
        when(messages.save(any())).thenAnswer(call -> call.getArgument(0));
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        final ChatModelRegistry models = mock(ChatModelRegistry.class);
        when(models.forModel(any())).thenReturn(chatModel);

        final ChatMessageEntity command = row(6, MessageType.USER, "/compact", null).entity();
        final CompactService service =
                new CompactService(
                        models,
                        mock(ChatHistoryService.class),
                        chatTopicRepository(),
                        messages,
                        new SummaryWriter(messages, transactions),
                        mock(PendingSummaryService.class),
                        mock(ConversationSlots.class),
                        mock(ChatEventService.class),
                        systemPrompts,
                        toolset,
                        new ClassPathResource("prompt/sys.md"),
                        new ByteArrayResource("COMPACTOR HANDBOOK".getBytes(UTF_8)),
                        Runnable::run,
                        transactions);
        service.compact(
                CONV,
                window,
                new CompactService.CompactTarget(
                        CompactMeta.Kind.COMPACT,
                        command.getPosition(),
                        LocalDateTime.now(),
                        (call, usage) -> null),
                null,
                new CompactService.CompactOptions(null, false, PROJECT, MODE));
    }

    // -------------------------------------------------------------------------
    // Заглушка эндпоинта
    // -------------------------------------------------------------------------

    private void respond(HttpExchange exchange) throws IOException {
        final String request = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        requests.add(request);
        final boolean streaming = request.contains("\"stream\":true");
        final byte[] response = (streaming ? STREAM : COMPLETION).getBytes(UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private JsonNode body(int index) {
        assertThat(requests).hasSizeGreaterThan(index);
        try {
            return json.readTree(requests.get(index));
        } catch (IOException e) {
            throw new AssertionError("The endpoint stub got a body that is not JSON", e);
        }
    }

    /**
     * Имена полей верхнего уровня, значения которых у двух тел разошлись (или есть лишь у одного).
     */
    private static List<String> differingFields(JsonNode left, JsonNode right) {
        final Set<String> names = new TreeSet<>();
        left.fieldNames().forEachRemaining(names::add);
        right.fieldNames().forEachRemaining(names::add);
        return names.stream()
                .filter(name -> !Objects.equals(left.get(name), right.get(name)))
                .toList();
    }

    private static List<JsonNode> allButLast(JsonNode messages) {
        final List<JsonNode> head = new ArrayList<>();
        messages.forEach(head::add);
        return head.subList(0, head.size() - 1);
    }

    private static JsonNode last(JsonNode messages) {
        return messages.get(messages.size() - 1);
    }

    // -------------------------------------------------------------------------
    // Окружение обоих путей
    // -------------------------------------------------------------------------

    /**
     * Настоящий {@link SystemPromptService}: подстановки {@code sys.md} — единственное место, где
     * системные сообщения двух запросов могут разойтись, и собирать их в тесте своими руками
     * значило бы проверять эту копию, а не то, что делают оба вызывающих.
     */
    private static SystemPromptService systemPrompts() {
        final ScriptGuideService scripts = mock(ScriptGuideService.class);
        when(scripts.instructions(false, PROJECT)).thenReturn("SCRIPTS");
        final SkillService skills = mock(SkillService.class);
        when(skills.catalogue(PROJECT)).thenReturn("SKILLS");
        return new SystemPromptService(new SystemPromptProperties(null, null), scripts, skills);
    }

    private static ChatTopicRepository chatTopicRepository() {
        final ChatTopicRepository repository = mock(ChatTopicRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        return repository;
    }

    /**
     * Ходы по три позиции: вопрос, ответ модели с вызовом инструмента и протокольная TOOL-строка с
     * его результатом. Вызов здесь не для полноты картины: сжатие возит окно теми же протокольными
     * сообщениями, какими его возит чат, и разойтись они могут именно на них.
     */
    private static List<PromptRow> turns(int count) {
        final List<PromptRow> rows = new ArrayList<>();
        for (int turn = 0; turn < count; turn++) {
            final String callId = "call-" + turn;
            rows.add(row(turn * 3, MessageType.USER, "question " + turn, null));
            rows.add(
                    row(
                            turn * 3 + 1,
                            MessageType.ASSISTANT,
                            "answer " + turn,
                            new ToolData(
                                    List.of(
                                            new ToolData.Call(
                                                    callId, "function", "getChatTopic", "{}")),
                                    null)));
            rows.add(
                    row(
                            turn * 3 + 2,
                            MessageType.TOOL,
                            "",
                            new ToolData(
                                    null,
                                    List.of(
                                            new ToolData.Response(
                                                    callId,
                                                    "getChatTopic",
                                                    "{\"topic\":\"topic " + turn + "\"}")))));
        }
        return rows;
    }

    private static PromptRow row(
            long position, MessageType type, String content, @Nullable ToolData toolData) {
        return new PromptRow(
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        content,
                        type,
                        position,
                        false,
                        false,
                        LocalDateTime.now(),
                        null,
                        toolData),
                content);
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
