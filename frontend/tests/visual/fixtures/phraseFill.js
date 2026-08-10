/**
 * Фикстуры для диалога заполнения плейсхолдеров
 * (components/chatPanel/PhraseFillModal.jsx).
 *
 * Тексты фраз — это поле `text` записи библиотеки (GET /api/phrases). Тип поля
 * живёт в самом тексте, после последнего двоеточия, поэтому фикстура — просто
 * строка; разбирает её parsePlaceholders.
 *
 * Результаты поиска повторяют форму ответов, из которых их строит
 * placeholderFields.js: gitApi.searchFiles, gitApi.searchCommits,
 * documentsApi.searchByName. Пути и хэши синтетические — id из засеянной базы
 * и настоящие хэши репозитория в фикстуры не тащим.
 */

/** Все шесть типов сразу — основной снимок диалога. */
export const allTypesPhrase =
  'Разбери {{Имя файла:file}} на коммите {{Коммит:commit}}, сверься с {{Документ:document}}, ' +
  'глубина {{Дней:number}}, тема «{{Тема:string}}», учитывать тесты: {{Тесты:boolean}}';

/** Фраза старого формата — типов нет, все поля должны стать строками. */
export const legacyPhrase = 'Покажи историю коммитов файла {{файл}} и объясни изменения по теме {{тема}}';

/**
 * Крайние случаи разбора в одной фразе: опечатка в типе, двоеточие внутри
 * подписи, подпись в две строки и столько полей, что колонка начинает
 * прокручиваться.
 */
export const edgeCasesPhrase =
  'Опечатка {{Файл:fiel}}, время {{Совещание в 10:30}}, ' +
  'подпись {{Очень длинное имя поля для проверки переноса подписи в диалоге:string}}, ' +
  'ещё {{A:number}} {{B:number}} {{C:number}} {{D:boolean}} {{E:file}} {{F:document}} {{G:commit}} {{H}}';

/** Ответ gitApi.searchFiles — поле типа file. */
export const fileResults = [
  { path: 'frontend/src/components/chatPanel/Message.jsx', name: 'Message.jsx' },
  { path: 'frontend/src/components/chatPanel/message.css', name: 'message.css' },
  { path: 'frontend/src/components/chatPanel/MessageList.jsx', name: 'MessageList.jsx' },
];

/** Ответ gitApi.searchCommits — поле типа commit. */
export const commitResults = [
  {
    hash: '5d405d4c0f1a2b3c4d5e6f708192a3b4c5d6e7f8',
    shortHash: '5d405d4',
    author: 'Claude',
    message: 'Типизированные плейсхолдеры фраз и диалог их заполнения',
  },
];

/** Ответ documentsApi.searchByName — поле типа document. */
export const documentResults = [
  { id: 75, title: 'анализ' },
  { id: 77, title: 'Пример: ссылки на файлы и документы' },
];

/** Превью allTypesPhrase, пока не заполнено ни одно поле: на местах — подписи полей. */
export const previewEmpty =
  'Разбери Имя файла на коммите Коммит, сверься с Документ, глубина Дней, ' +
  'тема «Тема», учитывать тесты: нет';

/**
 * Оно же после заполнения всего, кроме коммита и документа. Указатели читаются
 * названием файла, а не чип-токеном; флажок пустым не бывает и подставляется
 * всегда.
 */
export const previewFilled =
  'Разбери Message.jsx на коммите Коммит, сверься с Документ, глубина 30, ' +
  'тема «кэширование», учитывать тесты: да';

/** Текст, который диалог отдаёт в поле ввода, когда выбраны все три указателя. */
export const filledText =
  'Разбери ⟦file:frontend/src/components/chatPanel/Message.jsx⟧ на коммите ' +
  '⟦commit:5d405d4:Типизированные плейсхолдеры фраз и диалог их заполнения⟧, ' +
  'сверься с ⟦doc:75:анализ⟧, глубина 30, тема «кэширование», учитывать тесты: да';
