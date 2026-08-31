import { useTranslation } from 'react-i18next';
import { IconUndo } from '@/icons/index';
import '../styles/file-changes.css';

/**
 * Плашка отката: пользователь вернул файлы, изменённые ответом, к прежнему состоянию.
 *
 * Ряд истории, а не пузырь: написал его не человек — как и у карточки git-команды, весь смысл
 * ряда в мете (см. ChatHistoryService.appendFileRevert). Список файлов показан целиком: он
 * короткий (столько же строк, сколько в блоке изменений выше), а «откачено 3 файла» заставляло
 * бы искать, каких именно.
 */
const FileRevertNotice = ({ revert }) => {
  const { t } = useTranslation('chat');
  const paths = revert?.paths ?? [];

  return (
    <div className="file-revert" role="note">
      <span className="file-revert__icon" aria-hidden="true">
        <IconUndo size={12} />
      </span>
      <span className="file-revert__text">
        <span className="file-revert__title">{t('fileChange.reverted', { count: paths.length })}</span>
        {paths.map((path) => (
          <span key={path} className="file-revert__path">
            {path}
          </span>
        ))}
      </span>
    </div>
  );
};

export default FileRevertNotice;
