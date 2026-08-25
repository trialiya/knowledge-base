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
 */
export default function useGitBranch({ project, refreshToken }) {
  const requestKey = `${refreshToken ?? 0} ${project ?? ''}`;
  // Ответ вместе с ключом, которому он принадлежит, — как в useUncommittedChanges:
  // отдельный флаг loading означал бы setState из эффекта.
  const [answer, setAnswer] = useState(null);
  // Своя перезагрузка после команды: refreshToken принадлежит всему приложению,
  // и дёргать его ради собственной строки — значит перезапросить заодно дерево,
  // изменения и превью, которых fetch не касается.
  const [reloads, setReloads] = useState(0);
  const [running, setRunning] = useState(false);

  const key = `${requestKey} ${reloads}`;

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      gitApi.getBranches({ project, signal: controller.signal }),
      gitApi.getCapabilities({ project, signal: controller.signal }),
    ])
      .then(([status, capabilities]) => setAnswer({ key, status, capabilities }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        // Строка ветки — не главное содержимое панели: репозиторий, который не
        // отвечает про ветки, всё ещё показывает дерево, и строка просто молчит.
        setAnswer({ key, status: null, capabilities: null, error });
      });
    return () => controller.abort();
  }, [key, project]);

  const fresh = answer?.key === key ? answer : null;

  /**
   * Выполнить команду и показать состояние, которое она оставила: ответ уже
   * несёт его в себе (GitCommandResult.status), поэтому перезапрос нужен только
   * ради прав — их команда не меняет. Ошибка возвращается вызывающему, а не
   * гасится здесь: показать её — дело панели, у неё для этого один ErrorModal.
   */
  const run = useCallback(
    (command) => {
      setRunning(true);
      return command({ project })
        .then((result) => {
          setReloads((n) => n + 1);
          return result;
        })
        .finally(() => setRunning(false));
    },
    [project],
  );

  const commands = useMemo(
    () => ({
      fetchRemote: () => run(gitApi.fetch),
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
      loading: !fresh,
      error: fresh?.error ?? null,
      running,
      ...commands,
    }),
    [fresh, running, commands],
  );
}
