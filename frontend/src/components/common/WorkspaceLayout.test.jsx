import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import WorkspaceLayout from './WorkspaceLayout';

// i18n в тестах не инициализируем — берём ключ как подпись.
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const baseLeft = { title: 'Дерево', children: <div>содержимое дерева</div> };

describe('WorkspaceLayout', () => {
  it('показывает левую панель и сворачивает её по тумблеру', async () => {
    const onToggleLeft = jest.fn();
    render(<WorkspaceLayout left={baseLeft} center={<div>центр</div>} onToggleLeft={onToggleLeft} />);

    expect(screen.getByText('содержимое дерева')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'panels.collapseLeft' }));
    expect(onToggleLeft).toHaveBeenCalled();
  });

  it('в свёрнутом виде показывает рельс с кнопкой разворачивания вместо панели', () => {
    render(<WorkspaceLayout left={baseLeft} center={<div>центр</div>} leftCollapsed onToggleLeft={jest.fn()} />);

    expect(screen.queryByText('содержимое дерева')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'panels.expandLeft' })).toBeInTheDocument();
    expect(screen.getByText('центр')).toBeInTheDocument();
  });

  it('без вкладок не рисует ни правую панель, ни её рельс', () => {
    const { container } = render(<WorkspaceLayout left={baseLeft} center={<div>центр</div>} />);
    expect(container.querySelector('.workspace__side--right')).toBeNull();
    expect(container.querySelector('.workspace__rail--right')).toBeNull();
  });

  it('правая панель свёрнута по умолчанию и раскрывается кликом по иконке вкладки', async () => {
    const onRightTabChange = jest.fn();
    const tabs = [
      { key: 'attachments', label: 'Вложения', icon: <span>📎</span>, badge: 3, content: <div>список вложений</div> },
    ];
    render(
      <WorkspaceLayout left={baseLeft} center={<div>центр</div>} right={tabs} onRightTabChange={onRightTabChange} />,
    );

    // rightTab не задан → раскрытого содержимого нет, есть рельс с бейджем.
    expect(screen.queryByText('список вложений')).not.toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Вложения' }));
    expect(onRightTabChange).toHaveBeenCalledWith('attachments');
  });

  it('показывает содержимое активной вкладки и умеет её закрыть', async () => {
    const onRightTabChange = jest.fn();
    const tabs = [
      { key: 'summary', label: 'Описание', content: <div>текст описания</div> },
      { key: 'attachments', label: 'Вложения', content: <div>список вложений</div> },
    ];
    render(
      <WorkspaceLayout
        left={baseLeft}
        center={<div>центр</div>}
        right={tabs}
        rightTab="summary"
        onRightTabChange={onRightTabChange}
      />,
    );

    expect(screen.getByText('текст описания')).toBeInTheDocument();
    expect(screen.queryByText('список вложений')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Вложения' }));
    expect(onRightTabChange).toHaveBeenCalledWith('attachments');

    await userEvent.click(screen.getByRole('button', { name: 'panels.collapseRight' }));
    expect(onRightTabChange).toHaveBeenCalledWith(null);
  });

  it('устаревшая вкладка из адреса не ломает рендер — панель считается свёрнутой', () => {
    const tabs = [{ key: 'summary', label: 'Описание', icon: <span>★</span>, content: <div>текст описания</div> }];
    const { container } = render(
      <WorkspaceLayout left={baseLeft} center={<div>центр</div>} right={tabs} rightTab="сгинувшая-вкладка" />,
    );
    expect(screen.queryByText('текст описания')).not.toBeInTheDocument();
    expect(container.querySelector('.workspace__rail--right')).not.toBeNull();
  });
});
