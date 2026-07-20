import { useEffect, useState } from 'react';
import chatApi from '../../api/chatApi';

/**
 * Загружает список готовых режимов ассистента (GET /api/chats/modes) один раз.
 * Возвращает массив [{ id, label }] (без синтетического «без режима» — его
 * добавляет ModeSelector).
 *
 * @returns {{ modeOptions: [{id,label}] }}
 */
export default function useModeConfig() {
  const [modeOptions, setModeOptions] = useState([]);

  useEffect(() => {
    let cancelled = false;
    chatApi
      .getModes()
      .then((list) => {
        if (!cancelled) setModeOptions(Array.isArray(list) ? list : []);
      })
      .catch((err) => console.error('Ошибка загрузки списка режимов:', err));
    return () => {
      cancelled = true;
    };
  }, []);

  return { modeOptions };
}
