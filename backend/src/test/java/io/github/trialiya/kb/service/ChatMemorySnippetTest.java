package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatMemoryService#buildSnippet}: сниппет результата поиска по чатам должен
 * показывать само совпадение (короткий префикс — иначе оно уезжает за видимую область дропдауна) и
 * быть одной плотной строкой без переносов.
 */
class ChatMemorySnippetTest {

    @Test
    void matchStaysCloseToSnippetStart() {
        String longPrefix = "х".repeat(300);
        String snippet = ChatMemoryService.buildSnippet(longPrefix + " жирафы высокие", "жирафы");

        assertThat(snippet).isNotNull();
        assertThat(snippet).startsWith("…");
        // Совпадение не дальше префикса контекста от начала (плюс многоточие).
        assertThat(snippet.indexOf("жирафы")).isLessThanOrEqualTo(35);
        assertThat(snippet).contains("жирафы высокие");
    }

    @Test
    void matchIsCaseInsensitive() {
        String snippet = ChatMemoryService.buildSnippet("Про PostgreSQL и индексы", "postgresql");

        assertThat(snippet).isEqualTo("Про PostgreSQL и индексы");
    }

    @Test
    void collapsesWhitespaceIntoSingleLine() {
        String snippet =
                ChatMemoryService.buildSnippet("# Заголовок\n\nстрока про  жирафов\n", "жирафов");

        assertThat(snippet).isEqualTo("# Заголовок строка про жирафов");
    }

    @Test
    void fallsBackToHeadWhenQueryNotFound() {
        String content = "а".repeat(300);
        String snippet = ChatMemoryService.buildSnippet(content, "нет-такого");

        assertThat(snippet).isNotNull();
        assertThat(snippet).endsWith("…");
        assertThat(snippet.length()).isLessThanOrEqualTo(122); // prefix+suffix контекст + «…»
    }

    @Test
    void nullContentGivesNullSnippet() {
        assertThat(ChatMemoryService.buildSnippet(null, "q")).isNull();
    }
}
