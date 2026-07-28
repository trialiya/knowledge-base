import { useEffect, useState } from 'react';

/**
 * Время жизни снимка. Переключение между группами одной страницы — обычный жест
 * («Модели» → «Поиск» → назад), и три группы «Настроек» читают один и тот же
 * `/ai-config`, три группы «Администрирования» — один и тот же `/admin/system`:
 * без TTL каждое переключение уходило в сеть за уже полученными данными.
 *
 * Полминуты — компромисс: серия переключений укладывается в одну загрузку, а
 * рестарт сервера с новым application.yaml виден без перезагрузки вкладки, надо
 * только вернуться в группу чуть позже. Вечного кэша здесь быть не должно
 * (в отличие от usePreviewCache): конфиг меняется на сервере, а не в приложении.
 */
const TTL_MS = 30_000;

/**
 * Кэш на уровне модуля: функция загрузки → { at, promise }.
 *
 * Ключ — сама функция загрузки (метод api-модуля), поэтому две страницы с
 * разными эндпоинтами не мешают друг другу. Храним промис, а не результат: если
 * вторая группа монтируется, пока первый запрос ещё в полёте, она подписывается
 * на него, а не начинает свой.
 */
const cache = new Map();

/** Свежая запись кэша или undefined — отказы в кэше не задерживаются (см. load). */
function fresh(load) {
  const entry = cache.get(load);
  return entry && Date.now() - entry.at < TTL_MS ? entry : undefined;
}

function load(loader) {
  const entry = { at: Date.now(), promise: loader(), value: null };
  entry.promise.then(
    (value) => {
      entry.value = value;
    },
    // Отказ не кэшируем: следующая группа (или возврат в эту) обязана
    // попробовать снова, иначе упавший однажды запрос «залипал» бы на TTL.
    () => cache.delete(loader),
  );
  cache.set(loader, entry);
  return entry;
}

/**
 * Загрузка read-only снимка конфигурации для страниц «Настройки» и
 * «Администрирование» (`/api/settings/ai-config`, `/api/admin/system`).
 *
 * Обе страницы монтируют группы по одной, и каждая группа повторяла бы один и
 * тот же кусок: состояние data/error, отмена по размонтированию, ветки
 * «загрузка» и «ошибка». Хук держит первые три, а рендер веток остаётся за
 * вызывающим — заголовок группы у всех свой.
 *
 * @param loader стабильная функция загрузки (метод api-модуля, не стрелка в JSX):
 *   она же ключ кэша, и стрелка из JSX сбрасывала бы его на каждый рендер
 * @returns {{ data: object|null, error: Error|null }}
 */
const useConfigSnapshot = (loader) => {
  // Уже загруженный свежий снимок показываем сразу: иначе возврат в группу
  // моргал бы «Загрузка…» на один кадр при готовых данных.
  const [state, setState] = useState(() => ({ data: fresh(loader)?.value ?? null, error: null }));

  useEffect(() => {
    let cancelled = false;
    const entry = fresh(loader) ?? load(loader);
    entry.promise.then(
      (result) => {
        if (!cancelled) setState({ data: result, error: null });
      },
      (e) => {
        if (!cancelled) setState({ data: null, error: e });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [loader]);

  return state;
};

export default useConfigSnapshot;
