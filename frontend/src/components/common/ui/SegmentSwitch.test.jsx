import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SegmentSwitch from './SegmentSwitch';

const OPTIONS = [
  { value: 'diff', label: 'Сравнение', disabled: true },
  { value: 'base', label: 'База', title: 'Старая версия' },
  { value: 'compare', label: 'Изменённая' },
];

describe('SegmentSwitch', () => {
  it('выбранное состояние — в aria-pressed, а группа подписана для диктора', () => {
    render(<SegmentSwitch options={OPTIONS} value="base" onChange={() => {}} label="Что показать" />);

    expect(screen.getByRole('group', { name: 'Что показать' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'База' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Изменённая' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'База' })).toHaveAttribute('title', 'Старая версия');
    expect(screen.getByRole('button', { name: 'Изменённая' })).not.toHaveAttribute('title');
  });

  it('клик отдаёт значение, выключенная кнопка молчит', async () => {
    const onChange = vi.fn();
    render(<SegmentSwitch options={OPTIONS} value="base" onChange={onChange} label="Что показать" />);

    await userEvent.click(screen.getByRole('button', { name: 'Изменённая' }));
    await userEvent.click(screen.getByRole('button', { name: 'Сравнение' }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('compare');
  });
});
