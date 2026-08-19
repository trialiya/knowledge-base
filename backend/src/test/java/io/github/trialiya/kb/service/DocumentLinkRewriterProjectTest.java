package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Проставление проекта в ссылки на файлы — операция над уже сохранённым текстом пользователя и
 * модели, поэтому цена ошибки несимметрична: пропустить ссылку не страшно (её смысл и так
 * «дефолтный проект»), а испортить — значит переписать чужой текст без возможности откатить.
 */
class DocumentLinkRewriterProjectTest {

    @Test
    void stampsALinkThatNamesNoProject() {
        assertThat(DocumentLinkRewriter.stampProject("см. [A.java](/files?path=src/A.java)", "kb"))
                .isEqualTo("см. [A.java](/files?path=src/A.java&project=kb)");
    }

    /** Проект встаёт перед диапазоном строк: якорь `#Lx-Ly` обязан остаться последним. */
    @Test
    void theLineRangeStaysAtTheEnd() {
        assertThat(DocumentLinkRewriter.stampProject("[A](/files?path=src/A.java#L10-L20)", "kb"))
                .isEqualTo("[A](/files?path=src/A.java&project=kb#L10-L20)");
    }

    @Test
    void stampsEveryLinkInTheText() {
        String stamped =
                DocumentLinkRewriter.stampProject(
                        "[A](/files?path=a.java) и [B](/files?path=b.java#L1)", "kb");

        assertThat(stamped)
                .isEqualTo(
                        "[A](/files?path=a.java&project=kb) и [B](/files?path=b.java&project=kb#L1)");
    }

    /** Второй прогон бэкафилла не должен давать `&project=kb&project=kb`. */
    @Test
    void aLinkThatAlreadyNamesAProjectIsLeftAlone() {
        String once = DocumentLinkRewriter.stampProject("[A](/files?path=a.java)", "kb");

        assertThat(DocumentLinkRewriter.stampProject(once, "kb")).isNull();
        assertThat(DocumentLinkRewriter.stampProject("[A](/files?path=a.java&project=other)", "kb"))
                .isNull();
    }

    /**
     * Пробелы и амперсанды в именах файлов — обычное дело, и именно такие ссылки бэкафилл обязан
     * разметить: путь «половиной имени» откроет не тот файл или не откроет ничего.
     */
    @Test
    void awkwardFileNamesAreStampedToo() {
        assertThat(DocumentLinkRewriter.stampProject("[X](/files?path=docs/My File.md#L10)", "kb"))
                .isEqualTo("[X](/files?path=docs/My File.md&project=kb#L10)");
        assertThat(DocumentLinkRewriter.stampProject("[Q](/files?path=docs/Q&A.md)", "kb"))
                .isEqualTo("[Q](/files?path=docs/Q&A.md&project=kb)");
    }

    /**
     * «&project=» внутри имени файла — не параметр, а часть пути: параметр этот класс дописывает
     * последним и id в нём — из того же набора символов, что принимает ProjectCatalog. Иначе такая
     * ссылка осталась бы без проекта (её сочли бы уже размеченной), а на экспорте потеряла бы
     * половину имени.
     */
    @Test
    void anAmpersandProjectInsideTheFileNameIsNotTheParameter() {
        assertThat(DocumentLinkRewriter.stampProject("[N](/files?path=notes&project=x/n.md)", "kb"))
                .isEqualTo("[N](/files?path=notes&project=x/n.md&project=kb)");
        assertThat(DocumentLinkRewriter.flattenFileLinks("[N](/files?path=notes&project=x/n.md)"))
                .isEqualTo("N (notes&project=x/n.md)");
        assertThat(
                        DocumentLinkRewriter.flattenFileLinks(
                                "[N](/files?path=notes&project=x/n.md&project=kb)"))
                .isEqualTo("N (notes&project=x/n.md)");
    }

    /** Схлопывание таких же имён не должно обрезать путь по первому амперсанду. */
    @Test
    void flatteningKeepsAnAmpersandThatBelongsToTheFileName() {
        assertThat(
                        DocumentLinkRewriter.flattenFileLinks(
                                "[Q&A](/files?path=docs/Q&A.md&project=kb)"))
                .isEqualTo("Q&A (docs/Q&A.md)");
        assertThat(DocumentLinkRewriter.flattenFileLinks("[Q&A](/files?path=docs/Q&A.md)"))
                .isEqualTo("Q&A (docs/Q&A.md)");
    }

    @Test
    void textWithoutRepoLinksIsNotRewrittenAtAll() {
        assertThat(DocumentLinkRewriter.stampProject("см. [док](/?doc=42) и обычный текст", "kb"))
                .isNull();
        assertThat(DocumentLinkRewriter.stampProject("", "kb")).isNull();
    }

    /**
     * Не всякое упоминание пути — ссылка. Шаблон опирается на закрывающую скобку или якорь, поэтому
     * то, что стоит в тексте само по себе, остаётся нетронутым: дописать туда проект — значит
     * поменять смысл фразы, а не адрес.
     */
    @Test
    void abarePathInProseIsNotALink() {
        assertThat(DocumentLinkRewriter.stampProject("открой /files?path=a.java сам", "kb"))
                .isNull();
    }

    /** Экспорт схлопывает ссылку в «текст (путь)» — проект в путь просочиться не должен. */
    @Test
    void flatteningDropsTheProjectAlongWithTheRange() {
        assertThat(
                        DocumentLinkRewriter.flattenFileLinks(
                                "см. [A.java](/files?path=src/A.java&project=kb#L1-L10)."))
                .isEqualTo("см. A.java (src/A.java).");
    }

    @Test
    void flatteningStillHandlesLinksWrittenBeforeProjectsExisted() {
        assertThat(DocumentLinkRewriter.flattenFileLinks("[A.java](/files?path=src/A.java)"))
                .isEqualTo("A.java (src/A.java)");
    }
}
