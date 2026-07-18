import React from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from './ModalShell';
import { FileView } from '../filesPanel/FileContent';
import { baseName } from './utils';
import { IconX } from '../../icons';

// Unlike the doc fullscreen path (FullscreenEditorModal → MarkdownEditor),
// file content is source code, not markdown — rendering it through the
// markdown pipeline would garble it. This reuses FileView (the same
// syntax-highlighted, line-numbered renderer as FilePreviewModal / FilesPanel).
const FileFullscreenModal = ({ path, file, loading, error, onClose }) => {
  const { t } = useTranslation('files');
  const name = baseName(path);

  return (
    <ModalShell onClose={onClose} variant="fullscreen" className="file-preview-modal">
      <div className="fs-editor__head">
        <div className="file-preview-modal__title">
          <span className="file-preview-modal__name">{name}</span>
          <span className="file-preview-modal__path" title={path}>
            {path}
          </span>
        </div>
        <button className="fs-editor__close" title={t('preview.close')} onClick={onClose}>
          <IconX />
        </button>
      </div>
      <div className="fs-editor__body file-preview-modal__body">
        {loading && <div className="file-preview-modal__msg">{t('tree.loading')}</div>}
        {!loading && error && <div className="file-preview-modal__msg">{t('file.loadError')}</div>}
        {!loading && !error && file && <FileView file={file} />}
      </div>
    </ModalShell>
  );
};

export default FileFullscreenModal;
