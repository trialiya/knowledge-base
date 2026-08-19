import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import gitApi from '../../api/gitApi';
import { FileView } from '../filesPanel/FileContent';
import ModalShell from './ModalShell';
import { IconX } from '../../icons';

/**
 * Read-only file preview modal opened from a chat file link (`/files?path=...`) — shows the
 * file's content without leaving the chat / navigating to FilesPanel. Reuses the
 * `.file-preview-modal`/`.fs-editor*` chrome already styled for ChipEditor's chip preview
 * (chatWindow.css), but renders FileView (language badge, size, line count, binary placeholder,
 * line-numbered code) instead of a plain `<pre>`, since this modal has no path-only mode toggle.
 *
 * props:
 *   path              — repo-relative file path
 *   fromLine, toLine  — optional 1-based inclusive line range (from a `#Lx-Ly` link anchor)
 *   onClose           — () => void
 */
const FilePreviewModal = ({ path, project, fromLine, toLine, onClose }) => {
  const { t } = useTranslation('files');
  // Ответ сервера; null — запрос ещё идёт. Отдельного `loading` нет: он выводится
  // из ответа, а сброс на смену файла делается в рендере, чтобы кадра с
  // содержимым предыдущего файла не было.
  const [answer, setAnswer] = useState(null); // { file, error } | null

  const [req, setReq] = useState({ path, project, fromLine, toLine });
  if (req.path !== path || req.project !== project || req.fromLine !== fromLine || req.toLine !== toLine) {
    setReq({ path, project, fromLine, toLine });
    setAnswer(null);
  }

  useEffect(() => {
    let cancelled = false;
    gitApi
      .getFileContent(path, { from: fromLine, to: toLine, project })
      .then((result) => {
        if (!cancelled) setAnswer({ file: result, error: false });
      })
      .catch(() => {
        if (!cancelled) setAnswer({ file: null, error: true });
      });
    return () => {
      cancelled = true;
    };
  }, [path, project, fromLine, toLine]);

  const loading = answer === null;
  const file = answer?.file ?? null;
  const error = answer?.error ?? false;

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
