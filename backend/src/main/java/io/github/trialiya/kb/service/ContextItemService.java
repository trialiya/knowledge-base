package io.github.trialiya.kb.service;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.model.attachment.dto.AttachmentSummary;
import io.github.trialiya.kb.model.chat.dto.ContextItemRequest;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Контекст, приложенный пользователем к сообщению: проверка того, что прислал клиент, и опись для
 * модели.
 *
 * <p>Две вещи здесь сознательно не доверены фронту. Первая — принадлежность объекта чату: без
 * проверки любой id вложения из чужого чата стал бы способом прочитать его содержимое. Вторая —
 * подпись: она берётся с самого объекта, поэтому в истории остаётся имя файла, а не то, что клиент
 * решил про него написать.
 *
 * <p>Опись собирается при каждом чтении истории, а не один раз при записи. Поэтому переименованное
 * вложение попадает в промпт под новым именем, а удалённое просто исчезает из него — вместо вечного
 * обещания файла, которого больше нет. Плата за это — запрос на каждое построение промпта, то есть
 * на каждую итерацию tool-цикла, поэтому запрос ровно один и без колонки {@code content}.
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
        final List<ContextItemRequest> unique =
                List.copyOf(new LinkedHashSet<>(requested)); // порядок сохраняем, дубли снимаем
        unique.forEach(request -> kindOf(request.kind())); // ранний отказ на неизвестном виде

        final Map<Long, AttachmentSummary> attachments =
                attachmentsOf(conversationId, requestedAttachmentIds(unique));

        return unique.stream()
                .map(
                        request ->
                                switch (kindOf(request.kind())) {
                                    case ATTACHMENT -> {
                                        final AttachmentSummary found =
                                                attachments.get(attachmentId(request.ref()));
                                        if (found == null) {
                                            // Не существует или чужое — снаружи это один и тот же
                                            // ответ: существование чужих объектов не подтверждаем.
                                            throw new ResponseStatusException(
                                                    NOT_FOUND,
                                                    "Attachment not found: " + request.ref());
                                        }
                                        yield new ContextItem(
                                                ContextItemKind.ATTACHMENT,
                                                request.ref(),
                                                found.fileName());
                                    }
                                })
                .toList();
    }

    /**
     * Опись для модели: что именно приложено к этому сообщению и как это прочитать. Содержимое сюда
     * не разворачивается — файл может быть на мегабайт, и в истории он остался бы навсегда. Модель
     * читает его инструментом ровно тогда, когда оно понадобилось.
     *
     * @return приписка к тексту сообщения; пустая строка, если прикладывать оказалось нечего
     */
    public String render(String conversationId, List<ContextItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        final Map<Long, AttachmentSummary> attachments =
                attachmentsOf(conversationId, storedAttachmentIds(items));
        final List<String> lines =
                items.stream()
                        .map(item -> renderOne(item, attachments))
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

    private Optional<String> renderOne(ContextItem item, Map<Long, AttachmentSummary> attachments) {
        return switch (item.kind()) {
            case ATTACHMENT -> {
                final AttachmentSummary attachment =
                        attachments.get(attachmentIdOrNull(item.ref()));
                if (attachment == null) {
                    // Вложение удалили после отправки. Молчать честнее, чем звать модель читать
                    // то, чего нет: инструмент всё равно вернул бы ошибку.
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
                                + (attachment.outline() == null
                                        ? ""
                                        : " outline=\"" + attachment.outline() + "\"")
                                + (attachment.summary() == null
                                        ? ""
                                        : " summary=\"" + attachment.summary() + "\""));
            }
        };
    }

    /** Один запрос на всю опись: метаданные вложений чата по всем упомянутым id. */
    private Map<Long, AttachmentSummary> attachmentsOf(String conversationId, Set<Long> ids) {
        return attachmentService.findSummaries(conversationId, ids).stream()
                .collect(Collectors.toMap(AttachmentSummary::id, Function.identity()));
    }

    private Set<Long> requestedAttachmentIds(List<ContextItemRequest> requests) {
        return requests.stream()
                .filter(request -> kindOf(request.kind()) == ContextItemKind.ATTACHMENT)
                .map(request -> attachmentId(request.ref()))
                .collect(Collectors.toSet());
    }

    private static Set<Long> storedAttachmentIds(List<ContextItem> items) {
        return items.stream()
                .filter(item -> item.kind() == ContextItemKind.ATTACHMENT)
                .map(item -> attachmentIdOrNull(item.ref()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Ссылка на вложение — только число; всё остальное клиент прислал зря. */
    private static long attachmentId(String ref) {
        final Long id = attachmentIdOrNull(ref);
        if (id == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Not an attachment id: " + ref);
        }
        return id;
    }

    private static @Nullable Long attachmentIdOrNull(String ref) {
        try {
            return Long.parseLong(ref);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ContextItemKind kindOf(String raw) {
        for (ContextItemKind candidate : ContextItemKind.values()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(BAD_REQUEST, "Unknown context item kind: " + raw);
    }
}
