import { useTranslation } from 'react-i18next';
import { baseName, chipLabel, fetchContent } from './fileChips';
import ModalShell from '@/components/common/modal/ModalShell';
import useFileContent from '@/components/common/preview/useFileContent';
import FileView from '@/components/filesPanel/FileView';
import '@/components/common/ui/buttons.css';
import { IconX } from '@/icons/index';

// ── Превью содержимого файла — полноэкранная модалка ─────────────────────────

// `project` — репозиторий чата: путь чужого чипа показываем с именем его проекта,
// иначе превью и чип рядом с ним рассказывают о разных репозиториях одно и то же.
function FileChipPreview({ preview, project, onClose, onToggleRef }) {
  const { path, from, to, refOnly } = preview;
  const { t } = useTranslation('chat');
  const name = baseName(path);
  const range = from != null ? ` (${from}–${to})` : '';
  // Читаем из репозитория, названного в токене: чип может быть из соседнего
  // проекта, и превью обязано показать тот файл, который уедет в сообщение, —
  // поэтому и через кэш fetchContent, которым токен развернут при отправке.
  const fileProject = preview.project || project;
  const { file, loading, error } = useFileContent({
    path,
    project: fileProject,
    from,
    to,
    enabled: !refOnly,
    read: fetchContent,
  });

  return (
    <ModalShell onClose={onClose} variant="fullscreen" className="file-preview-modal">
      <div className="fs-editor__head">
        <div className="file-preview-modal__title">
          <span className="file-preview-modal__name">
            {name}
            {range}
          </span>
          <span className="file-preview-modal__path" title={chipLabel(preview.project, project, path)}>
            {chipLabel(preview.project, project, path)}
          </span>
        </div>
        <button
          type="button"
          className="btn btn--ghost btn--xs"
          aria-pressed={refOnly}
          onClick={onToggleRef}
          title={refOnly ? t('fileInput.useFullContent') : t('fileInput.usePathOnly')}
        >
          {refOnly ? '📄' : '📎'}
        </button>
        <button type="button" className="icon-btn" title={t('fileInput.closePreview')} onClick={onClose}>
          <IconX />
        </button>
      </div>
      <div className="fs-editor__body file-preview-modal__body">
        {!refOnly && (
          <>
            {loading && <div className="file-preview-modal__msg">{t('fileInput.searching')}</div>}
            {error && <div className="file-preview-modal__msg">{t('fileInput.previewError')}</div>}
            {file && <FileView file={file} path={path} project={fileProject || ''} />}
          </>
        )}
        {refOnly && <div className="file-preview-modal__ref-note">{path}</div>}
      </div>
    </ModalShell>
  );
}

export default FileChipPreview;
