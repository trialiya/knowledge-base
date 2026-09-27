package io.github.trialiya.kb.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.trialiya.kb.model.chat.spring.AssistantChatMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

/**
 * Что адвайзер вообще доезжает до вызова — и в той цепочке, ради которой он в основном и нужен.
 *
 * <p>Проверка выглядит формальной, но закрывает поймавшийся молча промах. Цепочку замыкает
 * терминальный адвайзер самого обращения к модели, и стоит он на {@link
 * org.springframework.core.Ordered#LOWEST_PRECEDENCE}. Пока адвайзер логирования стоял там же,
 * ничью между ними решал порядок складывания, и в цепочке БЕЗ {@code ToolCallingAdvisor} — а это
 * ровно раунд {@code /compact}, который его отключает через {@code
 * TOOL_CALLING_ADVISOR_AUTO_REGISTER} — первым оказывался терминальный: запрос уходил модели, а
 * лога не было. Диагностика, которая молчит именно на том пути, ради которого её включают, хуже
 * отсутствующей: она отвечает «в запросе всё в порядке» на вопрос, который никто не задал.
 */
class MessageLoggingAdvisorTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(MessageLoggingAdvisor.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    /** Синхронный вызов без адвайзера tool-цикла — форма запроса раунда {@code /compact}. */
    @Test
    void logsACallWithTheToolLoopAdvisorTurnedOff() {
        ChatClient.builder(model())
                .defaultSystem("SYSTEM")
                .build()
                .prompt()
                .user("question")
                .advisors(
                        a ->
                                a.advisors(new MessageLoggingAdvisor())
                                        .param(
                                                ChatClientAttributes
                                                        .TOOL_CALLING_ADVISOR_AUTO_REGISTER
                                                        .getKey(),
                                                false))
                .call()
                .chatResponse();

        assertThat(messages()).singleElement().asString().contains("SYSTEM").contains("question");
    }

    /** Стрим с адвайзерами на клиенте — форма запроса обычного прогона. */
    @Test
    void logsAStreamedRunToo() {
        ChatClient.builder(model())
                .defaultSystem("SYSTEM")
                .defaultAdvisors(new MessageLoggingAdvisor())
                .build()
                .prompt()
                .user("question")
                .stream()
                .chatResponse()
                .blockLast();

        assertThat(messages()).singleElement().asString().contains("question");
    }

    /**
     * То, ради чего лог и написан: у двух запросов с общим началом хэши префикса совпадают до
     * первого разошедшегося сообщения и расходятся на нём. Сравнение двух таких логов — один проход
     * сверху до первой несовпавшей строки, и это место, с которого провайдер перестал засчитывать
     * кэш.
     */
    @Test
    void thePrefixHashMatchesUpToTheFirstDifferentMessage() {
        ask("tail one");
        ask("tail two");

        final List<String> first = lines(0);
        final List<String> second = lines(1);
        // Заголовок, tools, params, SYSTEM и общее сообщение: начало у запросов одно.
        assertThat(first.subList(0, 5)).isEqualTo(second.subList(0, 5));
        // Последнее сообщение разное — и дальше префикс уже не сойдётся.
        assertThat(hashOf(first.get(5))).isNotEqualTo(hashOf(second.get(5)));
    }

    /**
     * Настройки запроса в текст промпта не входят, но кэш провайдера сорвать могут: у двух запросов
     * с одним текстом и разной моделью {@code params} расходится, а префикс — нет.
     */
    @Test
    void paramsAreHashedApartFromThePrefix() {
        askWith(OpenAiChatOptions.builder().model("deepseek-v4-flash"));
        askWith(OpenAiChatOptions.builder().model("deepseek-v4-pro"));

        final String first = paramsLine(0);
        final String second = paramsLine(1);
        assertThat(first).contains("model=deepseek-v4-flash");
        assertThat(paramsHashOf(first)).isNotEqualTo(paramsHashOf(second));
        assertThat(lines(0).get(3)).isEqualTo(lines(1).get(3)); // SYSTEM: текст тот же
    }

    /**
     * Настройки доставки в хэш не идут: прогон всегда просит счётчик токенов в стриме, раунд сжатия
     * — никогда, а кэш провайдера они делят.
     */
    @Test
    void streamOptionsDoNotTouchTheParamsHash() {
        askWith(OpenAiChatOptions.builder().model("m").streamUsage(true));
        askWith(OpenAiChatOptions.builder().model("m"));

        assertThat(paramsHashOf(paramsLine(0))).isEqualTo(paramsHashOf(paramsLine(1)));
    }

    /** Хэш отдельной схемы — только на TRACE: на DEBUG строка tools одна на весь набор. */
    @Test
    void eachToolGetsItsOwnHashOnTraceOnly() {
        askWithTool();
        assertThat(messages().getFirst()).doesNotContain("echoTool ");

        logger.setLevel(Level.TRACE);
        askWithTool();
        assertThat(lines(1))
                .anySatisfy(line -> assertThat(line).contains("echoTool").contains("hash "));
    }

    /**
     * Вес протокольной части. В {@code getText()} её нет вовсе, а весит она у длинного хода больше
     * самих реплик — без этих чисел лог показывал бы пустую TOOL-строку там, где уехали десятки
     * килобайт результата.
     */
    @Test
    void toolCallsAndResponsesAreWeighedToo() {
        final String arguments = "{\"path\":\"" + "a".repeat(40) + "\"}";
        final String result = "r".repeat(5000);
        ChatClient.builder(model())
                .defaultAdvisors(new MessageLoggingAdvisor())
                .build()
                .prompt()
                .messages(
                        new AssistantMessage(
                                "смотрю",
                                Map.of(),
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "c1", "function", "grep", arguments)),
                                List.of()) {},
                        new ToolResponseMessage(
                                List.<ToolResponseMessage.ToolResponse>of(
                                        new ToolResponseMessage.ToolResponse("c1", "grep", result)),
                                Map.of()) {})
                .call()
                .chatResponse();

        final List<String> lines = lines(0);
        assertThat(lines.get(3))
                .contains("ASSISTANT")
                .contains("grep(c1, " + arguments.length() + " chars)")
                .contains(String.valueOf("смотрю".length() + arguments.length()) + " chars");
        assertThat(lines.get(4))
                .contains("TOOL")
                .contains("grep(c1, " + result.length() + " chars)");
    }

    /**
     * Рассуждение, возвращаемое модели, входит в префикс: у одного и того же ответа с разным
     * рассуждением хэш обязан разойтись — иначе лог молчал бы ровно в месте обрыва кэша.
     */
    @Test
    void replayedReasoningIsPartOfThePrefix() {
        askWithReasoning("Смотрю лог.");
        askWithReasoning("Смотрю другой лог.");

        final String first = lines(0).get(4);
        assertThat(first).contains("ASSISTANT").contains("reasoning=11 chars");
        assertThat(hashOf(first)).isNotEqualTo(hashOf(lines(1).get(4)));
    }

    private void askWithReasoning(String reasoning) {
        ChatClient.builder(model())
                .defaultAdvisors(new MessageLoggingAdvisor())
                .build()
                .prompt()
                .messages(
                        new UserMessage("q"),
                        AssistantMessage.builder()
                                .content("ответ")
                                .properties(
                                        Map.of(AssistantChatMessage.REASONING_CONTENT, reasoning))
                                .build())
                .user("ещё")
                .call()
                .chatResponse();
    }

    /** Длинный текст в лог не уезжает — только начало и длина. */
    @Test
    void aLongMessageIsPreviewedNotDumped() {
        ask("x".repeat(10_000));

        final String line = lines(0).get(5);
        assertThat(line)
                .contains("10000 chars")
                .contains("x".repeat(MessageLoggingAdvisor.PREVIEW));
        assertThat(line).doesNotContain("x".repeat(MessageLoggingAdvisor.PREVIEW + 1));
    }

    private void ask(String question) {
        ChatClient.builder(model())
                .defaultSystem("SYSTEM")
                .defaultAdvisors(new MessageLoggingAdvisor())
                .build()
                .prompt()
                .messages(new UserMessage("shared"))
                .user(question)
                .call()
                .chatResponse();
    }

    private void askWith(OpenAiChatOptions.Builder options) {
        ChatClient.builder(model())
                .defaultSystem("SYSTEM")
                .defaultAdvisors(new MessageLoggingAdvisor())
                .build()
                .prompt()
                .options(options)
                .user("question")
                .call()
                .chatResponse();
    }

    private void askWithTool() {
        ChatClient.builder(model())
                .defaultAdvisors(
                        a ->
                                a.advisors(new MessageLoggingAdvisor())
                                        .param(
                                                ChatClientAttributes
                                                        .TOOL_CALLING_ADVISOR_AUTO_REGISTER
                                                        .getKey(),
                                                false))
                .build()
                .prompt()
                .toolCallbacks(ToolCallbacks.from(new EchoTool()))
                .user("question")
                .call()
                .chatResponse();
    }

    /** Инструмент ради схемы: вызываться он не будет. */
    static class EchoTool {
        @Tool(name = "echoTool", description = "Echoes the text back")
        public String echo(String text) {
            return text;
        }
    }

    private String paramsLine(int event) {
        return lines(event).stream()
                .filter(l -> l.startsWith("  params"))
                .findFirst()
                .orElseThrow();
    }

    private static String paramsHashOf(String line) {
        final int at = line.indexOf("hash ") + "hash ".length();
        return line.substring(at, at + 8);
    }

    /** Строки одной записи лога: {@code tools}, сообщения, итог. */
    private List<String> lines(int event) {
        return List.of(messages().get(event).split("\n"));
    }

    /** Хэш префикса из строки лога — восемь шестнадцатеричных цифр после «prefix ». */
    private static String hashOf(String line) {
        final int at = line.indexOf("prefix ") + "prefix ".length();
        return line.substring(at, at + 8);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Модель-заглушка: адвайзер только наблюдает, ответ ему безразличен. */
    private static ChatModel model() {
        final ChatModel model = mock(ChatModel.class);
        final ChatResponse response =
                new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(response);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        return model;
    }
}
