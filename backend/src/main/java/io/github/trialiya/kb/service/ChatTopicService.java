package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.utils.ChatUtils.getUser;
import static org.springframework.http.HttpStatus.FORBIDDEN;

import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Заведение чата.
 *
 * <p>Отдельного «создать чат» нет ни в API, ни в UI: {@code conversationId} придумывает фронт, а
 * строка в {@code chat_topic} появляется от первого дела, которое в этом чате делают. Долгое время
 * таким делом было только сообщение, и создание жило внутри контроллера чатов. Теперь тем же первым
 * делом может оказаться вложение: файл прикладывают к ещё не начатому разговору, и вложению нужен
 * чат-владелец (внешний ключ {@code attachments.conversation_id}).
 */
@AllArgsConstructor
@Service
public class ChatTopicService {

    private final ChatTopicRepository chatTopicRepository;

    /**
     * Убеждается, что чат существует и принадлежит текущему пользователю.
     *
     * @return {@code true}, если чат уже был; {@code false} — если создан этим вызовом. Разница
     *     важна вызывающему: у только что созданной строки {@code updatedAt} и так свежий, трогать
     *     его нечем.
     * @throws ResponseStatusException 403, если чат принадлежит другому пользователю
     */
    public boolean ensureExists(String conversationId) {
        return chatTopicRepository
                .findById(conversationId)
                .map(
                        existing -> {
                            if (!existing.getUser().equals(getUser())) {
                                throw new ResponseStatusException(FORBIDDEN, "Forbidden");
                            }
                            return true;
                        })
                .orElseGet(
                        () -> {
                            chatTopicRepository.save(
                                    new ChatTopicEntity(
                                            conversationId,
                                            getUser(),
                                            null,
                                            null,
                                            null,
                                            null,
                                            // перезаписывается аудитом
                                            // @CreatedDate/@LastModifiedDate перед вставкой
                                            LocalDateTime.now(),
                                            LocalDateTime.now(),
                                            true));
                            return false;
                        });
    }
}
