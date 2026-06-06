import React from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import MarkdownEditor from './MarkdownEditor';
import useEscape from '../common/useEscape';
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
  const { t } = useTranslation('knowledgeBase');
  useEscape(onClose);

  return createPortal(
    <div className="fs-editor-overlay" onMouseDown={onClose}>
      <div className="fs-editor" onMouseDown={(e) => e.stopPropagation()}>
        <div className="fs-editor__head">
          <span className="fs-editor__title">{title}</span>
          <button className="fs-editor__close" title={t('fullscreen.close')} onClick={onClose}>
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
