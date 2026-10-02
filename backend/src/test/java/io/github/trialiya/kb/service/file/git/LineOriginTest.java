package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Текстовая сторона поиска происхождения: строка версии файла и совпадение подстроки. */
class LineOriginTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Нумерация — как у git: перевод строки в конце пустой строки за собой не оставляет. */
    @Test
    void aLineIsNumberedAsGitNumbersIt() {
        byte[] file = bytes("one\r\nдва\nthree");

        assertThat(LineOrigin.lineAt(file, 1)).isEqualTo("one");
        assertThat(LineOrigin.lineAt(file, 2)).isEqualTo("два");
        assertThat(LineOrigin.lineAt(file, 3)).isEqualTo("three");
        assertThat(LineOrigin.lineAt(file, 4)).isNull();
        assertThat(LineOrigin.lineAt(bytes("a\n"), 2)).isNull();
        assertThat(LineOrigin.lineAt(bytes("a\n\nb\n"), 2)).isEmpty();
    }

    /** Подстрока ищется буквально и без учёта регистра — как её нашёл поиск. */
    @Test
    void theSubstringIsLiteralAndCaseInsensitive() {
        assertThat(LineOrigin.needle("a.b(").matcher("x A.B( y").find()).isTrue();
        assertThat(LineOrigin.needle("a.b(").matcher("axb(").find()).isFalse();
        assertThat(LineOrigin.needle("Поиск").matcher("страница ПОИСКА").find()).isTrue();
    }
}
