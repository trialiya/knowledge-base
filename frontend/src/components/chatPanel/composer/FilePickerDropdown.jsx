import { useTranslation } from 'react-i18next';
import highlightMatch, { highlightFileMatch } from '@/components/common/search/highlightMatch';
import { IconFileText } from '@/icons/index';
import PickerDropdown from './PickerDropdown';

/** Строка выдачи: чем документ отличается от файла — только подписями и иконкой. */
const describe = (node, query, type) => {
  if (type === 'doc') {
    return {
      key: node.id,
      icon: node.type === 'folder' ? '📁' : '📋',
      name: highlightMatch(node.title, query),
      path: <>#{highlightMatch(String(node.id), query)}</>,
    };
  }
  const { name, dir } = highlightFileMatch(node.name, node.path, query);
  // Файл в корне репозитория: каталога нет, и во второй строке повторяется имя —
  // тем же размеченным узлом, иначе на ней пропадает подсветка совпадения.
  return { key: node.path, icon: <IconFileText size={13} />, name, path: dir || name, pathTitle: node.path };
};

/**
 * Результаты поиска по триггеру чипа (`/file`, `/doc`) — над кареткой.
 *
 * Props:
 *   results              — GitFileNode[] | DocumentNode[]
 *   loading              — boolean
 *   query                — string
 *   anchorRect           — { top, left } каретки
 *   selectedIdx          — number
 *   type                 — 'file' | 'doc'
 *   onSelect(node)       — Enter / клик по строке → вставить ссылку
 *   onSelectWithContent  — клик по кнопке → вставить содержимое
 *   onDismiss()          — закрыть
 */
const FilePickerDropdown = ({
  results,
  loading,
  query,
  anchorRect,
  selectedIdx,
  onSelect,
  onSelectWithContent,
  onDismiss,
  type = 'file',
}) => {
  const { t } = useTranslation('chat');

  if (!anchorRect) return null;

  const style = {
    position: 'fixed',
    bottom: window.innerHeight - anchorRect.top + 6,
    left: Math.min(anchorRect.left, window.innerWidth - 524),
    zIndex: 9100,
  };

  const ns = type === 'doc' ? 'docInput' : 'fileInput';
  const hint = query ? t(`${ns}.hintQuery`, { query }) : t(`${ns}.hintStart`);
  const contentBtnLabel = t('fileInput.insertContent');

  const above = (
    <>
      {loading && (
        <div className="picker-dropdown__loading">
          <span className="picker-dropdown__spinner" />
          {t(`${ns}.searching`)}
        </div>
      )}
      {!loading && results.length === 0 && query.length >= 1 && (
        <div className="picker-dropdown__empty">{t(`${ns}.empty`)}</div>
      )}
    </>
  );

  const footer = (
    <>
      <kbd>↑↓</kbd> {t('fileInput.navigate')} · <kbd>Enter</kbd> {t('fileInput.insertRef')} · <kbd>Esc</kbd>{' '}
      {t('fileInput.dismiss')}
    </>
  );

  return (
    <PickerDropdown
      style={style}
      hint={hint}
      above={above}
      footer={footer}
      selectedIdx={selectedIdx}
      onDismiss={onDismiss}
    >
      {results.map((node, i) => {
        const item = describe(node, query, type);
        return (
          <div
            key={item.key}
            className={`picker-item ${i === selectedIdx ? 'picker-item--selected' : ''}`}
            onMouseDown={(e) => {
              e.preventDefault();
              onSelect(node);
            }}
          >
            <span className="picker-item__icon">{item.icon}</span>
            <span className="picker-item__body">
              <span className="picker-item__name">{item.name}</span>
              <span className="picker-item__path" title={item.pathTitle}>
                {item.path}
              </span>
            </span>
            <div className="picker-item__actions">
              <button
                type="button"
                className="picker-item__content-btn"
                title={contentBtnLabel}
                onMouseDown={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onSelectWithContent(node);
                }}
              >
                📄 {contentBtnLabel}
              </button>
            </div>
          </div>
        );
      })}
    </PickerDropdown>
  );
};

export default FilePickerDropdown;
