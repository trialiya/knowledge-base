import { useTranslation } from 'react-i18next';
import HeadCrumbs from '@/components/common/layout/HeadCrumbs';

/** Строит цепочку {name, path} от корня до полного пути (сам путь не включает корень). */
function segmentsOf(path) {
  if (!path) return [];
  let acc = '';
  return path.split('/').map((part) => {
    acc = acc ? `${acc}/${part}` : part;
    return { name: part, path: acc };
  });
}

/**
 * Путь к открытому файлу — он же шапка центра файлового раздела: оболочка общая
 * (.workspace__head), сами крошки — общий <HeadCrumbs>. Метаданные пути (размер,
 * язык, последний коммит) — на вкладке «Инфо» справа, здесь только навигация.
 *
 * Последнее звено — сам открытый файл, поэтому оно не кнопка и разделителя после
 * себя не требует.
 *
 * Цепочку отдаём целиком: не влезающую в шапку середину схлопывает сам
 * HeadCrumbs — по замеру ширины, а глубина пути сама по себе о переполнении
 * ничего не говорит (сегменты репозитория короткие, шесть штук часто помещаются).
 */
const Breadcrumb = ({ path, onNavigate }) => {
  const { t } = useTranslation('files');
  const segments = segmentsOf(path);

  const items = [
    { key: '', label: t('breadcrumb.root'), onNavigate: () => onNavigate('') },
    ...segments.map((seg, i) => ({
      key: seg.path,
      label: seg.name,
      onNavigate: i < segments.length - 1 ? () => onNavigate(seg.path) : undefined,
    })),
  ];

  return (
    <div className="workspace__head">
      <HeadCrumbs items={items} label={t('breadcrumb.label')} />
    </div>
  );
};

export default Breadcrumb;
