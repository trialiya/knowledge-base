import { useTranslation } from 'react-i18next';
import InfoList from '@/components/common/ui/InfoList';
import { cacheMissOf, cacheShare, formatContext, formatTokens, totalOf } from '../messages/tokenUsage';

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
 * Невидимая часть выхода — сколько провайдерский `total` перевешивает вход с выходом. `null` там,
 * где перевеса нет: у модели без reasoning-токенов строка отвечала бы нулём на вопрос, которого
 * никто не задавал.
 */
const reasoningValue = (spent) => {
  if (!spent) return null;
  const hidden = totalOf(spent) - Number(spent.promptTokens || 0) - Number(spent.outputTokens || 0);
  return hidden > 0 ? formatTokens(hidden) : null;
};

/**
 * Вкладка «Usage» правой панели чата: токены всего чата.
 *
 * Не дублирует плашки: в шапке и под ответом стоит по одному короткому числу про последний прогон,
 * а здесь — итог по чату, который в них не влезает и мельком не нужен.
 *
 * Разрез — провайдерского счёта: вход разбит на кэш-промах и кэш-попадание, и вместе с выходом (а
 * у модели с reasoning-токенами — и с невидимой его частью, строкой Reasoning) они складываются
 * ровно в Total. Так и только так эти числа сверяются с биллингом провайдера построчно, а сверить
 * их — единственный доступный пользователю способ узнать, что приложение считает верно.
 *
 * Своей вкладкой, а не секцией «Инфо»: набор читают как одно целое, а среди названия, дат и id он
 * терялся. Числа тут разного рода — занятое сейчас (контекст, системная часть) и потраченное за всё
 * время (вход, выход, обращения); занятое считает фронт по ленте, потраченное приходит с бэкенда
 * (см. useChatUsage и ChatUsageService).
 */
const ChatUsage = ({ usage }) => {
  const { t } = useTranslation('chat');

  const current = usage?.current;
  const spent = usage?.totals?.spent;
  const subagent = usage?.totals?.subagentSpent;
  if (!current && !spent && !subagent) {
    return <p className="info-list__hint">{t('usage.empty')}</p>;
  }
  const cached = spent ? Number(spent.cacheReadTokens) : 0;

  const rows = [
    { label: t('usage.context'), value: current ? formatContext(current) : null },
    // Системная часть — под контекстом и с долей от него: вопрос к ней всегда «сколько занято ещё
    // до разговора», а он про отношение, а не про абсолютное число.
    { label: t('usage.system'), value: systemValue(usage?.totals?.baseContextTokens, current, t) },
    // Провайдер тарифицирует вход двумя ставками, поэтому и разбивка такая же. Без кэша строка
    // одна: «Input (Cache miss)» без парной строки читался бы как часть чего-то большего.
    {
      label: cached > 0 ? t('usage.cacheMiss') : t('usage.input'),
      value: spent ? formatTokens(cacheMissOf(spent)) : null,
    },
    {
      label: t('usage.cacheHit'),
      value: cached > 0 ? t('usage.valuePercent', { value: formatTokens(cached), percent: cacheShare(spent) }) : null,
    },
    {
      label: t('usage.cacheWrite'),
      value: spent && spent.cacheWriteTokens > 0 ? formatTokens(spent.cacheWriteTokens) : null,
    },
    { label: t('usage.output'), value: spent ? formatTokens(spent.outputTokens) : null },
    // Невидимая часть выхода: провайдер считает её отдельно от сгенерированного текста, а платит
    // за неё клиент. Без этой строки Total не сходился бы со строками над ним, и разница выглядела
    // бы ошибкой счёта. Строки нет у моделей без reasoning — там разницы не существует.
    { label: t('usage.reasoning'), value: reasoningValue(spent) },
    { label: t('usage.total'), value: spent ? formatTokens(totalOf(spent)) : null },
    // Не токены, а их объяснение: без числа обращений непонятно, почему Total во столько раз
    // больше занятого контекста.
    { label: t('usage.modelCalls'), value: spent ? String(spent.modelCalls) : null },
  ];

  // Суб-агент — своя модель и свой тариф, поэтому его числа стоят отдельным блоком, а не строками
  // в счёте выше: сложить их с ним значило бы получить сумму по двум тарифам, которая не сверяется
  // со счётом провайдера ни одной строкой. Блока нет вовсе, пока суб-агент в чате не работал.
  const subagentRows = subagent
    ? [
        { label: t('usage.subagentRuns'), value: String(usage.totals.subagentRuns) },
        { label: t('usage.subagentInput'), value: formatTokens(subagent.promptTokens) },
        { label: t('usage.subagentOutput'), value: formatTokens(subagent.outputTokens) },
        { label: t('usage.subagentTotal'), value: formatTokens(totalOf(subagent)) },
      ]
    : null;

  return (
    <>
      <InfoList rows={rows} />
      {subagentRows && <InfoList rows={subagentRows} title={t('usage.subagentTitle')} note={t('usage.subagentNote')} />}
    </>
  );
};

export default ChatUsage;
