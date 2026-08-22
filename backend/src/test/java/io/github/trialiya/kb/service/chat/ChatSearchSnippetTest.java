package io.github.trialiya.kb.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatSearchService#buildSnippet}: сниппет результата поиска по чатам должен
 * показывать само совпадение (короткий префикс — иначе оно уезжает за видимую область дропдауна) и
 * быть одной плотной строкой без переносов.
 */
class ChatSearchSnippetTest {

    @Test
    void matchStaysCloseToSnippetStart() {
        String longPrefix = "х".repeat(300);
        String snippet = ChatSearchService.buildSnippet(longPrefix + " жирафы высокие", "жирафы");

        assertThat(snippet).isNotNull();
        assertThat(snippet).startsWith("…");
        // Совпадение не дальше префикса контекста от начала (плюс многоточие).
        assertThat(snippet.indexOf("жирафы")).isLessThanOrEqualTo(35);
        assertThat(snippet).contains("жирафы высокие");
    }

    @Test
    void matchIsCaseInsensitive() {
        String snippet = ChatSearchService.buildSnippet("Про PostgreSQL и индексы", "postgresql");

        assertThat(snippet).isEqualTo("Про PostgreSQL и индексы");
    }

    @Test
    void collapsesWhitespaceIntoSingleLine() {
        String snippet =
                ChatSearchService.buildSnippet("# Заголовок\n\nстрока про  жирафов\n", "жирафов");

        assertThat(snippet).isEqualTo("# Заголовок строка про жирафов");
    }

    @Test
    void fallsBackToHeadWhenQueryNotFound() {
        String content = "а".repeat(300);
        String snippet = ChatSearchService.buildSnippet(content, "нет-такого");

        assertThat(snippet).isNotNull();
        assertThat(snippet).endsWith("…");
        assertThat(snippet.length()).isLessThanOrEqualTo(122); // prefix+suffix контекст + «…»
    }

    @Test
    void nullContentGivesNullSnippet() {
        assertThat(ChatSearchService.buildSnippet(null, "q")).isNull();
    }
}
