// Что режим «Обзор» показывает для формы «скаляр»: одно короткое значение —
// `getChatId`, `getUserName`, `getCurrentDateTime`, `createAttachment`,
// `recordChatInsights` («Done»).
//
// Смысл вида ровно один: не разворачивать на весь экран секцию `pre` с тёмным
// фоном и подсветкой ради семи символов.

import { isContentText } from './contentResult';

/**
 * Разобранный ответ вызова → `{ value }` для `<ScalarResultView>`, либо null.
 *
 * Длинную строку вид не берёт — это уже содержимое, и его показывает `content`.
 * Границу задаёт `isContentText`, один предикат на оба вида.
 */
export const detectScalarResult = ({ parsed, isJson }) => {
  if (!isJson) return null;
  if (typeof parsed === 'number' || typeof parsed === 'boolean') return { value: String(parsed) };
  if (typeof parsed === 'string' && parsed.trim() && !isContentText(parsed)) return { value: parsed };
  return null;
};
