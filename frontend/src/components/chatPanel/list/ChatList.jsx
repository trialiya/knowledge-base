import { useTranslation } from 'react-i18next';
import useListNavigation from '../../common/search/useListNavigation';
import { IconTrash } from '../../../icons';

/**
 * Список чатов — содержимое левой панели рабочей области.
 *
 * Кнопка «Новый чат» и поиск по чатам живут не здесь, а в слотах
 * WorkspaceLayout (action / toolbar): их место в шапке панели общее для всех
 * разделов, поэтому список отвечает только за сами чаты. Вид строки — общий
 * .ws-item (common/sidePanel.css), клавиатура — общий useListNavigation.
 *
 * Переименование здесь не живёт — оно только в шапке открытого чата
 * (ChatHeader): один способ переименовать чат, а не два разных на экране.
 */
const ChatList = ({ chats, activeChatId, onSelectChat, onDeleteChat }) => {
  const { t } = useTranslation('chat');
  const handleListKeyDown = useListNavigation();

  // Пустой список означает только «ещё грузим»: после загрузки в нём всегда есть
  // хотя бы черновик нового чата (см. ChatWindow), поэтому «чатов нет» здесь
  // было бы неправдой.
  if (chats.length === 0) {
    return <div className="ws-hint">{t('common:loading')}</div>;
  }

  return (
    <ul className="ws-list" role="listbox" aria-label={t('list.title')} tabIndex={0} onKeyDown={handleListKeyDown}>
      {chats.map((chat) => (
        <li
          key={chat.id}
          data-ws-item
          role="option"
          aria-selected={chat.id === activeChatId}
          tabIndex={-1}
          className={`ws-item${chat.id === activeChatId ? ' ws-item--active' : ''}`}
          onClick={() => onSelectChat(chat.id)}
        >
          <span className="ws-item__label">{chat.title}</span>
          {(chats.length > 1 || chat.notFound) && (
            <span className="ws-item__actions">
              <button
                type="button"
                className="icon-btn icon-btn--danger ws-item__action"
                onClick={(e) => {
                  e.stopPropagation();
                  onDeleteChat(chat.id);
                }}
                title={t('list.delete')}
                aria-label={t('list.delete')}
              >
                <IconTrash size={12} />
              </button>
            </span>
          )}
        </li>
      ))}
    </ul>
  );
};

export default ChatList;
