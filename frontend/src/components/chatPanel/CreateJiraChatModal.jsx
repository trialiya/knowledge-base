import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Modal dialog for creating a JIRA-linked chat.
 *
 * Props:
 *   open     — whether the modal is visible
 *   onClose  — close handler
 *   onCreate — called with { jiraUrl, confluenceUrl, title } when user submits
 */
const CreateJiraChatModal = ({ open, onClose, onCreate }) => {
  const { t } = useTranslation('chat');
  const [jiraUrl, setJiraUrl] = useState('');
  const [confluenceUrl, setConfluenceUrl] = useState('');
  const [title, setTitle] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!open) return null;

  const handleSubmit = async () => {
    if (!jiraUrl.trim()) {
      setError(t('jiraModal.urlRequired'));
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
      setError(err.message || t('jiraModal.createError'));
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
          <span className="jira-modal__title">{t('jiraModal.title')}</span>
          <button className="jira-modal__close" onClick={onClose} title={t('common:close')}>
            ✕
          </button>
        </div>

        <div className="jira-modal__body">
          <label className="jira-modal__label">
            <span>
              {t('jiraModal.jiraLabel')} <span className="jira-modal__required">*</span>
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
            <span>{t('jiraModal.confluenceLabel')}</span>
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
            <span>{t('jiraModal.titleLabel')}</span>
            <input
              type="text"
              className="jira-modal__input"
              placeholder={t('jiraModal.titlePlaceholder')}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={loading}
            />
          </label>

          {error && <div className="jira-modal__error">{error}</div>}
        </div>

        <div className="jira-modal__footer">
          <button className="jira-modal__btn jira-modal__btn--cancel" onClick={onClose} disabled={loading}>
            {t('common:cancel')}
          </button>
          <button className="jira-modal__btn jira-modal__btn--create" onClick={handleSubmit} disabled={loading}>
            {loading ? t('jiraModal.creating') : t('jiraModal.create')}
          </button>
        </div>
      </div>
    </div>
  );
};

export default CreateJiraChatModal;
