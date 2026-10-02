package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.eclipse.jgit.diff.RawText;
import org.junit.jupiter.api.Test;

/** Текстовая сторона поиска происхождения: подстрока, похожесть строк, выбор прежней строки. */
class LineOriginTest {

    private static RawText text(String text) {
        return new RawText(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Подстрока ищется буквально и без учёта регистра — как её нашёл поиск. */
    @Test
    void theSubstringIsLiteralAndCaseInsensitive() {
        assertThat(LineOrigin.needle("a.b(").matcher("x A.B( y").find()).isTrue();
        assertThat(LineOrigin.needle("a.b(").matcher("axb(").find()).isFalse();
        assertThat(LineOrigin.needle("Поиск").matcher("страница ПОИСКА").find()).isTrue();
    }

    /**
     * Прежняя строка — та, где подстрока есть; из нескольких таких — самая похожая. Без подстроки
     * в куске прежней строки нет — подстрока вошла этой правкой.
     */
    @Test
    void theOldLineCarryingTheSubstringIsTheMostAlikeOfThoseThatHaveIt() {
        RawText old = text("int total = 0;\nlong total = sum(items);\nString label;\n");
        Pattern needle = LineOrigin.needle("total");

        assertThat(LineOrigin.carrying(old, 0, 3, needle, "long total = sum(items, tax);"))
                .isEqualTo(1);
        assertThat(LineOrigin.carrying(old, 2, 3, needle, "long total = sum(items, tax);"))
                .isEqualTo(-1);
        assertThat(LineOrigin.closest(old, 0, 3, "String label = name;")).isEqualTo(2);
    }

    /** Похожесть — по парам символов, отступ не в счёт; одинаковые строки — 1, чужие — около 0. */
    @Test
    void similarityIgnoresIndentation() {
        assertThat(LineOrigin.similarity("    return x;", "return x;")).isEqualTo(1.0);
        assertThat(LineOrigin.similarity("return total;", "return totals;")).isGreaterThan(0.8);
        assertThat(LineOrigin.similarity("return total;", "import java.util.List;"))
                .isLessThan(0.3);
    }

    @Test
    void aMovedBlockIsWeighedByItsLettersAndDigits() {
        assertThat(LineOrigin.alnum("  int x = 1; // ок")).isEqualTo(7);
        assertThat(LineOrigin.sameLine("\t  foo(a);", "foo(a);  ")).isTrue();
    }
}
