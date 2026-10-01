import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import useFileContent from '@/components/common/preview/useFileContent';
import FileView from '@/components/filesPanel/FileView';
import { IconX } from '@/icons/index';
import '@/components/common/ui/buttons.css';
import { DiffLines, DiffStats } from './diffRender';
import { filesUrl } from '@/navigation/urlScheme';
import '../styles/file-changes.css';

/**
 * Один изменённый ответом ИИ файл крупным планом: diff'ы всех правок этого файла из данного
 * ответа, а у созданного файла (diff'а у него нет) — его текущее содержимое, тем же FileView,
 * что и в «Файлах» (с номерами строк и переключателем разметки у markdown). Содержимое читается
 * мимо кэша чипов: файл после ответа могли править, а показать нужно то, что лежит сейчас.
 */
const FileDiffModal = ({ change, project, onClose }) => {
  const { t } = useTranslation('chat');
  const showsContent = change.diffs.length === 0;
  const { file, loading, error } = useFileContent({ path: change.path, project, enabled: showsContent });

  return (
    <ModalShell onClose={onClose} className="file-diff-modal">
      <div className="file-diff-modal__header">
        <span className="file-diff-modal__title" title={change.path}>
          {change.path} <DiffStats additions={change.additions} deletions={change.deletions} />
        </span>
        <a
          className="file-diff-modal__open-link"
          href={filesUrl(change.path, project)}
          target="_blank"
          rel="noreferrer"
        >
          {t('fileChange.openFile')}
        </a>
        <button type="button" className="icon-btn" onClick={onClose} title={t('common:close')}>
          <IconX />
        </button>
      </div>
      <div className="file-diff-modal__body">
        {showsContent ? (
          <>
            {loading && <div className="file-diff-modal__empty">{t('loading')}</div>}
            {!loading && error && <div className="file-diff-modal__empty">{t('fileChange.loadError')}</div>}
            {!loading && !error && file && <FileView file={file} path={change.path} project={project ?? ''} />}
          </>
        ) : (
          change.diffs.map((diff, i) => (
            // Индекс как key безопасен: список diff'ов иммутабелен в рамках открытой модалки.
            <pre key={i} className="file-diff-modal__diff">
              <DiffLines patch={diff} />
            </pre>
          ))
        )}
      </div>
    </ModalShell>
  );
};

export default FileDiffModal;
