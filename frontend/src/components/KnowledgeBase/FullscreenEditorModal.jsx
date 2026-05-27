import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import MarkdownEditor from './MarkdownEditor';
import { IconX } from './icons';

/**
 * Полноэкранная модалка для разворачивания описания.
 * props:
 *   title       — заголовок в шапке модалки
 *   value       — markdown-строка
 *   previewOnly — true → только preview (Summary.About), false → редактор (Content)
 *   onSave      — async (val) => void   (нужен только если previewOnly=false)
 *   onClose     — () => void
 *   tree, onNavigate — пробрасываются в MarkdownEditor для DocLinkTooltip
 */
const FullscreenEditorModal = ({ title, value, previewOnly = false, onSave, onClose, tree = [], onNavigate }) => {
  // Esc закрывает модалку
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return createPortal(
    <div className="fs-editor-overlay" onMouseDown={onClose}>
      <div className="fs-editor" onMouseDown={(e) => e.stopPropagation()}>
        <div className="fs-editor__head">
          <span className="fs-editor__title">{title}</span>
          <button className="fs-editor__close" title="Закрыть (Esc)" onClick={onClose}>
            <IconX />
          </button>
        </div>
        <div className="fs-editor__body">
          <MarkdownEditor
            value={value}
            previewOnly={previewOnly}
            onSave={onSave || (() => {})}
            tree={tree}
            onNavigate={onNavigate}
          />
        </div>
      </div>
    </div>,
    document.body,
  );
};

export default FullscreenEditorModal;
