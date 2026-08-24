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

const COMMANDS = [{ name: CHAT_COMMAND.COMPACT, triggers: ['/compact', '/сжать'] }];

/**
 * Команда, которой является это сообщение, — или null, если это обычный вопрос.
 *
 * Команда обязана начинать сообщение (ведущие пробелы допустимы) и быть отделена
 * от хвоста пробелом или переносом: `/compactor` — это слово, а не команда с
 * хвостом `or`.
 *
 * @returns {{ name: string, args: string } | null} args — хвост без ведущих пробелов
 */
export function parseChatCommand(text) {
  const trimmed = (text || '').trimStart();
  for (const { name, triggers } of COMMANDS) {
    for (const trigger of triggers) {
      if (!trimmed.toLowerCase().startsWith(trigger)) continue;
      const rest = trimmed.slice(trigger.length);
      if (rest !== '' && !/^\s/.test(rest)) continue;
      return { name, args: rest.trim() };
    }
  }
  return null;
}
