import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatTokens } from '../messages/tokenUsage';

/** 7 → «0:07», 95 → «1:35», 3700 → «1:01:40». Ведущий ноль минут — чтобы таймер не «прыгал». */
const formatElapsed = (ms) => {
  const total = Math.max(0, Math.floor(ms / 1000));
  const two = (v) => String(v).padStart(2, '0');
  const h = Math.floor(total / 3600);
  const m = Math.floor(total / 60) % 60;
  const s = total % 60;
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${m}:${two(s)}`;
};

/**
 * Строка над полем ввода на время прогона: сколько ответ уже идёт и на сколько токенов вырос его
 * input. Поле ввода при прогоне не блокируется — эта строка и говорит пишущему, что чат занят и
 * чем именно.
 *
 * Секундный тик живёт здесь, а не в композере: перерисовывается одна эта строка. Прирост — из
 * живых снимков RUN_USAGE (см. runInputGrowth); провайдер без usage в стриме числа не даст, и
 * тогда остаётся один таймер. Якоря может не быть у прогона, чей RUN_STARTED ещё не доехал, —
 * тогда наоборот, до якоря показывается только прирост.
 *
 * @param startedAt момент старта прогона по часам ЭТОЙ вкладки (chat.runStartedAt), null — не
 *     известен
 * @param inputGrowth прирост input за прогон в токенах, null — не измерен
 */
const RunStatus = ({ startedAt, inputGrowth }) => {
  const { t } = useTranslation('chat');
  const [now, setNow] = useState(() => Date.now());
  // Тик — только пока есть что отсчитывать: без якоря (сжатие, прогон до RUN_STARTED) интервал
  // впустую перерисовывал бы пустой компонент каждую секунду.
  useEffect(() => {
    if (!startedAt) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [startedAt]);

  if (!startedAt && inputGrowth == null) return null;
  // Без role="status": live-регион объявлял бы скринридеру каждую секунду таймера.
  return (
    <div className="run-status">
      {!!startedAt && <span className="run-status__time">{formatElapsed(now - startedAt)}</span>}
      {inputGrowth != null && (
        <span className="run-status__tokens" title={t('input.runTokensTooltip')}>
          {t('input.runTokens', { tokens: formatTokens(inputGrowth) })}
        </span>
      )}
    </div>
  );
};

export default RunStatus;
