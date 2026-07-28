import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import useListNavigation from './useListNavigation';

/**
 * Дерево из трёх строк — как в базе знаний и файлах: папка с двумя детьми.
 * `data-ws-item`, `aria-level` и `[data-ws-chevron]` — единственный контракт
 * между хуком и разметкой строк (см. common/sidePanel.css).
 */
const Tree = ({ open = true, onToggle = () => {}, onActivate = () => {} }) => {
  const onKeyDown = useListNavigation();
  return (
    <div role="tree" tabIndex={0} onKeyDown={onKeyDown} data-testid="tree">
      <div data-ws-item role="treeitem" tabIndex={-1} aria-level={1} aria-expanded={open} onClick={onActivate}>
        <span data-ws-chevron onClick={onToggle} />
        папка
      </div>
      {open && (
        <>
          <div data-ws-item role="treeitem" tabIndex={-1} aria-level={2} onClick={onActivate}>
            первый
          </div>
          <div data-ws-item role="treeitem" tabIndex={-1} aria-level={2} onClick={onActivate}>
            второй
          </div>
        </>
      )}
    </div>
  );
};

/** Список, у которого строка редактируется по месту (как переименование чата). */
const Renaming = ({ onActivate }) => {
  const onKeyDown = useListNavigation();
  return (
    <ul className="ws-list" role="listbox" tabIndex={0} onKeyDown={onKeyDown}>
      <li data-ws-item role="option" tabIndex={-1} onClick={onActivate}>
        <input aria-label="имя" defaultValue="чат" />
      </li>
    </ul>
  );
};

const row = (name) => screen.getByText(name).closest('[data-ws-item]');

describe('useListNavigation', () => {
  it('стрелками вниз/вверх ходит по строкам, Home/End — к краям', () => {
    render(<Tree />);
    const tree = screen.getByTestId('tree');

    fireEvent.keyDown(tree, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(row('папка'));

    fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(row('первый'));

    fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(row('папка'));

    fireEvent.keyDown(document.activeElement, { key: 'End' });
    expect(document.activeElement).toBe(row('второй'));

    fireEvent.keyDown(document.activeElement, { key: 'Home' });
    expect(document.activeElement).toBe(row('папка'));
  });

  it('не уезжает за границы списка', () => {
    render(<Tree />);
    const tree = screen.getByTestId('tree');
    fireEvent.keyDown(tree, { key: 'Home' });
    fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(row('папка'));

    fireEvent.keyDown(document.activeElement, { key: 'End' });
    fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(row('второй'));
  });

  it('Enter открывает строку, но не дублирует нативное нажатие на кнопке', () => {
    const onActivate = vi.fn();
    render(<Tree onActivate={onActivate} />);
    fireEvent.keyDown(screen.getByTestId('tree'), { key: 'ArrowDown' });
    fireEvent.keyDown(document.activeElement, { key: 'Enter' });
    expect(onActivate).toHaveBeenCalledTimes(1);

    // Пробел работает так же, как Enter.
    fireEvent.keyDown(document.activeElement, { key: ' ' });
    expect(onActivate).toHaveBeenCalledTimes(2);
  });

  it('вправо/влево раскрывает и сворачивает папку через её шеврон', () => {
    const onToggle = vi.fn();
    const { rerender } = render(<Tree open={false} onToggle={onToggle} />);
    fireEvent.keyDown(screen.getByTestId('tree'), { key: 'ArrowDown' });

    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' });
    expect(onToggle).toHaveBeenCalledTimes(1); // свёрнута → раскрыть

    rerender(<Tree open onToggle={onToggle} />);
    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' });
    expect(onToggle).toHaveBeenCalledTimes(2); // раскрыта → свернуть
  });

  it('вправо на раскрытой папке шагает внутрь, влево на ребёнке — к родителю', () => {
    render(<Tree open />);
    fireEvent.keyDown(screen.getByTestId('tree'), { key: 'ArrowDown' });

    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(row('первый'));

    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(row('папка'));
  });

  it('не перехватывает клавиши у поля переименования внутри строки', () => {
    const onActivate = vi.fn();
    render(<Renaming onActivate={onActivate} />);
    const input = screen.getByLabelText('имя');
    input.focus();

    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });

    // Фокус остался в поле, строка не «открылась» — стрелки и Enter принадлежат вводу.
    expect(document.activeElement).toBe(input);
    expect(onActivate).not.toHaveBeenCalled();
  });
});
