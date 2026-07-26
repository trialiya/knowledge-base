/**
 * Вкладки ПРАВОЙ панели детали базы знаний (в адресе — `?right=<key>`).
 *
 * Раньше это были вкладки центра (summary/content/contents/attachments) и жили
 * в `?tab=`. После перехода на общую раскладку центр занят самим содержимым
 * (редактором), а всё «о документе» переехало вправо — поэтому вкладки
 * `content` больше нет, а остальные ключи стали ключами правой панели.
 * Старые ссылки `?tab=` разбираются в useAppNavigation.
 *
 * Здесь только вкладки базы знаний; общие для всех разделов — в `rightTabs.js`.
 */
import { RIGHT_TAB } from './rightTabs';

export const DOC_TAB = {
  SUMMARY: 'summary',
  CONTENTS: 'contents',
  // Сквозные ключи берём из RIGHT_TAB — те же вкладки есть в чате и файлах,
  // и `?right=info` должен означать одно и то же во всех разделах.
  ATTACHMENTS: RIGHT_TAB.ATTACHMENTS,
  INFO: RIGHT_TAB.INFO,
};

export const ATTACHMENT_VIEW_MODE = {
  CONTENT: 'content',
  SUMMARY: 'summary',
};
