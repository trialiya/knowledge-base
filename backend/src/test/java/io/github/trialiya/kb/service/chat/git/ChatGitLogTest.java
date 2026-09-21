package io.github.trialiya.kb.service.chat.git;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * След, который git-команда оставляет в чате: ряд в истории и событие подписчикам — то же, что
 * делает сообщение в него. Допуск к чату (свой ли он и не занят ли) проверяется у общего владельца
 * этого правила, {@code ChatActionClaimTest}.
 */
class ChatGitLogTest {

    private static final String CONV = "conv-1";

    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);
    private final ChatEventService chatEvents = mock(ChatEventService.class);
    private final ChatGitLog log = new ChatGitLog(chatHistory, chatEvents);

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("anna", "x", List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

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
}
