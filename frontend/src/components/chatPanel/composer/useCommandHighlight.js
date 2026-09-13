import { useEffect } from 'react';
import { useHighlightOwner } from '@/components/common/search/useMatchHighlight';
import { NO_RANGES } from '@/components/common/search/findMatches';
import { parseChatCommand } from '../run/chatCommands';

// Имя подсветки; правило `::highlight(...)` — в chatPanel/styles/input.css.
const HL_COMMAND = 'kb-composer-command';

/**
 * Range на триггере команды. До команды может стоять только пробельный хвост
 * (иначе это не команда), поэтому весь триггер лежит в первом узле поля — в
 * текстовом. Если первым оказался не текст (чип в начале значения; <div>,
 * который иногда оставляет за собой браузер), подсветки просто нет: разбирать
 * ради неё DOM целиком дороже, чем стоит сам случай.
 */
const commandRange = (root, value) => {
  const command = parseChatCommand(value);
  const node = root?.firstChild;
  if (!command || node?.nodeType !== Node.TEXT_NODE || node.nodeValue.length < command.end) return NO_RANGES;
  const range = document.createRange();
  range.setStart(node, command.start);
  range.setEnd(node, command.end);
  return [range];
};

/**
 * Подсветка команды прямо в поле ввода — без единой правки DOM: Highlight
 * держит Range, а не узлы-обёртки, поэтому браузерный стек отмены и каретка
 * остаются нетронутыми (вся причина, по которой композер не оборачивает токен
 * в <span>).
 *
 * Range живёт ровно до перерисовки поля, а перерисовывает его только приход
 * нового `value` — на нём и пересчитываем. В браузере без Custom Highlight API
 * (Firefox поддержал его только в свежих версиях) не остаётся ничего: видимая
 * часть режима — строка-подсказка над полем, и она есть везде.
 */
export default function useCommandHighlight(rootRef, value) {
  const publish = useHighlightOwner();

  useEffect(() => {
    publish({ [HL_COMMAND]: commandRange(rootRef.current, value) });
  }, [publish, rootRef, value]);
}
