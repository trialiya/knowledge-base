import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DetailHeader from './DetailHeader';

/**
 * Переименование в шапке базы знаний работает так же, как в шапке чата: правку
 * открывает клик по самому имени, коммит идёт по blur (Enter его и вызывает),
 * Escape отменяет. Тест держит именно эту развязку — Escape уносит поле из DOM,
 * и приходящий следом blur не должен сохранить черновик.
 */
// i18n в тестах не инициализируем — берём ключ как подпись.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('DetailHeader: переименование', () => {
  const node = { id: 3, title: 'Старое имя', type: 'document' };

  function setup(props = {}) {
    const onRename = vi.fn();
    const { container } = render(
      <DetailHeader node={node} path={[]} onNavigate={vi.fn()} onRename={onRename} onDelete={vi.fn()} {...props} />,
    );
    return { onRename, field: () => container.querySelector('.detail-header__edit') };
  }

  it('открывает правку кликом по имени и сохраняет по Enter', async () => {
    const user = userEvent.setup();
    const { onRename, field } = setup();

    await user.click(screen.getByText('Старое имя'));
    await user.clear(field());
    await user.type(field(), '  Новое имя  {Enter}');

    expect(onRename).toHaveBeenCalledWith(3, 'Новое имя');
  });

  it('по Escape не сохраняет, хотя следом приходит blur', async () => {
    const user = userEvent.setup();
    const { onRename, field } = setup();

    await user.click(screen.getByText('Старое имя'));
    await user.type(field(), ' и хвост{Escape}');

    expect(onRename).not.toHaveBeenCalled();
    expect(screen.getByText('Старое имя')).toBeInTheDocument();
  });

  it('пустое имя не отправляет', async () => {
    const user = userEvent.setup();
    const { onRename, field } = setup();

    await user.click(screen.getByText('Старое имя'));
    await user.clear(field());
    await user.type(field(), '   {Enter}');

    expect(onRename).not.toHaveBeenCalled();
  });

  it('имя системного узла не открывает правку', async () => {
    const user = userEvent.setup();
    const { field } = setup({ node: { ...node, system: true } });

    await user.click(screen.getByText('Старое имя'));

    expect(field()).toBeNull();
  });
});
