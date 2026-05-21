import React, { useState } from 'react';

const ChatList = ({ chats, activeChatId, onSelectChat, onNewChat, onDeleteChat, onRenameChat, onNewJiraChat }) => {
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
    <div className="chat-list">
      <button onClick={onNewChat} className="new-chat-button">
        + Новый чат
      </button>
      {onNewJiraChat && (
        <button onClick={onNewJiraChat} className="new-jira-chat-button">
          🔗 JIRA чат
        </button>
      )}
      <ul>
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
                    className="rename-chat-button"
                    onClick={(e) => {
                      e.stopPropagation();
                      startEdit(chat.id, chat.title);
                    }}
                    title="Переименовать"
                  >
                    ✎
                  </button>
                  {chats.length > 1 && (
                    <button
                      className="delete-chat-button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onDeleteChat(chat.id);
                      }}
                      title="Удалить"
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
    </div>
  );
};

export default ChatList;
