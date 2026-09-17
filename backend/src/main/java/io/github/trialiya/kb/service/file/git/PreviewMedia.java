package io.github.trialiya.kb.service.file.git;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Какие файлы репозитория отдаются браузеру как есть — картинки, которые файловый браузер
 * показывает вместо текста, — и с каким типом содержимого.
 *
 * <p>Список закрытый, и это не удобство, а граница: по сырому байтовому эндпоинту с произвольным
 * типом содержимого можно было бы отдать со своего origin что угодно — например HTML — и получить
 * исполнение в контексте приложения. Тип берётся здесь по расширению, а не сниффится из
 * содержимого, поэтому его выбирает список, а не файл.
 */
public final class PreviewMedia {

    private static final Map<String, String> BY_EXTENSION =
            Map.ofEntries(
                    Map.entry("png", "image/png"),
                    Map.entry("jpg", "image/jpeg"),
                    Map.entry("jpeg", "image/jpeg"),
                    Map.entry("gif", "image/gif"),
                    Map.entry("webp", "image/webp"),
                    Map.entry("avif", "image/avif"),
                    Map.entry("bmp", "image/bmp"),
                    Map.entry("ico", "image/x-icon"),
                    Map.entry("svg", "image/svg+xml"));

    private PreviewMedia() {}

    /**
     * Тип содержимого, с которым файл можно отдать на встроенный показ, либо {@code null} — такие
     * файлы сырыми не отдаются вовсе.
     */
    public static @Nullable String mediaType(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) {
            return null;
        }
        return BY_EXTENSION.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
