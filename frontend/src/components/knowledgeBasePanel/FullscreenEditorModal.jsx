import React from 'react';
import { useTranslation } from 'react-i18next';
import MarkdownEditor from './MarkdownEditor';
import ModalShell from '../common/ModalShell';
import { IconX } from '../../icons';

/**
 * Полноэкранная модалка для разворачивания описания.
 * props:
 *   title       — заголовок в шапке модалки
 *   value       — markdown-строка (для Content — общий черновик)
 *   onChange    — (val) => void; правки черновика (нужен только если previewOnly=false)
 *   savedValue  — сохранённое описание (для вычисления «грязно» в редакторе)
 *   previewOnly — true → только preview (Summary.About), false → редактор (Content)
 *   onSave      — async (val) => void   (нужен только если previewOnly=false)
 *   onClose     — () => void
 *   tree, onNavigate — пробрасываются в MarkdownEditor для DocLinkTooltip
 */
const FullscreenEditorModal = ({
  title,
  value,
  onChange,
  savedValue = '',
  previewOnly = false,
  onSave,
  onClose,
  tree = [],
  onNavigate,
}) => {
  const { t } = useTranslation('knowledgeBase');

  return (
    <ModalShell onClose={onClose} variant="fullscreen">
      <div className="fs-editor__head">
        <span className="fs-editor__title">{title}</span>
        <button className="fs-editor__close" title={t('fullscreen.close')} onClick={onClose}>
          <IconX />
        </button>
      </div>
      <div className="fs-editor__body">
        <MarkdownEditor
          value={value}
          onChange={onChange}
          savedValue={savedValue}
          previewOnly={previewOnly}
          onSave={onSave || (() => {})}
          tree={tree}
          onNavigate={onNavigate}
        />
      </div>
    </ModalShell>
  );
};

export default FullscreenEditorModal;
