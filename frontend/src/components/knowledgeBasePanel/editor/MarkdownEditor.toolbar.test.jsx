import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MarkdownEditor from './MarkdownEditor';

/**
 * Кнопка тулбара описывает только преобразование, а текстовое поле достаёт
 * applyTransform — читать реф при сборке списка кнопок нельзя. Тест держит эту
 * развязку: преобразование обязано получить ЖИВОЕ поле с текущим выделением, а
 * не то, что было известно на момент сборки списка.
 */
// i18n в тестах не инициализируем — берём ключ как подпись, по ним и ищем кнопки.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('MarkdownEditor: тулбар', () => {
  function setup(value = 'привет') {
    const onChange = vi.fn();
    render(<MarkdownEditor value={value} onChange={onChange} savedValue={value} />);
    return { onChange, textarea: screen.getByRole('textbox') };
  }

  it('оборачивает выделение разметкой выбранной кнопки', async () => {
    const user = userEvent.setup();
    const { onChange, textarea } = setup('привет мир');
    textarea.setSelectionRange(7, 10); // «мир»

    await user.click(screen.getByTitle('editor.bold'));

    expect(onChange).toHaveBeenCalledWith('привет **мир**');
  });

  it('без выделения оборачивает подставленный текст в позиции каретки', async () => {
    const user = userEvent.setup();
    const { onChange, textarea } = setup('привет мир');
    textarea.setSelectionRange(7, 7);

    await user.click(screen.getByTitle('editor.bold'));

    expect(onChange).toHaveBeenCalledWith('привет **editor.insertText**мир');
  });

  it('префикс строки встаёт в её начало, где бы ни стояла каретка', async () => {
    const user = userEvent.setup();
    const { onChange, textarea } = setup('первая\nвторая');
    textarea.setSelectionRange(10, 10); // внутри «вторая»

    await user.click(screen.getByTitle('editor.heading'));

    expect(onChange).toHaveBeenCalledWith('первая\n## вторая');
  });
});
