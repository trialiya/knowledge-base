import React from 'react';
import { useTranslation } from 'react-i18next';
import { IconChevronRight } from '../../icons';

/** Строит цепочку {name, path} от корня до полного пути (сам путь не включает корень). */
function segmentsOf(path) {
  if (!path) return [];
  let acc = '';
  return path.split('/').map((part) => {
    acc = acc ? `${acc}/${part}` : part;
    return { name: part, path: acc };
  });
}

const Breadcrumb = ({ path, onNavigate }) => {
  const { t } = useTranslation('files');
  const segments = segmentsOf(path);

  return (
    <nav className="file-breadcrumb" aria-label={t('breadcrumb.label')}>
      <button className="file-breadcrumb__crumb" onClick={() => onNavigate('')}>
        {t('breadcrumb.root')}
      </button>
      {segments.map((seg, i) => (
        <React.Fragment key={seg.path}>
          <span className="file-breadcrumb__sep" aria-hidden="true">
            <IconChevronRight size={11} />
          </span>
          {i === segments.length - 1 ? (
            <span className="file-breadcrumb__crumb file-breadcrumb__crumb--current">{seg.name}</span>
          ) : (
            <button className="file-breadcrumb__crumb" onClick={() => onNavigate(seg.path)}>
              {seg.name}
            </button>
          )}
        </React.Fragment>
      ))}
    </nav>
  );
};

export default Breadcrumb;
