import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

import ruCommon from './locales/ru/common.json';
import ruChat from './locales/ru/chat.json';
import ruKnowledgeBase from './locales/ru/knowledgeBase.json';
import ruSettings from './locales/ru/settings.json';
import ruFiles from './locales/ru/files.json';
import enCommon from './locales/en/common.json';
import enChat from './locales/en/chat.json';
import enKnowledgeBase from './locales/en/knowledgeBase.json';
import enSettings from './locales/en/settings.json';
import enFiles from './locales/en/files.json';

const resources = {
  ru: { common: ruCommon, chat: ruChat, knowledgeBase: ruKnowledgeBase, settings: ruSettings, files: ruFiles },
  en: { common: enCommon, chat: enChat, knowledgeBase: enKnowledgeBase, settings: enSettings, files: enFiles },
};

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
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

export default i18n;
