import React from 'react';
import './errorModal.css';

/**
 * Простое модальное окно для отображения ошибок.
 *
 * props:
 *  - open: boolean — показывать ли модалку
 *  - icon: string — эмодзи/иконка (по умолчанию ⚠️)
 *  - title: string — заголовок
 *  - message: string — текст под заголовком
 *  - confirmLabel: string — текст кнопки (по умолчанию "Понятно")
 *  - onClose: () => void — закрытие модалки
 */
const ErrorModal = ({ open, icon = '⚠️', title, message, confirmLabel = 'Понятно', onClose }) => {
  if (!open) return null;

  return (
    <div className="error-modal-overlay" onClick={onClose}>
      <div className="error-modal" role="alertdialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="error-modal__icon">{icon}</div>
        {title && <h3 className="error-modal__title">{title}</h3>}
        {message && <p className="error-modal__message">{message}</p>}
        <button className="error-modal__btn" onClick={onClose} autoFocus>
          {confirmLabel}
        </button>
      </div>
    </div>
  );
};

export default ErrorModal;
