// Что режим «Обзор» показывает для формы «прогон скрипта» (`runScript`):
// статистика, лог, ошибка, прочитанные пути и пачка правок с diff'ами.
// Единственный составной результат во всём наборе — в JSON это простыня, где
// патчи, лог и числа лежат вперемешку.
//
// Разбор — по форме, а не по имени инструмента (см. registry.js).

import { isPlainObject } from './contentResult';
import { detectDiffResult } from './diffResult';
import { nonEmptyString as str } from './fieldValue';

// Плитки статистики в порядке вывода; всё, чего здесь нет, идёт следом в порядке
// самого ответа — у стороннего инструмента набор счётчиков может быть свой.
const STAT_ORDER = ['filesRead', 'bytesRead', 'calls', 'filesEdited', 'elapsedMs'];

const isStringArray = (value) => Array.isArray(value) && value.every((item) => typeof item === 'string');

/**
 * Объект счётчиков → плитки. Все значения обязаны быть числами: именно это и
 * делает объект статистикой, а не вложенным куском ответа.
 */
const statsOf = (stats) => {
  const keys = Object.keys(stats);
  if (keys.length === 0 || !keys.every((key) => Number.isFinite(stats[key]))) return null;

  const known = STAT_ORDER.filter((key) => key in stats);
  const rest = keys.filter((key) => !STAT_ORDER.includes(key));
  return [...known, ...rest].map((key) => ({ key, value: stats[key] }));
};

/** Возврат скрипта — что угодно, включая объект; в блок он идёт строкой. */
const scriptValue = (value) => {
  if (value === null || value === undefined) return null;
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
};

/**
 * Разобранный ответ вызова → данные для `<ScriptRunView>`, либо null.
 *
 * Упавший прогон — тоже результат, а не пустой экран: статистика и лог
 * показывают, докуда скрипт дошёл, и ровно за этим на них и смотрят.
 */
export const detectScriptRun = ({ parsed, isJson }) => {
  if (!isJson || !isPlainObject(parsed)) return null;
  if (!isPlainObject(parsed.stats)) return null;
  if (!isStringArray(parsed.log) || !isStringArray(parsed.filesRead)) return null;
  if (!Array.isArray(parsed.edits)) return null;

  // Вид ошибки обязателен, если ошибка вообще есть: половина смысла `ScriptError`
  // в том, что упавший прогон назван — синтаксис, лимит и таймаут чинятся
  // по-разному. Придумывать вид за бэкенд этот разбор не станет.
  const failed = parsed.error !== null && parsed.error !== undefined;
  if (failed && !(isPlainObject(parsed.error) && str(parsed.error.kind))) return null;

  const stats = statsOf(parsed.stats);
  if (!stats) return null;

  // Правки показывает вид diff'а — разбор один, а не второй такой же здесь.
  // Непустой список, который тем видом не разбирается, уводит в JSON весь ответ:
  // показать статистику и умолчать про правки хуже, чем показать всё сырым.
  const edits = parsed.edits.length > 0 ? detectDiffResult({ parsed: parsed.edits, isJson: true }) : null;
  if (parsed.edits.length > 0 && !edits) return null;

  const error = failed
    ? {
        kind: str(parsed.error.kind),
        message: str(parsed.error.message),
        line: Number.isInteger(parsed.error.line) ? parsed.error.line : null,
      }
    : null;

  return {
    stats,
    // Из ответа, а не из проекта чата: у runScript есть аргумент project, и
    // прогон мог читать соседний репозиторий — тогда filesRead и edits о нём.
    project: str(parsed.project) || null,
    value: scriptValue(parsed.value),
    log: parsed.log,
    filesRead: parsed.filesRead,
    edits,
    error,
  };
};
