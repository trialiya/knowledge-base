import { useTranslation } from 'react-i18next';
import { formatDateTime, formatRelativeTime, isRelativeTimeLive } from '@/utils/formatting';
import useNow from './useNow';

/**
 * Момент относительно «сейчас» (`formatRelativeTime`) с точной датой в подсказке:
 * «2 часа назад» отвечает на вопрос «давно ли», а когда именно — видно по
 * наведению. Пустое или битое значение не рисует ничего.
 *
 * Подпись идёт сама, без новых пропсов (`useNow`), — но только пока она
 * относительная: дату старше суток тик не меняет, и такие подписи на него не
 * подписываются.
 */
const RelativeTime = ({ value, className, formatTitle = formatDateTime }) => {
  const { i18n } = useTranslation();
  const now = useNow(isRelativeTimeLive(value));
  const label = formatRelativeTime(value, i18n.language, now);
  if (!label) return null;
  // Атрибут — машинный формат: строка из ответа может быть и не ISO, и Date.
  return (
    <time className={className} dateTime={new Date(value).toISOString()} title={formatTitle(value, i18n.language)}>
      {label}
    </time>
  );
};

export default RelativeTime;
