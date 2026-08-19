import { STORAGE_KEY_PANELS } from '@/constants/storage';

/**
 * Память состояния боковых панелей рабочей области — ОТДЕЛЬНО ДЛЯ КАЖДОГО view.
 *
 * Источник правды для ТЕКУЩЕГО view — URL (см. useAppNavigation: `?left=0`,
 * `?right=<tab>`). Но в адресе живёт состояние только открытого раздела, поэтому
 * переключение вкладки «Чат → База знаний» иначе теряло бы то, как пользователь
 * настроил панели в базе знаний. Здесь мы помним раскладку каждого раздела и
 * подставляем её в URL при возврате (switchView).
 *
 * Хранилище — localStorage, чтобы раскладка переживала перезагрузку и для тех
 * разделов, которых нет в текущем адресе.
 */

/** Дефолт: левая панель раскрыта, правая — свёрнута (rightTab === null). */
export const DEFAULT_PANEL_STATE = { leftCollapsed: false, rightTab: null };

/** Прочитать всю карту `{ [view]: state }`. Битый JSON не должен ронять приложение. */
function readAll() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_PANELS);
    const parsed = raw ? JSON.parse(raw) : null;
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

/** Состояние панелей для раздела (или дефолт, если раздел ещё не настраивали). */
export function readPanelState(view) {
  const saved = readAll()[view];
  if (!saved || typeof saved !== 'object') return { ...DEFAULT_PANEL_STATE };
  return {
    leftCollapsed: !!saved.leftCollapsed,
    rightTab: typeof saved.rightTab === 'string' && saved.rightTab ? saved.rightTab : null,
  };
}

/** Запомнить состояние панелей раздела. Ошибки квоты игнорируем — это UI-настройка. */
export function savePanelState(view, state) {
  if (!view) return;
  try {
    const all = readAll();
    all[view] = { leftCollapsed: !!state.leftCollapsed, rightTab: state.rightTab || null };
    localStorage.setItem(STORAGE_KEY_PANELS, JSON.stringify(all));
  } catch {
    /* ignore quota / private-mode errors */
  }
}
