import { parseChatCommand } from '../run/chatCommands';
import CommitLink from '@/components/common/preview/CommitLink';
import { parseCommitLink } from '@/components/common/preview/docLinkParsing';

// Markdown-ссылка в тексте вопроса. Текст вопроса — не markdown, и разбирается
// в нём ровно один вид ссылки — на коммит (см. withCommitLinks).
const MD_LINK = /\[([^\]\n]+)\]\(([^)\s]+)\)/g;

/**
 * Текст с ссылками на коммит, ставшими ссылками. Их вставляет чип коммита из поля
 * ввода (fileChips.expandTokensForSend): модели уходит markdown-ссылка, а в ленте
 * она показывается хешем, по которому можно перейти, а не сырой разметкой.
 * Остальная разметка остаётся текстом — вопрос пользователь писал не markdown'ом,
 * и `[x](y)`, набранное руками, он и увидит.
 */
function withCommitLinks(text, keyPrefix) {
  const parts = [];
  let last = 0;
  for (const m of text.matchAll(MD_LINK)) {
    const commitLink = parseCommitLink(m[2]);
    if (!commitLink) continue;
    if (m.index > last) parts.push(text.slice(last, m.index));
    parts.push(
      <CommitLink key={`${keyPrefix}-${m.index}`} commitLink={commitLink}>
        {m[1].replace(/^`(.*)`$/, '$1')}
      </CommitLink>,
    );
    last = m.index + m[0].length;
  }
  if (parts.length === 0) return text;
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}

/**
 * Текст вопроса пользователя. Если это была команда чату (`/compact`), её токен
 * выделен: в ленте команда иначе неотличима от слова со слэшем, а понять, почему
 * после неё нет ответа модели, надо уметь и спустя сотню сообщений.
 *
 * Разбор — тот же `parseChatCommand`, что сработал на отправке: выделено ровно
 * то, что чат и понял командой. Здесь обычный текст, а не contentEditable,
 * поэтому хватает спана — подсветка через Range нужна только композеру.
 */
const UserMessageText = ({ text }) => {
  const command = parseChatCommand(text);
  if (!command) return <div className="user-message-text">{withCommitLinks(text, 'text')}</div>;

  // Текст распадается на узлы, и совпадение find-бара больше не может лежать на
  // границе команды и хвоста. Цена копеечная: искать «compact» вместе со словом
  // из фокуса сжатия — не то, зачем открывают поиск по ленте.
  return (
    <div className="user-message-text">
      {text.slice(0, command.start)}
      <span className="user-message-text__command">{text.slice(command.start, command.end)}</span>
      {withCommitLinks(text.slice(command.end), 'tail')}
    </div>
  );
};

export default UserMessageText;
