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
 * @param {Function} [options.canReplaceDoc] `() => boolean` — спрашивается перед
 *   «Назад»/«Вперёд», которые сменили бы документ базы знаний; отказ
 *   возвращает адрес на место до confirmLeave. Читается там же.
 * @returns `{ nav, pendingView, pendingDiscard, confirmLeave, cancelLeave, ...переходы }` —
 *   `pendingView` — раздел, в который просятся, пока вопрос об уходе открыт;
 *   `pendingDiscard` — вопрос задан «Назад»/«Вперёд», и уход правки выбросит
 */
export default function useAppNavigation(options) {
  const [store] = useState(() => createNavStore(options));
  const { nav, pendingView, pendingDiscard } = useSyncExternalStore(store.subscribe, store.getSnapshot);

  useEffect(() => {
    store.canonicalize();
    window.addEventListener('popstate', store.onPopState);
    return () => window.removeEventListener('popstate', store.onPopState);
  }, [store]);

  return { nav, pendingView, pendingDiscard, ...store.api };
}
