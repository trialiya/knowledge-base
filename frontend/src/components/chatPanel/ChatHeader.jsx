import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import ModelSelector from './ModelSelector';
import { IconPaperclip, IconTrash, IconSearch } from '../../icons';

/**
 * Шапка активного чата: заголовок с инлайн-переименованием, селектор модели,
 * дата создания и кнопки поиска/вложений/удаления. Вынесено из ChatWindow —
 * состояние редактирования заголовка живёт здесь и не ре-рендерит оркестратор
 * на каждый keystroke.
 *
 * `chat` обязателен (не null) — условие рендера держит вызывающая сторона
 * (ChatWindow: activeChat && <ChatHeader …/>). Так внутри нет раннего return
 * между хуками и JSX, о который легко споткнуться, добавляя хук ниже него.
 *
 * props:
 *   chat            — активный чат (обязателен)
 *   modelConfig, modelOptions, selectedModelId — конфиг селектора модели
 *   isStreaming     — идёт генерация (блокирует селектор модели)
 *   canSearch       — доступен ли find-бар для этого чата
 *   searchOpen      — find-бар открыт (подсветка кнопки)
 *   onToggleSearch  — () => void
 *   attachPanelOpen — панель вложений открыта (подсветка кнопки)
 *   attachCount     — счётчик для бейджа на кнопке вложений
 *   onToggleAttach  — () => void
 *   onRename        — (chatId, title) => void
 *   onDelete        — (chatId) => void
 *   onModelChange   — (modelId) => void
 */
const ChatHeader = ({
  chat,
  modelConfig,
  modelOptions,
  selectedModelId,
  isStreaming,
  canSearch,
  searchOpen,
  onToggleSearch,
  attachPanelOpen,
  attachCount,
  onToggleAttach,
  onRename,
  onDelete,
  onModelChange,
}) => {
  const { t } = useTranslation('chat');
  // Черновик переименования. Храним ВМЕСТЕ с id чата, для которого оно началось:
  // активный чат может смениться до blur (выбор в поиске, синхронизация из другой
  // вкладки), и коммит по текущему chat.id переименовал бы другой чат текстом
  // первого. Коммит проверяет, что редактируемый чат всё ещё активен.
  const [editing, setEditing] = useState(null); // null | { id, draft }
  // Отмена по Escape: blur после него приходит с уже устаревшим замыканием,
  // поэтому флаг живёт в ref и гасится в самом обработчике blur.
  const cancelRef = useRef(false);

  // Смена активного чата сбрасывает незавершённое редактирование заголовка.
  const chatId = chat.id;
  useEffect(() => {
    setEditing(null);
    cancelRef.current = false;
  }, [chatId]);

  const commitRename = () => {
    const cancelled = cancelRef.current;
    cancelRef.current = false;
    if (!cancelled && editing && editing.draft.trim() && editing.id === chat.id) {
      onRename(editing.id, editing.draft.trim());
    }
    setEditing(null);
  };

  return (
    <div className="chat-header">
      <div className="chat-header-title">
        {editing ? (
          <input
            className="chat-header-edit"
            value={editing.draft}
            autoFocus
            onChange={(e) => setEditing((ed) => (ed ? { ...ed, draft: e.target.value } : ed))}
            onBlur={commitRename}
            onKeyDown={(e) => {
              if (e.key === 'Enter') e.target.blur();
              if (e.key === 'Escape') {
                cancelRef.current = true;
                e.target.blur();
              }
            }}
          />
        ) : (
          <h3 title={t('window.renameHint')} onClick={() => setEditing({ id: chat.id, draft: chat.title })}>
            {chat.title}
          </h3>
        )}
        {!chat.notFound && !chat.loadError && modelOptions.length > 0 && (
          <ModelSelector
            value={selectedModelId}
            defaultId={modelConfig.defaultModel.id}
            options={modelOptions}
            disabled={isStreaming}
            onChange={onModelChange}
          />
        )}
        {chat.createdAt && (
          <div className="chat-meta">{t('window.createdAt', { date: new Date(chat.createdAt).toLocaleString() })}</div>
        )}
      </div>
      {/* Search toggle button in header (Ctrl/Cmd+F) */}
      {canSearch && (
        <button
          className={`chat-header-search-btn ${searchOpen ? 'chat-header-search-btn--active' : ''}`}
          onClick={onToggleSearch}
          title={t('inChatSearch.open')}
        >
          <IconSearch size={14} />
        </button>
      )}
      {/* Attachment toggle button in header */}
      <button
        className={`chat-header-attachments-btn ${attachPanelOpen ? 'chat-header-attachments-btn--active' : ''}`}
        onClick={onToggleAttach}
        title={t('window.attachments')}
      >
        <IconPaperclip size={15} />
        {attachCount > 0 && <span className="attach-badge">{attachCount}</span>}
      </button>
      <button className="chat-header-delete" onClick={() => onDelete(chat.id)}>
        <IconTrash />
      </button>
    </div>
  );
};

export default ChatHeader;
