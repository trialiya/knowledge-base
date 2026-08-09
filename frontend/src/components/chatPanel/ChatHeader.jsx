import { useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconTrash, IconSearch } from '../../icons';

/**
 * Шапка активного чата: заголовок с инлайн-переименованием и кнопки
 * поиска/удаления. Вынесено из ChatWindow — состояние редактирования
 * заголовка живёт здесь и не ре-рендерит оркестратор на каждый keystroke.
 * Селекторы модели/режима переехали под поле ввода (ComposerToolbar), вложения —
 * в правую панель рабочей области (её тумблер общий для всех разделов).
 *
 * Оболочка шапки общая — .workspace__head (common/workspaceLayout.css). Даты
 * здесь нет намеренно: она (вместе с моделью, режимом и id) на вкладке «Инфо».
 *
 * `chat` обязателен (не null) — условие рендера держит вызывающая сторона
 * (ChatWindow: activeChat && <ChatHeader …/>). Так внутри нет раннего return
 * между хуками и JSX, о который легко споткнуться, добавляя хук ниже него.
 *
 * props:
 *   chat            — активный чат (обязателен)
 *   canSearch       — доступен ли find-бар для этого чата
 *   searchOpen      — find-бар открыт (подсветка кнопки)
 *   onToggleSearch  — () => void
 *   onRename        — (chatId, title) => void
 *   onDelete        — (chatId) => void
 */
const ChatHeader = ({ chat, canSearch, searchOpen, onToggleSearch, onRename, onDelete }) => {
  const { t } = useTranslation('chat');
  // Черновик переименования. Храним ВМЕСТЕ с id чата, для которого оно началось:
  // активный чат может смениться до blur (выбор в поиске, синхронизация из другой
  // вкладки), и коммит по текущему chat.id переименовал бы другой чат текстом
  // первого. Коммит проверяет, что редактируемый чат всё ещё активен.
  const [editing, setEditing] = useState(null); // null | { id, draft }
  // Отмена по Escape: blur после него приходит с уже устаревшим замыканием,
  // поэтому флаг живёт в ref. Гасится в обработчике blur и ещё раз при начале
  // нового переименования — Escape уносит поле из DOM, и blur может не прийти.
  const cancelRef = useRef(false);

  // Смена активного чата сбрасывает незавершённое редактирование заголовка.
  // Сброс идёт в рендере, а не эффектом: иначе один кадр показывал бы поле
  // ввода с черновиком от предыдущего чата.
  const [prevChatId, setPrevChatId] = useState(chat.id);
  if (prevChatId !== chat.id) {
    setPrevChatId(chat.id);
    setEditing(null);
  }

  const commitRename = () => {
    const cancelled = cancelRef.current;
    cancelRef.current = false;
    if (!cancelled && editing && editing.draft.trim() && editing.id === chat.id) {
      onRename(editing.id, editing.draft.trim());
    }
    setEditing(null);
  };

  return (
    <div className="workspace__head">
      {editing ? (
        <input
          className="workspace__head-edit chat-header__edit"
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
        <h3
          className="workspace__head-title chat-header__title"
          title={t('window.renameHint')}
          onClick={() => {
            cancelRef.current = false;
            setEditing({ id: chat.id, draft: chat.title });
          }}
        >
          {chat.title}
        </h3>
      )}
      <div className="workspace__head-actions">
        {/* Search toggle button in header (Ctrl/Cmd+F) */}
        {canSearch && (
          <button
            className={`icon-btn${searchOpen ? ' icon-btn--done' : ''}`}
            onClick={onToggleSearch}
            title={t('inChatSearch.open')}
          >
            <IconSearch size={14} />
          </button>
        )}
        <button className="icon-btn icon-btn--danger" onClick={() => onDelete(chat.id)}>
          <IconTrash />
        </button>
      </div>
    </div>
  );
};

export default ChatHeader;
