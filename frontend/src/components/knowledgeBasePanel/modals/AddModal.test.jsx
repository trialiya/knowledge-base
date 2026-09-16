import { render, screen } from '@testing-library/react';
import AddModal from './AddModal';

/**
 * Дерево в окне «Новый элемент» — место для нового узла, то есть одни папки:
 * документы из него компонент выбрасывает сам. Тест держит вторую половину
 * этого правила — ту, которую не видно на снимке: раскрывать нечего и там, где
 * дети есть, но все они документы.
 */
// i18n в тестах не поднимаем — подписи здесь не проверяются.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const tree = [
  {
    id: 'f-docs',
    title: 'Документация',
    type: 'folder',
    children: [
      { id: 'f-arch', title: 'Архитектура', type: 'folder', children: [] },
      { id: 'd-readme', title: 'Как читать', type: 'document' },
    ],
  },
  { id: 'f-notes', title: 'Заметки', type: 'folder', children: [{ id: 'd-note', title: 'Заметка', type: 'document' }] },
  { id: 'd-glossary', title: 'Глоссарий', type: 'document' },
];

const setup = () => render(<AddModal tree={tree} defaultParentId={null} onClose={() => {}} onCreate={() => {}} />);

/** Шеврон строки: у папки, которую нечем раскрыть, на его месте пустая распорка. */
const chevron = (title) => screen.getByText(title).closest('.fp-row').querySelector('.fp-row__chevron svg');

describe('AddModal: выбор места', () => {
  it('документ местом быть не может — ни на верхнем уровне, ни внутри папки', () => {
    setup();

    expect(screen.queryByText('Глоссарий')).toBeNull();
    expect(screen.queryByText('Как читать')).toBeNull();
  });

  it('у папки с одними документами шеврона нет — раскрывать в ней нечего', () => {
    setup();

    expect(chevron('Заметки')).toBeNull();
    expect(chevron('Документация')).not.toBeNull();
  });
});
