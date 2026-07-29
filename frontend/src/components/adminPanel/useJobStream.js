import { useCallback, useEffect, useRef, useState } from 'react';
import { readJsonSseStream } from '../../api/sse';

// ─── Длинная операция как поток событий ──────────────────────────────────────
// Экспорт, сравнение и импорт устроены на бэке одинаково: кадр на каждый
// обработанный узел и один терминальный кадр — done со сводкой или error с
// причиной. Здесь этот протокол превращается в состояние строки-операции.
//
// Почему не useOperation из common/OperationRow: тот знает только «идёт/готово/
// ошибка» и рассчитан на один HTTP-ответ. Здесь между стартом и итогом есть что
// показывать, и это главное, ради чего операции переведены на SSE.

const IDLE = { state: 'idle', processed: 0, path: null, summary: null, error: null };

/**
 * @param {(signal:AbortSignal)=>Promise<Response>} start запускает запрос и отдаёт SSE-ответ
 * @param {object} [options]
 * @param {(entry:object)=>void} [options.onEntry] на каждый кадр entry (записи сравнения)
 * @param {(event:object)=>void} [options.onProgress] на каждый кадр progress (строки журнала)
 * @param {()=>void} [options.onStart] перед запуском — сбросить накопленное вызывающим
 * @returns {[object, () => Promise<void>, () => void]} [состояние, запуск, отмена]
 */
export default function useJobStream(start, { onEntry, onProgress, onStart } = {}) {
  const [status, setStatus] = useState(IDLE);
  const abortRef = useRef(null);

  // Незавершённый поток при размонтировании панели оставил бы висеть соединение,
  // а на бэке — задачу, которую уже некому смотреть.
  useEffect(() => () => abortRef.current?.abort(), []);

  const cancel = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setStatus((prev) => (prev.state === 'running' ? { ...prev, state: 'idle' } : prev));
  }, []);

  const run = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    onStart?.();
    setStatus({ ...IDLE, state: 'running' });

    let terminal = null;
    try {
      const res = await start(controller.signal);
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);

      await readJsonSseStream(res, (event) => {
        switch (event.type) {
          case 'ENTRY':
            onEntry?.(event.entry);
            setStatus((prev) => ({ ...prev, processed: event.processed, path: event.path }));
            break;
          case 'PROGRESS':
            onProgress?.(event);
            setStatus((prev) => ({ ...prev, processed: event.processed, path: event.path }));
            break;
          case 'DONE':
            terminal = { state: 'done', summary: event.summary, processed: event.processed };
            break;
          case 'ERROR':
            terminal = { state: 'error', error: event.message };
            break;
          default:
            break;
        }
      });
      // Поток закончился без терминального кадра — это обрыв, а не успех.
      setStatus((prev) => ({
        ...prev,
        ...(terminal ?? { state: 'error', error: null }),
      }));
    } catch (e) {
      if (e.name === 'AbortError') return;
      setStatus((prev) => ({ ...prev, state: 'error', error: e.message }));
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
    }
  }, [start, onEntry, onProgress, onStart]);

  return [status, run, cancel];
}
