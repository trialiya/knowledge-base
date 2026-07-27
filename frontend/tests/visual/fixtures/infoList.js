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
 * Набор из базы знаний: у черновика/узла дерева половина полей пустая — они
 * отбрасываются, и кнопок копирования у них тоже не появляется.
 */
export const sparseNodeRows = [
  { label: 'Type', value: 'Документ' },
  { label: 'Created', value: '' },
  { label: 'Updated', value: null },
  { label: 'ID', value: '7', mono: true },
];
