import { useEffect, useMemo, useState } from 'react';
import chatApi from '@/api/chatApi';

/**
 * Человекочитаемая подпись модели по её id. Модель, выбывшая из конфигурации, показывается
 * самим id: он честнее пустой строки и переживает перенастройку списка — под старым ответом
 * подпись обязана остаться (см. MessageList), даже когда модели в списке уже нет.
 *
 * @param {[{id:string,label:string}]} options список моделей (может быть пустым/не доехавшим)
 * @param {?string} id id модели; null/пусто — подписи нет
 */
export const modelLabelOf = (options, id) => (id ? options?.find((o) => o.id === id)?.label ?? id : null);

/**
 * Загружает конфиг моделей (GET /api/chats/models) один раз и отдаёт его вместе с
 * нормализованным списком опций для селектора.
 *
 * @returns {{ modelConfig: {defaultModel:{id,label}, models:[{id,label}]}|null,
 *             modelOptions: [{id,label}] }}
 */
export default function useModelConfig() {
  const [modelConfig, setModelConfig] = useState(null);

  useEffect(() => {
    let cancelled = false;
    chatApi
      .getModels()
      .then((cfg) => {
        if (!cancelled) setModelConfig(cfg);
      })
      .catch((err) => console.error('Ошибка загрузки списка моделей:', err));
    return () => {
      cancelled = true;
    };
  }, []);

  // Опции для селектора: модели из конфига + дефолтная (если её нет в списке — добавляем).
  const modelOptions = useMemo(() => {
    const def = modelConfig?.defaultModel;
    if (!def?.id) return [];
    const list = Array.isArray(modelConfig.models) ? [...modelConfig.models] : [];
    if (!list.some((m) => m.id === def.id)) list.unshift(def);
    return list;
  }, [modelConfig]);

  return { modelConfig, modelOptions };
}
