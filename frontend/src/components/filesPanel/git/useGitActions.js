import { useCallback, useMemo, useRef, useState } from 'react';
import runGitCommand from './runGitCommand';

/**
 * Оркестрация git-команд панели: что спросить перед командой, что показать
 * после и кого предупредить о том, что репозиторий сдвинулся.
 *
 * Живёт отдельно от `useGitBranch`, который знает только про состояние ветки:
 * там — данные, здесь — сценарий. Панели остаётся отрисовать модалки по
 * возвращённым дескрипторам.
 *
 * Команды делятся по тому, где показывается их отказ. Быстрые — переключиться,
 * спрятать в stash, откатить файл — заканчиваются одинаково: успех — сигнал
 * `onRepoChanged` (checkout и stash двигают всё рабочее дерево разом, и точечной
 * инвалидации тут не из чего собрать), отказ — уведомление с текстом самого git.
 * Коммит и push идут из своих окон, и отказ остаётся в окне, из которого их
 * запустили: набранное сообщение и выбор файлов переживают отказ pre-commit
 * hook'а, а «Permission denied (publickey)» читают там же, где нажимали push.
 */
export default function useGitActions({ git, project, refreshToken, onRepoChanged, notify, t }) {
  // Открытое окно: 'commit', 'push' либо null. Одно состояние на оба, потому что
  // одновременно они не открываются — оба запускают команду по одному репозиторию.
  const [dialog, setDialog] = useState(null);
  // Спрашивают ли имя новой ветки. Единственное, что осталось у однострочного
  // запроса: коммит переехал в своё окно, где выбирают ещё и файлы.
  const [naming, setNaming] = useState(false);
  // Файл, который собираются откатить: единственная команда, теряющая работу,
  // и подтверждение обязано назвать файл.
  const [discarding, setDiscarding] = useState(null);
  // Отказ команды, запущенной из окна, — его показывает само окно.
  const [failure, setFailure] = useState(null);
  // Номер открытия окна. Команда переживает закрытие — push к недоступному
  // remote отвечает через десяток секунд, — и её отказ обязан умереть вместе с
  // окном, из которого её запустили: иначе следующее открытое окно (а окна два)
  // встретило бы человека чужой красной карточкой.
  const session = useRef(0);

  // Правило «перечитать в любом случае» — общее с панелью чата, см. runGitCommand.
  const run = useCallback(
    (command) =>
      runGitCommand(command, {
        onSettled: onRepoChanged,
        onFailure: (error) => notify(failureNotice(error, t)),
      }),
    [onRepoChanged, notify, t],
  );

  const runInDialog = useCallback(
    (name, command) => {
      const mine = session.current;
      setFailure(null);
      return runGitCommand(command, {
        onFailure: (error) => {
          if (session.current !== mine) return;
          setFailure({ command: name, reason: error?.reason ?? null });
        },
        onSettled: onRepoChanged,
      });
    },
    [onRepoChanged],
  );

  const openDialog = useCallback((which) => {
    session.current += 1;
    setFailure(null);
    setDialog(which);
  }, []);

  const confirmNewBranch = useCallback(
    (value) => {
      setNaming(false);
      run(() => git.switchBranch(value, true));
    },
    [run, git],
  );

  const confirmDiscard = useCallback(() => {
    const path = discarding;
    setDiscarding(null);
    if (path) run(() => git.discard(path));
  }, [discarding, run, git]);

  /**
   * Состояние репозитория в том виде, в каком его читают общие окна коммита и
   * push (см. `common/git/CommitDialog`). Панель чата собирает такой же объект
   * своим `useChatGit`, добавляя к нему запрет на время работы модели; здесь
   * запрет один — идущая команда, по репозиторию их выполняется одна за раз.
   *
   * Без `changes`: список незакоммиченного панель спрашивает лениво (в режиме
   * дерева он ей не нужен вовсе), и решение «спрашивать ли» принимается по
   * открытому окну — то есть уже по тому, что вернул этот хук. Список
   * подкладывает панель, см. FilesPanel.
   */
  const dialogGit = useMemo(
    () => ({
      status: git.status,
      project,
      refreshToken,
      failure,
      disabled: git.running,
      disabledReason: git.running ? 'running' : null,
      commit: (message, paths) => runInDialog('commit', () => git.commit(message, paths)),
      push: () => runInDialog('push', git.push),
    }),
    [git, project, refreshToken, failure, runInDialog],
  );

  return {
    dialog,
    dialogGit,
    naming,
    discarding,
    // fetch ничего в рабочем дереве не меняет, но счётчики двигает — их
    // поднимает сам useGitBranch своим сигналом, поэтому общий ему не нужен.
    fetch: () => git.fetchRemote().catch((error) => notify(failureNotice(error, t))),
    pull: () => run(() => git.pull()),
    switchBranch: (branch) => run(() => git.switchBranch(branch, false)),
    stashPush: () => run(() => git.stashPush()),
    stashPop: () => run(() => git.stashPop()),
    abortMerge: () => run(() => git.abortMerge()),
    askCommit: () => openDialog('commit'),
    askPush: () => openDialog('push'),
    // Закрытие уносит с собой отказ: окно, открытое заново, начинается с чистого
    // листа, а прошлый отказ к новой попытке отношения не имеет.
    closeDialog: () => openDialog(null),
    askNewBranch: () => setNaming(true),
    cancelNewBranch: () => setNaming(false),
    confirmNewBranch,
    askDiscard: setDiscarding,
    cancelDiscard: () => setDiscarding(null),
    confirmDiscard,
  };
}

/**
 * Дескриптор уведомления об отказе. Текст git'а показываем как есть — «Permission
 * denied (publickey)» говорит человеку, что чинить; своё сообщение только там,
 * где ответа не было вовсе (сеть, 500) и показывать нечего.
 */
const failureNotice = (error, t) => ({
  titleKey: 'git.failed',
  messageKey: 'git.failedMessage',
  params: { reason: error?.reason || t('git.failedUnknown') },
});
