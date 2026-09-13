// ─── Команды композера ───────────────────────────────────────────────────────
// Сообщение, начинающееся со слэша, может быть не вопросом модели, а командой
// чату. Разбор живёт здесь, отдельно от useChatRun: «что такое команда» — это
// правило про текст, и проверять его отдельным тестом дешевле, чем через хук.
//
// Триггеры чипов (`/file`, `/doc` — см. composer/chipTriggers.js) сюда не
// относятся: они срабатывают У КАРЕТКИ по ходу набора и разворачиваются в токен
// ещё до отправки, а команда — это всё сообщение целиком, от первого символа.

/** Сжатие контекста: `/compact` и `/сжать`, хвост — фокус сжатия. */
export const CHAT_COMMAND = { COMPACT: 'compact' };

/** Почему команду сейчас не выполнить (см. chatCommandBlock). */
export const COMMAND_BLOCK = { RUNNING: 'running', NOTHING_TO_COMPACT: 'nothingToCompact' };

const COMMANDS = [{ name: CHAT_COMMAND.COMPACT, triggers: ['/compact', '/сжать'] }];

/**
 * Команда, которой является это сообщение, — или null, если это обычный вопрос.
 *
 * Команда обязана начинать сообщение (ведущие пробелы допустимы) и быть отделена
 * от хвоста пробелом или переносом: `/compactor` — это слово, а не команда с
 * хвостом `or`.
 *
 * `start`/`end` — границы самого триггера в переданном тексте. По ним команду
 * подсвечивают — в композере и в отправленном пузыре, — и это единственный
 * способ показать ровно то, что сработает на отправке: разбирать текст второй
 * раз своим правилом значит обещать одно, а отправить другое.
 *
 * @returns {{ name: string, args: string, start: number, end: number } | null}
 *   args — хвост без ведущих пробелов
 */
export function parseChatCommand(text) {
  const src = text || '';
  const start = src.length - src.trimStart().length;
  const trimmed = src.slice(start);
  for (const { name, triggers } of COMMANDS) {
    for (const trigger of triggers) {
      if (!trimmed.toLowerCase().startsWith(trigger)) continue;
      const rest = trimmed.slice(trigger.length);
      if (rest !== '' && !/^\s/.test(rest)) continue;
      return { name, args: rest.trim(), start, end: start + trigger.length };
    }
  }
  return null;
}

/**
 * Что помешает выполнить команду прямо сейчас — или null, если ничего.
 *
 * Правило одно на двоих: по нему композер пишет над полем, что команда не
 * сработает, и по нему же отправка отказывает. Разъехавшись, они дали бы поле,
 * обещающее то, чего отправка не делает, — то же, от чего страхует общий
 * `parseChatCommand`.
 *
 * `running` композер знает чуть шире, чем отправка: у него это «чат занят», а у
 * отправки — «прогону уже выдан runId». Разница — доли секунды между отправкой
 * вопроса и ответом сервера на неё, и в эту щель композер осторожнее, а не
 * смелее: он скажет «не сработает» там, где отправка ещё пропустила бы.
 */
export function chatCommandBlock(command, { running, chatStarted }) {
  if (!command) return null;
  if (running) return COMMAND_BLOCK.RUNNING;
  // В ещё не начатом чате сжимать нечего — и заводить его ради команды незачем.
  if (command.name === CHAT_COMMAND.COMPACT && !chatStarted) return COMMAND_BLOCK.NOTHING_TO_COMPACT;
  return null;
}
