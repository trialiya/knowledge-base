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

  // Такой ответ приходит у файла, удалённого из рабочего дерева: git о нём
  // помнит, показать нечего, и смотреть на него идут в режим изменений — там
  // удалённый файл и виден. Раньше эта ветка была недостижима: бэкенд отвечал
  // на такой путь отказом, и центр показывал «не удалось загрузить».
  test('удалённый файл в режиме изменений показывает diff, а не «не найдено»', () => {
    const diff = {
      entry: {
        status: 'D',
        path: 'gone.js',
        patchHeader: '--- a/gone.js\n+++ /dev/null',
        patch: '@@ -1 +0,0 @@\n-var a;',
      },
      loading: false,
      error: false,
    };

    render(<FileContent content={{ type: 'not-found', path: 'gone.js' }} path="gone.js" loading={false} diff={diff} />);

    expect(screen.getByText('changes.status.D')).toBeInTheDocument();
    expect(screen.queryByText('file.notFound')).not.toBeInTheDocument();
  });

  test('файл с содержимым показывает его', () => {
    const file = { content: 'const a = 1;\n', language: 'js', lineCount: 1, sizeBytes: 13 };

    render(<FileContent content={{ type: 'file', path: 'a.js', file }} path="a.js" loading={false} />);

    expect(screen.getByText('const a = 1;')).toBeInTheDocument();
  });
});
