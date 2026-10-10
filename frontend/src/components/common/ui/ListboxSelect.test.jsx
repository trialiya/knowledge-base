import { fireEvent, render, screen } from '@testing-library/react';
import ListboxSelect from './ListboxSelect';

const options = Array.from({ length: 5 }, (_, i) => ({ id: `t${i}`, label: `Tool ${i}` }));

const renderSelect = (props = {}) =>
  render(<ListboxSelect value="t2" options={options} onChange={() => {}} ariaLabel="Choose" {...props} />);

describe('ListboxSelect', () => {
  // Меню, вылезшее за край прокручиваемого предка, не должно сдвигать предок вбок.
  it('открытие и навигация не прокручивают предков', () => {
    const scrollIntoView = vi.fn();
    const focus = vi.spyOn(HTMLElement.prototype, 'focus');
    Element.prototype.scrollIntoView = scrollIntoView;
    renderSelect();

    fireEvent.click(screen.getByRole('button', { name: 'Choose' }));
    const menu = screen.getByRole('listbox');
    fireEvent.keyDown(menu, { key: 'ArrowDown' });

    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(scrollIntoView).not.toHaveBeenCalled();
    expect(screen.getByRole('option', { name: 'Tool 3' })).toHaveClass('lb-select__option--active');
    focus.mockRestore();
    delete Element.prototype.scrollIntoView;
  });

  it('align="end" прижимает меню к правому краю триггера', () => {
    renderSelect({ align: 'end' });
    fireEvent.click(screen.getByRole('button', { name: 'Choose' }));
    expect(screen.getByRole('listbox')).toHaveClass('lb-select__menu--end');
  });

  it('выбор пункта отдаёт его id и закрывает меню', () => {
    const onChange = vi.fn();
    renderSelect({ onChange });
    fireEvent.click(screen.getByRole('button', { name: 'Choose' }));
    fireEvent.click(screen.getByRole('option', { name: 'Tool 4' }));
    expect(onChange).toHaveBeenCalledWith('t4');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
});
