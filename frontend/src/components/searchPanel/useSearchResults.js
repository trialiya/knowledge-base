import { useEffect, useEffectEvent, useState } from 'react';
import gitApi from '@/api/gitApi';
import documentsApi from '@/api/documentsApi';
import chatApi from '@/api/chatApi';
import mergeFileHits from './mergeFileHits';
import commitHits from './commitHits';

/** Сколько файлов просить по имени: больше бэкенд всё равно не отдаст (потолок 50). */
const NAME_LIMIT = 50;

/** Сколько коммитов просить: каждый приходит с описанием целиком. */
const COMMIT_LIMIT = 50;

/** Сколько чатов запрашивать: больше двадцати в одном экране всё равно не читают. */
const CHAT_LIMIT = 20;

/**
 * Ответ одной категории вместе с ключом запроса, которому он принадлежит.
 *
 * «Идёт поиск» — это несовпадение ключей, а не отдельный флаг: пока ответ на
 * новый ключ не пришёл, на экране остаётся предыдущий, а не пустота. Но искать
 * перестали вовсе — ответа нет: счётчик от стёртого запроса утверждал бы, что
 * найденное всё ещё где-то есть.
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

  return { entry: enabled ? answer : null, loading: enabled && answer?.key !== key };
}

/**
 * Результаты единого поиска: четыре категории, каждая со своим запросом.
 *
 * Категорию пользователь выбирает одну, но счётчики раздел показывает у всех —
 * иначе непонятно, стоит ли туда переключаться, — поэтому спрашиваются все
 * сразу. Запросы независимы: отказ по файлам (битая регулярка, тайм-аут git) не
 * должен прятать найденные документы и чаты.
 *
 * Ключ у каждой категории свой, из того, что на неё влияет. Общий ключ на все
 * перезапрашивал бы документы и чаты на смену маски пути, а поиск по
 * документам в режимах semantic и hybrid — это ещё и эмбеддинг запроса.
 *
 * @param query    строка запроса; пустая — не ищем вовсе
 * @param mode     режим поиска по документам (hybrid | semantic | keyword)
 * @param path     glob-фильтр пути (только файлы)
 * @param project  репозиторий, в котором искать; пусто — дефолтный (файлы и коммиты)
 * @param rev      ревизия: искать в снимке, а не в рабочем дереве; у коммитов —
 *                 историю от неё, а не от HEAD (файлы и коммиты)
 * @param regex    трактовать запрос как регулярное выражение (только файлы)
 * @param untracked заходить и в неотслеживаемые файлы (только файлы)
 */
export default function useSearchResults({ query, mode, path, project, rev, regex, untracked }) {
  const enabled = !!query;
  // В снимке коммита неотслеживаемых файлов нет, и бэкенд с ревизией этот
  // параметр не смотрит вовсе (GitController.grep уходит в grepContentAt).
  // Отсюда же и ключ: иначе снятая галочка перезапрашивала бы тот же ответ.
  const inTree = rev ? false : untracked;

  // Имена ищутся только по рабочему дереву: в снимке ревизии списка файлов
  // бэкенд не отдаёт. Регулярка и маска пути — фильтры содержимого; имя, по
  // которому они не проверены, выдачу под ними только засорило бы.
  const byName = !rev && !regex && !path;
  const files = useAnswer(JSON.stringify([query, path, project, rev, regex, inTree]), enabled, (signal) => {
    const grep = gitApi.grep(query, { path, project, rev, regex, untracked: inTree, signal });
    if (!byName) return grep;
    // Отказ поиска по имени не должен прятать найденное в содержимом.
    const names = Promise.resolve()
      .then(() => gitApi.searchFiles(query, { limit: NAME_LIMIT, project, signal }))
      .catch(() => []);
    return Promise.all([grep, names]).then(([g, n]) => mergeFileHits(g, n, query));
  });
  // Маска пути, регулярка и неотслеживаемые — фильтры содержимого файлов; на
  // историю из них влияют только репозиторий и ревизия.
  const commits = useAnswer(JSON.stringify([query, project, rev]), enabled, (signal) =>
    gitApi
      .searchCommits(query, { limit: COMMIT_LIMIT, body: true, rev, project, signal })
      .then((found) => commitHits(found, query, COMMIT_LIMIT)),
  );
  const docs = useAnswer(JSON.stringify([query, mode]), enabled, (signal) =>
    documentsApi.searchGrouped(query, mode, signal),
  );
  const chats = useAnswer(query, enabled, (signal) => chatApi.searchChatsGrouped(query, CHAT_LIMIT, signal));

  return { files, commits, docs, chats };
}
