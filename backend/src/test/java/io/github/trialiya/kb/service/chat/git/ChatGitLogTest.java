package io.github.trialiya.kb.service.chat.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Команда из чата пишет ряд в его историю и рассылает событие подписчикам — то же, что делает
 * сообщение в него. Поэтому и допуск у неё такой же, как у любого эндпоинта чата: чужой разговор
 * отвечает одинаково, из какого бы места в него ни постучали.
 */
class ChatGitLogTest {

    private static final String CONV = "conv-1";

    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);
    private final ChatEventService chatEvents = mock(ChatEventService.class);
    private final ChatTopicRepository chatTopicRepository = mock(ChatTopicRepository.class);
    private final ConversationSlots slots = mock(ConversationSlots.class);

    private final ChatGitLog log =
            new ChatGitLog(chatHistory, chatEvents, chatTopicRepository, slots);

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("anna", "x", List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Чужой чат — {@code 403}: id беседы утекает ссылками и логами, а сам по себе не разрешение.
     */
    @Test
    void aChatBelongingToSomebodyElseIsRefused() {
        givenChatOwnedBy("boris");

        assertThatThrownBy(() -> log.claimIdleAndOwned(CONV))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * Несуществующий чат — {@code 404}, а не молчаливое заведение: это опечатка в id, не сценарий.
     */
    @Test
    void anUnknownChatIsRefusedRatherThanCreated() {
        when(chatTopicRepository.findById(CONV)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> log.claimIdleAndOwned(CONV))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * Пока в чате идёт прогон, команда не выполняется: модель в этот момент читает и правит те же
     * файлы, и pull подменил бы содержимое между двумя её вызовами инструментов.
     */
    @Test
    void aChatWithARunInFlightIsRefused() {
        givenChatOwnedBy("anna");
        when(slots.claim(CONV))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "already generating"));

        assertThatThrownBy(() -> log.claimIdleAndOwned(CONV))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** Свободный свой чат пропускается — и с этого мгновения занят заявкой команды. */
    @Test
    void anOwnedIdleChatIsClaimedRatherThanMerelyChecked() {
        givenChatOwnedBy("anna");
        when(slots.claim(CONV)).thenReturn("claim-1");

        assertThat(log.claimIdleAndOwned(CONV)).isEqualTo("claim-1");
    }

    /**
     * Владельца проверяют до заявки: чужой чат не должен даже кратко становиться занятым — иначе
     * посторонний мог бы держать его заблокированным, ничего в нём не имея права делать.
     */
    @Test
    void aRefusedChatIsNeverClaimed() {
        givenChatOwnedBy("boris");

        assertThatThrownBy(() -> log.claimIdleAndOwned(CONV))
                .isInstanceOf(ResponseStatusException.class);

        verify(slots, never()).claim(anyString());
    }

    /** Заявку возвращают той же службе — иначе чат остался бы занятым навсегда. */
    @Test
    void theClaimIsHandedBack() {
        log.release(CONV, "claim-1");

        verify(slots).release(CONV, "claim-1");
    }

    /**
     * Упавшая запись не превращается в отказ команды: репозиторий к этому моменту уже сдвинулся, и
     * ошибка в ответ на успешный pull заставила бы панель нарисовать состояние, которого нет.
     */
    @Test
    void aFailedRecordDoesNotFailTheCommandThatAlreadyRan() {
        when(chatHistory.appendGitEvent(anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));

        log.record(CONV, "pull", "kb", true, "Fast-forward", "main");

        verify(chatEvents, never()).publish(anyString(), any(), any(), any(), any());
    }

    /**
     * Записанная команда рассылается вкладкам — парой к тесту выше: там событие не уходит потому,
     * что записи не случилось, а не потому, что рассылки нет вовсе. Дойдёт ли оно до вкладки,
     * решает уже {@code ChatEventService} (у чата без открытых вкладок и без прогона хаба нет, и
     * событие теряется) — здесь он мок, и проверять это надо там.
     */
    @Test
    void aRecordedCommandIsAnnouncedToTheTabs() {
        when(chatHistory.appendGitEvent(anyString(), any()))
                .thenReturn(
                        new ChatMessageEntity(
                                42L,
                                CONV,
                                "",
                                MessageType.USER,
                                3,
                                false,
                                false,
                                LocalDateTime.now(),
                                ChatMessageMeta.ofGitEvent(
                                        new GitEventMeta("pull", "kb", true, "", "main"))));

        log.record(CONV, "pull", "kb", true, "Fast-forward", "main");

        verify(chatEvents).publish(anyString(), any(), any(), any(), any());
    }

    private void givenChatOwnedBy(String user) {
        final ChatTopicEntity topic = mock(ChatTopicEntity.class);
        when(topic.getUser()).thenReturn(user);
        when(chatTopicRepository.findById(CONV)).thenReturn(Optional.of(topic));
    }
}
