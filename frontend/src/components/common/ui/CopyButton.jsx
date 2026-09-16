import { useTranslation } from 'react-i18next';
import { IconCopySmall, IconCopied } from '@/icons/index';
import useCopyFeedback from './useCopyFeedback';
import './buttons.css';

/**
 * Кнопка-иконка «копировать» — иконка, подпись и кратковременное «скопировано»
 * в одном месте. Стоит на общем `icon-btn`: своего вида у копирования нет,
 * а `icon-btn--done` уже описывает подсветку «сделано». Ступень и приглушённость
 * задаёт место, где кнопка стоит, — через `className` (`icon-btn--sm`,
 * `icon-btn--quiet`).
 *
 * props:
 *   value     — что уйдёт в буфер
 *   label     — что именно копируется; уточняет aria-label там, где кнопок
 *               несколько и одного «Копировать» скринридеру мало
 *   title     — своя подсказка вместо общего «Копировать» (у сообщения это
 *               «Копировать сообщение»: в футере рядом стоят другие кнопки)
 *   keepEmpty — не прятать кнопку при пустом значении
 *
 * Пустое значение по умолчанию прячет кнопку целиком: копировать нечего, а
 * неактивная кнопка в шапке секции только занимает место. `keepEmpty` — для
 * постоянного ряда действий (футер сообщения), где исчезнувшая кнопка
 * пересобирала бы ряд: пустой текст там означает пустое сообщение, а не
 * отсутствие самого действия.
 */
const CopyButton = ({ value, label, title, className = '', keepEmpty = false }) => {
  const { t } = useTranslation();
  const [copied, copy] = useCopyFeedback();

  if (!value && !keepEmpty) return null;

  const hint = copied ? t('copied') : title ?? t('copy');

  return (
    <button
      type="button"
      className={`icon-btn${copied ? ' icon-btn--done' : ''}${className ? ` ${className}` : ''}`}
      onClick={() => copy(value ?? '')}
      title={hint}
      aria-label={label ? `${hint}: ${label}` : hint}
    >
      {copied ? <IconCopied /> : <IconCopySmall />}
    </button>
  );
};

export default CopyButton;
