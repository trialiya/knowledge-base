import React from 'react';
import { useTranslation } from 'react-i18next';
import MarkdownEditor from './MarkdownEditor';
import ModalShell from '../common/ModalShell';
import { IconX } from '../../icons';

/**
 * Полноэкранная модалка для разворачивания содержимого документа/папки.
 * props:
 *   title       — заголовок в шапке модалки
 *   value       — markdown-строка (общий черновик содержимого)
 *   onChange    — (val) => void; правки черновика
 *   savedValue  — сохранённое описание (для вычисления «грязно» в редакторе)
 *   onSave      — async (val) => void
 *   onClose     — () => void
 *   previewOnly — только просмотр, без тулбара и правки (разворот doc-ссылки из
 *                 тултипа: там нечего сохранять — документ даже не открыт)
 *   tree, onNavigate — пробрасываются в MarkdownEditor для DocLinkTooltip
 */
const FullscreenEditorModal = ({
  title,
  value,
  onChange,
  savedValue = '',
  onSave,
  onClose,
  previewOnly = false,
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
          onSave={onSave || (() => {})}
          previewOnly={previewOnly}
          tree={tree}
          onNavigate={onNavigate}
        />
      </div>
    </ModalShell>
  );
};

export default FullscreenEditorModal;
