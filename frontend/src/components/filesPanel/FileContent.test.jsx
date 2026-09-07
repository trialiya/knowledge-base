import { render, screen } from '@testing-library/react';
import FileContent from './FileContent';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key }),
}));

describe('FileContent', () => {
  // Снимок ревизии отвечает типом FILE и `file: null`, когда блоб крупнее предела
  // чтения из истории: дерево и предки в таком ответе есть, нет только содержимого.
  // Раньше центр разыменовывал file и падал вместе со всей панелью.
  test('файл без содержимого объясняет это, а не падает', () => {
    render(<FileContent content={{ type: 'file', path: 'huge.bin', file: null }} path="huge.bin" loading={false} />);

    expect(screen.getByText('file.contentUnavailable')).toBeInTheDocument();
  });

  test('файл с содержимым показывает его', () => {
    const file = { content: 'const a = 1;\n', language: 'js', lineCount: 1, sizeBytes: 13 };

    render(<FileContent content={{ type: 'file', path: 'a.js', file }} path="a.js" loading={false} />);

    expect(screen.getByText('const a = 1;')).toBeInTheDocument();
  });
});
