/**
 * Фикстура перехода к найденному сообщению: что видно в центре чата, когда чат
 * открыли из единого поиска (components/chatPanel/center/useInChatSearch.js,
 * messages/MessageList.jsx, общий FindBar).
 *
 * Совпадения намеренно и в вопросе, и в ответе: кейс про то, что активным
 * подсвечено ВСЁ сообщение целиком (бар ходит по сообщениям, а не по
 * вхождениям), а остальные вхождения — тише. Данные синтетические, но по форме
 * это пузыри ленты (см. run/messagesPage.js: mid, dbId, sender, text).
 */

/** Запрос, с которым сюда пришли: он же стоит в адресе как `?find=`. */
export const query = 'таймаут';

export const openedFromSearch = [
  { mid: 'm1', dbId: 101, sender: 'user', text: 'Почему падает индексация большого репозитория?' },
  {
    mid: 'm2',
    dbId: 102,
    sender: 'ai',
    text: 'Скорее всего, срабатывает **таймаут** `git grep`: процесс не успевает пройти дерево целиком и его снимают принудительно.',
  },
  { mid: 'm3', dbId: 103, sender: 'user', text: 'А где этот таймаут настраивается?' },
  {
    mid: 'm4',
    dbId: 104,
    sender: 'ai',
    text: 'Ключ `kb.git.grep.timeout` в application.yml. Таймаут по умолчанию — 10 секунд; при нём же пишется предупреждение в лог.',
  },
];

// Счётчик бара считает СООБЩЕНИЯ (список совпадений серверный), а не вхождения,
// и по умолчанию бар садится на самое свежее из них. Выводим и то и другое из
// самой ленты: захардкоженное «3/3» соврало бы при первой же правке текстов.
const matched = openedFromSearch.filter((m) => new RegExp(query, 'i').test(m.text));

/** Сколько сообщений совпало и на каком из них стоит бар. */
export const total = matched.length;
export const activeIndex = total - 1;
export const activeMid = matched[activeIndex].mid;

/**
 * Та же лента, но пришли по ссылке на КОНКРЕТНОЕ сообщение (`?msg=`) — клик по
 * строке карточки результата, а не по её заголовку. Активно не самое свежее
 * совпадение, а названное: здесь первое из совпавших.
 */
export const openedOnMessage = openedFromSearch;
export const messageIndex = 0;
export const messageMid = matched[messageIndex].mid;
