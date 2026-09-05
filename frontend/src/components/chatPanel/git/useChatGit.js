import { useCallback, useMemo, useState } from 'react';
import useGitBranch from '@/components/filesPanel/git/useGitBranch';
import useUncommittedChanges from '@/components/filesPanel/changes/useUncommittedChanges';
import runGitCommand from '@/components/filesPanel/git/runGitCommand';

/**
 * Состояние репозитория для панели чата и запуск команд из неё.
 *
 * Данные те же, что у панели «Файлы», и берутся теми же хуками — ветка с
 * правами и список незакоммиченного. Своё здесь ровно два правила, которых у
 * файловой панели нет и быть не может.
 *
 * Первое: пока модель работает, команды не запускаются. Кнопки гасятся, но
 * настоящий запрет — на сервере (см. `ChatGitLog.claimIdleAndOwned`, который
 * чат не проверяет, а занимает на время команды): между нажатием и запросом чат
 * может стать занятым, а окно коммита вообще могло открыться до отправки вопроса.
 *
 * Второе: команда несёт с собой id чата, и бэкенд оставляет в его истории ряд с
 * выводом. Поэтому в черновике, у которого id ещё выдуман фронтом, команд нет
 * вовсе: записывать некуда.
 *
 * Отказ не гасится, а остаётся здесь: панель показывает его текстом самого git
 * («Permission denied (publickey)»), и это ровно то, по чему человек поймёт,
 * что чинить.
 */
export default function useChatGit({
  chatId,
  project,
  refreshToken,
  refsToken,
  busy,
  visible,
  onRepoChanged,
  onRefsChanged,
}) {
  const branch = useGitBranch({ project, refreshToken, refsToken, chat: chatId, onRefsChanged });
  // Список несохранённого рисует только сама вкладка, и стоит он отдельного
  // запроса на каждый тик обновления. Пока вкладку не открыли, спрашивать
  // нечего: ветка и права нужны и закрытой (по ним решается, быть ли вкладке
  // вообще и гореть ли на ней точке), а список — нет.
  const changes = useUncommittedChanges({
    project,
    refreshToken,
    enabled: !!visible && !!chatId && !!branch.capabilities?.commands,
  });
  // Последняя команда — то, что вкладка показывает одной строкой. Журнала нет:
  // вывод целиком лежит в ленте чата, где команда и оставила свой ряд.
  const [last, setLast] = useState(null);
  const [failure, setFailure] = useState(null);

  /**
   * Обе команды двигают рабочее дерево или его отношение к remote, поэтому после
   * каждой поднимается общий сигнал: дерево, список изменений и открытый файл
   * перечитываются вместе.
   *
   * Правило «перечитать и на успехе, и на отказе» — общее с панелью «Файлы»,
   * см. runGitCommand.
   */
  const run = useCallback(
    (name, command) => {
      setFailure(null);
      return runGitCommand(command, {
        onSuccess: (result) => setLast({ command: result?.command ?? name, ok: true, at: Date.now() }),
        onFailure: (error) => {
          setLast({ command: name, ok: false, at: Date.now() });
          setFailure({ command: name, reason: error?.reason ?? null });
        },
        onSettled: onRepoChanged,
      });
    },
    [onRepoChanged],
  );

  const commands = useMemo(
    () => ({
      // Из чата запускаются ровно две команды: сохранить работу и опубликовать
      // её. Ветки, stash, pull и откат живут в панели «Файлы» — второе место с
      // теми же командами обязано было бы с ней разойтись.
      commit: (message, paths) => run('commit', () => branch.commit(message, paths)),
      push: () => run('push', branch.push),
    }),
    [run, branch],
  );

  return useMemo(
    () => ({
      status: branch.status,
      capabilities: branch.capabilities,
      // Проект и сигнал обновления едут вместе с состоянием: окно коммита само
      // спрашивает патч выбранного файла, а окно push — список коммитов, и оба
      // обязаны спрашивать их про тот же репозиторий и на том же тике, что и
      // список изменений рядом.
      project,
      refreshToken,
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
    [branch, changes.entries, last, failure, busy, chatId, commands, project, refreshToken],
  );
}
