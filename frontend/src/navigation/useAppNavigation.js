import { useEffect, useState, useSyncExternalStore } from 'react';
import { createNavStore } from './navStore';

/**
 * useAppNavigation — навигация приложения для React.
 *
 * Состояние, переходы и запись window.history живут в navStore.js; хук лишь
 * подписывает рендер на стор, канонизирует адрес при монтировании и слушает
 * popstate. Методы стора стабильны по определению — стор один на всё время
 * жизни хука, — поэтому их можно класть в зависимости и пропсы как есть.
 *
 * @param {object}   [options]
 * @param {Function} [options.canLeave] `(prev, next) => boolean` — спрашивается
 *   перед уходом в другой раздел; отказ откладывает переход до confirmLeave
 *   (см. navStore). Читается один раз, при создании стора.
 * @returns `{ nav, pendingView, confirmLeave, cancelLeave, ...переходы }` —
 *   `pendingView` — раздел, в который просятся, пока вопрос об уходе открыт
 */
export default function useAppNavigation(options) {
  const [store] = useState(() => createNavStore(options));
  const { nav, pendingView } = useSyncExternalStore(store.subscribe, store.getSnapshot);

  useEffect(() => {
    store.canonicalize();
    window.addEventListener('popstate', store.onPopState);
    return () => window.removeEventListener('popstate', store.onPopState);
  }, [store]);

  return { nav, pendingView, ...store.api };
}
