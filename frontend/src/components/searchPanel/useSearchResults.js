import { useEffect, useState } from 'react';
import gitApi from '@/api/gitApi';
import documentsApi from '@/api/documentsApi';
import chatApi from '@/api/chatApi';

/** Сколько чатов запрашивать: больше двадцати в одном экране всё равно не читают. */
const CHAT_LIMIT = 20;

/** Результат одной категории: ответ либо отказ — но не оба и не «ничего». */
const settle = (r) => (r.status === 'fulfilled' ? { data: r.value, error: null } : { data: null, error: r.reason });

/**
 * Результаты единого поиска: все три категории на один запрос.
 *
 * Категорию пользователь выбирает одну, но счётчики раздел показывает у всех
 * трёх — иначе непонятно, стоит ли туда переключаться, — поэтому и запросов
 * три. Они независимы (`allSettled`): отказ по файлам (битая регулярка, тайм-аут
 * git) не должен прятать найденные документы и чаты.
 *
 * Ответ хранится вместе с ключом запроса, которому принадлежит: «идёт поиск» —
 * это несовпадение ключей, а не отдельный флаг, и пока новый ответ не пришёл на
 * экране остаётся предыдущий, а не пустота.
 *
 * @param query    строка запроса; пустая — не ищем вовсе
 * @param mode     режим поиска по документам (hybrid | semantic | keyword)
 * @param path     glob-фильтр пути (только файлы)
 * @param project  репозиторий, в котором искать; пусто — дефолтный (только файлы)
 * @param rev      ревизия: искать в снимке, а не в рабочем дереве (только файлы)
 * @param regex    трактовать запрос как регулярное выражение (только файлы)
 * @param untracked заходить и в неотслеживаемые файлы (только файлы)
 */
export default function useSearchResults({ query, mode, path, project, rev, regex, untracked }) {
  const key = JSON.stringify([query, mode, path, project, rev, regex, untracked]);
  const [answer, setAnswer] = useState({ key: null, files: null, docs: null, chats: null });

  useEffect(() => {
    if (!query) return undefined;
    const ctrl = new AbortController();
    // Промис-цепочка, а не async-функция: setState из тела async-функции,
    // вызванной эффектом, запрещён правилом react-hooks/set-state-in-effect.
    Promise.allSettled([
      gitApi.grep(query, { path, project, rev, regex, untracked, signal: ctrl.signal }),
      documentsApi.searchGrouped(query, mode, ctrl.signal),
      chatApi.searchChatsGrouped(query, CHAT_LIMIT, ctrl.signal),
    ]).then(([files, docs, chats]) => {
      if (ctrl.signal.aborted) return;
      setAnswer({ key, files: settle(files), docs: settle(docs), chats: settle(chats) });
    });
    return () => ctrl.abort();
  }, [key, query, mode, path, project, rev, regex, untracked]);

  const fresh = answer.key === key;
  return {
    // Пока ответ на текущий ключ не пришёл — показываем предыдущий и говорим,
    // что идёт поиск; пустой запрос не ищет ничего и потому не грузится.
    loading: !!query && !fresh,
    files: answer.files,
    docs: answer.docs,
    chats: answer.chats,
  };
}
