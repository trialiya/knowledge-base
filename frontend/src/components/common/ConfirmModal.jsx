import React from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Lightweight confirmation dialog, styled to match MoveConfirmModal.
 *
 * Props:
 *   open          – whether the modal is visible
 *   icon          – emoji/character shown in the header (default ⚠️)
 *   title         – heading text
 *   message       – body text (string or node)
 *   confirmLabel  – confirm button text (default → t('confirm'))
 *   cancelLabel   – cancel button text (default → t('cancel'))
 *   onConfirm()   – user confirmed
 *   onCancel()    – user cancelled / clicked backdrop
 */
const ConfirmModal = ({ open, icon = '⚠️', title, message, confirmLabel, cancelLabel, onConfirm, onCancel }) => {
  const { t } = useTranslation();
  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal confirm-modal" role="alertdialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="confirm-modal__header">
          <span className="confirm-modal__icon" aria-hidden="true">
            {icon}
          </span>
          <h3>{title}</h3>
        </div>

        {message && <p className="confirm-modal__body">{message}</p>}

        <div className="modal-buttons">
          <button onClick={onConfirm}>{confirmLabel ?? t('confirm')}</button>
          <button onClick={onCancel}>{cancelLabel ?? t('cancel')}</button>
        </div>
      </div>
    </div>
  );
};

export default ConfirmModal;
