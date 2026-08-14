import { useCallback, useState } from 'react';

/**
 * Одно уведомление на раздел вместо флага и модалки на каждый повод. Раздел
 * рисует один `<ErrorModal>` по возвращённому дескриптору — сколько бы поводов
 * ни было, одновременно на экране всё равно только один.
 *
 * Дескриптор — `{ icon, titleKey, messageKey, params }`: ключи, а не готовый
 * текст, чтобы открытая модалка переезжала на другой язык вместе с остальным
 * интерфейсом. Ключи резолвятся тем `t`, которым рисуют модалку, — то есть в
 * пространстве имён раздела.
 */
export default function useNotice() {
  const [notice, setNotice] = useState(null);

  const notify = useCallback((descriptor) => setNotice(descriptor), []);
  const dismissNotice = useCallback(() => setNotice(null), []);

  return { notice, notify, dismissNotice };
}
