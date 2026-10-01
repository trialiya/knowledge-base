import { useTranslation } from 'react-i18next';
import { navigateToCommit } from '@/navigation/fileNavigationBus';
import { commitUrl } from '@/navigation/urlScheme';
import { isBrowserClick } from '@/components/common/preview/useLinkTooltip';
import './commitHashLink.css';

/**
 * Хеш коммита, который открывает сам коммит: снимок в «Файлах» с изменёнными
 * файлами и вкладкой «Коммит» (urlScheme.commitUrl). Для мест, где хеш стоит
 * в интерфейсе, а не в markdown (там — CommitLink с карточкой).
 *
 * `newTab` — для модалок: переход внутри приложения увёл бы раздел из-под
 * открытого окна, а оно осталось бы висеть поверх «Файлов». Так же открывает
 * файл FileDiffModal. Без него — обычный переход, Ctrl/Cmd+клик — браузеру.
 *
 * `rev` — полный хеш, когда он есть: короткий однажды перестаёт быть
 * однозначным; показывается `children` (обычно короткий хеш).
 */
const CommitHashLink = ({ rev, project = null, newTab = false, className = '', children }) => {
  const { t } = useTranslation('common');
  const href = commitUrl(rev, project);

  if (newTab) {
    return (
      <a className={className} href={href} target="_blank" rel="noreferrer" title={t('commitLink.openNewTab')}>
        {children}
      </a>
    );
  }

  const onClick = (e) => {
    if (isBrowserClick(e)) return;
    e.preventDefault();
    navigateToCommit(rev, project);
  };

  return (
    <a className={className} href={href} onClick={onClick} title={t('commitLink.open')}>
      {children}
    </a>
  );
};

export default CommitHashLink;
