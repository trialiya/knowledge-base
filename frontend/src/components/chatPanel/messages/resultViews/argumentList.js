// Что режим «Обзор» показывает вместо JSON в секции аргументов вызова.
//
// Болезнь у аргументов та же, что была у результата: у `editFile` в
// `argumentsRaw` лежит целиком новый фрагмент файла, у `createDocument` — весь
// markdown документа, у `runScript` — сам скрипт. В JSON всё это одна строка с
// экранированными `\n`, и прочитать её нельзя.
//
// Правило одно на любые аргументы, включая MCP-инструменты: короткое значение
// печатается в строку, длинное или многострочное — блоком с номерами строк.

import { isContentText, isPlainObject } from './contentResult';

/**
 * `argumentsRaw` → `{ fields, blocks }` для `<ArgumentListView>`, либо null.
 *
 * Границу «строка или блок» задаёт `isContentText` — тот же предикат, что делит
 * `content` и `scalar` в результате. Порог у длинного текста один на модалку:
 * два разъехались бы на первой же правке.
 */
export const detectArgumentList = (argumentsRaw) => {
  if (typeof argumentsRaw !== 'string' || !argumentsRaw.trim()) return null;

  let parsed;
  try {
    parsed = JSON.parse(argumentsRaw);
  } catch {
    return null;
  }
  if (!isPlainObject(parsed)) return null;

  const entries = Object.entries(parsed);
  if (entries.length === 0) return null;

  const fields = [];
  const blocks = [];
  for (const [key, value] of entries) {
    // Строка идёт как есть, остальное — через JSON: `null`, число и список так
    // и останутся тем, что модель прислала, а не превратятся в «[object Object]».
    const inline = typeof value === 'string' ? value : JSON.stringify(value);
    if (isContentText(inline)) {
      // Развёрнутый JSON — только в блоке: длинный список из десятка путей
      // читается по строкам, а в строку он всё равно не поместился бы.
      const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
      const lines = text.split('\n');
      blocks.push({ key, lines, chars: text.length });
    } else {
      fields.push({ key, value: inline });
    }
  }
  return { fields, blocks };
};
