import { useEffect, useEffectEvent, useState } from 'react';
import gitApi from '@/api/gitApi';
import documentsApi from '@/api/documentsApi';
import chatApi from '@/api/chatApi';

/** Сколько чатов запрашивать: больше двадцати в одном экране всё равно не читают. */
const CHAT_LIMIT = 20;

/**
 * Ответ одной категории вместе с ключом запроса, которому он принадлежит.
 *
 * «Идёт поиск» — это несовпадение ключей, а не отдельный флаг: пока ответ на
 * новый ключ не пришёл, на экране остаётся предыдущий, а не пустота.
 *
 * Тело запроса замыкает свежие фильтры и потому меняется на каждый рендер, а
 * перезапускать поиск должен только ключ — отсюда useEffectEvent вместо
 * зависимости.
 *
 * @returns {{ entry: {data, error}|null, loading: boolean }}
 */
function useAnswer(key, enabled, load) {
  const [answer, setAnswer] = useState(null); // { key, data, error } | null
  const start = useEffectEvent((signal) => load(signal));

  useEffect(() => {
    if (!enabled) return undefined;
    const ctrl = new AbortController();
    start(ctrl.signal).then(
      (data) => {
        if (!ctrl.signal.aborted) setAnswer({ key, data, error: null });
      },
      (error) => {
        // Отмена — не отказ: её ответ уже никому не нужен, а на экране должен
        // остаться предыдущий, пока не придёт ответ на новый ключ.
        if (!ctrl.signal.aborted) setAnswer({ key, data: null, error });
      },
    );
    return () => ctrl.abort();
  }, [key, enabled]);

  return { entry: answer, loading: enabled && answer?.key !== key };
}

/**
 * Результаты единого поиска: три категории, каждая со своим запросом.
 *
 * Категорию пользователь выбирает одну, но счётчики раздел показывает у всех
 * трёх — иначе непонятно, стоит ли туда переключаться, — поэтому спрашиваются
 * все три сразу. Запросы независимы: отказ по файлам (битая регулярка, тайм-аут
 * git) не должен прятать найденные документы и чаты.
 *
 * Ключ у каждой категории свой, из того, что на неё влияет. Общий ключ на все
 * три перезапрашивал бы документы и чаты на смену маски пути, а поиск по
 * документам в режимах semantic и hybrid — это ещё и эмбеддинг запроса.
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
  const enabled = !!query;

  const files = useAnswer(JSON.stringify([query, path, project, rev, regex, untracked]), enabled, (signal) =>
    gitApi.grep(query, { path, project, rev, regex, untracked, signal }),
  );
  const docs = useAnswer(JSON.stringify([query, mode]), enabled, (signal) =>
    documentsApi.searchGrouped(query, mode, signal),
  );
  const chats = useAnswer(query, enabled, (signal) => chatApi.searchChatsGrouped(query, CHAT_LIMIT, signal));

  return { files, docs, chats };
}
