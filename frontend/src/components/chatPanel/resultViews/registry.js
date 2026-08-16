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
import { detectRecordList } from './recordList';
import RecordListView from './RecordListView';
import { detectContentResult } from './contentResult';
import ContentResultView from './ContentResultView';
import { detectScalarResult } from './scalarResult';
import ScalarResultView from './ScalarResultView';

// Порядок от узкого к широкому: `content` ловит любой длинный текст, поэтому
// новые виды добавляются ВЫШЕ него. Это же и способ уточнять уже работающий
// отбор: `tree` встанет над `recordList` и заберёт у него `getTreeSkeleton`,
// который сейчас честно, но плоско показан списком.
//
// `scalar` формой не пересекается ни с чем (короткая однострочная строка —
// дополнение к `isContentText`), поэтому стоит последним просто как самый
// мелкий случай.
const VIEWS = [
  { id: 'diff', detect: detectDiffResult, View: DiffResultView },
  { id: 'recordList', detect: detectRecordList, View: RecordListView },
  { id: 'content', detect: detectContentResult, View: ContentResultView },
  { id: 'scalar', detect: detectScalarResult, View: ScalarResultView },
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
