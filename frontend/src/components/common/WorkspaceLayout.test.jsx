import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import WorkspaceLayout from './WorkspaceLayout';
import { resetLeftPanelWidthForTests } from './useLeftPanelWidth';

// i18n в тестах не инициализируем — берём ключ как подпись.
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const baseLeft = { title: 'Дерево', children: <div>содержимое дерева</div> };

// Ширина левой панели — общий стор на уровне модуля (см. useLeftPanelWidth):
// без сброса тест, оставивший её не-дефолтной, ломает соседний, который
// рассчитывает на чистое состояние.
beforeEach(() => {
  resetLeftPanelWidthForTests();
});

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

  it('ширину левой панели можно менять с клавиатуры — и её сразу видят все разделы', async () => {
    // Ширина живёт на :root, а не на конкретной панели: чат и база знаний
    // смонтированы одновременно, и «своя» ширина у каждого снова разводила бы
    // границу панели между разделами.
    const rootWidth = () => document.documentElement.style.getPropertyValue('--ws-left-width');

    // Два раздела на экране разом — как в приложении.
    render(<WorkspaceLayout left={baseLeft} center={<div>чат</div>} />);
    render(<WorkspaceLayout left={baseLeft} center={<div>база знаний</div>} />);
    const [resizer, otherResizer] = screen.getAllByRole('separator', { name: 'panels.resizeLeft' });

    expect(rootWidth()).toBe('280px');

    resizer.focus();
    await userEvent.keyboard('{ArrowRight}{ArrowRight}');
    expect(rootWidth()).toBe('312px');
    // Второй раздел узнал о новой ширине, хотя тянули не его.
    expect(resizer).toHaveAttribute('aria-valuenow', '312');
    expect(otherResizer).toHaveAttribute('aria-valuenow', '312');

    await userEvent.keyboard('{Home}'); // сброс к ширине по умолчанию
    expect(rootWidth()).toBe('280px');
    expect(localStorage.getItem('ui_leftWidth')).toBe('280');
  });

  it('ширина не может отобрать у центра больше 60% окна', async () => {
    // MAX_LEFT_WIDTH (520px) — паспортный потолок для широких экранов; на узком
    // окне он сам по себе не спасает от того, что панель займёт большую часть
    // рабочей области. Верхняя граница — минимум из паспортной и доли текущего
    // window.innerWidth (см. viewportMax() в useLeftPanelWidth).
    const originalWidth = window.innerWidth;
    Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 700 });
    try {
      render(<WorkspaceLayout left={baseLeft} center={<div>центр</div>} />);
      screen.getByRole('separator', { name: 'panels.resizeLeft' }).focus();

      // С большим запасом шагов — попытка упереться в потолок, а не проверка
      // конкретного количества нажатий.
      // eslint-disable-next-line no-await-in-loop
      for (let i = 0; i < 20; i++) await userEvent.keyboard('{ArrowRight}');

      expect(document.documentElement.style.getPropertyValue('--ws-left-width')).toBe('420px'); // 700 * 0.6
    } finally {
      Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: originalWidth });
    }
  });

  it('у свёрнутой панели разделителя нет — тянуть нечего', () => {
    render(<WorkspaceLayout left={baseLeft} center={<div>центр</div>} leftCollapsed onToggleLeft={jest.fn()} />);
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();
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
