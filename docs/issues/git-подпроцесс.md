# Один запуск git-подпроцесса

#478 вынес из `GitGrepRunner` общий запуск read-only подпроцесса —
`GitReadProcess` (дедлайн через watchdog, дренаж stderr, потолок строк, деление
только по `\n`). #481 перевёл `GitCommands.exec` на `GitReadProcess.readLine`,
но только на него.

## Что `exec` дублирует

`GitCommands.exec` (`service/file/git/GitCommands.java:589-656`) против
`GitReadProcess.run` (`GitReadProcess.java:87-176`):

- старт процесса в корне репозитория и `IllegalStateException` при отказе
  старта (`:590-591, :605-606` против `:104, :173-174`);
- kill по дедлайну, но по-разному: `exec` читает в
  `CompletableFuture.supplyAsync` (общий ForkJoinPool, блокирующий ввод-вывод
  в нём) и ждёт `process.waitFor(TIMEOUT_SECONDS)`; `GitReadProcess` — watchdog
  на виртуальном потоке с `AtomicBoolean timedOut`;
- обработка interrupt один в один (`:649-651` против `:170-172`);
- ожидание дренажа после выхода: `OUTPUT_DRAIN_SECONDS = 5` через
  `reading.get` против `STDERR_DRAIN_WAIT = 1s` через `awaitDrain`;
- два типа таймаут-исключения: `GitCommandFailedException` (ловит
  `GitCommandController.java:276`) и `GitReadTimeoutException extends
  IllegalStateException` (ловит `GitController.java:108, :142`).

Что у `exec` хуже: потолка строк нет. Длинный fetch/push целиком держится в
`List<String>` и только потом режется до последних 4000 символов (`truncate`,
`:664-669`).

## Что законно различается

- stderr: `exec` сливает его в stdout (`redirectErrorStream(true)`), потому что
  прогресс и отказы git — это и есть то, что показывает панель;
  `GitReadProcess` держит stderr отдельно (до 200 строк), чтобы предупреждение
  не разобрали как вывод.
- Окружение: `GIT_TERMINAL_PROMPT=0`, `GIT_SSH_COMMAND=ssh -o BatchMode=yes`,
  закрытый stdin — сетевые команды.
- `-c core.quotepath=false` нужен только читающим.
- Ненулевой exit у `exec` — ошибка с выводом в тексте; `GitReadProcess`
  возвращает код вызывающему.
- `exec` хранит хвост (git пишет вердикт последним), `GitReadProcess` — голову.
- Масштаб таймаута: 120 с на команду против общего дедлайна 20 с на grep/blame.

## Что сделать

Общий низкоуровневый слой: «запустить argv с окружением и режимом stderr, убить
по дедлайну, стримить строки в consumer, ограниченно подождать дренаж, вернуть
exit code и флаг таймаута». Поверх него:

- `GitReadProcess` добавляет дренаж stderr, потолок-голову и quotepath;
- `exec` — кольцевой буфер последних N строк/символов (закрывает
  неограниченный список), окружение и отображение ненулевого exit в
  `GitCommandFailedException`.

Заодно чтение уйдёт с общего пула, а два типа таймаут-исключений можно
свести к одному с разным маппингом в контроллерах.

Других запусков подпроцессов в бэке нет: скрипты — in-process GraalJS с
`allowCreateProcess(false)` (`ScriptRunner.java:402`). Дренаж stderr в
`GitReadProcess.java:114` читает через `BufferedReader.readLine` (делит по
голому `\r`) — для stderr это безвредно.
