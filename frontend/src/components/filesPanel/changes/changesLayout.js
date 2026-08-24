import { STORAGE_KEY_CHANGES_LAYOUT } from '@/constants/storage';

/**
 * Память раскладки списка изменений. Модуль, а не состояние компонента:
 * панель перемонтируется при смене проекта (см. FilesPanel), а предпочтение
 * «плоско или деревом» проект переживает.
 *
 * Дефолт — плоский список: незакоммиченных файлов обычно единицы, и дерево из
 * них — это ступени каталогов вокруг трёх строк.
 */

/** Прочитать сохранённую раскладку. Битое значение — как несохранённое. */
export function readChangesFlat() {
  try {
    return localStorage.getItem(STORAGE_KEY_CHANGES_LAYOUT) !== 'tree';
  } catch {
    return true;
  }
}

/** Запомнить раскладку. Ошибки квоты игнорируем — это UI-настройка. */
export function saveChangesFlat(flat) {
  try {
    localStorage.setItem(STORAGE_KEY_CHANGES_LAYOUT, flat ? 'flat' : 'tree');
  } catch {
    /* ignore quota / private-mode errors */
  }
}
