/**
 * Вкладки ПРАВОЙ панели детали базы знаний (в адресе — `?right=<key>`).
 *
 * Раньше это были вкладки центра (summary/content/contents/attachments) и жили
 * в `?tab=`. После перехода на общую раскладку центр занят самим содержимым
 * (редактором), а всё «о документе» переехало вправо — поэтому вкладки
 * `content` больше нет, а остальные ключи стали ключами правой панели.
 * Старые ссылки `?tab=` разбираются в useAppNavigation.
 */
export const DOC_TAB = {
  SUMMARY: 'summary',
  CONTENTS: 'contents',
  ATTACHMENTS: 'attachments',
  INFO: 'info',
};

export const ATTACHMENT_VIEW_MODE = {
  CONTENT: 'content',
  SUMMARY: 'summary',
};
