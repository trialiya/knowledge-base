import { STORAGE_KEY_LAST_MODEL, STORAGE_KEY_LAST_MODE } from '../../constants/storage';

// ─── Модель и режим последней отправки ──────────────────────────────────────
// Ими стартует новый чат и подстраховывается отправка, когда у чата своих не
// задано. В рендере не участвуют (выбор показывает сам чат), а прочитать их
// нужно синхронно в момент отправки — поэтому обычный модуль, а не состояние:
// компонентное зеркало пришлось бы держать в рефе.
//
// localStorage — чтобы выбор пережил перезагрузку; запись в него может упасть
// по квоте, и это не повод терять значение в текущей сессии.

let lastModel = read(STORAGE_KEY_LAST_MODEL) || null;
let lastMode = read(STORAGE_KEY_LAST_MODE) || '';

function read(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* ignore quota errors */
  }
}

/** Модель последней отправки; null — отправок ещё не было. */
export function getLastModel() {
  return lastModel;
}

export function setLastModel(model) {
  lastModel = model;
  write(STORAGE_KEY_LAST_MODEL, model);
}

/** Режим последней отправки; '' — сознательный выбор «без режима». */
export function getLastMode() {
  return lastMode;
}

export function setLastMode(mode) {
  lastMode = mode || '';
  write(STORAGE_KEY_LAST_MODE, lastMode);
}
