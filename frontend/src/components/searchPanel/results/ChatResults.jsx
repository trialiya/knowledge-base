import { useTranslation } from 'react-i18next';
import { IconMessage } from '@/icons/index';
import { chatPath } from '@/navigation/urlScheme';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import ResultGroup from './ResultGroup';

/** Автор сообщения: ключ перевода на роль, которую отдаёт бэкенд. */
const ROLE_KEY = { USER: 'chats.roleUser', ASSISTANT: 'chats.roleAssistant', TOOL: 'chats.roleTool' };

/**
 * Совпадения в чатах: карточка на чат, внутри — сообщения по времени.
 *
 * Чат мог попасть в выдачу и по названию темы, без единого совпавшего
 * сообщения, — такую карточку помечает `titleMatched`, иначе она выглядела бы
 * найденной непонятно за что.
 */
const ChatResults = ({ result, query, onOpenChat }) => {
  const { t, i18n } = useTranslation('search');

  return result.chats.map((chat) => (
    <ResultGroup
      key={chat.conversationId}
      icon={<IconMessage size={14} />}
      title={chat.topic ? highlightSubstring(chat.topic, query) : t('chats.untitled')}
      href={chatPath(chat.conversationId)}
      onOpen={() => onOpenChat(chat.conversationId)}
      meta={chat.updatedAt ? new Date(chat.updatedAt).toLocaleDateString(i18n.language) : null}
      subtitle={chat.titleMatched && <span className="search-group__badge">{t('chats.titleMatched')}</span>}
      rows={chat.messages.map((message) => ({
        key: message.id,
        node: (
          <>
            <span className="search-line__where">
              {t(ROLE_KEY[message.role] || 'chats.roleUser')}
              {message.createdAt && (
                <time className="search-line__time">
                  {new Date(message.createdAt).toLocaleTimeString(i18n.language, {
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </time>
              )}
            </span>
            <span className="search-line__text">{highlightSubstring(message.snippet || '', query)}</span>
          </>
        ),
      }))}
    />
  ));
};

export default ChatResults;
