import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

// Словарь каждого языка — отдельный чанк, а не часть стартового бандла:
// показанный язык грузится на старте, второй — только если на него переключатся.
// Языку-фолбэку это не помогает: i18next тянет его вместе с текущим, поэтому
// английский интерфейс стоит русского словаря сверху, а русский — только своего.
const bundles = {
  ru: () => import('./locales/ru'),
  en: () => import('./locales/en'),
};

// Минимальный backend вместо статического `resources`: i18next зовёт read() на
// каждый (язык, неймспейс), а промис динамического импорта кешируется — за все
// пять неймспейсов языка уходит один запрос за чанком.
const lazyBundles = {
  type: 'backend',
  read(language, namespace, callback) {
    const load = bundles[language];
    if (!load) {
      callback(new Error(`Нет словаря для языка ${language}`), null);
      return;
    }
    load().then(
      (mod) => callback(null, mod.default[namespace]),
      (err) => callback(err, null),
    );
  },
};

/** Резолвится, когда словарь стартового языка загружен: до этого t() вернёт ключи. */
export const i18nReady = i18n
  .use(lazyBundles)
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    fallbackLng: 'ru',
    supportedLngs: ['ru', 'en'],
    ns: ['common', 'chat', 'knowledgeBase', 'settings', 'files'],
    defaultNS: 'common',
    fallbackNS: 'common',
    interpolation: { escapeValue: false },
    detection: {
      order: ['localStorage', 'navigator'],
      lookupLocalStorage: 'kb-lang',
      caches: ['localStorage'],
    },
    react: { useSuspense: false },
  });

// index.html жёстко объявляет lang="ru" (значение до загрузки JS, совпадает с fallbackLng).
// Без синхронизации английский интерфейс остаётся размеченным как русский: скринридер читает
// его с русской фонетикой, а браузер предлагает «перевести страницу».
const syncHtmlLang = (lng) => {
  if (lng) document.documentElement.lang = lng;
};
syncHtmlLang(i18n.resolvedLanguage);
i18n.on('languageChanged', syncHtmlLang);

export default i18n;
