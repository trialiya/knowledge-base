import { useTranslation } from 'react-i18next';
import FileView from '@/components/filesPanel/FileView';
import ModalShell from '@/components/common/modal/ModalShell';
import '@/components/common/ui/buttons.css';
import { IconX } from '@/icons/index';
import RevPath, { revPathText } from './RevPath';
import useFileContent from './useFileContent';

/**
 * Read-only file preview modal opened from a file link (`/files?path=...`) — shows the
 * file's content without leaving the chat / navigating to FilesPanel: the range a `#Lx-Ly`
 * link names, or the whole file (the link card's «expand»). The `.file-preview-modal` /
 * `.fs-editor*` chrome is shared with ChipEditor's chip preview, and so is the renderer —
 * FileView (language badge, size, line count, binary placeholder, line-numbered code).
 *
 * props:
 *   path              — repo-relative file path
 *   fromLine, toLine  — optional 1-based inclusive line range (from a `#Lx-Ly` link anchor)
 *   rev               — optional revision: the file as of that commit (a `&rev=` link)
 *   onClose           — () => void
 */
const FilePreviewModal = ({ path, project, rev = null, fromLine, toLine, onClose }) => {
  const { t } = useTranslation('files');
  const { file, loading, error } = useFileContent({ path, project, rev, from: fromLine, to: toLine });

  const name = path.slice(path.lastIndexOf('/') + 1);

  return (
    <ModalShell onClose={onClose} variant="fullscreen" className="file-preview-modal">
      <div className="fs-editor__head">
        <div className="file-preview-modal__title">
          <span className="file-preview-modal__name">{name}</span>
          <span className="file-preview-modal__path" title={revPathText(path, rev)}>
            <RevPath path={path} rev={rev} project={project} />
          </span>
        </div>
        <button type="button" className="icon-btn" title={t('preview.close')} onClick={onClose}>
          <IconX />
        </button>
      </div>
      <div className="fs-editor__body file-preview-modal__body">
        {loading && <div className="file-preview-modal__msg">{t('tree.loading')}</div>}
        {!loading && error && <div className="file-preview-modal__msg">{t('file.loadError')}</div>}
        {!loading && !error && file && <FileView file={file} path={path} project={project} rev={rev || ''} />}
      </div>
    </ModalShell>
  );
};

export default FilePreviewModal;
