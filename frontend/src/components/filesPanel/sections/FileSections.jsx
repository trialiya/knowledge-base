import { useTranslation } from 'react-i18next';
import useListNavigation from '@/components/common/search/useListNavigation';
import useFileSections from './useFileSections';
import './fileSections.css';

const PREAMBLE = 'preamble';

/** Уровень заголовка из `kind` (h1…h6); у преамбулы — 0. */
const levelOf = (kind) => (kind === PREAMBLE ? 0 : Number(kind?.slice(1)) || 1);

/**
 * Вкладка «Разделы» правой панели файлового браузера: заголовки открытого
 * markdown-файла, вложенные по уровню. Клик по разделу прокручивает к нему
 * центр (`onJump(line)`) — и в разметке, и в исходнике.
 *
 * Отступ считается от самого мелкого уровня в файле, а не от h1: у файла,
 * который начинается с `##`, иначе весь список стоял бы на ступеньку правее.
 */
const FileSections = ({ path, project, rev = '', refreshToken = 0, activeLine = null, onJump }) => {
  const { t } = useTranslation('files');
  const handleKeyDown = useListNavigation();
  const { sections, loading, error } = useFileSections({ path, project, rev, refreshToken });

  const headings = sections.filter((s) => s.kind !== PREAMBLE);
  const minLevel = headings.length ? Math.min(...headings.map((s) => levelOf(s.kind))) : 1;

  return (
    <div
      className="file-sections ws-list"
      role="tree"
      aria-label={t('sections.aria')}
      tabIndex={0}
      onKeyDown={handleKeyDown}
    >
      {loading && (
        <div className="ws-hint" role="none">
          {t('loading')}
        </div>
      )}
      {error && (
        <div className="ws-hint" role="none">
          {t('sections.loadError')}
        </div>
      )}
      {!loading && !error && headings.length === 0 && (
        <div className="ws-hint" role="none">
          {t('sections.empty')}
        </div>
      )}
      {!loading &&
        !error &&
        headings.length > 0 &&
        sections.map((section) => {
          const preamble = section.kind === PREAMBLE;
          const depth = preamble ? 0 : levelOf(section.kind) - minLevel;
          const active = section.startLine === activeLine;
          return (
            <div
              key={`${section.startLine}\n${section.signature}`}
              data-ws-item
              role="treeitem"
              aria-level={depth + 1}
              aria-selected={active}
              tabIndex={-1}
              className={`ws-item file-sections__row${active ? ' ws-item--active' : ''}${
                preamble ? ' file-sections__row--preamble' : ''
              }`}
              style={{ '--depth': depth }}
              // Путь раздела — тот, что понимают инструменты документов; полное
              // имя нужно и здесь: глубокий заголовок обрезан многоточием.
              title={preamble ? undefined : section.signature}
              onClick={() => onJump(section.startLine)}
            >
              <span className="ws-item__label">{preamble ? t('sections.preamble') : section.name}</span>
              <span className="file-sections__line">{section.startLine}</span>
            </div>
          );
        })}
    </div>
  );
};

export default FileSections;
