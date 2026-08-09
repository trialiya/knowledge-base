/**
 * Словари языков грузятся по требованию (см. i18n/index.js). Проверяем ровно
 * это: стартовый язык доехал, второй в память не попал, а переключение его
 * подтягивает. Целостность самих словарей проверяет i18n.test.js.
 */

it('грузит словарь показанного языка и не тянет второй до переключения', async () => {
  localStorage.setItem('kb-lang', 'ru');
  const { default: i18n, i18nReady } = await import('./index');
  await i18nReady;

  expect(i18n.resolvedLanguage).toBe('ru');
  expect(i18n.hasResourceBundle('ru', 'settings')).toBe(true);
  expect(i18n.hasResourceBundle('en', 'settings')).toBe(false);
  expect(i18n.t('common:save')).toBe('Сохранить');

  await i18n.changeLanguage('en');

  expect(i18n.hasResourceBundle('en', 'settings')).toBe(true);
  expect(document.documentElement.lang).toBe('en');
});
