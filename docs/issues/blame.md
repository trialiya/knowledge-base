# Blame: что осталось вокруг #478, #483, #485, #490

#478 завёл колонку авторства строк (`GitBlameRunner` + `GitBlame` на бэке,
`useFileBlame` / `blameRows` / `BlameCell` во фронте), #483 — подсветку ханка,
#485 — дату и описание в ячейке и переход к строкам ханка в снимке коммита,
#490 — возврат «Назад» к ним. #494 свёл разбор ревизии в `CommitFiles.commitOf`
/ `commitOrNull`, разбор exit-кода — в `GitReadProcess.Output.requireExit`,
`503` на таймаут — в `GitController.read()`, убрал `email` / `shortHash` из
ханка; #495 положил `useFileBlame` и `useFileContent` на `useKeyedRequest`, а
пары `href` + `onClick` — на `AppLink`. Ниже — что осталось.

## Бэкенд

- **`RepoFiles.isBinary` → «не текст» — в четырёх местах.**
  `GitBlameRunner.requireText` (`:139`), `FileViews.java:45`,
  `FileOutlines.java:26`, `Diffs.java:223`: проверка одна, исключение и текст —
  у каждого свои. `RepoFiles.requireText(path, bytes)` с одним сообщением.
  `GitService` (`:734, :773-816`) не отказывает, а возвращает признак в
  `GitFileBytes` — это другой случай, его не трогать.
- **`.git-blame-ignore-revs` из рабочего дерева и из снимка** —
  `workingTreeIgnoreFile` (`GitBlameRunner.java:145`) через
  `RepoFiles.readWindow`, `snapshotIgnoreFile` (`:154`) через
  `CommitFiles.Commit`. Та же пара есть у `GitService.getFileContent`
  (`:209-216`, после #497 — одна ветка `snapshot(rev)` на метод). Если чтение
  «файл по пути в рабочем дереве или в коммите, не больше N байт» станет одним
  методом, blame возьмёт его первым.
- **Инструмента blame у модели нет.** В `functions/GitFunction.java` есть
  `getCommitLog(filePath)`, но на «кто и когда менял эти строки» модели
  отвечать нечем. `getFileBlame(path, fromLine, toLine, rev)` поверх
  `GitBlameRunner`: ханки диапазона, `ModelView` без `path`, предел по числу
  ханков и `ToolResult.truncated`, запись в `ai-инструменты.md`. Это новая
  возможность, а не рефакторинг — отдельный PR.

## Фронтенд

- **Подсказка ячейки — нативный `title`** (`BlameCell.jsx:51`): описание,
  автор, хеш, дата в одну строку `\n`. У `CommitLink` (#466) на наведение —
  `CommitPreviewTooltip` через `useLinkTooltip` + `useCommitPreview`: автор,
  дата, сообщение целиком, файлы. Ячейки одного коммита делят кэш превью по
  хешу, так что цена — один запрос на коммит, не на ханк.
- **Состояние экрана через четыре уровня пропсов.** `fileBlame` /
  `setFileBlame` идут `App.jsx:293, :299` → `FilesPanel.jsx` → `FileContent.jsx`
  → `FileView.jsx`; рядом тем же путём идут `changes` / `onChangesToggle`,
  `diff` / `onToggleDiff` и `reloadToken`. `useAppNavigation` — уже адаптер над
  `navStore`; `FileView` в «Файлах» мог бы читать `fileBlame` и звать
  `setFileBlame` сам. Оговорка: `onToggleBlame = null` сейчас значит «превью в
  модалке, колонки нет» — этот признак надо оставить явным пропсом.
- **`useKeyedRequest` прерывает запрос при уходе с ключа** — для
  `FileChipPreview.jsx:22-29` это значит, что закрытое до ответа превью чипа
  больше не греет кэш `fileChips` (`fetchContent`, `fileChips.js:178-184`,
  кэширует только разрешённый ответ, так что отменённый его не портит).
  Безвредно; записано, чтобы при жалобе «чип отправился холодным» не искать
  причину заново.
- **`.claude/rules/frontend-ui.md:214`** — строка про `AppLink` длиннее 80
  колонок, остальной файл переносится по 80; Spotless правила не проверяет.
