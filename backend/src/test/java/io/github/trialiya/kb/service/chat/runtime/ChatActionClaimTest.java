package io.github.trialiya.kb.service.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Допуск к чату для действия человека — git-команды, отката правок, команды {@code /script}. Все
 * трое пишут ряд в историю и рассылают событие подписчикам, то есть делают с разговором ровно то,
 * что делает сообщение в него; поэтому и правило у них одно, и проверяется оно здесь один раз.
 */
class ChatActionClaimTest {

    private static final String CONV = "conv-1";

    private final ChatTopicRepository chatTopicRepository = mock(ChatTopicRepository.class);
    private final ConversationSlots slots = mock(ConversationSlots.class);

    private final ChatActionClaim claim = new ChatActionClaim(chatTopicRepository, slots);

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

        assertThatThrownBy(() -> claim.claimIdleAndOwned(CONV))
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

        assertThatThrownBy(() -> claim.claimIdleAndOwned(CONV))
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

        assertThatThrownBy(() -> claim.claimIdleAndOwned(CONV))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** Свободный свой чат пропускается — и с этого мгновения занят заявкой команды. */
    @Test
    void anOwnedIdleChatIsClaimedRatherThanMerelyChecked() {
        givenChatOwnedBy("anna");
        when(slots.claim(CONV)).thenReturn("claim-1");

        assertThat(claim.claimIdleAndOwned(CONV)).isEqualTo("claim-1");
    }

    /**
     * Владельца проверяют до заявки: чужой чат не должен даже кратко становиться занятым — иначе
     * посторонний мог бы держать его заблокированным, ничего в нём не имея права делать.
     */
    @Test
    void aRefusedChatIsNeverClaimed() {
        givenChatOwnedBy("boris");

        assertThatThrownBy(() -> claim.claimIdleAndOwned(CONV))
                .isInstanceOf(ResponseStatusException.class);

        verify(slots, never()).claim(anyString());
    }

    /** Заявку возвращают той же службе — иначе чат остался бы занятым навсегда. */
    @Test
    void theClaimIsHandedBack() {
        claim.release(CONV, "claim-1");

        verify(slots).release(CONV, "claim-1");
    }

    /**
     * Упавшая запись не превращается в отказ команды: репозиторий к этому моменту уже сдвинулся, и
     * ошибка в ответ на успешный pull заставила бы панель нарисовать состояние, которого нет.
     */
    private void givenChatOwnedBy(String user) {
        final ChatTopicEntity topic = mock(ChatTopicEntity.class);
        when(topic.getUser()).thenReturn(user);
        when(chatTopicRepository.findById(CONV)).thenReturn(Optional.of(topic));
    }
}
