import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '../common/ModalShell';
import '../common/buttons.css';
import './createJiraChatModal.css';

/**
 * Modal dialog for creating a JIRA-linked chat.
 *
 * Props:
 *   open     — whether the modal is visible
 *   onClose  — close handler
 *   onCreate — called with { jiraUrl, confluenceUrl, title } when user submits
 */
const CreateJiraChatModal = ({ open, onClose, onCreate, confluenceConfigured }) => {
  const { t } = useTranslation('chat');
  const [jiraUrl, setJiraUrl] = useState('');
  const [confluenceUrl, setConfluenceUrl] = useState('');
  const [title, setTitle] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

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

  return (
    <ModalShell open={open} onClose={onClose} variant="wide" className="jira-modal">
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

        {confluenceConfigured && (
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
        )}

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

      <div className="jira-modal__footer modal-shell__footer">
        <button className="btn btn--ghost" onClick={onClose} disabled={loading}>
          {t('common:cancel')}
        </button>
        <button className="btn btn--primary" onClick={handleSubmit} disabled={loading}>
          {loading ? t('jiraModal.creating') : t('jiraModal.create')}
        </button>
      </div>
    </ModalShell>
  );
};

export default CreateJiraChatModal;
