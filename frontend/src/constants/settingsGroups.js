/**
 * Группы страниц «Настройки» и «Админ-панель» — второй сегмент пути
 * (`/settings/<группа>`, `/admin/<группа>`).
 *
 * Первая группа каждого раздела — дефолтная: в адрес она не пишется, голый
 * `/settings` означает именно её. Порядок ключей здесь — порядок строк в левом
 * списке.
 */

export const SETTINGS_GROUP = {
  PHRASES: 'phrases',
  MODELS: 'models',
  SEARCH: 'search',
  TOOLS: 'tools',
  SCRIPTS: 'scripts',
};

export const ADMIN_GROUP = {
  INDEX: 'index',
  BULK: 'bulk',
  SYSTEM: 'system',
};

const GROUPS = {
  settings: Object.values(SETTINGS_GROUP),
  admin: Object.values(ADMIN_GROUP),
};

/** Дефолтная группа раздела `view` ('settings' | 'admin'). */
export function defaultGroup(view) {
  return GROUPS[view][0];
}

/**
 * Группа в той форме, в какой её хранит адрес: известная и не дефолтная — как
 * есть, иначе пусто. Неизвестная (ручная правка адреса, группа, которой больше
 * нет) открывает дефолтную, а не пустую страницу.
 */
export function normalizeGroup(view, group) {
  return GROUPS[view].includes(group) && group !== defaultGroup(view) ? group : '';
}
