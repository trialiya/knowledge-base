package io.github.trialiya.kb.service.chat.memory;

import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

/**
 * Реализация {@link ChatMemory} поверх {@link ChatHistoryService} — тот самый шов, через который
 * Spring AI видит историю чата (см. {@code MessageChatMemoryAdvisor} в {@code ChatConfig}).
 *
 * <p>Своя, а не {@code MessageWindowChatMemory} поверх {@code ChatMemoryRepository}, ровно по двум
 * причинам.
 *
 * <ul>
 *   <li><b>Окна на записи здесь нет.</b> Сколько истории уедет модели, решает {@code
 *       SummarizeService} — сжатые ряды выпадают из {@link ChatHistoryService#promptRows}. Оконная
 *       память резала бы список ещё и на записи, по собственному счётчику сообщений, который к
 *       этому решению никакого отношения не имеет.
 *   <li><b>{@link #add} ничего не читает.</b> Advisor памяти зовёт его дважды за итерацию
 *       tool-цикла (новое user/tool-сообщение в {@code before}, ответ модели в {@code after}), и
 *       оконной памяти на каждый такой вызов нужна вся история целиком — чтобы склеить её с новыми
 *       сообщениями и отдать хранилищу обратно. Для этого хранилища это чистая трата: запись строго
 *       дописывающая, и позицию нового ряда {@link ChatHistoryService#append} узнаёт одним запросом
 *       за максимумом. Так на итерацию остаётся одно чтение окна — то, что действительно уходит в
 *       промпт.
 * </ul>
 */
@AllArgsConstructor
@Service
public class ChatHistoryMemory implements ChatMemory {

    private final ChatHistoryService history;

    @Override
    public List<Message> get(String conversationId) {
        return history.promptMessages(conversationId);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        history.append(conversationId, messages);
    }

    @Override
    public void clear(String conversationId) {
        history.delete(conversationId);
    }
}
