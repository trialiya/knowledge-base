import { useTranslation } from 'react-i18next';
import useListNavigation from '@/components/common/search/useListNavigation';
import useFileOutline from './useFileOutline';
import { MARKDOWN, PREAMBLE, outlineRows } from './outlineRows';
import './fileOutline.css';

/**
 * Вкладка «Структура» правой панели файлового браузера — у markdown-файла она
 * «Разделы»: символы открытого файла (классы, методы, функции, таблицы — или
 * заголовки), вложенные по диапазону строк. Клик прокручивает центр к строке
 * символа (`onJump(line)`).
 *
 * Вид символа подписан у кода и не подписан у markdown: там уровень заголовка
 * уже виден по отступу, а «h2» рядом с каждым разделом был бы шумом.
 */
const FileOutline = ({ path, project, language, rev = '', refreshToken = 0, activeLine = null, onJump }) => {
  const { t } = useTranslation('files');
  const handleKeyDown = useListNavigation();
  const { symbols, loading, error } = useFileOutline({ path, project, rev, refreshToken });

  const markdown = language === MARKDOWN;
  const empty = symbols.every((s) => s.kind === PREAMBLE);

  return (
    <div
      className="file-outline ws-list"
      role="tree"
      aria-label={t('outline.aria')}
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
          {t('outline.loadError')}
        </div>
      )}
      {!loading && !error && empty && (
        <div className="ws-hint" role="none">
          {t(markdown ? 'outline.emptyMarkdown' : 'outline.empty')}
        </div>
      )}
      {!loading &&
        !error &&
        !empty &&
        outlineRows(symbols).map(({ symbol, depth }) => {
          const preamble = symbol.kind === PREAMBLE;
          const active = symbol.startLine === activeLine;
          return (
            <div
              key={`${symbol.startLine}\n${symbol.kind}\n${symbol.name}`}
              data-ws-item
              role="treeitem"
              aria-level={depth + 1}
              aria-selected={active}
              tabIndex={-1}
              className={`ws-item file-outline__row${active ? ' ws-item--active' : ''}${
                preamble ? ' file-outline__row--preamble' : ''
              }`}
              style={{ '--depth': depth }}
              // Сигнатура у кода, путь раздела у markdown — полностью: в строке
              // длинное имя обрезано многоточием.
              title={preamble ? undefined : symbol.signature || undefined}
              onClick={() => onJump(symbol.startLine)}
            >
              {!markdown && <span className="file-outline__kind">{symbol.kind}</span>}
              <span className="ws-item__label">{preamble ? t('outline.preamble') : symbol.name}</span>
              <span className="file-outline__line">{symbol.startLine}</span>
            </div>
          );
        })}
    </div>
  );
};

export default FileOutline;
