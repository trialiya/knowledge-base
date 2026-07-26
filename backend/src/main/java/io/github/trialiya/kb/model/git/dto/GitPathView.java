package io.github.trialiya.kb.model.git.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Всё, что нужно файловому браузеру, чтобы открыть один путь: чем этот путь является, его
 * содержимое и листинги каталогов-предков для раскрытия дерева слева.
 *
 * <p>Один ответ вместо цепочки запросов: раньше клиент шёл к {@code /tree} по одному уровню
 * вложенности (не зная заранее, файл перед ним или каталог), и только потом запрашивал содержимое —
 * на глубоком пути это десяток последовательных round-trip'ов.
 *
 * @param path запрошенный путь (нормализованный; "" — корень репозитория)
 * @param type тип записи (FILE, DIRECTORY, или null для missing)
 * @param file содержимое файла — только для {@code type=FILE}
 * @param nodes прямые потомки — только для {@code type=DIRECTORY}
 * @param tree листинги каталогов-предков (от корня до родителя пути); пуст, если клиент их не
 *     запрашивал (уже есть в его кэше) либо путь лежит в корне
 */
public record GitPathView(
        String path,
        @Nullable FileEntryType type,
        @Nullable GitFileContent file,
        @Nullable List<GitFileNode> nodes,
        List<GitTreeLevel> tree) {}
