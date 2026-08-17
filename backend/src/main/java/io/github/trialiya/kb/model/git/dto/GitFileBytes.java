package io.github.trialiya.kb.model.git.dto;

/**
 * Сырые байты отслеживаемого файла — в отличие от {@link GitFileContent}, бинарные файлы тоже.
 *
 * <p>Возвращается окно {@code [offset, offset + bytes.length)}, а не файл целиком: байты попадают в
 * скрипт по одному числу на байт, поэтому большой файл читается кусками (см. {@code
 * KbScriptApi.readBytes}). Полный размер файла всё равно приезжает в {@link #sizeBytes}, чтобы
 * вызывающему не пришлось отдельно спрашивать, сколько осталось.
 *
 * @param path относительный путь
 * @param bytes запрошенное окно; пустой массив, если окно начинается за концом файла
 * @param offset смещение начала окна в байтах от начала файла
 * @param sizeBytes размер всего файла, а не окна
 * @param binary true если файл бинарный (эвристика по NUL-байту, как у git)
 */
public record GitFileBytes(
        String path, byte[] bytes, long offset, long sizeBytes, boolean binary) {}
