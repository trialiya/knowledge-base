import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import HeadCrumbs from './HeadCrumbs';

const items = [
  { key: 'a', label: 'src', onNavigate: jest.fn() },
  { key: 'b', label: 'main', onNavigate: jest.fn() },
];

describe('HeadCrumbs', () => {
  it('рисует звенья кнопками и зовёт onNavigate по клику', async () => {
    render(<HeadCrumbs items={items} label="Путь" />);

    expect(screen.getByRole('navigation', { name: 'Путь' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'src' }));
    expect(items[0].onNavigate).toHaveBeenCalled();
  });

  it('звено без onNavigate — текущее: не кнопка и идти по нему некуда', () => {
    render(<HeadCrumbs items={[...items, { key: 'c', label: 'App.js' }]} label="Путь" />);

    expect(screen.getByText('App.js')).toHaveClass('workspace__head-crumb--current');
    expect(screen.queryByRole('button', { name: 'App.js' })).toBeNull();
  });

  it('разделителей на один меньше, чем звеньев, — и на один больше с trailingSep', () => {
    // Замыкающий разделитель нужен там, где цепочку продолжает заголовок шапки
    // (база знаний), и не нужен там, где последнее звено её и заканчивает.
    const { container, rerender } = render(<HeadCrumbs items={items} label="Путь" />);
    expect(container.querySelectorAll('.workspace__head-crumb-sep')).toHaveLength(1);

    rerender(<HeadCrumbs items={items} label="Путь" trailingSep />);
    expect(container.querySelectorAll('.workspace__head-crumb-sep')).toHaveLength(2);
  });

  it('пустой путь не рисует ничего', () => {
    const { container } = render(<HeadCrumbs items={[]} label="Путь" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('прокручивает строку к концу пути: важен открытый объект, а не корень', () => {
    // jsdom не раскладывает элементы, поэтому scrollWidth подменяем: проверяем
    // сам факт «уехали в конец», а не конкретное число.
    const scrollWidth = jest.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(500);
    try {
      const { container } = render(<HeadCrumbs items={items} label="Путь" />);
      expect(container.querySelector('.workspace__head-crumbs').scrollLeft).toBe(500);
    } finally {
      scrollWidth.mockRestore();
    }
  });
});
