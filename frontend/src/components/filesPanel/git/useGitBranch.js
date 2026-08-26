import { useCallback, useEffect, useMemo, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * На какой ветке показанный панелью репозиторий, насколько он разошёлся с
 * upstream и что пользователю вообще разрешено с ним делать.
 *
 * Два ответа за раз, потому что рисуются они одной строкой: ветку показывают
 * всегда, кнопки — только там, где проект их разрешил (см. GitCapabilities).
 * Права спрашиваются вместе с состоянием, а не один раз на приложение: они
 * зависят от проекта и от того, доступно ли дерево на запись прямо сейчас, —
 * перемонтированный ro-mount отбирает их у работающего сервера.
 *
 * `refreshToken` — тот же внешний сигнал «в репозитории что-то поменялось», что
 * и у дерева файлов: коммит, сделанный из панели, двигает и счётчик «впереди».
 *
 * `chat` — id беседы, из которой команду запускают. Панель «Файлы» его не
 * передаёт, и для неё ничего не меняется; с ним бэкенд оставляет в истории чата
 * ряд с выводом и отказывает, пока в этом чате работает модель (см.
 * `GitCommandController`). Чтение состояния от него не зависит: ветка у
 * репозитория одна, кто бы её ни спрашивал.
 */
export default function useGitBranch({ project, refreshToken, chat }) {
  // Ответ вместе с ключом, которому он принадлежит, — как в useUncommittedChanges:
  // отдельный флаг loading означал бы setState из эффекта.
  const [answer, setAnswer] = useState(null);
  const [running, setRunning] = useState(false);
  // Только для fetch: он двигает счётчики, но не рабочее дерево, и поднимать
  // ради него общий сигнал значило бы перезапросить заодно дерево, изменения и
  // превью, которых fetch не касается.
  const [reloads, setReloads] = useState(0);

  const key = `${refreshToken ?? 0} ${project ?? ''} ${reloads}`;

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      gitApi.getBranches({ project, signal: controller.signal }),
      gitApi.getCapabilities({ project, signal: controller.signal }),
    ])
      .then(([status, capabilities]) => setAnswer({ key, project, status, capabilities }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        // Строка ветки — не главное содержимое панели: репозиторий, который не
        // отвечает про ветки, всё ещё показывает дерево, и строка просто молчит.
        setAnswer({ key, project, status: null, capabilities: null, error });
      });
    return () => controller.abort();
  }, [key, project]);

  // Пока перезапрос не вернулся, показываем прежний ответ, а не пустоту: иначе
  // каждая команда гасила бы то, из чего её запустили — она поднимает общий
  // сигнал обновления, сигнал входит в ключ, и на время round-trip'а строка
  // ветки и модалка команд оставались бы без состояния.
  //
  // Но только про тот же репозиторий. Ответ другого проекта не устаревший, а
  // чужой: показать ветку проекта A под выбранным B — это не мигание, это
  // ложь, и модалка по ней предложила бы переключиться на ветку, которой у B
  // нет. Проект входит в ключ, поэтому сравнивается он, а не ключ целиком.
  const fresh = answer?.project === project ? answer : null;
  const loading = answer?.key !== key;

  /**
   * Выполнить команду. Перечитывать состояние здесь нечем и незачем: команда,
   * сдвинувшая рабочее дерево, доходит до панели через общий `refreshToken` —
   * он же входит в ключ запроса, — и лишний запрос отсюда стал бы вторым на ту
   * же перерисовку. Исключение — fetch: он рабочее дерево не трогает и общий
   * сигнал не поднимает, поэтому свою строку обновляет сам.
   *
   * Ошибка возвращается вызывающему, а не гасится здесь: показать её — дело
   * панели, у неё для этого один ErrorModal.
   */
  const run = useCallback(
    (command) => {
      setRunning(true);
      return command({ project, chat }).finally(() => setRunning(false));
    },
    [project, chat],
  );

  const commands = useMemo(
    () => ({
      fetchRemote: () =>
        run(gitApi.fetch).then((result) => {
          setReloads((n) => n + 1);
          return result;
        }),
      pull: () => run(gitApi.pull),
      push: () => run(gitApi.push),
      switchBranch: (branch, create) => run((o) => gitApi.switchBranch(branch, { ...o, create })),
      stashPush: () => run(gitApi.stashPush),
      stashPop: () => run(gitApi.stashPop),
      commit: (message) => run((o) => gitApi.commit(message, o)),
      discard: (path) => run((o) => gitApi.discard(path, o)),
      abortMerge: () => run(gitApi.abortMerge),
    }),
    [run],
  );

  return useMemo(
    () => ({
      status: fresh?.status ?? null,
      capabilities: fresh?.capabilities ?? null,
      loading,
      error: fresh?.error ?? null,
      running,
      ...commands,
    }),
    [fresh, loading, running, commands],
  );
}
