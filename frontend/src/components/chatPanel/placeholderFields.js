// ─── Виджеты типизированных плейсхолдеров ────────────────────────────────────
// Одно место, где описано «чем заполняется тип»: каким запросом ищем, как рисуем
// строку результата и что уезжает в текст фразы. Добавить тип — это один объект
// здесь плюс строка в PLACEHOLDER_TYPES, а не ветки `type === 'file' ? …` по
// всему диалогу.
//
// file/document/commit кладут в текст не путь, а тот же чип-токен, что и ручные
// триггеры композера (`/file`, `/doc`), поэтому содержимое подставится при
// отправке само — см. expandTokensForSend.

import gitApi from '../../api/gitApi';
// i18n-инстанс напрямую: модуль не компонент, useTranslation здесь недоступен.
import i18n from '../../i18n';
import { searchDocsAsync } from './chipTriggers';
import { makeToken, makeDocToken, makeCommitToken } from './fileChips';

const SEARCH_LIMIT = 10;

/**
 * Спецификация поля по типу плейсхолдера.
 *
 *   kind      — какой виджет рисует диалог: 'text' | 'boolean' | 'search'
 *   inputType — тип <input> (только kind='text')
 *   search    — (query, signal) => Promise<item[]> (только kind='search')
 *   describe  — item => { key, icon, title, subtitle } для строки выдачи
 *   toValue   — выбранное значение => строка, которая встанет вместо плейсхолдера
 */
export const PLACEHOLDER_FIELDS = {
  string: { kind: 'text', inputType: 'text' },
  number: { kind: 'text', inputType: 'number' },
  boolean: {
    kind: 'boolean',
    toValue: (on) => i18n.t(on ? 'chat:phraseFill.booleanYes' : 'chat:phraseFill.booleanNo'),
  },
  file: {
    kind: 'search',
    search: (q, signal) => gitApi.searchFiles(q, SEARCH_LIMIT, signal),
    describe: (item) => ({ key: item.path, icon: '📄', title: item.name, subtitle: item.path }),
    toValue: (item) => makeToken(item.path),
  },
  document: {
    kind: 'search',
    search: (q, signal) => searchDocsAsync(q, signal),
    describe: (item) => ({ key: `doc-${item.id}`, icon: '📋', title: item.title, subtitle: `#${item.id}` }),
    toValue: (item) => makeDocToken(item.id, item.title),
  },
  commit: {
    kind: 'search',
    search: (q, signal) => gitApi.searchCommits(q, SEARCH_LIMIT, signal),
    describe: (item) => ({
      key: item.hash,
      icon: '🔖',
      title: item.message,
      subtitle: `${item.shortHash} · ${item.author}`,
    }),
    toValue: (item) => makeCommitToken(item.shortHash, item.message),
  },
};

/** Спецификация поля; неизвестный тип ведёт себя как строка (см. classify в phrasePlaceholders). */
export function fieldSpec(type) {
  return PLACEHOLDER_FIELDS[type] ?? PLACEHOLDER_FIELDS.string;
}

/**
 * Что стало со значением поля: заполнено ли оно, как показать его человеку и
 * какая строка встанет вместо плейсхолдера.
 *
 * Превью и подстановка расходятся у указателей: в текст уезжает чип-токен, а
 * читать его в превью незачем — там нужно название файла или тема коммита.
 * Решение «заполнено» одно на оба, иначе превью показывало бы значение, которое
 * подстановка молча выбросит.
 *
 * @returns {{ filled: boolean, preview?: string, text?: string }}
 */
export function resolveValue(type, value) {
  const spec = fieldSpec(type);
  if (spec.kind === 'boolean') {
    const text = spec.toValue(Boolean(value));
    return { filled: true, preview: text, text };
  }
  if (spec.kind === 'search') {
    if (!value) return { filled: false };
    return { filled: true, preview: spec.describe(value).title, text: spec.toValue(value) };
  }
  const text = typeof value === 'string' ? value.trim() : '';
  return text ? { filled: true, preview: text, text } : { filled: false };
}
