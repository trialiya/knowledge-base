import { useCallback, useState } from 'react';
import runGitCommand from './runGitCommand';

/** Что спрашивают перед командой: имя новой ветки или сообщение коммита. */
export const GIT_PROMPT = { BRANCH: 'branch', COMMIT: 'commit' };

/**
 * Оркестрация git-команд панели: что спросить перед командой, что показать
 * после и кого предупредить о том, что репозиторий сдвинулся.
 *
 * Живёт отдельно от `useGitBranch`, который знает только про состояние ветки:
 * там — данные, здесь — сценарий. Панели остаётся отрисовать модалки по
 * возвращённым дескрипторам.
 *
 * Каждая команда заканчивается одинаково: успех — сигнал `onRepoChanged`
 * (checkout и stash двигают всё рабочее дерево разом, и точечной инвалидации
 * тут не из чего собрать), отказ — уведомление с текстом самого git.
 */
export default function useGitActions({ git, onRepoChanged, notify, t }) {
  // Что сейчас спрашивают — ключ из GIT_PROMPT либо null.
  const [prompt, setPrompt] = useState(null);
  // Файл, который собираются откатить: единственная команда, теряющая работу,
  // и подтверждение обязано назвать файл.
  const [discarding, setDiscarding] = useState(null);

  // Правило «перечитать в любом случае» — общее с панелью чата, см. runGitCommand.
  const run = useCallback(
    (command) =>
      runGitCommand(command, {
        onSettled: onRepoChanged,
        onFailure: (error) => notify(failure(error, t)),
      }),
    [onRepoChanged, notify, t],
  );

  const confirmPrompt = useCallback(
    (value) => {
      const which = prompt;
      setPrompt(null);
      if (which === GIT_PROMPT.BRANCH) run(() => git.switchBranch(value, true));
      if (which === GIT_PROMPT.COMMIT) run(() => git.commit(value));
    },
    [prompt, run, git],
  );

  const confirmDiscard = useCallback(() => {
    const path = discarding;
    setDiscarding(null);
    if (path) run(() => git.discard(path));
  }, [discarding, run, git]);

  return {
    prompt,
    discarding,
    // fetch ничего в рабочем дереве не меняет, но счётчики двигает — их
    // поднимает сам useGitBranch своим сигналом, поэтому общий ему не нужен.
    fetch: () => git.fetchRemote().catch((error) => notify(failure(error, t))),
    pull: () => run(() => git.pull()),
    push: () => run(() => git.push()),
    switchBranch: (branch) => run(() => git.switchBranch(branch, false)),
    stashPush: () => run(() => git.stashPush()),
    stashPop: () => run(() => git.stashPop()),
    abortMerge: () => run(() => git.abortMerge()),
    askNewBranch: () => setPrompt(GIT_PROMPT.BRANCH),
    askCommit: () => setPrompt(GIT_PROMPT.COMMIT),
    cancelPrompt: () => setPrompt(null),
    confirmPrompt,
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
const failure = (error, t) => ({
  titleKey: 'git.failed',
  messageKey: 'git.failedMessage',
  params: { reason: error?.reason || t('git.failedUnknown') },
});
