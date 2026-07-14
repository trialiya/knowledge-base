import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModelSelector from './ModelSelector';
import { IconPaperclip, IconTrash, IconSearch } from '../../icons';

/**
 * Шапка активного чата: заголовок с инлайн-переименованием, селектор модели,
 * дата создания и кнопки поиска/вложений/удаления. Вынесено из ChatWindow —
 * состояние редактирования заголовка живёт здесь и не ре-рендерит оркестратор
 * на каждый keystroke.
 *
 * props:
 *   chat            — активный чат (null → шапка не рендерится)
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
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');

  if (!chat) return null;

  return (
    <div className="chat-header">
      <div className="chat-header-title">
        {editingTitle ? (
          <input
            className="chat-header-edit"
            value={titleDraft}
            autoFocus
            onChange={(e) => setTitleDraft(e.target.value)}
            onBlur={() => {
              if (titleDraft.trim()) onRename(chat.id, titleDraft.trim());
              setEditingTitle(false);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') e.target.blur();
              if (e.key === 'Escape') {
                setEditingTitle(false);
              }
            }}
          />
        ) : (
          <h3
            title={t('window.renameHint')}
            onClick={() => {
              setTitleDraft(chat.title);
              setEditingTitle(true);
            }}
          >
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
