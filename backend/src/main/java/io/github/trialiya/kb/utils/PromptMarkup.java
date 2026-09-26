package io.github.trialiya.kb.utils;

/**
 * Текст извне в разметке, которую читает модель: нотисы ({@code <git-command>}, {@code
 * <script-run>}) и блок {@code <active-project>}.
 */
public final class PromptMarkup {

    private PromptMarkup() {}

    /**
     * Текст, который не может выйти за пределы своего места в разметке. Имена веток, пути, описания
     * из манифеста репозитория — всё это пишет не приложение, а ни git, ни манифест не запрещают
     * кавычку и угловые скобки. Кавычкой закрывают атрибут, угловой скобкой — сам тег, и ветка,
     * названная {@code main>...</git-command}, дописала бы модели произвольный текст поверх нотиса.
     */
    public static String inert(String value) {
        return value.replace("\"", "'").replace("<", "‹").replace(">", "›");
    }
}
