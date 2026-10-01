# Фронтенд: что ещё не перенесено на общее

#478 и #480 перевели тумблеры вида на общие `btn btn--ghost btn--xs` +
`aria-pressed` (класс `--active` убран), свели относительное время в
`formatRelativeTime` (`utils/formatting.js`), пометили ячейки blame
`data-find-skip`. #466 сделал `CommitHashLink` и `useLinkTooltip`. Ниже — где
тот же паттерн остался старым. Правило — «migrate on touch»: править в PR,
который этот файл и так открывает.

Пути относительно `frontend/src/components/`.

## 1. Тумблеры со своим `--active`

Ни один не ставит `aria-pressed`, все рисуют `{ }` / 👁:

| Файл | Класс | CSS |
| --- | --- | --- |
| `chatPanel/messages/resultViews/ContentResultView.jsx:60-67` | `tool-result__md-toggle(--active)` | `chatPanel/styles/tool-result.css:53, :65, :69` |
| `chatPanel/messages/FileDiffModal.jsx:58-66` | `fcd-md-toggle(--active)` | `chatPanel/styles/file-changes.css:112, :123, :126` |
| `chatPanel/composer/FileChipPreview.jsx:33-50` (две кнопки) | `file-preview-modal__toggle(--active)` | `chatPanel/styles/modals.css:27, :39, :43` |

Ещё:

- `knowledgeBasePanel/modals/HistoryModal.jsx:286-302` — сегментный
  переключатель diff/base/compare на голых `<button>` с `is-active`
  (`knowledgeBasePanel/styles/editor.css:310-330`). `SegmentSwitch` из
  `chatPanel/messages/ToolCallDetailModal.jsx:45-71` делает то же на
  `btn btn--ghost btn--xs` + `aria-pressed` — вынести в `common/ui` и
  использовать в обоих.
- `knowledgeBasePanel/detail/ContentsTable.jsx:95-101` —
  `contents-pagination__page--active` без `aria-pressed` / `aria-current`.
- `chatPanel/composer/Phrases.jsx:125` — `phrases-star phrases-star--on`, есть
  общий `icon-btn--star`.

## 2. Свои кнопки закрытия

- `fcd-close` в `chatPanel/messages/FileDiffModal.jsx:71`
  (`file-changes.css:62, :73`) — литеральный ✕.
- `fs-editor__close` (`knowledgeBasePanel/styles/detail.css:254, :268`) в
  `FileChipPreview.jsx:51`, `common/preview/FilePreviewModal.jsx:71`,
  `common/preview/FileFullscreenModal.jsx:25`,
  `knowledgeBasePanel/editor/FullscreenEditorModal.jsx:36`,
  `HistoryModal.jsx:239`.

`CompactSummaryModal.jsx:52`, `ToolCallDetailModal.jsx:211` и
`common/attachments/AttachmentModal.jsx:55` уже на `icon-btn`. Все модалки — на
`ModalShell`, своих оверлеев не осталось; остаточное — шапки `fs-editor__head`
и `fcd-header` / `fcd-title` / `fcd-open-link` в `FileDiffModal.jsx:55-73`.

## 3. Даты мимо `utils/formatting.js`

| Файл | Сейчас | Чем заменить |
| --- | --- | --- |
| `knowledgeBasePanel/modals/HistoryModal.jsx:38-45` | свой `fmtDate` = `toLocaleString` в try/catch | `formatDateTime` (тот же вывод) |
| `settingsPanel/ScriptSchedules.jsx:15` | `toLocaleString` | `formatDateTime` |
| `chatPanel/messages/CompactNotice.jsx:42-43` | `toLocaleString` для `title` | `formatDateTime` |
| `chatPanel/messages/Message.jsx:33-44` | свой `formatFullDatetime` (длинный месяц) для подсказки над относительным временем | в `formatting.js` или `formatDateTime` |
| `chatPanel/messages/resultViews/fieldValue.js:63, :67` | `toLocaleDateString` / `toLocaleString` | `:67` — `formatDateTime`; `:63` — осознанно «только дата», нужен `formatDate` |
| `chatPanel/messages/resultViews/DiffResultView.jsx:68-69` | дата коммита `toLocaleDateString` | `formatRelativeTime` — как у blame |
| `searchPanel/results/CommitResults.jsx:29` | дата коммита | `formatRelativeTime` |
| `searchPanel/results/ChatResults.jsx:56-59, :84` | `toLocaleTimeString` (HH:MM без даты), `toLocaleDateString` | `formatRelativeTime` — как у сообщения чата |
| `searchPanel/results/DocResults.jsx:96` | `toLocaleDateString` | `formatRelativeTime` |
| `knowledgeBasePanel/tree/SearchResults.jsx:72` | `toLocaleDateString`, без защиты от null | `formatRelativeTime` |
| `knowledgeBasePanel/detail/ContentsTable.jsx:77` | то же, без защиты от null | `formatRelativeTime` |
| `common/preview/DocPreviewTooltip.jsx:66` | `toLocaleDateString` | `formatDateTime` — соседний `CommitPreviewTooltip.jsx:16` уже на нём |

