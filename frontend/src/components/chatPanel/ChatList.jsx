import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Список чатов — содержимое левой панели рабочей области.
 *
 * Кнопка «Новый чат» и поиск по чатам живут не здесь, а в слотах
 * WorkspaceLayout (action / toolbar): их место в шапке панели общее для всех
 * разделов, поэтому список отвечает только за сами чаты.
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

  return (
    <ul className="chat-list">
      {chats.map((chat) => (
        <li
          key={chat.id}
          className={`chat-list-item ${chat.id === activeChatId ? 'active' : ''}`}
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
              className="chat-edit-input"
              onClick={(e) => e.stopPropagation()}
            />
          ) : (
            <>
              <span className="chat-title">{chat.title}</span>
              <div className="chat-actions">
                <button
                  className="icon-btn chat-list-item__action"
                  onClick={(e) => {
                    e.stopPropagation();
                    startEdit(chat.id, chat.title);
                  }}
                  title={t('list.rename')}
                >
                  ✎
                </button>
                {chats.length > 1 && (
                  <button
                    className="icon-btn icon-btn--danger chat-list-item__action"
                    onClick={(e) => {
                      e.stopPropagation();
                      onDeleteChat(chat.id);
                    }}
                    title={t('list.delete')}
                  >
                    ✕
                  </button>
                )}
              </div>
            </>
          )}
        </li>
      ))}
    </ul>
  );
};

export default ChatList;
