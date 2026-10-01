import { fireEvent, render, screen } from '@testing-library/react';
import AppLink from './AppLink';

describe('AppLink', () => {
  it('обычный клик — переход внутри приложения, без перехода браузера', () => {
    const onNavigate = vi.fn();
    render(
      <AppLink href="/files/a.md" onNavigate={onNavigate}>
        a.md
      </AppLink>,
    );
    const link = screen.getByRole('link', { name: 'a.md' });

    const notPrevented = fireEvent.click(link);

    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(notPrevented).toBe(false);
    expect(link).toHaveAttribute('href', '/files/a.md');
  });

  it.each([{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }])(
    'клик %o отдан браузеру',
    (modifier) => {
      const onNavigate = vi.fn();
      render(
        <AppLink href="/files/a.md" onNavigate={onNavigate}>
          a.md
        </AppLink>,
      );

      const notPrevented = fireEvent.click(screen.getByRole('link'), modifier);

      expect(onNavigate).not.toHaveBeenCalled();
      expect(notPrevented).toBe(true);
    },
  );

  it('ref доходит до <a> — по нему карточка ссылки встаёт на место', () => {
    const ref = { current: null };
    render(
      <AppLink ref={ref} href="/x" onNavigate={() => {}}>
        x
      </AppLink>,
    );
    expect(ref.current).toBe(screen.getByRole('link'));
  });

  it('остальные пропсы — на ссылку, свой onClick заменяет переход', () => {
    const onNavigate = vi.fn();
    const onClick = vi.fn();
    render(
      <AppLink href="/x" onNavigate={onNavigate} onClick={onClick} className="c" title="t">
        x
      </AppLink>,
    );
    const link = screen.getByRole('link');
    fireEvent.click(link);

    expect(link).toHaveClass('c');
    expect(link).toHaveAttribute('title', 't');
    expect(onClick).toHaveBeenCalled();
    expect(onNavigate).not.toHaveBeenCalled();
  });
});
