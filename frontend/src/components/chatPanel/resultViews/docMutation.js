// Что режим «Обзор» показывает для формы «мутация документа»: `createDocument`,
// `updateDocument` и четыре секционных инструмента отвечают одним `DocumentShort`.
// Карточка со ссылкой в историю версий — то же, что блок правок под ответом ИИ
// (`DocChangeBlock`), только на один вызов.
//
// Разбор — по форме, а не по имени инструмента (см. registry.js).

import { isPlainObject } from './contentResult';

// Факты карточки, в порядке вывода. Значения — как есть; форматирует их
// компонент (даты — по локали, подписи — через i18n).
const FACT_FIELDS = ['type', 'version', 'descriptionVersion', 'updatedAt', 'parentId', 'summaryStale'];

/**
 * Разобранный ответ вызова → карточка для `<DocMutationView>`, либо null.
 *
 * Мутация — это ссылка на документ, а не документ: в `DocumentShort` всё
 * скалярное и содержимого нет вовсе. Поэтому отбой по описанию и по любой
 * вложенной коллекции — так вид не забирает ни `getDocument` у документа с
 * пустым описанием, ни `getDocumentOutline`, у которого те же `id`/`title`/
 * `descriptionVersion` плюс `sections`.
 */
export const detectDocMutation = ({ parsed, isJson }) => {
  if (!isJson || !isPlainObject(parsed)) return null;
  if ('description' in parsed || Object.values(parsed).some(Array.isArray)) return null;

  if (!Number.isInteger(parsed.id)) return null;
  if (typeof parsed.title !== 'string' || !parsed.title.trim()) return null;
  // Обе версии сразу: у документа их две — своя и у описания, — и вместе они
  // встречаются только там, где правка и произошла.
  if (!Number.isInteger(parsed.version) || !Number.isInteger(parsed.descriptionVersion)) return null;

  return {
    id: parsed.id,
    title: parsed.title,
    descriptionVersion: parsed.descriptionVersion,
    facts: FACT_FIELDS.map((key) => ({ key, value: parsed[key] })).filter(
      // Опущенный флаг фактом не считается: «сводка устарела: нет» — это шум,
      // а не сведение о правке.
      ({ value }) => value !== null && value !== undefined && value !== '' && value !== false,
    ),
  };
};
