# Разбор markdown мимо `MarkdownSections`

#474 починил в `MarkdownSections.scanHeadings` распознавание fence на
вложенном пункте списка. `MarkdownSections` уже используют
`MarkdownOutlineParser`, `DocumentGrep` и `DocumentFunction`. Остальные
разборщики заголовков и блоков кода живут своей жизнью и расходятся с ним.

## Бэкенд

- **`TopicPrompt.CODE_BLOCK`** (`service/chat/topic/TopicPrompt.java:74-75`):
  закрывающий fence должен быть ровно той же длины, что открывающий (`\1`).
  CommonMark и `MarkdownSections` (`:212-214`) принимают ту же букву длиной не
  меньше. Fence на любом отступе принимается (мягче, чем `MarkdownSections`);
  маркер списка не учитывается. Работает на ответе модели, не на документах.
- **`DocumentFunction.requireStartsWithHeading`** (`functions/DocumentFunction.java:798-803`):
  `#{1,6}[ \t].*` не принимает голый `#`, который `MarkdownSections.HEADING`
  (`MarkdownSections.java:37`) принимает. Один из двух неправ.
- **`DocumentLinkRewriter`** (`service/document/DocumentLinkRewriter.java:29-76`):
  регулярки по всему тексту, ссылки внутри блоков кода переписываются при
  экспорте.
- **`TextChunker`** (`service/embedding/TextChunker.java:90, :104`): режет по
  `\n{2,}`, потом по предложениям и словам, о markdown не знает — чанк может
  разорвать fenced block посередине и оторвать заголовок от тела. Кандидат на
  чанкинг по `MarkdownSections.parse` (секция → чанки).

## Фронтенд

- **`markdownToJira.js:162-173`**: fence `/^\s*(```|~~~)\s*([\w+-]*)\s*$/` —
  только ровно три символа (четыре бэктика не fence), закрывающий должен
  совпасть буквально, info string с пробелом или другими символами не
  принимается, маркер списка перед fence не учитывается. Экспорт в Jira
  документа с четырьмя бэктиками ломается. `HEADING_RE` (`:51`) не допускает
  отступа.
- **`common/ui/utils.js:55` `makeSnippet`**: `.replace(/^#{1,6}\s+/gm, '')`
  срезает `#` и внутри блоков кода.
- **`common/preview/sectionAnchor.js` `headingPaths`**: повторяет правила путей
  `MarkdownSections` (` > `, `[n]`, `_preamble`) поверх DOM h1–h6. Fence там
  не проблема, но правила надо держать синхронными с бэком — стоит ссылка в
  обе стороны в комментарии.

## Что сделать

На бэке — один разборщик fence: `TopicPrompt` и `requireStartsWithHeading`
на `MarkdownSections`, `DocumentLinkRewriter` пропускает блоки кода через тот
же скан. Во фронте — `markdownToJira` получает fence по правилам CommonMark
(длина ≥ 3, закрывающий не короче, любой info string), с тестом на четыре
бэктика и на fence в пункте списка — тот же случай, что #474.
