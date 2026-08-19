import ChatInfo from './ChatInfo';
import AttachmentPanel from '../common/AttachmentPanel';
import { IconInfo, IconPaperclip } from '../../icons';
import { RIGHT_TAB } from '../../constants/rightTabs';
import { OWNER_TYPE } from '../../constants/ownerType';
import { DRAFT_CHAT_ID } from '../../constants/storage';

/**
 * Вкладки правой панели чата: метаданные и вложения.
 *
 * Функция-сборщик, а не компонент — как buildDetailTabs у базы знаний:
 * WorkspaceLayout принимает вкладки массивом ({ key, label, icon, badge,
 * content }) и сам решает, показать их рельсом или раскрытой панелью.
 */
export function buildChatTabs({
  t,
  chatId,
  infoChat,
  modelLabel,
  modeLabel,
  projectLabel,
  attachmentCount,
  onAttachmentCountChange,
  attachmentsRefreshSignal,
  onAttachmentDeleted,
}) {
  return [
    {
      // «Инфо» первой — так же, как в базе знаний и файлах.
      key: RIGHT_TAB.INFO,
      label: t('tabs.info'),
      icon: <IconInfo size={16} />,
      content: <ChatInfo chat={infoChat} modelLabel={modelLabel} modeLabel={modeLabel} projectLabel={projectLabel} />,
    },
    {
      key: RIGHT_TAB.ATTACHMENTS,
      label: t('window.attachments'),
      icon: <IconPaperclip size={16} />,
      badge: attachmentCount,
      // Черновика на бэке ещё нет, и загружать в него нельзя: «new» — выдумка фронта,
      // а загрузка вложения заводит чат с тем id, который ей дали. Чат рождается с
      // настоящим UUID — из композера (скрепка) или с первого сообщения.
      content:
        !chatId || chatId === DRAFT_CHAT_ID ? (
          <p className="chat-empty-note">{chatId ? t('window.attachmentsInDraft') : t('window.selectChat')}</p>
        ) : (
          <AttachmentPanel
            key={chatId}
            ownerType={OWNER_TYPE.CHAT}
            ownerId={chatId}
            onCountChange={onAttachmentCountChange}
            refreshSignal={attachmentsRefreshSignal}
            onDeleted={onAttachmentDeleted}
          />
        ),
    },
  ];
}
