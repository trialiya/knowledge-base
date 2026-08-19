import { useCallback, useEffect, useRef, useState } from 'react';
import { COPY_DONE_MS } from '@/constants/ui';

/**
 * Копирование в буфер обмена с кратковременным состоянием «скопировано».
 *
 * Один и тот же кусок логики (writeText в try/catch, флаг «готово», таймер на
 * его сброс и очистка таймера при размонтировании) до этого был переписан в
 * каждом месте с кнопкой копирования — CodeBlock, Message, MarkdownEditor,
 * плашки тул-коллов. Новые места берут его отсюда, старые
 * переезжают по мере правок (migrate-on-touch).
 *
 * Возвращает [copiedKey, copy]:
 *   copy(text, key)  — key нужен там, где кнопок несколько (строки InfoList,
 *                      «копировать markdown» / «копировать jira»): по нему
 *                      видно, какая именно сработала. Одна кнопка — key не
 *                      нужен, тогда copiedKey это просто true/null.
 *
 * Ошибку writeText глотаем намеренно: в insecure context (http не на localhost)
 * clipboard API недоступен, и падать из-за этого кнопке незачем — но и «готово»
 * в этом случае не показываем, оно бы врало.
 */
export default function useCopyFeedback() {
  const [copiedKey, setCopiedKey] = useState(null);
  const timerRef = useRef(null);

  useEffect(() => () => clearTimeout(timerRef.current), []);

  const copy = useCallback(async (text, key = true) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      return;
    }
    setCopiedKey(key);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setCopiedKey(null), COPY_DONE_MS);
  }, []);

  return [copiedKey, copy];
}
