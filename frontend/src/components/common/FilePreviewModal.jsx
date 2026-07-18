import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import gitApi from '../../api/gitApi';
import { FileView } from '../filesPanel/FileContent';
import ModalShell from './ModalShell';
import { IconX } from '../../icons';

/**
 * Read-only file preview modal opened from a chat file link (`/files?path=...`) — shows the
 * file's content without leaving the chat / navigating to FilesPanel. Reuses the
 * `.file-preview-modal`/`.fs-editor*` chrome already styled for FileChipInput's chip preview
 * (chatWindow.css), but renders FileView (language badge, size, line count, binary placeholder,
 * line-numbered code) instead of a plain `<pre>`, since this modal has no path-only mode toggle.
 *
 * props:
 *   path              — repo-relative file path
 *   fromLine, toLine  — optional 1-based inclusive line range (from a `#Lx-Ly` link anchor)
 *   onClose           — () => void
 */
const FilePreviewModal = ({ path, fromLine, toLine, onClose }) => {
  const { t } = useTranslation('files');
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    gitApi
      .getFileContent(path, fromLine, toLine)
      .then((result) => {
        if (cancelled) return;
        setFile(result);
        setLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        setError(true);
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [path, fromLine, toLine]);

  const name = path.slice(path.lastIndexOf('/') + 1);

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

export default FilePreviewModal;
