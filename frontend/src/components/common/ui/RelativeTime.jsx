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
  // Атрибут — машинный формат: строка из ответа может быть и не ISO, и Date.
  return (
    <time className={className} dateTime={new Date(value).toISOString()} title={formatDateTime(value, i18n.language)}>
      {label}
    </time>
  );
};

export default RelativeTime;
