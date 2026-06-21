package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallRepository;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Проверка «работы с моделями» без обращения к реальному LLM.
 *
 * <p>Идея: собрать настоящий {@link ChatClient} ровно так, как это делает {@code ChatConfig}
 * (advisor памяти поверх модели), но подменить сам {@link ChatModel} моком. Память при этом
 * НАСТОЯЩАЯ — {@link ChatMemoryService} поверх PostgreSQL из Testcontainers. Это позволяет
 * проверить именно то, что обычно ломается в интеграции с моделью, не тратя токены и не завися от
 * сети:
 *
 * <ul>
 *   <li>выбранная модель ({@link OpenAiChatOptions#getModel()}) реально доходит до слоя модели —
 *       это контракт переключения модели в чате;
 *   <li>пользовательское сообщение попадает в промпт (advisor истории работает);
 *   <li>ответ ассистента персистится в память чата и затем доступен для перезагрузки диалога.
 * </ul>
 *
 * <p>Так можно расширять покрытие: например, мок может вернуть {@code ChatResponse} с tool-call и
 * проверить раунд-трип инструментов; здесь оставлен базовый happy-path.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonConfig.class, JdbcConfig.class, PgVectorJdbcConfig.class})
class ChatModelClientIT extends AbstractPostgresIntegrationTest {

    @Autowired private ChatTopicRepository topicRepo;
    @Autowired private ChatMessageRepository messageRepo;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void selectedModelReachesModelLayerAndReplyIsPersisted() {
        String conversationId = UUID.randomUUID().toString();

        // ── настоящая память поверх Postgres ────────────────────────────────
        ChatMemoryService memoryService =
                new ChatMemoryService(
                        topicRepo, messageRepo, mock(ToolCallRepository.class), objectMapper);
        ChatMemory chatMemory =
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(memoryService)
                        .maxMessages(50)
                        .build();

        // ── модель-заглушка ────────────────────────────────────────────────
        ChatModel chatModel = mock(ChatModel.class);
        // ChatClient.builder копирует defaultOptions модели — не отдаём null
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("Привет, человек!")))));

        ChatClient chatClient =
                ChatClient.builder(chatModel)
                        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .build();

        // ── вызов с явным выбором модели ────────────────────────────────────
        String reply =
                chatClient
                        .prompt()
                        .user("Привет, модель")
                        .options(OpenAiChatOptions.builder().model("gpt-test"))
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();

        assertThat(reply).isEqualTo("Привет, человек!");

        // выбранная модель и пользовательский текст реально дошли до слоя модели
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt sent = promptCaptor.getValue();
        assertThat(sent.getOptions().getModel()).isEqualTo("gpt-test");
        assertThat(sent.getInstructions())
                .anyMatch(m -> m.getText() != null && m.getText().contains("Привет, модель"));

        // ответ ассистента сохранён в памяти чата и доступен при перезагрузке диалога
        List<Message> history = memoryService.findByConversationId(conversationId);
        assertThat(history).extracting(Message::getText).contains("Привет, человек!");
    }
}
