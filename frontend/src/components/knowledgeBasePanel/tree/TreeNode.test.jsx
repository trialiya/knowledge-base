import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import TreeNode from './TreeNode';

// i18n в тестах не инициализируем — берём ключ как подпись.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

/**
 * Клик по строке папки — это выбор. Он раскрывает папку, но не сворачивает уже
 * раскрытую: иначе, выбирая папку, в которую только что заглянули шевроном,
 * пользователь терял бы её содержимое из виду. Сворачивают шеврон и повторный
 * клик по уже выбранной папке.
 */
describe('TreeNode: клик по папке', () => {
  const folder = {
    id: 1,
    title: 'Папка',
    type: 'folder',
    hasChildren: true,
    _childrenLoaded: true,
    children: [{ id: 2, title: 'Документ', type: 'document' }],
  };

  function setup(selectedId = null) {
    const onSelect = vi.fn();
    const props = { node: folder, level: 0, onSelect, onDelete: vi.fn(), onReorder: vi.fn(), onLoadChildren: vi.fn() };
    const view = render(<TreeNode {...props} selectedId={selectedId} />);
    const row = () => screen.getByRole('treeitem', { name: /Папка/ });
    const rerender = (id) => view.rerender(<TreeNode {...props} selectedId={id} />);
    return { onSelect, row, rerender, container: view.container };
  }

  it('невыбранную свёрнутую — выбирает и раскрывает', async () => {
    const user = userEvent.setup();
    const { onSelect, row } = setup();
    await user.click(row());
    expect(onSelect).toHaveBeenCalledWith(folder);
    expect(row()).toHaveAttribute('aria-expanded', 'true');
  });

  it('невыбранную раскрытую — выбирает, не сворачивая', async () => {
    const user = userEvent.setup();
    const { onSelect, row, container } = setup();
    await user.click(container.querySelector('[data-ws-chevron]'));
    expect(onSelect).not.toHaveBeenCalled();
    expect(row()).toHaveAttribute('aria-expanded', 'true');

    await user.click(row());
    expect(onSelect).toHaveBeenCalledWith(folder);
    expect(row()).toHaveAttribute('aria-expanded', 'true');
  });

  it('повторный клик по выбранной сворачивает', async () => {
    const user = userEvent.setup();
    const { row, rerender } = setup();
    await user.click(row());
    rerender(1);
    await user.click(row());
    expect(row()).toHaveAttribute('aria-expanded', 'false');
  });
});
