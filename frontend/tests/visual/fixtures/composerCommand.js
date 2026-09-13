// Фикстура композера в режиме команды чату.
//
// Снимать это в приложении неудобно: состояние зависит от набранного текста, и
// на стенде оно задаётся черновиком (`initialText`) сразу. Кейса два — с
// командой и с обычным вопросом: смысл правила «уйдёт чату, а не модели» весь в
// разнице между ними.
//
// Подсветка самого `/compact` — CSS Custom Highlight API, и она на снимке видна
// только в браузере с его поддержкой (Chromium стенда — с ним).

const noop = () => {};

const model = {
  config: { defaultModel: { id: 'gpt' } },
  options: [{ id: 'gpt', label: 'GPT' }],
  selected: 'gpt',
  onChange: noop,
};

const mode = { options: [{ id: 'review', label: 'Ревью' }], selected: '', onChange: noop };

const project = {
  options: [{ id: 'kb', label: 'Project' }],
  defaultId: 'kb',
  selected: 'kb',
  onChange: noop,
};

const base = { model, mode, project, chatId: 'chat-1' };

/** Набрана команда: строка над полем и подсвеченный триггер. */
export const command = { ...base, initialText: '/compact подробнее про миграции' };

/** Обычный вопрос со слэшем внутри: ни строки, ни подсветки — команды здесь нет. */
export const question = { ...base, initialText: 'что именно делает /compact с вложениями?' };

/**
 * Команда набрана в ещё не начатом чате: строка говорит не «уйдёт чату», а почему
 * команда не пройдёт. Причину называет то же правило, по которому откажет
 * отправка (`chatCommandBlock`), — поле не обещает того, чего не будет.
 */
export const commandBlocked = { ...base, chatId: 'new', initialText: '/compact подробнее про миграции' };
