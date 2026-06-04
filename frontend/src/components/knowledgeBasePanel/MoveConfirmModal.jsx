import React from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Modal shown when a drag-and-drop would move a node to a different parent.
 *
 * Props:
 *   draggedTitle  – title of the node being moved
 *   fromTitle     – display name of the current parent ("Root" for root level)
 *   toTitle       – display name of the target parent
 *   onConfirm()   – user clicked "Move"
 *   onCancel()    – user clicked "Cancel" or backdrop
 */
const MoveConfirmModal = ({ draggedTitle, fromTitle, toTitle, onConfirm, onCancel }) => {
  const { t } = useTranslation('knowledgeBase');

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal move-confirm-modal" onClick={(e) => e.stopPropagation()}>
        <div className="move-confirm-modal__header">
          <span className="move-confirm-modal__icon" aria-hidden="true">
            📂
          </span>
          <h3>{t('move.title')}</h3>
        </div>

        <p className="move-confirm-modal__body">{t('move.body', { title: draggedTitle })}</p>

        <div className="move-confirm-modal__path">
          <div className="move-confirm-modal__path-row">
            <span className="move-confirm-modal__path-icon">📁</span>
            <span className="move-confirm-modal__path-label">{t('move.from')}</span>
            <span className="move-confirm-modal__path-name">{fromTitle}</span>
          </div>
          <div className="move-confirm-modal__path-arrow">↓</div>
          <div className="move-confirm-modal__path-row">
            <span className="move-confirm-modal__path-icon">📁</span>
            <span className="move-confirm-modal__path-label">{t('move.to')}</span>
            <span className="move-confirm-modal__path-name">{toTitle}</span>
          </div>
        </div>

        <div className="modal-buttons">
          <button onClick={onConfirm}>{t('move.confirm')}</button>
          <button onClick={onCancel}>{t('move.cancel')}</button>
        </div>
      </div>
    </div>
  );
};

export default MoveConfirmModal;
