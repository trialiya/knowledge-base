/**
 * Фикстуры для вкладки «Инфо» правой панели (components/common/InfoList.jsx).
 *
 * Компонент принимает готовые строки, поэтому фикстура — это `rows` в том виде,
 * в каком их собирают разделы (FileInfo, DetailInfo, ChatInfo). Данные
 * синтетические: id и хеш не из базы.
 */

/**
 * Набор из файлового раздела: короткие значения справа, моноширинные путь и
 * хеш, плюс `block`-строка с сообщением коммита. На нём и видно, как встают
 * кнопки копирования — в один ряд со значением и отдельно в многострочной
 * block-строке.
 */
export const fileRows = [
  { label: 'Name', value: 'MovePhraseRequest.java' },
  { label: 'Path', value: 'backend/src/main/java/io/github/trialiya/kb/model/phrase/dto', mono: true },
  { label: 'Type', value: 'File' },
  { label: 'Size', value: '1.2 KB' },
  { label: 'Modified', value: '12.03.2026, 14:08' },
  { label: 'Author', value: 'Ivan Petrov' },
  { label: 'Commit', value: '9f3c1ab', mono: true },
  {
    label: 'Commit message',
    value: 'Вынести перенос фразы в отдельный запрос и убрать позиционную арифметику из истории',
    block: true,
  },
];

/**
 * Набор из чата: длинное название и id длиннее правой панели — на них видно,
 * как строка переносится целиком, оставляя значение справа, а не уводит его
 * под лейбл по левому краю. Дата с временем, наоборот, рядом с лейблом влезает.
 *
 * Хвост — расширенная статистика токенов (ChatInfo). Числа взяты правдоподобные и намеренно
 * разные по порядку: занято 21.1k, а total input 81.1k — разрыв объясняет строка Cache read, и
 * без него набор ничего бы не показывал.
 */
export const chatRows = [
  { label: 'Название', value: 'История коммитов backend/build.gradle' },
  { label: 'Создан', value: '18.07.2026, 20:59:02' },
  { label: 'Последнее изменение', value: '18.07.2026, 21:01:19' },
  { label: 'Модель', value: 'Default' },
  { label: 'ID', value: 'c5dfa618-0ad2-4845-a976-ada46c50f9a4', mono: true },
  { label: 'Занято контекста', value: '21.1k' },
  { label: 'Total input', value: '81.1k' },
  { label: 'Output', value: '1.1k' },
  { label: 'Cache read', value: '63.4k (78%)' },
  { label: 'Cache write', value: '2.4k' },
  { label: 'Обращений к модели', value: '6' },
];

/**
 * Набор из базы знаний: у черновика/узла дерева половина полей пустая — они
 * отбрасываются, и кнопок копирования у них тоже не появляется.
 */
export const sparseNodeRows = [
  { label: 'Type', value: 'Документ' },
  { label: 'Created', value: '' },
  { label: 'Updated', value: null },
  { label: 'ID', value: '7', mono: true },
];
