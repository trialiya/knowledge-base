import { useTranslation } from 'react-i18next';
import { FILE_TAB } from '@/constants/fileTabs';
import { navigateToFile } from '@/navigation/fileNavigationBus';
import { filesUrl } from '@/navigation/urlScheme';
import { isBrowserClick } from '@/components/common/preview/useLinkTooltip';
import { formatDateTime, formatRelativeTime } from '@/utils/formatting';

/**
 * Ячейка колонки blame: кто и когда последним менял строки ханка. Клик
 * открывает этот же файл в снимке того коммита с вкладкой «Коммит» справа —
 * одним переходом (см. navStore.openFilePath); Ctrl/Cmd+клик — браузеру, по
 * тому же адресу. Ханк без коммита — незакоммиченная правка: вести некуда.
 *
 * `data-find-skip`: Ctrl+F в файле ищет по тексту, а не по авторам и хешам.
 */
const BlameCell = ({ hunk, span, path, project }) => {
  const { t, i18n } = useTranslation('files');
  if (!hunk) return <td className="file-code__blame" rowSpan={span} data-find-skip="" />;
  if (!hunk.hash) {
    return (
      <td className="file-code__blame" rowSpan={span} data-find-skip="">
        <span className="file-code__blame-uncommitted">{t('file.blameUncommitted')}</span>
      </td>
    );
  }
  const href = filesUrl(path, project, { rev: hunk.hash, right: FILE_TAB.COMMIT });
  const onClick = (e) => {
    if (isBrowserClick(e)) return;
    e.preventDefault();
    navigateToFile(path, project, { rev: hunk.hash, right: FILE_TAB.COMMIT });
  };
  const when = formatDateTime(hunk.date, i18n.language);
  const title = [hunk.summary, `${hunk.author}${when ? ` · ${when}` : ''}`].filter(Boolean).join('\n');
  return (
    <td className="file-code__blame" rowSpan={span} data-find-skip="">
      <a className="file-code__blame-link" href={href} onClick={onClick} title={title}>
        <span className="file-code__blame-hash">{hunk.shortHash}</span>
        <span className="file-code__blame-author">{hunk.author}</span>
        <span className="file-code__blame-date">{formatRelativeTime(hunk.date, i18n.language)}</span>
      </a>
    </td>
  );
};

export default BlameCell;
