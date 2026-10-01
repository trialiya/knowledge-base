import { useEffect, useEffectEvent, useState } from 'react';

/**
 * Один запрос на ключ: `request(signal)` уходит, когда ключ меняется, и его
 * ответ хранится вместе с ключом, который его получил. Отсюда всё остальное:
 * `loading` — «на текущий ключ ответа ещё нет», так что кадра с ответом на
 * прошлый ключ не бывает; запрос прошлого ключа отменяется сигналом, и его
 * опоздавший ответ отброшен. `key == null` — не спрашивать вовсе.
 *
 * Ключ — строка или число, по которому решается «тот же это запрос или нет»:
 * всё, от чего зависит ответ, должно в него входить. Сама `request` в ключ не
 * входит и может быть лямбдой на месте — эффект её не отслеживает.
 *
 * Без кэша между ключами: вернуться к прошлому ключу — спросить снова. Кэш с
 * затравкой — usePreviewCache.
 */
export default function useKeyedRequest(key, request) {
  const [answer, setAnswer] = useState(null); // { key, value, error } | null
  const start = useEffectEvent((signal) => request(signal));

  useEffect(() => {
    if (key == null) return undefined;
    const controller = new AbortController();
    const done = (value, error) => {
      if (!controller.signal.aborted) setAnswer({ key, value, error });
    };
    start(controller.signal).then(
      (value) => done(value, null),
      (error) => done(null, error),
    );
    return () => controller.abort();
  }, [key]);

  const current = answer?.key === key ? answer : null;
  return {
    loading: key != null && !current,
    value: current?.value ?? null,
    error: current?.error ?? null,
  };
}
