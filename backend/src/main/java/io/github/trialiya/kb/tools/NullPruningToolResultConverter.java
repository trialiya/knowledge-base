package io.github.trialiya.kb.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@link CompactToolResultConverter}, который вдобавок выкидывает из JSON ответа поля со значением
 * {@code null}: в выдаче на сотни записей пустые {@code "patch": null} стоят модели заметную долю
 * контекста и ничего ей не говорят.
 *
 * <p>В списке однотипных объектов поле выкидывается, только если оно {@code null} у всех записей
 * списка сразу. Режим «Обзор» чата узнаёт список записей по совпадению набора ключей ({@code
 * recordList.js}); удаление по записи разбило бы такой список на разные сигнатуры — например,
 * {@code oldPath} есть только у переименованного файла. Одиночный объект чистится целиком.
 */
public class NullPruningToolResultConverter extends CompactToolResultConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convert(@Nullable Object result, @Nullable Type returnType) {
        String json = super.convert(result, returnType);
        final JsonNode tree;
        try {
            tree = OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return json; // не JSON — чистить нечего
        }
        if (tree == null || !tree.isContainerNode()) return json;
        prune(tree);
        return tree.toString();
    }

    static void prune(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().removeIf(field -> field.getValue().isNull());
            object.forEach(NullPruningToolResultConverter::prune);
        } else if (node instanceof ArrayNode array && !pruneRecordList(array)) {
            array.forEach(NullPruningToolResultConverter::prune);
        }
    }

    /**
     * Список объектов: убирает поля, которые {@code null} у каждой записи, и чистит вложенное.
     * Верхний уровень записи целиком не чистится — иначе у записей разошёлся бы набор ключей.
     *
     * @return {@code false}, если массив — не список объектов и не тронут
     */
    private static boolean pruneRecordList(ArrayNode array) {
        List<ObjectNode> records = new ArrayList<>();
        for (JsonNode element : array) {
            if (!(element instanceof ObjectNode object)) return false;
            records.add(object);
        }
        if (records.isEmpty()) return false;
        Set<String> names = new LinkedHashSet<>();
        records.forEach(record -> record.fieldNames().forEachRemaining(names::add));
        List<String> allNull =
                names.stream()
                        .filter(
                                name ->
                                        records.stream()
                                                .allMatch(
                                                        r ->
                                                                r.path(name).isNull()
                                                                        || r.path(name)
                                                                                .isMissingNode()))
                        .toList();
        for (ObjectNode record : records) {
            record.remove(allNull);
            record.forEach(NullPruningToolResultConverter::prune);
        }
        return true;
    }
}
