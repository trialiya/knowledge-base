import { useTranslation } from 'react-i18next';
import InfoList from '@/components/common/ui/InfoList';
import { cacheMissOf, cacheShare, formatTokens } from '../messages/tokenUsage';

/**
 * Системная часть контекста: доля от занятого — там, где занятое известно. Она и есть ответ на
 * вопрос «сколько контекста ушло ещё до разговора», а одно абсолютное число на него не отвечает.
 */
const systemValue = (base, current, t) => {
  if (!base) return null;
  if (!current) return formatTokens(base);
  return t('usage.valuePercent', {
    value: formatTokens(base),
    percent: Math.round((base / current.contextTokens) * 100),
  });
};

/**
 * Вкладка «Usage» правой панели чата: токены всего чата.
 *
 * Не дублирует плашки: в шапке и под ответом стоит по одному короткому числу про последний прогон,
 * а здесь — итог по чату, который в них не влезает и мельком не нужен.
 *
 * Разрез — провайдерского счёта: вход разбит на кэш-промах и кэш-попадание, и вместе с выходом они
 * складываются ровно в Total. Так и только так эти числа сверяются с биллингом провайдера
 * построчно, а сверить их — единственный доступный пользователю способ узнать, что приложение
 * считает верно.
 *
 * Своей вкладкой, а не секцией «Инфо»: набор читают как одно целое, а среди названия, дат и id он
 * терялся. Числа тут разного рода — занятое сейчас (контекст, системная часть) и потраченное за всё
 * время (вход, выход, обращения); как считается каждое, см. tokenUsage.js и RunTokenUsage на бэке.
 */
const ChatUsage = ({ usage }) => {
  const { t } = useTranslation('chat');

  const current = usage?.current;
  const totals = usage?.totals;
  if (!current && !totals) {
    return <p className="info-list__hint">{t('usage.empty')}</p>;
  }
  const cached = totals ? Number(totals.cacheReadTokens) : 0;

  const rows = [
    { label: t('usage.context'), value: current ? formatTokens(current.contextTokens) : null },
    // Системная часть — под контекстом и с долей от него: вопрос к ней всегда «сколько занято ещё
    // до разговора», а он про отношение, а не про абсолютное число.
    { label: t('usage.system'), value: systemValue(usage?.base, current, t) },
    // Провайдер тарифицирует вход двумя ставками, поэтому и разбивка такая же. Без кэша строка
    // одна: «Input (Cache miss)» без парной строки читался бы как часть чего-то большего.
    {
      label: cached > 0 ? t('usage.cacheMiss') : t('usage.input'),
      value: totals ? formatTokens(cacheMissOf(totals)) : null,
    },
    {
      label: t('usage.cacheHit'),
      value: cached > 0 ? t('usage.valuePercent', { value: formatTokens(cached), percent: cacheShare(totals) }) : null,
    },
    {
      label: t('usage.cacheWrite'),
      value: totals && totals.cacheWriteTokens > 0 ? formatTokens(totals.cacheWriteTokens) : null,
    },
    { label: t('usage.output'), value: totals ? formatTokens(totals.outputTokens) : null },
    {
      label: t('usage.total'),
      value: totals ? formatTokens(Number(totals.promptTokens) + Number(totals.outputTokens)) : null,
    },
    // Не токены, а их объяснение: без числа обращений непонятно, почему Total во столько раз
    // больше занятого контекста.
    { label: t('usage.modelCalls'), value: totals ? String(totals.modelCalls) : null },
  ];

  return <InfoList rows={rows} note={usage?.partial ? t('usage.partial') : null} />;
};

export default ChatUsage;
