import { useState } from 'react';
import { chatUsageTotals, contextUsageOf } from '../messages/tokenUsage';

/**
 * Токены чата для правой панели: занятый контекст сейчас и итоги по прогонам.
 *
 * Существует ради стабильной ссылки, а не ради счёта. ChatWindow перерисовывается на КАЖДЫЙ чанк
 * стрима, и вкладки правой панели собираются мемо, которое намеренно не зависит от ленты (иначе
 * таблица вложений пересоздавалась бы по буквам ответа). Числа же меняет одно событие RUN_USAGE
 * раз в несколько секунд — поэтому здесь считается на каждый рендер (проход по странице ленты
 * дёшев), но наружу отдаётся прежний объект, пока сами числа не изменились.
 *
 * Сравнение — по строке, а не по полям: полей семь, они разного смысла, и ручной shallow-equal по
 * ним разошёлся бы с ними на первом же добавленном.
 *
 * @param messages лента чата (пузыри), может быть пустой
 * @param partial загружена не вся история (`chat.hasMore`) — итоги тогда относятся к загруженной
 *     части, и «Инфо» обязана это сказать: иначе число выглядит как итог по всему чату
 * @returns {{current: object|null, totals: object|null, partial: boolean}}
 */
const useChatUsage = (messages, partial) => {
  const fresh = { current: contextUsageOf(messages), totals: chatUsageTotals(messages), partial: !!partial };
  const key = JSON.stringify(fresh);

  // Замена во время рендера, а не эффектом (см. .claude/rules/frontend-ui.md): эффект отдал бы
  // один кадр со старыми числами уже после того, как приехали новые.
  const [held, setHeld] = useState({ key, value: fresh });
  if (held.key !== key) {
    setHeld({ key, value: fresh });
    return fresh;
  }
  return held.value;
};

export default useChatUsage;
