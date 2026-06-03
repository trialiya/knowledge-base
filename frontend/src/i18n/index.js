import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

import ru from './locales/ru.json';
import en from './locales/en.json';

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      ru: { translation: ru },
      en: { translation: en },
    },
    fallbackLng: 'ru',
    supportedLngs: ['ru', 'en'],
    // Реакт сам экранирует — выключаем двойное экранирование i18next.
    interpolation: { escapeValue: false },
    detection: {
      // Сначала ранее выбранный язык, потом язык браузера.
      order: ['localStorage', 'navigator'],
      lookupLocalStorage: 'kb-lang',
      caches: ['localStorage'],
    },
    // Ресурсы забандлены — асинхронной загрузки нет, Suspense не нужен.
    react: { useSuspense: false },
  });

export default i18n;
