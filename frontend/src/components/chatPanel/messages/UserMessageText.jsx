import { parseChatCommand } from '../run/chatCommands';

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
  if (!command) return <div className="user-message-text">{text}</div>;

  // Текст распадается на узлы, и совпадение find-бара больше не может лежать на
  // границе команды и хвоста. Цена копеечная: искать «compact» вместе со словом
  // из фокуса сжатия — не то, зачем открывают поиск по ленте.
  return (
    <div className="user-message-text">
      {text.slice(0, command.start)}
      <span className="user-message-text__command">{text.slice(command.start, command.end)}</span>
      {text.slice(command.end)}
    </div>
  );
};

export default UserMessageText;
