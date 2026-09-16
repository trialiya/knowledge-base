// Фикстура нижней панели композера: три селектора и кнопки действия.
//
// Состояния здесь те, что живьём не поймать: занятость чата держится ровно
// столько, сколько модель пишет ответ, а исчезнувший проект — до первого
// сообщения после него. Снять их на стенде дешевле, чем ловить в приложении с
// поднятым бэкендом и живой моделью.

const noop = () => {};

const model = {
  config: { defaultModel: { id: 'gpt' } },
  options: [
    { id: 'gpt', label: 'GPT' },
    { id: 'sonnet', label: 'Sonnet' },
  ],
  selected: 'gpt',
  onChange: noop,
};

const mode = {
  options: [{ id: 'review', label: 'Ревью' }],
  selected: '',
  onChange: noop,
};

const project = {
  options: [{ id: 'kb', label: 'Project' }],
  defaultId: 'kb',
  selected: 'kb',
  onChange: noop,
};

/** Чат свободен: одна кнопка «отправить», селекторы доступны. */
export const idleComposer = { model, mode, project, busy: false, generating: false };

/**
 * Идёт ответ модели: «остановить» появляется РЯДОМ с «отправить» — писать во время
 * ответа можно, сообщение встанет в очередь прогона. Селекторы при этом заперты:
 * прогон уже едет на своих настройках.
 */
export const generatingComposer = { model, mode, project, busy: false, generating: true };

/**
 * Идёт сжатие контекста (`/compact`): писать некуда — очереди у сжатия нет, —
 * поэтому «отправить» неактивна, и «остановить» тоже: прерывать сжатие нечем.
 */
export const compactingComposer = {
  model,
  mode,
  project,
  busy: true,
  generating: true,
  stoppable: false,
};

/**
 * Проект чата исчез из конфигурации (его выключили или переименовали id):
 * селектор показывает подставленный дефолтный проект, а рядом стоит
 * предупреждение с исчезнувшим id — см. resolveProjectChoice. Живьём это
 * состояние не воспроизвести: незнакомый проект бэкенд отклоняет (400), а
 * колонку chat_topic.project пишет приём сообщения — оно же и приводит её к
 * дефолту, так что предупреждение видно ровно до первого сообщения.
 */
export const projectGoneComposer = {
  model,
  mode,
  project: { ...project, missing: 'billing' },
  busy: false,
  generating: false,
};
