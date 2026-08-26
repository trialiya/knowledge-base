import { useTranslation } from 'react-i18next';
import { IconCopySmall, IconCopied } from '@/icons/index';
import useCopyFeedback from './useCopyFeedback';
import './buttons.css';

/**
 * Кнопка-иконка «копировать» — иконка, подпись и кратковременное «скопировано»
 * в одном месте. Стоит на общем `icon-btn`: своего вида у копирования нет,
 * а `icon-btn--done` уже описывает подсветку «сделано».
 *
 * Пустое значение прячет кнопку целиком: копировать нечего, а неактивная
 * кнопка в шапке секции только занимает место.
 *
 * `label` уточняет, что именно копируется, — нужен там, где кнопок несколько
 * и одного «Копировать» скринридеру мало.
 */
const CopyButton = ({ value, size, label, className = '' }) => {
  const { t } = useTranslation();
  const [copied, copy] = useCopyFeedback();

  if (!value) return null;

  const title = copied ? t('copied') : t('copy');

  return (
    <button
      type="button"
      className={`icon-btn${copied ? ' icon-btn--done' : ''}${className ? ` ${className}` : ''}`}
      onClick={() => copy(value)}
      title={title}
      aria-label={label ? `${title}: ${label}` : title}
    >
      {copied ? <IconCopied size={size} /> : <IconCopySmall size={size} />}
    </button>
  );
};

export default CopyButton;
