import { useTranslation } from 'react-i18next';
import { formatDateTime, formatRelativeTime } from '@/utils/formatting';

/**
 * Момент относительно «сейчас» (`formatRelativeTime`) с точной датой в подсказке:
 * «2 часа назад» отвечает на вопрос «давно ли», а когда именно — видно по
 * наведению. Пустое или битое значение не рисует ничего.
 */
const RelativeTime = ({ value, className }) => {
  const { i18n } = useTranslation();
  const label = formatRelativeTime(value, i18n.language);
  if (!label) return null;
  return (
    <time className={className} dateTime={value} title={formatDateTime(value, i18n.language)}>
      {label}
    </time>
  );
};

export default RelativeTime;
