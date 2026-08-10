import { createRef } from 'react';
import { render } from '@testing-library/react';
import ChipEditor from './ChipEditor';
import { getCaretOffset } from './fileChipEditorDom';

vi.mock('../../api/gitApi', () => ({
  default: { searchFiles: vi.fn(), searchCommits: vi.fn(), getFileContent: vi.fn() },
}));

vi.mock('../../api/documentsApi', () => ({
  default: { searchByName: vi.fn(), fetchById: vi.fn() },
}));

const editor = () => document.querySelector('.message-input--rich');

function renderEditor(value) {
  const ref = createRef();
  const props = { onChange: () => {}, onSend: () => {}, placeholder: '' };
  const { rerender } = render(<ChipEditor ref={ref} value="" {...props} />);
  rerender(<ChipEditor ref={ref} value={value} {...props} />);
  return ref;
}

describe('ChipEditor', () => {
  // Регрессия: focus() на contentEditable ставит каретку в начало, и текст,
  // положенный в поле снаружи (вставка фразы), приходилось проматывать целиком.
  it('focusEnd puts the caret after the whole value', () => {
    const text = 'Покажи историю коммитов файла';
    const ref = renderEditor(text);

    ref.current.focusEnd();

    expect(document.activeElement).toBe(editor());
    expect(getCaretOffset(editor())).toBe(text.length);
  });

  it('focusEnd puts the caret after a trailing chip', () => {
    const value = 'Посмотри ⟦file:src/App.jsx⟧';
    const ref = renderEditor(value);

    ref.current.focusEnd();

    expect(getCaretOffset(editor())).toBe(value.length);
  });
});
