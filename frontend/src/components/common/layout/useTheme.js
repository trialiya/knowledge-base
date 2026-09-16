import { useCallback, useSyncExternalStore } from 'react';
import { STORAGE_KEY_THEME } from '@/constants/storage';

/** Что можно выбрать. 'system' — идти за настройкой ОС, а не за своей. */
export const THEMES = ['system', 'light', 'dark'];

const DEFAULT_THEME = 'system';
const DARK_QUERY = '(prefers-color-scheme: dark)';

function readStored() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY_THEME);
    return THEMES.includes(saved) ? saved : DEFAULT_THEME;
  } catch {
    return DEFAULT_THEME;
  }
}

/** Тёмная ли тема у системы. В окружении без matchMedia — нет. */
function systemPrefersDark() {
  return typeof window !== 'undefined' && !!window.matchMedia?.(DARK_QUERY).matches;
}

/**
 * Выбор превращается в одну из двух настоящих тем ЗДЕСЬ, а не в CSS.
 *
 * Медиазапрос в CSS заставил бы писать тёмные значения дважды — под
 * `@media (prefers-color-scheme: dark)` для «системной» и под атрибутом для
 * выбранной руками, — а препроцессора, который свёл бы их в одно место, в
 * проекте нет. Два списка по семьдесят ролей разошлись бы на первой же правке.
 */
function resolve(choice) {
  return choice === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : choice;
}

/* ── Выбор темы — один на всё приложение ──────────────────────────────────
   Не состояние компонента: тему спрашивает и меню в шапке, и (позже) всё, что
   рисует не средствами CSS. Значение живёт в модуле, его раздают через
   useSyncExternalStore — так же, как ширину левой панели. */

let current = readStored();
/* Снимок для useSyncExternalStore — строка «выбор|разрешённое». Именно пара, а
   не один выбор: при «как в системе» закат меняет картинку, не трогая выбора, и
   по одному ему React не увидел бы, что перерисовывать есть что. Строка (а не
   объект) сравнивается по значению — новый снимок на каждый вызов геттера
   уводил бы рендер в цикл. */
let snapshot = '';
const listeners = new Set();

/**
 * Тему рисует атрибут на корне документа — его видят все стили сразу.
 * Светлая пишется тоже, а не снимается: явно выбранная светлая и «ещё не
 * решили» отличаются только здесь, а выглядят одинаково.
 */
function applyAttr(choice) {
  const resolved = resolve(choice);
  document.documentElement.dataset.theme = resolved;
  snapshot = `${choice}|${resolved}`;
}

function commit(choice) {
  const next = THEMES.includes(choice) ? choice : DEFAULT_THEME;
  const before = snapshot;
  applyAttr(next);
  if (next !== current) {
    current = next;
    try {
      localStorage.setItem(STORAGE_KEY_THEME, next);
    } catch {
      /* ignore quota / private-mode errors */
    }
  }
  if (snapshot !== before) listeners.forEach((notify) => notify());
}

function subscribe(notify) {
  listeners.add(notify);
  return () => listeners.delete(notify);
}

applyAttr(current); // до первого кадра: иначе он будет светлым и мигнёт

/* Системная тема меняется на лету (закат, расписание в ОС). Слушаем всегда, а
   не только при выборе «как в системе»: подписка одна на модуль, и включать её
   по условию значило бы снимать и ставить заново на каждом переключении. */
if (typeof window !== 'undefined' && window.matchMedia) {
  window.matchMedia(DARK_QUERY).addEventListener('change', () => {
    if (current !== 'system') return;
    const before = snapshot;
    applyAttr(current);
    if (snapshot !== before) listeners.forEach((notify) => notify());
  });
}

/**
 * ТОЛЬКО ДЛЯ ТЕСТОВ. `current` живёт на уровне модуля — общий на все экземпляры
 * хука, как и в приложении, из-за чего тесты в одном файле становятся
 * order-dependent: оставивший тему не-дефолтной портит следующий.
 * Вызывать из beforeEach/afterEach конкретного test-файла, не из кода приложения.
 */
export function resetThemeForTests() {
  current = DEFAULT_THEME;
  applyAttr(current);
  try {
    localStorage.removeItem(STORAGE_KEY_THEME);
  } catch {
    /* ignore quota / private-mode errors */
  }
}

/**
 * Выбранная тема оформления и способ её сменить.
 *
 * Возвращает и сам выбор (`theme`: 'system' | 'light' | 'dark'), и то, во что
 * он разрешился (`resolved`: 'light' | 'dark'): меню отмечает галочкой первое,
 * а показать, что сейчас на экране, может только второе.
 *
 * Хранится в localStorage, а не в URL: в адресе живёт то, чем осмысленно
 * поделиться ссылкой, а тема — личная настройка рабочего места. По той же
 * причине она не уходит на бэкенд: это настройка машины, а не учётной записи,
 * и на рабочем компьютере человек вправе хотеть не то же, что на домашнем.
 */
export default function useTheme() {
  const [theme, resolved] = useSyncExternalStore(subscribe, () => snapshot).split('|');
  return { theme, resolved, setTheme: useCallback((next) => commit(next), []) };
}
