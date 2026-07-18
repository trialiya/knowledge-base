import React from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from './ModalShell';
import './buttons.css';
import './errorModal.css';

/**
 * Простое модальное окно для отображения ошибок.
 *
 * props:
 *  - open: boolean — показывать ли модалку
 *  - icon: string — эмодзи/иконка (по умолчанию ⚠️)
 *  - title: string — заголовок
 *  - message: string — текст под заголовком
 *  - confirmLabel: string — текст кнопки (по умолчанию → t('gotIt'))
 *  - onClose: () => void — закрытие модалки
 */
const ErrorModal = ({ open, icon = '⚠️', title, message, confirmLabel, onClose }) => {
  const { t } = useTranslation();

  return (
    <ModalShell open={open} onClose={onClose} variant="sm" role="alertdialog" className="error-modal">
      <div className="error-modal__icon">{icon}</div>
      {title && <h3 className="error-modal__title">{title}</h3>}
      {message && <p className="error-modal__message">{message}</p>}
      <button className="btn btn--primary error-modal__btn" onClick={onClose} autoFocus>
        {confirmLabel ?? t('gotIt')}
      </button>
    </ModalShell>
  );
};

export default ErrorModal;
