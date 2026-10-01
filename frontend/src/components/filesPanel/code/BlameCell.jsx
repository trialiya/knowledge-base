import { useTranslation } from 'react-i18next';
import { FILE_TAB } from '@/constants/fileTabs';
import { navigateToFile } from '@/navigation/fileNavigationBus';
import { filesUrl, formatLines } from '@/navigation/urlScheme';
import { formatCompactDateTime, formatDateTime } from '@/utils/formatting';
import shortRev from '@/components/common/git/shortRev';
import AppLink from '@/components/common/ui/AppLink';

/**
 * Ячейка колонки blame: когда менялись строки ханка и чем — дата и начало
 * описания коммита, сколько влезет в ширину колонки. Автор, хеш и полное
 * описание — в подсказке.
 *
 * Клик открывает этот же файл в снимке того коммита с вкладкой «Коммит» справа и
 * строками ханка, выделенными там (`?lines=`), — одним переходом (см.
 * navStore.openFilePath); Ctrl/Cmd+клик — браузеру, по тому же адресу. Путь и
 * номера — те, что были у файла в том коммите (`hunk.path`, `hunk.sourceLine`):
 * после переименования нынешнее имя там не найдётся, а строки, сдвинутые
 * позднейшими правками, стояли там на другом месте. Ханк без коммита —
 * незакоммиченная правка: вести некуда. Строки ханка в ЭТОМ файле переход
 * пишет в текущую запись истории (`backLines`): «Назад» вернёт к ним, выделенным
 * и прокрученным, а не к началу файла.
 *
 * `data-find-skip`: Ctrl+F в файле ищет по тексту, а не по описаниям коммитов.
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
  const target = hunk.path || path;
  const options = {
    rev: hunk.hash,
    lines: hunk.sourceLine ? formatLines(hunk.sourceLine, hunk.lineCount) : undefined,
    right: FILE_TAB.COMMIT,
  };
  const href = filesUrl(target, project, options);
  const open = () =>
    navigateToFile(target, project, { ...options, backLines: formatLines(hunk.fromLine, hunk.lineCount) });
  const byline = [hunk.author, shortRev(hunk.hash), formatDateTime(hunk.date, i18n.language)]
    .filter(Boolean)
    .join(' · ');
  const title = [hunk.summary, byline].filter(Boolean).join('\n');
  return (
    <td className="file-code__blame" rowSpan={span} data-find-skip="">
      <AppLink className="file-code__blame-link" href={href} onNavigate={open} title={title}>
        <span className="file-code__blame-date">{formatCompactDateTime(hunk.date, i18n.language)}</span>
        <span className="file-code__blame-summary">{hunk.summary}</span>
      </AppLink>
    </td>
  );
};

export default BlameCell;
