package io.github.trialiya.kb.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
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
