package io.github.trialiya.kb.service.chat.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.git.dto.TextEdit;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Что нужно сделать с рабочим деревом, чтобы файловых правок одного ответа в нём не осталось:
 * созданные файлы удалить, отредактированные — вернуть теми же заменами наоборот.
 *
 * <p>Собирается из рядов ответа и ничего больше не знает: ни про git, ни про чат. Отсюда и
 * отдельный класс — правило «что считается откатываемым» проверяется юнит-тестом на голых рядах.
 *
 * <p>Источник аргументов — протокольные {@code tool_data} ряда, а не «крошки» вызовов для UI: у
 * первых аргументы лежат целиком (их читает модель), у вторых {@code oldString} мог бы оказаться
 * усечённым, и откат записал бы в файл обрезок. Поэтому же откат ничего не хранит про запас: {@code
 * editFile} обратим ровно тем, что уже записано в истории.
 *
 * @param deletions пути файлов, созданных ответом
 * @param edits обратные замены по файлам, в порядке применения (правки ответа, развёрнутые
 *     наоборот); файл из {@link #deletions} сюда не попадает — его не правят, а удаляют
 */
record FileRevertPlan(Set<String> deletions, Map<String, List<TextEdit>> edits) {

    /** Инструменты, правку которых откат умеет отменять. */
    private static final String CREATE = "createFile";

    private static final String EDIT = "editFile";

    /** Правка скрипта: обратимых аргументов у неё нет — весь блок становится неоткатываемым. */
    private static final String SCRIPT = "runScript";

    /**
     * Аргументы читаются собственным маппером, а не {@code RecordingToolCallback.parseToolInput}:
     * тот режет значения до сотни символов для плашек, и {@code oldString} из него вернул бы в файл
     * обрезок вместо прежнего текста.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

    boolean isEmpty() {
        return deletions.isEmpty() && edits.isEmpty();
    }

    /** Все затронутые пути в порядке, в котором ответ их трогал. */
    List<String> paths() {
        final Set<String> all = new LinkedHashSet<>(edits.keySet());
        all.addAll(deletions);
        return List.copyOf(all);
    }

    /**
     * Разбирает ряды одного ответа в план отката.
     *
     * @throws FileRevertRefusedException если в ответе есть правка, которую откат отменить не
     *     умеет: пачка {@code runScript} или вызов, записанный версией без {@code callId} (по нему
     *     ищутся аргументы, и без него откат не знает, что именно менялось)
     */
    static FileRevertPlan of(List<ChatMessageEntity> answerRows) {
        final Map<String, List<TextEdit>> edits = new LinkedHashMap<>();
        final Set<String> deletions = new LinkedHashSet<>();
        for (ChatMessageEntity row : answerRows) {
            final Map<String, String> arguments = argumentsByCallId(row.getToolData());
            for (ToolInvocationMeta call : row.getInvocations()) {
                collect(call, arguments, edits, deletions);
            }
        }
        // Правки в файле, который этим же ответом и создан, откатывать нечем и незачем: файл
        // уходит целиком.
        edits.keySet().removeAll(deletions);
        return new FileRevertPlan(
                Collections.unmodifiableSet(deletions),
                Collections.unmodifiableMap(reverse(edits)));
    }

    private static void collect(
            ToolInvocationMeta call,
            Map<String, String> arguments,
            Map<String, List<TextEdit>> edits,
            Set<String> deletions) {
        if (SCRIPT.equals(call.name()) && changedFiles(call)) {
            throw new FileRevertRefusedException(
                    "The answer changed files with runScript — those edits can only be undone with"
                            + " git.");
        }
        if (!CREATE.equals(call.name()) && !EDIT.equals(call.name())) {
            return;
        }
        // Упавший вызов файла не тронул: откатывать по нему нечего, и аргументов у него может не
        // быть вовсе (модель могла ошибиться именно в них).
        if (call.status() == ToolInvocationStatus.ERROR) {
            return;
        }
        final String path = path(call);
        final Map<String, Object> args =
                parseArguments(call.callId() == null ? null : arguments.get(call.callId()));
        if (path == null || args.isEmpty()) {
            throw new FileRevertRefusedException(
                    "This answer was written by an older version and does not carry what it changed"
                            + " — undo it with git.");
        }
        if (CREATE.equals(call.name())) {
            deletions.add(path);
            return;
        }
        edits.computeIfAbsent(path, p -> new ArrayList<>())
                .add(
                        new TextEdit(
                                text(args, "oldString"),
                                text(args, "newString"),
                                Boolean.TRUE.equals(args.get("replaceAll"))));
    }

    /** Путь так, как его записал репозиторий, — тем же полем, по которому чат рисует плашку. */
    private static @Nullable String path(ToolInvocationMeta call) {
        final Object path = call.resultMeta() == null ? null : call.resultMeta().get("path");
        return path == null ? null : String.valueOf(path);
    }

    private static Map<String, Object> parseArguments(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, ARGUMENTS);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static String text(Map<String, Object> args, String key) {
        final Object value = args.get(key);
        if (!(value instanceof String text)) {
            throw new FileRevertRefusedException(
                    "The recorded edit has no " + key + " — undo it with git.");
        }
        return text;
    }

    private static boolean changedFiles(ToolInvocationMeta call) {
        return call.resultMeta() != null
                && call.resultMeta().get("edits") instanceof List<?> list
                && !list.isEmpty();
    }

    private static Map<String, String> argumentsByCallId(@Nullable ToolData toolData) {
        if (toolData == null || toolData.toolCalls() == null) {
            return Map.of();
        }
        final Map<String, String> byId = new HashMap<>();
        for (ToolData.Call call : toolData.toolCalls()) {
            byId.put(call.id(), call.arguments());
        }
        return byId;
    }

    /**
     * Правки одного файла наоборот: список задом наперёд, в каждой замене стороны переставлены.
     *
     * <p>{@code replaceAll} сохраняется как был. Строго говоря, обратная замена «всех вхождений»
     * может задеть и то, что совпало с {@code newString} до ответа, — но выбор между этим и отказом
     * откатить такую правку вовсе решён в пользу отката: пользователь смотрит на diff ответа и
     * просит вернуть как было.
     */
    private static Map<String, List<TextEdit>> reverse(Map<String, List<TextEdit>> edits) {
        final Map<String, List<TextEdit>> reversed = new LinkedHashMap<>();
        edits.forEach(
                (path, forward) ->
                        reversed.put(
                                path,
                                forward.reversed().stream()
                                        .map(
                                                e ->
                                                        new TextEdit(
                                                                e.newString(),
                                                                e.oldString(),
                                                                e.replaceAll()))
                                        .toList()));
        return reversed;
    }
}