Не даты, трогать не надо: TTL-арифметика (`fileTreeStore.js:56`,
`useConfigSnapshot.js:29`, `usePreviewCache.js:42`, `activeRun.js:43`) и
`toLocaleString` у чисел в `ScriptsSettings` / `ModelsSettings`.

## 4. Хеши коммитов текстом

- `chatPanel/messages/resultViews/contentResult.js:109` —
  `obj.commit.slice(0, 7)` в факт, `ContentResultView.jsx:36-39` рисует его
  текстом. В модалке вызова, значит `CommitHashLink` с `newTab`, как у
  `DiffResultView`. И `shortRev` вместо `slice`.
- `chatPanel/messages/resultViews/diffResult.js:63` — `slice(0, 7)` вместо
  `shortRev`; отображение уже ссылка.
- `common/preview/FilePreviewTooltip.jsx:48`, `FilePreviewModal.jsx:68`,
  `FileFullscreenModal.jsx:22` — `path @ shortRev(rev)` текстом; rev может
  быть `CommitHashLink` (в модалках с `newTab`).

Уже ссылки: `FileInfo.jsx:74`, `CommitInfo.jsx:53`, `GitOutputCard.jsx:40`,
`PushDialog.jsx:78`, `CommitResults.jsx:33` (вся строка — ссылка). В
`BlameCell.jsx:40` хеш внутри ссылки на файл в той ревизии — намеренно.

## 5. Загрузка полного файла с отменой — трижды

`common/preview/FilePreviewModal.jsx:24-56` (`[req, setReq]`, флаг
`cancelled`, `gitApi.getFileContent`), `chatPanel/messages/FileDiffModal.jsx:25-51`
(та же форма над `fetchContent`), `chatPanel/composer/useChipPreview.js:27-49`
(свой fetch плюс защита от устаревания). В `useFilePreview` это не вкладывается:
он читает только 20 строк (`PREVIEW_LINES`). Нужен общий хук «полное
содержимое файла» или параметр у `useFilePreview`. Рендер тоже разный:
`FileChipPreview.jsx:62-71` и `FileDiffModal` — голый `<pre>` + ReactMarkdown,
`FilePreviewModal` — `FileView` через `CodeView`.

Хуки превью (`useFilePreview`, `useDocPreview`, `useCommitPreview`) уже на
`usePreviewCache`; `FileLink`, `CommitLink`, `DocLinkTooltip` — на
`useLinkTooltip`. Копий позиционирования и таймеров нет.

## 6. `data-find-skip` только в `BlameCell`

Корень поиска в «Файлах» — `file-content__body` (`filesPanel/FileContent.jsx:96`);
в модалках Ctrl+F даёт `ModalShell` через `useModalFind`.

- `filesPanel/code/CodeView.jsx:28` — `<td className="file-code__gutter">`:
  номера строк совпадают с числовым запросом.
- `filesPanel/FileView.jsx:137-177` `.file-view__meta` — бейдж языка, «N
  строк», размер, бейдж обрезки, подписи тумблеров Diff/Blame/👁, бейдж ошибки
  blame.
- `filesPanel/FileContent.jsx:118-126` — бейдж статуса в ветке «gone».
- `filesPanel/changes/ChangeDiffView.jsx:27` — `<PatchHeader>` (`diff --git`,
  `index`, `---`, `+++`).
- `chatPanel/messages/diffRender.jsx:78` `diff-line__no` — номера строк diff;
  маркеры `+` / `-` — часть текста строки (`:79`), для пропуска нужен отдельный
  span.
- `chatPanel/messages/resultViews/codeLines.jsx:63` `tool-code__line-no` — в
  модалке вызова.
- `FilePreviewModal` рендерит `FileView` и наследует gutter и meta.
