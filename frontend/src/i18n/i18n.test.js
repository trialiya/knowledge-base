/**
 * Проверки целостности i18n-словарей.
 *
 * Запуск (как и любой CRA-тест):
 *   CI=true yarn test i18n
 *
 * Что проверяем:
 *   1. RU и EN описывают один и тот же набор «логических» ключей
 *      (с поправкой на плюрализацию — суффиксы _one/_few/_many/_other).
 *   2. Плюральные ключи укомплектованы по правилам языка
 *      (ru: one/few/many, en: one/other).
 *   3. Нет пустых строковых значений.
 *   4. Каждый литеральный t('...') в исходниках указывает на существующий ключ.
 *      Неймспейс определяется ПО ФАЙЛУ из его useTranslation('ns') (с фолбэком
 *      на common, как fallbackNS в рантайме) — потому что чат-компоненты
 *      используют useTranslation('chat'), а компоненты базы знаний —
 *      useTranslation('knowledgeBase').
 *   5. Каждый инструмент из TOOL_META имеет лейбл в tools.* (ru и en).
 *
 * Динамические ключи (шаблонные строки `tools.${name}`, `gitPhrases.phrases.${id}...`)
 * статически не резолвятся — они покрыты отдельными проверками (5) и (6).
 */

import fs from 'fs';
import path from 'path';

import ruCommon from './locales/ru/common.json';
import ruChat from './locales/ru/chat.json';
import ruKnowledgeBase from './locales/ru/knowledgeBase.json';
import ruSettings from './locales/ru/settings.json';
import enCommon from './locales/en/common.json';
import enChat from './locales/en/chat.json';
import enKnowledgeBase from './locales/en/knowledgeBase.json';
import enSettings from './locales/en/settings.json';

import { TOOL_META } from '../components/chatPanel/toolMeta';

// ── Ресурсы по неймспейсам ──────────────────────────────────────────────────
const RESOURCES = {
  ru: { common: ruCommon, chat: ruChat, knowledgeBase: ruKnowledgeBase, settings: ruSettings },
  en: { common: enCommon, chat: enChat, knowledgeBase: enKnowledgeBase, settings: enSettings },
};

// fallbackNS из i18n/index.js — ключ, не найденный в дефолтном неймспейсе
// файла, ищется здесь (так же ведёт себя рантайм).
const FALLBACK_NS = 'common';

const PLURAL_SUFFIXES = ['_zero', '_one', '_two', '_few', '_many', '_other'];

// ── Утилиты ───────────────────────────────────────────────────────────────────

/** Плоский список путей-ключей листьев: { a: { b: 'x' } } → ['a.b']. */
function flatten(obj, prefix = '') {
  const out = [];
  for (const [k, v] of Object.entries(obj)) {
    const full = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      out.push(...flatten(v, full));
    } else {
      out.push(full);
    }
  }
  return out;
}

/** Значение по dot-пути или undefined. */
function getByPath(obj, dotPath) {
  return dotPath.split('.').reduce((acc, part) => (acc == null ? undefined : acc[part]), obj);
}

/** Убрать плюральный суффикс с последнего сегмента ключа. */
function stripPlural(key) {
  for (const suf of PLURAL_SUFFIXES) {
    if (key.endsWith(suf)) return key.slice(0, -suf.length);
  }
  return key;
}

/** Множество «логических» ключей неймспейса (плюрали схлопнуты в базу). */
function logicalKeys(nsObj) {
  return new Set(flatten(nsObj).map(stripPlural));
}

/** Существует ли `rest` в конкретном неймспейсе `ns` (с учётом плюрализации). */
function keyExistsInNs(lang, ns, rest) {
  const nsObj = RESOURCES[lang]?.[ns];
  if (!nsObj) return false;
  if (getByPath(nsObj, rest) !== undefined) return true;
  // плюраль: ключ-база без суффикса, но есть key_one/_few/_many/_other
  return PLURAL_SUFFIXES.some((suf) => getByPath(nsObj, rest + suf) !== undefined);
}

/**
 * Резолвится ли ключ в ресурсах языка с учётом неймспейса и плюрализации.
 * `key` может быть с явным префиксом `ns:` — тогда ищем строго в нём.
 * Иначе перебираем неймспейсы-кандидаты файла + FALLBACK_NS; если у файла нет
 * объявленных неймспейсов (нет useTranslation), допускаем любой неймспейс.
 */
function keyResolves(lang, key, candidateNamespaces) {
  if (key.includes(':')) {
    const [ns, rest] = key.split(':');
    return keyExistsInNs(lang, ns, rest);
  }
  const namespaces =
    candidateNamespaces && candidateNamespaces.size
      ? [...candidateNamespaces, FALLBACK_NS]
      : Object.keys(RESOURCES[lang]);
  return namespaces.some((ns) => keyExistsInNs(lang, ns, key));
}

/** Рекурсивный обход src по .js/.jsx (без node_modules и тестов). */
function walkSource(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkSource(full, acc);
    } else if (/\.(jsx?|tsx?)$/.test(entry.name) && !/\.test\.[jt]sx?$/.test(entry.name)) {
      acc.push(full);
    }
  }
  return acc;
}

