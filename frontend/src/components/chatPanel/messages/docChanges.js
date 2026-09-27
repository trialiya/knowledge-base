// Сводка документных правок одного ответа ИИ — по строке на документ для
// `DocChangeBlock`. Отдельно от компонента, чтобы свёртку можно было проверить
// без рендера.

import { getDocChangeRef } from './toolMeta';
import { TOOL_STATUS } from '@/constants/toolStatus';

// Меняет ли вызов описание документа, т.е. поднимает ли он descriptionVersion.
// `updateDocument` без `description` — это переименование: версия в его ответе
// уже была до ответа ИИ. Остальные мутации пишут содержимое.
const bumpsDescription = (tc) => {
  if (tc.name !== 'updateDocument') return true;
  const args = tc.arguments;
  return args == null || typeof args !== 'object' || args.description != null;
};

/**
 * Вызовы ответа (в хронологическом порядке) → строки блока изменений:
 * `{ id, title, created, descriptionVersion, baseVersion }`.
 *
 * - `created` — документ создан этим ответом; последующие правки того же
 *   документа эту пометку не снимают.
 * - `descriptionVersion` — последняя версия описания, до которой дошёл ответ.
 * - `baseVersion` — версия описания до ответа: с ней `HistoryModal` сравнивает
 *   `descriptionVersion`, так что видна сумма всех правок ответа, а не только
 *   последняя. У созданного документа — 0: версии «до» не было. `null` — ответ
 *   описание не менял (например, только переименовал), сравнивать нечего.
 * - `title` — последний известный заголовок: переименование позже создания
 *   должно быть видно.
 *
 * Упавшие вызовы пропускаются: они не создали версии и не трогают уже учтённые
 * успешные правки того же документа.
 */
export const collectDocChanges = (toolCalls) => {
  const byId = new Map();
  for (const tc of toolCalls || []) {
    const ref = getDocChangeRef(tc);
    if (!ref || ref.status === TOOL_STATUS.ERROR) continue;
    const title = ref.title || tc.arguments?.title || tc.arguments?.name || null;
    const created = ref.action === 'createDocument';
    const version = ref.descriptionVersion;
    let cur = byId.get(ref.id);
    if (!cur) {
      let startVersion = null;
      if (created) startVersion = 0;
      else if (version != null) startVersion = bumpsDescription(tc) ? version - 1 : version;
      cur = { id: ref.id, title, created, descriptionVersion: version, startVersion };
      byId.set(ref.id, cur);
      continue;
    }
    if (created) {
      cur.created = true;
      cur.startVersion = 0;
    }
    if (version != null && version > (cur.descriptionVersion ?? 0)) cur.descriptionVersion = version;
    if (cur.startVersion == null && version != null) cur.startVersion = bumpsDescription(tc) ? version - 1 : version;
    if (title) cur.title = title;
  }
  return [...byId.values()].map(({ startVersion, ...c }) => ({
    ...c,
    baseVersion:
      startVersion != null && c.descriptionVersion != null && startVersion < c.descriptionVersion ? startVersion : null,
  }));
};
