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
 * Список, который версия для модели опускает, когда он пуст: нет ключа — пустой
 * список, есть ключ не того типа — не та форма (`null`).
 */
const listOr = (value, isList) => {
  if (value === undefined) return [];
  return isList(value) ? value : null;
};

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

/**
 * Откуда взялся скрипт: у прогона по имени (`runSavedScript`) это файл проекта,
 * у написанного моделью — ничего, поле в ответе просто отсутствует. Имя и путь
 * обязательны вместе: шапка без одного из них говорит меньше, чем не говорит
 * ничего, и разбирать половину источника незачем.
 */
const scriptSource = (source) => {
  if (!isPlainObject(source)) return null;
  const name = str(source.name);
  const path = str(source.path);
  if (!name || !path) return null;
  const hasArgs = isPlainObject(source.args) && Object.keys(source.args).length > 0;
  return {
    kind: str(source.kind) || null,
    name,
    path,
    sha: str(source.sha) || null,
    args: hasArgs ? JSON.stringify(source.args) : null,
  };
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
  // Пустые `log`, `filesRead` и `edits` версия для модели не печатает, а `value`
  // печатает всегда, даже `null`, — по нему форма и узнаётся, когда списков нет.
  if (!('value' in parsed)) return null;
  const log = listOr(parsed.log, isStringArray);
  const filesRead = listOr(parsed.filesRead, isStringArray);
  const editList = listOr(parsed.edits, Array.isArray);
  if (!log || !filesRead || !editList) return null;

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
  const edits = editList.length > 0 ? detectDiffResult({ parsed: editList, isJson: true }) : null;
  if (editList.length > 0 && !edits) return null;

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
    // Id, под которым значение сохранено в чате: следующий скрипт читает его
    // через kb.result(id). Нет — значит, не сохраняли (упал, ничего не вернул).
    resultId: str(parsed.resultId) || null,
    // Необязателен: скрипт, написанный моделью, источника не называет.
    source: scriptSource(parsed.source),
    value: scriptValue(parsed.value),
    log,
    filesRead,
    // Только в версии для модели: ей уходят первые пути, остальные — числом.
    filesReadMore: Number.isInteger(parsed.filesReadMore) && parsed.filesReadMore > 0 ? parsed.filesReadMore : 0,
    // Только в версии для модели и только при resultLimit: до скольких элементов урезано значение.
    truncatedTo:
      isPlainObject(parsed.truncated) && Number.isInteger(parsed.truncated.limit) ? parsed.truncated.limit : null,
    edits,
    error,
  };
};
