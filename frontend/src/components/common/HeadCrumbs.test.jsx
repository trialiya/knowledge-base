import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import HeadCrumbs from './HeadCrumbs';

// i18n в тестах не инициализируем — берём ключ как подпись.
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const items = [
  { key: 'a', label: 'src', onNavigate: jest.fn() },
  { key: 'b', label: 'main', onNavigate: jest.fn() },
];

const deep = ['repo', 'backend', 'src', 'main', 'App.java'].map((label, i, all) => ({
  key: label,
  label,
  onNavigate: i < all.length - 1 ? jest.fn() : undefined,
}));

/**
 * jsdom ничего не раскладывает: clientWidth всегда 0, поэтому подменённый
 * scrollWidth и означает «строка не влезает». Без подмены цепочка помещается.
 */
const withOverflow = (fn) => {
  const spy = jest.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(500);
  try {
    return fn();
  } finally {
    spy.mockRestore();
  }
};

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
    withOverflow(() => {
      const { container } = render(<HeadCrumbs items={items} label="Путь" />);
      expect(container.querySelector('.workspace__head-crumbs').scrollLeft).toBe(500);
    });
  });

  it('влезающую цепочку не схлопывает, какой бы глубокой она ни была', () => {
    render(<HeadCrumbs items={deep} label="Путь" />);

    expect(screen.getByText('backend')).toBeInTheDocument();
    expect(screen.getByText('main')).toBeInTheDocument();
    expect(screen.queryByText('…')).toBeNull();
  });

  it('не влезающую схлопывает серединой, оставляя корень, папку и сам файл', () => {
    withOverflow(() => render(<HeadCrumbs items={deep} label="Путь" />));

    // keepEnd = 2 без trailingSep: последнее звено — сам файл, и без соседней
    // папки по пути не понять, откуда он открыт.
    expect(screen.getByText('repo')).toBeInTheDocument();
    expect(screen.getByText('main')).toBeInTheDocument();
    expect(screen.getByText('App.java')).toBeInTheDocument();
    expect(screen.queryByText('backend')).toBeNull();
    expect(screen.getByRole('button', { name: 'crumbs.expand' })).toHaveAttribute('title', 'backend / src');
  });

  it('с trailingSep из конца оставляет одно звено — имя объекта идёт заголовком шапки', () => {
    withOverflow(() => render(<HeadCrumbs items={deep} label="Путь" trailingSep />));

    expect(screen.getByText('repo')).toBeInTheDocument();
    expect(screen.getByText('App.java')).toBeInTheDocument();
    expect(screen.queryByText('main')).toBeNull();
  });

  it('по клику «…» середина раскрывается обратно и больше не схлопывается', async () => {
    // Подмену держим и на время клика: иначе «раскрылось» доказывало бы лишь
    // то, что переполнение кончилось, а не то, что кнопка что-то делает.
    const spy = jest.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(500);
    try {
      render(<HeadCrumbs items={deep} label="Путь" />);
      await userEvent.click(screen.getByRole('button', { name: 'crumbs.expand' }));

      expect(screen.getByText('backend')).toBeInTheDocument();
      expect(screen.queryByText('…')).toBeNull();
    } finally {
      spy.mockRestore();
    }
  });
});
