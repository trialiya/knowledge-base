/**
 * Фикстуры ответа GET /api/chats/projects (ChatController.getProjects →
 * ProjectOptions: defaultProject + projects[{id, label, available}]).
 *
 * Список проектов спрашивает не только селектор в чате: за ним же идёт всплывающая
 * подсказка ссылки на файл (DocLinkTooltip), поэтому ответ нужен и там, где
 * проектов на экране не видно вовсе — например у редактора документа.
 *
 * Данные — как на профиле h2 из run/application-playwright-smoke.yaml: один
 * проект, он же дефолтный.
 */

/** Единственный проект — тот же `default`, что поднимается в прогоне по живому приложению. */
export const singleDefaultProject = {
  defaultProject: 'default',
  projects: [{ id: 'default', label: 'Project', available: true }],
};

/**
 * Два проекта, один из которых не открылся: селектор помечает недоступный, а не
 * оставляет узнавать об этом по отказу вызова.
 */
export const unavailableProject = {
  defaultProject: 'default',
  projects: [
    { id: 'default', label: 'Project', available: true },
    { id: 'docs', label: 'Docs', available: false },
  ],
};
