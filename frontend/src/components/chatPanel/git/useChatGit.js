import { useCallback, useMemo, useState } from 'react';
import useGitBranch from '@/components/filesPanel/git/useGitBranch';
import useUncommittedChanges from '@/components/filesPanel/changes/useUncommittedChanges';

/**
 * Состояние репозитория для панели чата и запуск команд из неё.
 *
 * Данные те же, что у панели «Файлы», и берутся теми же хуками — ветка с
 * правами и список незакоммиченного. Своё здесь ровно два правила, которых у
 * файловой панели нет и быть не может.
 *
 * Первое: пока модель работает, команды не запускаются. Кнопки гасятся, но
 * настоящий запрет — на сервере (см. `ChatGitLog.requireIdleAndOwned`): между
 * нажатием и запросом чат может стать занятым, а модалка вообще могла открыться
 * до отправки вопроса.
 *
 * Второе: команда несёт с собой id чата, и бэкенд оставляет в его истории ряд с
 * выводом. Поэтому в черновике, у которого id ещё выдуман фронтом, команд нет
 * вовсе: записывать некуда.
 *
 * Отказ не гасится, а остаётся здесь: панель показывает его текстом самого git
 * («Permission denied (publickey)»), и это ровно то, по чему человек поймёт,
 * что чинить.
 */
export default function useChatGit({ chatId, project, refreshToken, busy, onRepoChanged }) {
  const branch = useGitBranch({ project, refreshToken, chat: chatId });
  const changes = useUncommittedChanges({
    project,
    refreshToken,
    enabled: !!chatId && !!branch.capabilities?.commands,
  });
  // Последняя команда — то, что вкладка показывает одной строкой. Журнала нет:
  // вывод целиком лежит в ленте чата, где команда и оставила свой ряд.
  const [last, setLast] = useState(null);
  const [failure, setFailure] = useState(null);

  const run = useCallback(
    (name, command) => {
      setFailure(null);
      return command()
        .then((result) => {
          setLast({ command: result?.command ?? name, ok: true, at: Date.now() });
          onRepoChanged?.();
          return result;
        })
        .catch((error) => {
          setLast({ command: name, ok: false, at: Date.now() });
          setFailure({ command: name, reason: error?.reason ?? null });
          // Отказ тоже мог сдвинуть дерево: конфликтующий `stash pop` сначала
          // накладывает stash и только потом отказывает, оставляя на диске
          // настоящие конфликты. Обновлять только на успехе значило бы
          // показывать состояние, которого больше нет.
          onRepoChanged?.();
          return null;
        });
    },
    [onRepoChanged],
  );

  const commands = useMemo(
    () => ({
      fetch: () => run('fetch', branch.fetchRemote),
      pull: () => run('pull', branch.pull),
      push: () => run('push', branch.push),
      switchBranch: (name) => run(`switch ${name}`, () => branch.switchBranch(name, false)),
      stashPush: () => run('stash', branch.stashPush),
      stashPop: () => run('stash pop', branch.stashPop),
      commit: (message) => run('commit', () => branch.commit(message)),
      abortMerge: () => run('merge --abort', branch.abortMerge),
    }),
    [run, branch],
  );

  return useMemo(
    () => ({
      status: branch.status,
      capabilities: branch.capabilities,
      loading: branch.loading,
      running: branch.running,
      changes: changes.entries,
      last,
      failure,
      dismissFailure: () => setFailure(null),
      disabled: !!busy || branch.running || !chatId,
      // Причин «сейчас нельзя» три, и они разные: модель работает, команда уже
      // идёт, чата ещё нет. Одна подпись на все три врала бы в двух случаях из
      // трёх — а именно её человек и читает, чтобы понять, чего ждать.
      disabledReason: busy ? 'busy' : branch.running ? 'running' : chatId ? null : 'draft',
      ...commands,
    }),
    [branch, changes.entries, last, failure, busy, chatId, commands],
  );
}
