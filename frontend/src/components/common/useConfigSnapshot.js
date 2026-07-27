import { useEffect, useState } from 'react';

/**
 * Загрузка read-only снимка конфигурации для страниц «Настройки» и
 * «Администрирование» (`/api/settings/ai-config`, `/api/admin/system`).
 *
 * Обе страницы монтируют группы по одной, и каждая группа повторяла бы один и
 * тот же кусок: состояние data/error, отмена по размонтированию, ветки
 * «загрузка» и «ошибка». Хук держит первые три, а рендер веток остаётся за
 * вызывающим — заголовок группы у всех свой.
 *
 * Кэша здесь намеренно нет (в отличие от usePreviewCache): конфиг читается с
 * сервера, и после его перезапуска панель должна показывать новые значения, а
 * не то, что осело в памяти вкладки.
 *
 * @param load стабильная функция загрузки (метод api-модуля, не стрелка в JSX)
 * @returns {{ data: object|null, error: Error|null }}
 */
const useConfigSnapshot = (load) => {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    load()
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((e) => {
        if (!cancelled) setError(e);
      });
    return () => {
      cancelled = true;
    };
  }, [load]);

  return { data, error };
};

export default useConfigSnapshot;
