import { useTranslation } from 'react-i18next';
import { IconMessage, IconSparkle, IconTool } from '@/icons/index';
import { chatUrl } from '@/navigation/urlScheme';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import ResultGroup from './ResultGroup';

/** Автор сообщения: ключ перевода и значок на роль, которую отдаёт бэкенд. */
const ROLE = {
  USER: { key: 'chats.roleUser', Icon: IconMessage },
  ASSISTANT: { key: 'chats.roleAssistant', Icon: IconSparkle },
  TOOL: { key: 'chats.roleTool', Icon: IconTool },
};

/**
 * Совпадения в чатах: карточка на чат, внутри — сообщения по времени.
 *
 * Сообщение — это подпись «кто и когда» и его совпадения строками под ней, как
 * у документа раздел и найденные в нём строки: подпись отвечает на «где это
 * сказано», а место под текст остаётся всё.
 *
 * Чат мог попасть в выдачу и по названию темы, без единого совпавшего
 * сообщения, — такую карточку помечает `titleMatched`, иначе она выглядела бы
 * найденной непонятно за что.
 *
 * Переход уносит запрос в адрес чата — там его подхватит find-бар и сядет на
 * совпадение. У карточки без совпавших сообщений запроса в адресе нет: бар
 * открылся бы с честным «0/0», а искать в этом чате нечего.
 *
 * Обе строки сообщения называют вдобавок и его само (`?msg=`): бар сядет
 * именно на то, по которому кликнули, а не на самое свежее совпадение.
 * Заголовок карточки сообщения не называет — «открыть чат» значит открыть его
 * на свежем.
 */
const ChatResults = ({ result, query, onOpenChat }) => {
  const { t, i18n } = useTranslation('search');

  return result.chats.map((chat) => {
    const find = chat.messages.length > 0 ? query : '';
    const rows = chat.messages.flatMap((message) => {
      const { key, Icon } = ROLE[message.role] || ROLE.USER;
      const target = { find, msg: message.id };
      const link = {
        href: chatUrl(chat.conversationId, target),
        onOpen: () => onOpenChat(chat.conversationId, target),
      };
      return [
        {
          key: `m:${message.id}`,
          heading: true,
          ...link,
          node: (
            <>
              <Icon size={11} />
              <span className="search-line__section">{t(key)}</span>
              {message.createdAt && (
                <time className="search-line__time">
                  {new Date(message.createdAt).toLocaleTimeString(i18n.language, {
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </time>
              )}
            </>
          ),
        },
        ...message.fragments.map((text, i) => ({
          key: `${message.id}:${i}`,
          ...link,
          node: (
            <>
              <span className="search-line__no" />
              <span className="search-line__text">{highlightSubstring(text, query)}</span>
            </>
          ),
        })),
      ];
    });

    return (
      <ResultGroup
        key={chat.conversationId}
        icon={<IconMessage size={14} />}
        title={chat.topic ? highlightSubstring(chat.topic, query) : t('chats.untitled')}
        href={chatUrl(chat.conversationId, { find })}
        onOpen={() => onOpenChat(chat.conversationId, { find })}
        meta={chat.updatedAt ? new Date(chat.updatedAt).toLocaleDateString(i18n.language) : null}
        subtitle={chat.titleMatched && <span className="search-group__badge">{t('chats.titleMatched')}</span>}
        rows={rows}
      />
    );
  });
};

export default ChatResults;
