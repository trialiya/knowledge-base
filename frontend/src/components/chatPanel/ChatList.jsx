import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconEdit, IconTrash } from '../../icons';

/**
 * Список чатов — содержимое левой панели рабочей области.
 *
 * Кнопка «Новый чат» и поиск по чатам живут не здесь, а в слотах
 * WorkspaceLayout (action / toolbar): их место в шапке панели общее для всех
 * разделов, поэтому список отвечает только за сами чаты. Вид строки — общий
 * .ws-item (common/sidePanel.css), как у деревьев базы знаний и файлов.
 */
const ChatList = ({ chats, activeChatId, onSelectChat, onDeleteChat, onRenameChat }) => {
  const { t } = useTranslation('chat');
  const [editingId, setEditingId] = useState(null);
  const [editValue, setEditValue] = useState('');

  const startEdit = (id, currentTitle) => {
    setEditingId(id);
    setEditValue(currentTitle);
  };

  const cancelEdit = () => {
    setEditingId(null);
    setEditValue('');
  };

  const saveEdit = (id) => {
    if (editValue.trim() && onRenameChat) {
      onRenameChat(id, editValue.trim());
    }
    cancelEdit();
  };

  const handleKeyDown = (e, id) => {
    if (e.key === 'Enter') {
      saveEdit(id);
    } else if (e.key === 'Escape') {
      cancelEdit();
    }
  };

  // Пустой список означает только «ещё грузим»: после загрузки в нём всегда есть
  // хотя бы черновик нового чата (см. ChatWindow), поэтому «чатов нет» здесь
  // было бы неправдой.
  if (chats.length === 0) {
    return <div className="ws-hint">{t('common:loading')}</div>;
  }

  return (
    <ul className="ws-list">
      {chats.map((chat) => (
        <li
          key={chat.id}
          className={`ws-item${chat.id === activeChatId ? ' ws-item--active' : ''}`}
          onClick={() => onSelectChat(chat.id)}
        >
          {editingId === chat.id ? (
            <input
              type="text"
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              onBlur={() => saveEdit(chat.id)}
              onKeyDown={(e) => handleKeyDown(e, chat.id)}
              autoFocus
              className="ws-item__edit"
              onClick={(e) => e.stopPropagation()}
            />
          ) : (
            <>
              <span className="ws-item__label">{chat.title}</span>
              <span className="ws-item__actions">
                <button
                  type="button"
                  className="icon-btn ws-item__action"
                  onClick={(e) => {
                    e.stopPropagation();
                    startEdit(chat.id, chat.title);
                  }}
                  title={t('list.rename')}
                  aria-label={t('list.rename')}
                >
                  <IconEdit size={12} />
                </button>
                {chats.length > 1 && (
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
                )}
              </span>
            </>
          )}
        </li>
      ))}
    </ul>
  );
};

export default ChatList;
