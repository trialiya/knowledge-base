import React, { useState } from 'react';

/**
 * Modal dialog for creating a JIRA-linked chat.
 *
 * Props:
 *   open     — whether the modal is visible
 *   onClose  — close handler
 *   onCreate — called with { jiraUrl, confluenceUrl, title } when user submits
 */
const CreateJiraChatModal = ({ open, onClose, onCreate }) => {
  const [jiraUrl, setJiraUrl] = useState('');
  const [confluenceUrl, setConfluenceUrl] = useState('');
  const [title, setTitle] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!open) return null;

  const handleSubmit = async () => {
    if (!jiraUrl.trim()) {
      setError('Укажите ссылку на JIRA задачу');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await onCreate({
        jiraUrl: jiraUrl.trim(),
        confluenceUrl: confluenceUrl.trim() || null,
        title: title.trim() || null,
      });
      // Reset & close on success
      setJiraUrl('');
      setConfluenceUrl('');
      setTitle('');
      onClose();
    } catch (err) {
      setError(err.message || 'Ошибка создания чата');
    } finally {
      setLoading(false);
    }
  };

  const handleBackdrop = (e) => {
    if (e.target === e.currentTarget) onClose();
  };

  return (
    <div className="jira-modal-backdrop" onClick={handleBackdrop}>
      <div className="jira-modal">
        <div className="jira-modal__header">
          <span className="jira-modal__title">Создать JIRA чат</span>
          <button className="jira-modal__close" onClick={onClose} title="Закрыть">
            ✕
          </button>
        </div>

        <div className="jira-modal__body">
          <label className="jira-modal__label">
            <span>
              JIRA задача <span className="jira-modal__required">*</span>
            </span>
            <input
              type="url"
              className="jira-modal__input"
              placeholder="https://instance.atlassian.net/browse/PROJ-123"
              value={jiraUrl}
              onChange={(e) => setJiraUrl(e.target.value)}
              autoFocus
              disabled={loading}
            />
          </label>

          <label className="jira-modal__label">
            <span>Confluence страница</span>
            <input
              type="url"
              className="jira-modal__input"
              placeholder="https://instance.atlassian.net/wiki/spaces/..."
              value={confluenceUrl}
              onChange={(e) => setConfluenceUrl(e.target.value)}
              disabled={loading}
            />
          </label>

          <label className="jira-modal__label">
            <span>Название чата</span>
            <input
              type="text"
              className="jira-modal__input"
              placeholder="Авто (ключ задачи)"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={loading}
            />
          </label>

          {error && <div className="jira-modal__error">{error}</div>}
        </div>

        <div className="jira-modal__footer">
          <button className="jira-modal__btn jira-modal__btn--cancel" onClick={onClose} disabled={loading}>
            Отмена
          </button>
          <button className="jira-modal__btn jira-modal__btn--create" onClick={handleSubmit} disabled={loading}>
            {loading ? 'Создание…' : 'Создать'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default CreateJiraChatModal;
