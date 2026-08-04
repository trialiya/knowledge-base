package io.github.trialiya.kb.service;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.model.chat.dto.ContextItemRequest;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Контекст, приложенный пользователем к сообщению: проверка того, что прислал клиент, и рендер для
 * модели.
 *
 * <p>Две вещи здесь сознательно не доверены фронту. Первая — принадлежность объекта чату: без
 * проверки любой id вложения из чужого чата стал бы способом прочитать его содержимое. Вторая —
 * подпись: она берётся с самого объекта, поэтому в истории остаётся имя файла, а не то, что клиент
 * решил про него написать.
 *
 * <p>Рендер идёт при каждом чтении истории, а не один раз при записи. Поэтому переименованное
 * вложение попадает в промпт под новым именем, а удалённое просто исчезает из него — вместо вечного
 * обещания файла, которого больше нет.
 */
@Slf4j
@AllArgsConstructor
@Service
public class ContextItemService {

    /** Ограничение на число элементов в одном сообщении — защита от бесконечного промпта. */
    private static final int MAX_ITEMS = 20;

    private final AttachmentService attachmentService;

    /**
     * Проверяет присланные клиентом ссылки и превращает их в то, что уйдёт в {@code
     * chat_message.meta}. Всё, что не проходит проверку, — ошибка запроса, а не тихо выброшенный
     * элемент: пользователь видел чип и вправе ожидать, что модель увидит файл.
     */
    public List<ContextItem> resolve(
            String conversationId, @Nullable List<ContextItemRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (requested.size() > MAX_ITEMS) {
            throw new ResponseStatusException(
                    BAD_REQUEST, "Too many context items: " + requested.size());
        }
        return requested.stream()
                .distinct()
                .map(request -> resolveOne(conversationId, request))
                .toList();
    }

    private ContextItem resolveOne(String conversationId, ContextItemRequest request) {
        final ContextItemKind kind = kindOf(request.kind());
        return switch (kind) {
            case ATTACHMENT -> {
                final Attachment attachment = attachment(request.ref());
                if (!conversationId.equals(attachment.conversationId())) {
                    // Вложение чужого чата: 404, а не 403 — существование чужих объектов не
                    // подтверждаем.
                    throw new ResponseStatusException(
                            NOT_FOUND, "Attachment not found: " + request.ref());
                }
                yield new ContextItem(kind, request.ref(), attachment.fileName());
            }
        };
    }

    private Attachment attachment(String ref) {
        final long id;
        try {
            id = Long.parseLong(ref);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Not an attachment id: " + ref);
        }
        return attachmentService.getById(id);
    }

    private static ContextItemKind kindOf(String raw) {
        for (ContextItemKind candidate : ContextItemKind.values()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(BAD_REQUEST, "Unknown context item kind: " + raw);
    }

    /**
     * Блок для модели: что именно приложено к этому сообщению и как это прочитать. Содержимое сюда
     * не разворачивается — файл может быть на мегабайт, и в истории он остался бы навсегда. Модель
     * читает его инструментом ровно тогда, когда оно понадобилось.
     *
     * @return приписка к тексту сообщения; пустая строка, если прикладывать оказалось нечего
     */
    public String render(String conversationId, List<ContextItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        final List<String> lines =
                items.stream()
                        .map(item -> renderOne(conversationId, item))
                        .flatMap(Optional::stream)
                        .toList();
        if (lines.isEmpty()) {
            return "";
        }
        return "\n\n<attached-context>\nThe user attached the following to this message:\n"
                + String.join("\n", lines)
                + "\nUse getAttachmentContent(attachmentId) to read the full text of an attachment."
                + "\n</attached-context>";
    }

    private Optional<String> renderOne(String conversationId, ContextItem item) {
        return switch (item.kind()) {
            case ATTACHMENT -> {
                final Attachment attachment;
                try {
                    attachment = attachment(item.ref());
                } catch (RuntimeException e) {
                    // Вложение удалили после отправки. Молчать честнее, чем звать модель читать
                    // то, чего нет: инструмент всё равно вернул бы ошибку.
                    log.debug("[{}] context attachment {} is gone", conversationId, item.ref());
                    yield Optional.empty();
                }
                yield Optional.of(
                        "- attachment id="
                                + attachment.id()
                                + " name=\""
                                + attachment.fileName()
                                + "\" type="
                                + attachment.contentType()
                                + " size="
                                + attachment.fileSize()
                                + (attachment.summary() == null
                                        ? ""
                                        : " summary=\"" + attachment.summary() + "\""));
            }
        };
    }
}
