import React from 'react';

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
const MoveConfirmModal = ({ draggedTitle, fromTitle, toTitle, onConfirm, onCancel }) => (
  <div className="modal-overlay" onClick={onCancel}>
    <div className="modal move-confirm-modal" onClick={(e) => e.stopPropagation()}>
      <div className="move-confirm-modal__header">
        <span className="move-confirm-modal__icon" aria-hidden="true">
          📂
        </span>
        <h3>Переместить в другую папку?</h3>
      </div>

      <p className="move-confirm-modal__body">
        Вы перемещаете <strong>«{draggedTitle}»</strong> в другую папку. Это изменит его расположение в дереве.
      </p>

      <div className="move-confirm-modal__path">
        <div className="move-confirm-modal__path-row">
          <span className="move-confirm-modal__path-icon">📁</span>
          <span className="move-confirm-modal__path-label">Откуда:</span>
          <span className="move-confirm-modal__path-name">{fromTitle}</span>
        </div>
        <div className="move-confirm-modal__path-arrow">↓</div>
        <div className="move-confirm-modal__path-row">
          <span className="move-confirm-modal__path-icon">📁</span>
          <span className="move-confirm-modal__path-label">Куда:</span>
          <span className="move-confirm-modal__path-name">{toTitle}</span>
        </div>
      </div>

      <div className="modal-buttons">
        <button onClick={onConfirm}>Переместить</button>
        <button onClick={onCancel}>Отмена</button>
      </div>
    </div>
  </div>
);

export default MoveConfirmModal;
