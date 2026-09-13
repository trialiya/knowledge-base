import { createRef } from 'react';
import { render } from '@testing-library/react';
import ChipEditor from './ChipEditor';

vi.mock('@/api/gitApi', () => ({
  default: { searchFiles: vi.fn(), searchCommits: vi.fn(), getFileContent: vi.fn() },
}));

vi.mock('@/api/documentsApi', () => ({
  default: { searchByName: vi.fn(), fetchById: vi.fn() },
}));

// Custom Highlight API в тестовой среде нет — подставляем ровно те две вещи, которые
// трогает реестр подсветки: `CSS.highlights` (Map по контракту) и `Highlight`,
// который принимает Range'ы аргументами.
class FakeHighlight {
  constructor(...ranges) {
    this.ranges = ranges;
  }
}

beforeEach(() => {
  window.CSS = { ...window.CSS, highlights: new Map() };
  window.Highlight = FakeHighlight;
});

const highlighted = () => {
  const hl = window.CSS.highlights.get('kb-composer-command');
  return (hl?.ranges || []).map((r) => r.toString());
};

function renderEditor(value) {
  const props = { ref: createRef(), onChange: () => {}, onSend: () => {}, placeholder: '' };
  const { rerender } = render(<ChipEditor value="" {...props} />);
  rerender(<ChipEditor value={value} {...props} />);
  return (next) => rerender(<ChipEditor value={next} {...props} />);
}

describe('подсветка команды в композере', () => {
  it('подсвечен ровно триггер, без хвоста', () => {
    renderEditor('/compact только про поиск');

    expect(highlighted()).toEqual(['/compact']);
  });

  it('ведущие пробелы в подсветку не входят', () => {
    renderEditor('  /сжать');

    expect(highlighted()).toEqual(['/сжать']);
  });

  // Та же граница, что у разбора: `/compactor` — слово, а не команда с хвостом.
  it('слово, начинающееся с триггера, не команда', () => {
    renderEditor('/compactor даёт ошибку');

    expect(highlighted()).toEqual([]);
  });

  it('подсветка снимается, когда команду стёрли', () => {
    const setValue = renderEditor('/compact');
    expect(highlighted()).toEqual(['/compact']);

    setValue('compact');

    expect(highlighted()).toEqual([]);
  });
});
