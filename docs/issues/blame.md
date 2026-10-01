# Blame: что осталось вокруг #478, #483, #485, #490

#478 завёл колонку авторства строк (`GitBlameRunner` + `GitBlame` на бэке,
`useFileBlame` / `blameRows` / `BlameCell` во фронте), #483 — подсветку ханка,
#485 — дату и описание в ячейке и переход к строкам ханка в снимке коммита,
#490 — возврат «Назад» к ним. Ниже — где этот код повторяет соседний, и где
приёмы из других коммитов (#466, #484, #493) к нему ещё не применены.

## Бэкенд

- **Три способа «ревизия → коммит».** `GitBlameRunner.resolve`
  (`service/file/git/GitBlameRunner.java:125`) — `repository.resolve` +
  `parseCommit`, `null` при любой ошибке; `CommitFiles.commitOf`
  (`CommitFiles.java:332`) — то же, но `IllegalArgumentException` с разбором
  причины; `GitService.resolveCommitId` (`GitService.java:547`) — без
  `parseCommit` вовсе, так что дерево или блоб под хешем проходят проверку.
  Одна функция `CommitFiles.commitOrNull`, поверх неё `commitOf`;
  `resolveCommitId` на `commitOf`.
- **Разбор exit-кода подпроцесса — дважды.** `GitBlameRunner.run`
  (`:140-156`) и `GitGrepRunner` (`:277-290`): одно и то же
  `log.warn("Git command exited {}: {} → {}")` и `IllegalStateException` с
  `out.said()`. Это место для `GitReadProcess.Output` — метод вроде
  `requireExit(allowed…)`, а grep поверх него разбирает свой 128-й код.
  Политика по `cut()` у них разная намеренно (grep режет, blame отказывается)
  и остаётся у вызывающих.
- **`GitReadTimeoutException → 503` — дважды в контроллере.**
  `controller/GitController.java:101-105` (grep) и `:135-139` (blame) —
  одинаковые `try/catch`. `GitCommandController.java:301` для своего случая
  уже держит `@ExceptionHandler`; здесь просится такой же на
  `GitReadTimeoutException`, и оба метода теряют по пять строк.
- **`RepoFiles.isBinary` → «не текст» — в пяти местах.**
  `GitBlameRunner.requireText` (`:157`), `FileViews.java:45`,
  `FileOutlines.java:26`, `GitWriter.java:220`, `Diffs.java:223`: проверка одна,
  исключение и текст — у каждого свои. `RepoFiles.requireText(path, bytes)`
  с одним сообщением.
- **`.git-blame-ignore-revs` из рабочего дерева и из снимка** —
  `workingTreeIgnoreFile` (`:163`) через `RepoFiles.readWindow`,
  `snapshotIgnoreFile` (`:172`) через `CommitFiles.Commit`. Та же пара есть у
  `GitService.getFileContent` (`:662` / `:691`). Если чтение «файл по пути в
  рабочем дереве или в коммите, не больше N байт» станет одним методом, blame
  возьмёт его первым.
- **`GitFileBlame.Hunk.email` и `shortHash`** (`model/git/dto/GitFileBlame.java`)
  интерфейс не показывает: подпись ячейки — автор, короткий хеш, дата
  (`BlameCell.jsx:47`), и короткий хеш фронт умеет считать сам (`shortRev`,
  так #493 сделал в `diffResult`). Пока DTO только для REST, это просто
  лишние байты на каждый ханк; если появится инструмент (ниже), обоим полям
  место под `ToolJson.UiOnly` или вне `ModelView`.
- **Инструмента blame у модели нет.** В `functions/GitFunction.java` есть
  `getCommitLog(filePath)`, но на «кто и когда менял эти строки» модели
  отвечать нечем. `getFileBlame(path, fromLine, toLine, rev)` поверх
  `GitBlameRunner`: ханки диапазона, `ModelView` без `email` / `shortHash` /
  `path`, предел по числу ханков и `ToolResult.truncated`, запись в
  `ai-инструменты.md`. Это новая возможность, а не рефакторинг — отдельный PR.

## Фронтенд

- **`useFileBlame` и `useFileContent` — один хук в двух редакциях.**
  `filesPanel/code/useFileBlame.js` и `common/preview/useFileContent.js`
  (#493): ключ запроса, `{ key, answer }` в состоянии, «ответ на чужой ключ —
  мимо», `loading = ключ есть, ответа нет`. Разница только в отмене
  (`AbortController` против флага `cancelled`) и в `useMemo`. Общий
  `useKeyedRequest(key, fetch)` в `common/preview`, оба поверх него;
  `useFilePreview` с кэшем остаётся отдельно.
- **Подсказка ячейки — нативный `title`** (`BlameCell.jsx:47-51`): описание,
  автор, хеш, дата в одну строку `\n`. У `CommitLink` (#466) на наведение —
  `CommitPreviewTooltip` через `useLinkTooltip` + `useCommitPreview`: автор,
  дата, сообщение целиком, файлы. Ячейки одного коммита делят кэш превью по
  хешу, так что цена — один запрос на коммит, не на ханк.
- **Пара `href` + `onClick(isBrowserClick → navigate)`** — шестой экземпляр
  (`BlameCell.jsx:42`, `CommitHashLink.jsx`, `CommitLink.jsx`, `FileLink.jsx`,
  `DocLinkTooltip.jsx`, `searchPanel/results/ResultGroup.jsx`). Один
  `<AppLink href onNavigate>` в `common/ui`, который сам отличает клик
  браузера от перехода внутри приложения.
- **Состояние экрана через четыре уровня пропсов.** `fileBlame` /
  `setFileBlame` идут `App.jsx:293-299` → `FilesPanel.jsx:46-52, :276-277,
  :334-340, :371-377` → `FileContent.jsx:53-54, :119-120` → `FileView.jsx:91-92`;
  рядом тем же путём идут `changes` / `onChangesToggle`, `diff` /
  `onToggleDiff` и `reloadToken`. `useAppNavigation` — уже адаптер над
  `navStore`; `FileView` в «Файлах» мог бы читать `fileBlame` и звать
  `setFileBlame` сам. Оговорка: `onToggleBlame = null` сейчас значит «превью в
  модалке, колонки нет» — этот признак надо оставить явным пропсом.
