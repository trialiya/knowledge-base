// ─── Что можно набрать со слэша ──────────────────────────────────────────────
// Слэш первым символом начинает и команду чату (run/chatCommands.js), и триггер
// чипа (chipTriggers.js). Реестра два, и правильно, что два: команда уходит чату
// целиком, а триггер разворачивается в токен ещё до отправки. Но у каретки они
// один список — два выпадающих списка на один слэш перекрывали бы друг друга.
//
// Здесь только правило «что показать на набранное»; кто из двух дальше владеет
// кареткой, решает ChipEditor.

import { COMMANDS } from '../run/chatCommands';
import { TRIGGER_TYPES } from './chipTriggers';

/** Раздел списка: команда чату или вставка в сообщение. */
export const SLASH_KIND = { COMMAND: 'command', INSERT: 'insert' };

const ENTRIES = [
  ...COMMANDS.map(({ name, triggers, args }) => ({ kind: SLASH_KIND.COMMAND, name, triggers, args: !!args })),
  ...Object.values(TRIGGER_TYPES).map(({ type, triggers }) => ({
    kind: SLASH_KIND.INSERT,
    name: type,
    triggers,
    args: false,
  })),
];

/**
 * Пункты списка для набранного текста — или null, если списку здесь не место.
 *
 * Место у него одно: поле состоит из одного набранного слэш-префикса и ничего
 * больше. Команда обязана начинать сообщение, а обещать её посреди текста
 * значило бы обещать несуществующее; пробел после триггера — уже не префикс, там
 * работает разбор отправки либо поиск чипа.
 *
 * Набранное целиком, без остатка — тоже не место: открытый список съел бы Enter
 * у отправки и закрыл бы собой строку про уже набранную команду. Полный триггер
 * чипа в этот момент подхватывает поиск чипа, полную команду — подсказка над
 * полем. Правило это про ЛЮБОЙ совпавший целиком триггер, а не про «дополнять
 * нечего»: `/compact` — законченная команда, хотя `/compact-1` её и продолжает,
 * и выбирать за пользователя более длинную по Enter список не вправе. До неё
 * остаётся один символ (`/compact-`), и это дешевле отнятого Enter.
 *
 * `trigger` — тот синоним, что совпал с набранным: набравший `/сж` получит
 * `/сжать`, а не каноническое `/compact`. Подменять набранное нельзя, а знать
 * про второй синоним стоит — он уезжает в `alt`.
 */
export function slashMenuItems(text) {
  if (!/^\/\S*$/.test(text || '')) return null;
  const typed = text.toLowerCase();
  if (ENTRIES.some((entry) => entry.triggers.includes(typed))) return [];
  const items = [];
  for (const entry of ENTRIES) {
    const trigger = entry.triggers.find((t) => t.startsWith(typed) && t !== typed);
    if (trigger) items.push({ ...entry, trigger, alt: entry.triggers.filter((t) => t !== trigger) });
  }
  return items;
}
