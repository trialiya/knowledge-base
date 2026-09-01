package io.github.trialiya.kb.convert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.model.chat.entity.FileRevertMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

public final class ChatMessageMetaToJsonConverter {

    private ChatMessageMetaToJsonConverter() {}

    /**
     * Схема колонки {@code chat_message.meta}. Проекция, а не сам {@link ChatMessageMeta}: чтение
     * обязано переживать и записи прошлых версий, и записи будущих ({@code ignoreUnknown}), а поля
     * с приведением (kind контекстного элемента, {@code null} в {@code toolCalls}) разбираются
     * здесь, а не в доменной записи.
     *
     * <p><b>Новое поле {@link ChatMessageMeta} само сюда не попадёт.</b> Список полей тут явный, и
     * в обе стороны: не дописав его здесь, получишь запись, которая пишется и читается как {@code
     * null}, — молча, потому что компилятор об этом ничего не скажет.
     *
     * <p>Незаполненные поля в JSON не пишутся: у большинства рядов заполнено два-три поля из
     * десяти, а колонка хранится в каждом сообщении каждого чата. Чтение от этого не страдает —
     * отсутствующее поле Jackson отдаёт как {@code null}, то есть ровно тем, чем оно было записано,
     * а пустые списки нормализует {@link ChatMessageMeta} своим компактным конструктором. Аннотация
     * стоит на проекции, а не на {@link ObjectMapper}: он общий с REST, и ответы API форму менять
     * не должны.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MetaJson(
            @Nullable String runId,
            @Nullable Boolean toolCalls,
            List<ToolInvocationMeta> invocations,
            @Nullable List<ContextItemJson> contextItems,
            @Nullable String project,
            @Nullable String projectSwitchFrom,
            @Nullable String model,
            @Nullable CompactMeta compact,
            @Nullable GitEventMeta gitEvent,
            @Nullable Boolean interjection,
            @Nullable RunTokenUsage usage,
            @Nullable List<ProjectSpan> visitedProjects,
            @Nullable FileRevertMeta fileRevert) {}

    /**
     * {@code kind} читается строкой, а не сразу {@link ContextItemKind}: вид, которого эта версия
     * ещё (или уже) не знает, обязан просто выпасть из списка. Иначе откат приложения после
     * появления нового вида превращался бы в отказ читать историю целиком — на каждом открытии
     * чата, где такой элемент записан.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContextItemJson(
            @Nullable String kind,
            @Nullable String ref,
            @Nullable String label,
            @Nullable Map<String, Object> payload) {

        Optional<ContextItem> toItem() {
            if (kind == null || ref == null) {
                return Optional.empty();
            }
            final Map<String, Object> safePayload = payload == null ? Map.of() : payload;
            return kindOf(kind).map(k -> new ContextItem(k, ref, label, safePayload));
        }

        private static Optional<ContextItemKind> kindOf(String raw) {
            for (ContextItemKind candidate : ContextItemKind.values()) {
                if (candidate.name().equals(raw)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    private static List<ContextItem> contextItemsOf(@Nullable List<ContextItemJson> raw) {
        return raw == null
                ? List.of()
                : raw.stream().map(ContextItemJson::toItem).flatMap(Optional::stream).toList();
    }

    @ReadingConverter
    public static class Reader implements Converter<String, ChatMessageMeta> {

        private static final TypeReference<List<ToolInvocationMeta>> LIST_TYPE =
                new TypeReference<>() {};

        private final ObjectMapper objectMapper;

        public Reader(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ChatMessageMeta convert(String source) {
            try {
                String trimmed = source.trim();
                if (trimmed.startsWith("[")) {
                    // Legacy format: bare array — это всегда «крошки» вызовов инструментов.
                    return new ChatMessageMeta(objectMapper.readValue(trimmed, LIST_TYPE));
                }
                // New format: {"runId":"...","toolCalls":true,"invocations":[...]}. Поле toolCalls
                // проставлено и в новых записях, и в старых (см. миграцию backfill).
                MetaJson json = objectMapper.readValue(trimmed, MetaJson.class);
                return new ChatMessageMeta(
                        json.runId(),
                        Boolean.TRUE.equals(json.toolCalls()),
                        json.invocations(),
                        contextItemsOf(json.contextItems()),
                        json.project(),
                        json.projectSwitchFrom(),
                        json.model(),
                        json.compact(),
                        json.gitEvent(),
                        Boolean.TRUE.equals(json.interjection()),
                        json.usage(),
                        json.visitedProjects() == null ? List.of() : json.visitedProjects(),
                        json.fileRevert());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to deserialize chat message meta", e);
            }
        }
    }

    @WritingConverter
    public static class Writer implements Converter<ChatMessageMeta, String> {

        private final ObjectMapper objectMapper;

        public Writer(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String convert(ChatMessageMeta source) {
            try {
                return objectMapper.writeValueAsString(
                        new MetaJson(
                                source.runId(),
                                source.toolCalls(),
                                source.invocations(),
                                source.contextItems().stream()
                                        .map(
                                                i ->
                                                        new ContextItemJson(
                                                                i.kind().name(),
                                                                i.ref(),
                                                                i.label(),
                                                                i.payload()))
                                        .toList(),
                                source.project(),
                                source.projectSwitchFrom(),
                                source.model(),
                                source.compact(),
                                source.gitEvent(),
                                // false не выписывается: флаг несут единицы рядов, а колонка —
                                // каждый ряд каждого чата (см. javadoc проекции).
                                source.interjection() ? Boolean.TRUE : null,
                                source.usage(),
                                // Пустой список — не выписывается по той же причине, что и false
                                // выше: спаны несут только строки-сводки, а колонка есть у каждого
                                // ряда каждого чата.
                                source.visitedProjects().isEmpty()
                                        ? null
                                        : source.visitedProjects(),
                                source.fileRevert()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize chat message meta", e);
            }
        }
    }
}
