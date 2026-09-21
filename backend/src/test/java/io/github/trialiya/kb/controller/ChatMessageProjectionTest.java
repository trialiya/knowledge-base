package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.dto.ChatMessage;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.chat.entity.ScriptEventMeta;
import io.github.trialiya.kb.model.script.ScriptStats;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Ряды, которые оставило действие пользователя, доезжают до клиента из истории — а не только живым
 * событием.
 *
 * <p>Проверяется именно это, потому что сломать легко и не видно: у такого ряда пустой текст, и
 * стоит ему не попасть в проекцию или в фильтр пустых, как карточка живёт до первой перезагрузки
 * страницы, а после неё превращается в пустой пузырь. Поля тут перечислены руками, и компилятор об
 * этом не напомнит.
 */
class ChatMessageProjectionTest {

    @Test
    void aScriptRunRowSurvivesTheProjectionLikeAGitCommandRow() throws Exception {
        final ScriptEventMeta event =
                new ScriptEventMeta(
                        "locale-diff",
                        "frontend/scripts/locale-diff.js",
                        "kb",
                        true,
                        "3 ключа",
                        null,
                        "сверено 12 файлов",
                        List.of("frontend/src/i18n/ru/chat.json"),
                        new ScriptStats(12, 2048, 30, 1, 420));

        final ChatMessage message = project(row(ChatMessageMeta.ofScriptEvent(event)));

        assertThat(message.scriptEvent()).isEqualTo(event);
        assertThat(message.content()).isEmpty();
        assertThat(isEventRow(row(ChatMessageMeta.ofScriptEvent(event)))).isTrue();
    }

    /** Соседний ряд того же сорта — чтобы тест ловил обрыв в любой из двух веток фильтра. */
    @Test
    void aGitCommandRowStillSurvivesIt() throws Exception {
        final GitEventMeta event = new GitEventMeta("pull", "kb", true, "Fast-forward", "main");

        assertThat(project(row(ChatMessageMeta.ofGitEvent(event))).gitEvent()).isEqualTo(event);
        assertThat(isEventRow(row(ChatMessageMeta.ofGitEvent(event)))).isTrue();
    }

    /** Обычный вопрос рядом события не является — иначе фильтр пустых потерял бы смысл. */
    @Test
    void anOrdinaryQuestionIsNotAnEventRow() throws Exception {
        assertThat(
                        isEventRow(
                                new ChatMessageEntity(
                                        1L,
                                        "conv-1",
                                        "почини сборку",
                                        MessageType.USER,
                                        1,
                                        false,
                                        false,
                                        LocalDateTime.now(),
                                        null,
                                        null)))
                .isFalse();
    }

    // Оба метода приватные и статические не случайно: они деталь контроллера, а не его API.
    // Тест зовёт их рефлексией, чтобы не расширять видимость ради проверки.

    private static ChatMessage project(ChatMessageEntity entity) throws Exception {
        final Method method =
                ChatController.class.getDeclaredMethod(
                        "toChatMessage", ChatMessageEntity.class, List.class);
        method.setAccessible(true);
        return (ChatMessage) method.invoke(null, entity, List.of());
    }

    private static boolean isEventRow(ChatMessageEntity entity) throws Exception {
        final Method method =
                ChatController.class.getDeclaredMethod("isEventRow", ChatMessageEntity.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, entity);
    }

    private static ChatMessageEntity row(ChatMessageMeta meta) {
        return new ChatMessageEntity(
                7L,
                "conv-1",
                "",
                MessageType.USER,
                3,
                false,
                false,
                LocalDateTime.now(),
                meta,
                null);
    }
}