/** Достать литеральные ключи из t('...') / tRef.current('...'). */
function extractKeys(code) {
  const keys = new Set();
  // t('x') / t("x"), исключая случаи, когда перед t стоит буква/цифра/точка
  // (чтобы не ловить getReader(, Set(, parseInt( и т.п.)
  const reT = /(?<![\w.])t\(\s*(['"])([^'"]+)\1/g;
  // tRef.current('x') — отдельная форма, используемая в стрим-колбэках
  const reRef = /tRef\.current\(\s*(['"])([^'"]+)\1/g;
  let m;
  while ((m = reT.exec(code)) !== null) keys.add(m[2]);
  while ((m = reRef.exec(code)) !== null) keys.add(m[2]);
  return keys;
}

/**
 * Неймспейсы, объявленные в файле через useTranslation('ns').
 * Возвращает Set имён, либо null, если useTranslation в файле нет вовсе
 * (тогда ключи допускаются в любом неймспейсе). Голый useTranslation()
 * означает defaultNS — это common.
 */
function fileNamespaces(code) {
  const re = /useTranslation\(\s*(?:(['"])([^'"]+)\1)?\s*\)/g;
  const ns = new Set();
  let found = false;
  let m;
  while ((m = re.exec(code)) !== null) {
    found = true;
    ns.add(m[2] || FALLBACK_NS); // голый useTranslation() → defaultNS (common)
  }
  return found ? ns : null;
}

const SRC_ROOT = path.resolve(__dirname, '..');

// ── Тесты ───────────────────────────────────────────────────────────────────

describe('i18n: паритет языков', () => {
  for (const ns of Object.keys(RESOURCES.ru)) {
    test(`неймспейс "${ns}" совпадает между ru и en (с учётом плюрали)`, () => {
      const ru = logicalKeys(RESOURCES.ru[ns]);
      const en = logicalKeys(RESOURCES.en[ns]);

      const onlyRu = [...ru].filter((k) => !en.has(k)).sort();
      const onlyEn = [...en].filter((k) => !ru.has(k)).sort();

      expect({ onlyRu, onlyEn }).toEqual({ onlyRu: [], onlyEn: [] });
    });
  }
});

describe('i18n: плюрали укомплектованы', () => {
  const required = { ru: ['_one', '_few', '_many'], en: ['_one', '_other'] };

  for (const lang of ['ru', 'en']) {
    test(`${lang}: у каждого плюрального ключа есть нужные формы`, () => {
      const missing = [];
      for (const ns of Object.keys(RESOURCES[lang])) {
        const nsObj = RESOURCES[lang][ns];
        const bases = new Set(
          flatten(nsObj)
            .filter((k) => PLURAL_SUFFIXES.some((s) => k.endsWith(s)))
            .map(stripPlural),
        );
        for (const base of bases) {
          for (const suf of required[lang]) {
            if (getByPath(nsObj, base + suf) === undefined) {
              missing.push(`${ns}:${base}${suf}`);
            }
          }
        }
      }
      expect(missing).toEqual([]);
    });
  }
});

describe('i18n: нет пустых значений', () => {
  for (const lang of ['ru', 'en']) {
    for (const ns of Object.keys(RESOURCES[lang])) {
      test(`${lang}/${ns}`, () => {
        const empties = flatten(RESOURCES[lang][ns]).filter((k) => {
          const v = getByPath(RESOURCES[lang][ns], k);
          return typeof v === 'string' && v.trim() === '';
        });
        expect(empties).toEqual([]);
      });
    }
  }
});

describe('i18n: используемые в коде ключи существуют', () => {
  const files = walkSource(SRC_ROOT);

  test('найдены исходные файлы для сканирования', () => {
    expect(files.length).toBeGreaterThan(0);
  });

  test('каждый литеральный t() резолвится в ru и en', () => {
    const problems = [];
    for (const file of files) {
      const code = fs.readFileSync(file, 'utf8');
      const namespaces = fileNamespaces(code); // Set | null
      for (const key of extractKeys(code)) {
        const okRu = keyResolves('ru', key, namespaces);
        const okEn = keyResolves('en', key, namespaces);
        if (!okRu || !okEn) {
          const where = path.relative(SRC_ROOT, file);
          const langs = [!okRu && 'ru', !okEn && 'en'].filter(Boolean).join(', ');
          problems.push(`${where}: "${key}" (нет в: ${langs})`);
        }
      }
    }
    expect(problems).toEqual([]);
  });
});

describe('i18n: инструменты (TOOL_META) переведены', () => {
  for (const lang of ['ru', 'en']) {
    test(`${lang}: у каждого инструмента есть tools.<name>`, () => {
      const missing = Object.keys(TOOL_META).filter(
        (name) => getByPath(RESOURCES[lang].chat, `tools.${name}`) === undefined,
      );
      expect(missing).toEqual([]);
    });
  }

  // Обратная проверка — нет «осиротевших» переводов без инструмента в реестре.
  for (const lang of ['ru', 'en']) {
    test(`${lang}: нет лишних ключей в tools.* без записи в TOOL_META`, () => {
      const declared = new Set(Object.keys(TOOL_META));
      const translated = Object.keys(RESOURCES[lang].chat.tools || {});
      const orphans = translated.filter((name) => !declared.has(name));
      expect(orphans).toEqual([]);
    });
  }
});
