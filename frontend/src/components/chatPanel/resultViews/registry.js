// Каталог представлений режима «Обзор»: упорядоченный список, первое подошедшее
// выигрывает. Свича по именам инструментов здесь нет и не будет — инструменты
// MCP-серверов приходят снаружи, их набор заранее неизвестен, и любой новый
// @Tool пришлось бы дописывать сюда руками. Отбор идёт по ФОРМЕ ответа.
//
// Каждый вид — пара из чистой функции детекта (`.js` рядом, покрыт тестами) и
// компонента. Детект получает уже разобранный ответ и возвращает готовые к
// отрисовке данные либо null; null у всех — модалка показывает только JSON и
// переключателя режимов не рисует вовсе.

import { detectDiffResult } from './diffResult';
import DiffResultView from './DiffResultView';
import { detectContentResult } from './contentResult';
import ContentResultView from './ContentResultView';

// Порядок от узкого к широкому: `content` ловит любой длинный текст, поэтому
// стоит последним — новые виды добавляются ВЫШЕ него, не ниже.
const VIEWS = [
  { id: 'diff', detect: detectDiffResult, View: DiffResultView },
  { id: 'content', detect: detectContentResult, View: ContentResultView },
];

/**
 * Ответ инструмента → вход детектора. Разбор один на все виды: `resultText`
 * бывает в десятки килобайт, и парсить его заново на каждый вид — заметная
 * работа впустую.
 *
 * `isJson: false` — инструмент вернул не JSON (голый текст); `parsed` тогда null,
 * и виду остаётся смотреть на `resultText`.
 */
export const parseResult = (resultText, argumentsRaw) => {
  if (typeof resultText !== 'string' || !resultText) return null;
  try {
    return { parsed: JSON.parse(resultText), isJson: true, resultText, argumentsRaw };
  } catch {
    return { parsed: null, isJson: false, resultText, argumentsRaw };
  }
};

/** Ответ вызова → `{ id, View, data }` для режима «Обзор», либо null. */
export const detectResultView = (resultText, argumentsRaw) => {
  const input = parseResult(resultText, argumentsRaw);
  if (!input) return null;
  for (const { id, detect, View } of VIEWS) {
    const data = detect(input);
    if (data) return { id, View, data };
  }
  return null;
};
