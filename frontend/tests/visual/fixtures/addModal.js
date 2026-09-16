/**
 * Фикстура окна «Добавить» базы знаний
 * (components/knowledgeBasePanel/modals/AddModal.jsx).
 *
 * Пропс у окна один содержательный — дерево папок, из которого выбирают место.
 * Документы в него класть незачем: окно само оставляет только папки.
 */

/** Две папки верхнего уровня, у одной — вложенная. */
export const folderTree = [
  {
    id: 'f-docs',
    title: 'Документация',
    type: 'folder',
    children: [{ id: 'f-arch', title: 'Архитектура', type: 'folder', children: [] }],
  },
  { id: 'f-ops', title: 'Эксплуатация', type: 'folder', children: [] },
];
