package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.OutlineResult;
import io.github.trialiya.kb.service.file.outline.LanguageDetector;
import io.github.trialiya.kb.service.file.outline.OutlineService;

/**
 * Байты файла → структурный обзор: язык по имени, отказ для бинарного и неподдерживаемого, символы
 * от {@link OutlineService}.
 *
 * <p>Отдельно от {@code GitService} по той же причине, что и {@link FileViews}: байты приходят из
 * рабочего дерева и из дерева коммита, а правила обзора у них одни.
 */
final class FileOutlines {

    private FileOutlines() {}

    /**
     * @param path нормализованный путь относительно корня репозитория
     * @param tracked знает ли о файле git; для чтения из коммита всегда {@code true}
     * @param bytes содержимое как есть
     * @throws IllegalArgumentException если файл бинарный или его язык обзор не поддерживает
     */
    static GitFileOutline of(OutlineService outlineService, String path, boolean tracked, byte[] bytes) {
        if (RepoFiles.isBinary(bytes)) {
            throw new IllegalArgumentException("Cannot outline a binary file: " + path);
        }
        String language = LanguageDetector.detect(path);
        if (language == null || !outlineService.isLanguageSupported(language)) {
            throw new IllegalArgumentException("Unsupported language for outline: "
                    + (language == null ? "unknown" : language)
                    + " (supported: "
                    + String.join(", ", outlineService.supportedLanguages())
                    + ")");
        }

        String source = RepoFiles.decodeToLf(bytes);
        int total = 1 + (int) source.chars().filter(c -> c == '\n').count();
        OutlineResult result = outlineService.outline(language, source);
        return new GitFileOutline(path, tracked, language, total, result.parser(), result.symbols());
    }
}
