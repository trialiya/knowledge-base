/**
 * Фикстуры для find-бара модалки (components/common/ModalFindBar.jsx,
 * useModalFind.js, подключены в ModalShell).
 *
 * Кейс проверяет ровно одно: поиск идёт по содержимому диалога, а не по
 * странице под ним. Поэтому фикстура — пара «текст в модалке» + «текст под
 * оверлеем» с одним и тем же словом, чтобы счётчик совпадений было с чем
 * сравнить. Данные синтетические: лог урезан до нескольких строк.
 */

/** Запрос, которым проверяется кейс, и сколько вхождений у него в каждом из текстов. */
export const query = 'gradle';

/**
 * Содержимое модалки — превью прикреплённого лога сборки. Три вхождения
 * запроса: одно в заголовке (имя файла) и два в теле.
 */
export const dialogContent = {
  title: 'gradle-build-error.log',
  body: [
    '> Task :backend:compileJava FAILED',
    '',
    'FAILURE: Build failed with an exception.',
    '',
    '* What went wrong:',
    "Execution failed for task ':backend:compileJava'.",
    '> invalid flag: --enable-preview',
    '  (ToolTranslationsTest uses unnamed variables `_`, a Java 21 preview feature',
    '   finalized in 22-25; toolchain resolved to Java 21 - run with',
    '   --init-script gradle/java21.gradle to enable preview features)',
    '',
    'BUILD FAILED in 4s',
  ].join('\n'),
  matches: 3,
};

/**
 * Текст под оверлеем — переписка чата, из которой модалка и открыта. Вхождений
 * запроса тут больше, чем в диалоге, и ни одно не должно попасть в счётчик.
 */
export const pageBehind = {
  chatTitle: 'История коммитов backend/build.gradle',
  matches: 11,
};
