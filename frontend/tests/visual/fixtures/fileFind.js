/**
 * Фикстура перехода к найденному файлу: что видно в центре «Файлов», когда файл
 * открыли из единого поиска (components/filesPanel/FileContent.jsx,
 * общий useAddressFind и FindBar).
 *
 * Совпадений намеренно несколько и они разнесены по файлу: кейс про счётчик
 * «N/M» и про то, что активное совпадение видно отдельно от остальных. Данные
 * синтетические — файл придуман, но по форме это ответ `GET /api/git/files/content`.
 */

/** Запрос, с которым сюда пришли: он же стоит в адресе как `?find=`. */
export const query = 'timeout';

export const openedFromSearch = {
  path: 'backend/src/main/java/io/github/trialiya/kb/service/file/git/GitGrepRunner.java',
  file: {
    language: 'java',
    lineCount: 14,
    sizeBytes: 612,
    content: [
      'final class GitGrepRunner {',
      '',
      '    private final Duration timeout;',
      '',
      '    List<GitGrepMatch> grepContent(String pattern, @Nullable String pathGlob) {',
      '        List<String> args = GitGrep.args(pattern, pathspec, regex, ctx, roots, null);',
      '        Process process = new ProcessBuilder(args).start();',
      '        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {',
      '            process.destroyForcibly();',
      '            return timedOut("git grep did not finish within " + timeout.toSeconds() + "s");',
      '        }',
      '        return parse(process.inputReader());',
      '    }',
      '}',
    ].join('\n'),
  },
};
