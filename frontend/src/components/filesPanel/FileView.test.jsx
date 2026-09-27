import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileView from './FileView';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key }),
}));

const binary = { content: null, binary: true, lineCount: 0, sizeBytes: 2048 };
const svg = {
  content: '<svg xmlns="http://www.w3.org/2000/svg"></svg>',
  binary: false,
  lineCount: 1,
  sizeBytes: 44,
};

describe('FileView', () => {
  test('растровую картинку показывает рисунком, а не заглушкой «бинарный файл»', () => {
    render(<FileView file={binary} path="docs/img/logo.png" project="kb" />);

    expect(screen.getByRole('img')).toHaveAttribute('src', '/api/git/files/raw?path=docs%2Fimg%2Flogo.png&project=kb');
    expect(screen.queryByText('file.binary')).not.toBeInTheDocument();
  });

  test('картинка из снимка ревизии берётся из той же ревизии', () => {
    render(<FileView file={binary} path="logo.png" rev="v1.0.0" />);

    expect(screen.getByRole('img')).toHaveAttribute('src', '/api/git/files/raw?path=logo.png&rev=v1.0.0');
  });

  test('SVG открывается рисунком и переключается в исходник и обратно', async () => {
    const user = userEvent.setup();
    render(<FileView file={svg} path="icon.svg" />);

    expect(screen.getByRole('img')).toBeInTheDocument();

    await user.click(screen.getByRole('button'));
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByText(/<svg/)).toBeInTheDocument();

    await user.click(screen.getByRole('button'));
    expect(screen.getByRole('img')).toBeInTheDocument();
  });

  // Выбор вида сделан для одного файла: у следующего он может быть и невозможен
  // (у растра исходника нет), поэтому переживать открытие другого он не должен.
  test('выбор вида не переезжает на другой файл', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<FileView file={svg} path="icon.svg" />);

    await user.click(screen.getByRole('button'));
    expect(screen.queryByRole('img')).not.toBeInTheDocument();

    rerender(<FileView file={svg} path="other.svg" />);
    expect(screen.getByRole('img')).toBeInTheDocument();
  });

  test('картинка, которую сервер не отдал, объясняет это словами', () => {
    render(<FileView file={binary} path="logo.png" />);

    fireEvent.error(screen.getByRole('img'));

    expect(screen.getByText('file.imageUnavailable')).toBeInTheDocument();
  });

  // Дерево и содержимое перезапрашивает панель, а байты картинки грузит браузер
  // по неизменному адресу: без перемонтирования на экране осталась бы прошлая
  // картинка — та, которую только что откатили или подтянули pull'ом.
  test('обновление репозитория перезапрашивает картинку', () => {
    const { rerender } = render(<FileView file={binary} path="logo.png" reloadToken={1} />);
    const before = screen.getByRole('img');

    rerender(<FileView file={binary} path="logo.png" reloadToken={2} />);

    expect(screen.getByRole('img')).not.toBe(before);
  });

  // Расширение обещает разметку, но прочитать её текстом не вышло (UTF-16):
  // показать по кнопке нечего, и кнопки нет.
  test('markdown, не прочитавшийся текстом, кнопку вида не показывает', () => {
    render(<FileView file={{ ...binary, lineCount: 0 }} path="notes.md" />);

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByText('file.binary')).toBeInTheDocument();
  });

  // То же у SVG, и по той же причине — но рисунок остаётся: в UTF-16 браузер
  // его нарисует, читать такой файл текстом отказались мы.
  test('SVG, не прочитавшийся текстом, остаётся рисунком без кнопки вида', () => {
    render(<FileView file={binary} path="icon.svg" />);

    expect(screen.getByRole('img')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  test('файл, который не картинка, остаётся заглушкой', () => {
    render(<FileView file={binary} path="build/app.jar" />);

    expect(screen.getByText('file.binary')).toBeInTheDocument();
  });

  describe('прокрутка к разделу', () => {
    const md = { content: 'вступление\n\n## Установка\nтекст\n## FAQ\n', binary: false, lineCount: 6, sizeBytes: 50 };
    let scrolled;
    beforeEach(() => {
      scrolled = [];
      Element.prototype.scrollIntoView = vi.fn(function scrollIntoView() {
        scrolled.push(this);
      });
    });
    afterEach(() => {
      delete Element.prototype.scrollIntoView;
    });

    // Markdown открывается исходником: строка кода помечена своим номером.
    test('в исходнике едет к строке кода, и повторный клик едет снова', () => {
      const { rerender } = render(<FileView file={md} path="guide.md" />);

      rerender(<FileView file={md} path="guide.md" jump={{ path: 'guide.md', line: 3 }} />);
      rerender(<FileView file={md} path="guide.md" jump={{ path: 'guide.md', line: 3 }} />);

      expect(scrolled).toHaveLength(2);
      expect(scrolled[0].tagName).toBe('TR');
      expect(scrolled[0]).toHaveTextContent('## Установка');
    });

    test('в разметке едет к заголовку с этой строкой исходника', async () => {
      const user = userEvent.setup();
      const { rerender } = render(<FileView file={md} path="guide.md" />);
      await user.click(screen.getByRole('button'));

      rerender(<FileView file={md} path="guide.md" jump={{ path: 'guide.md', line: 5 }} />);

      expect(scrolled).toHaveLength(1);
      expect(scrolled[0].tagName).toBe('H2');
      expect(scrolled[0]).toHaveTextContent('FAQ');
    });

    // Голова и хвост без середины: номер строки в хвосте указывал бы не туда.
    test('у усечённого большого файла к разделу не едет', () => {
      const excerpt = { ...md, truncated: true, fromLine: null };
      const { rerender } = render(<FileView file={excerpt} path="guide.md" />);

      rerender(<FileView file={excerpt} path="guide.md" jump={{ path: 'guide.md', line: 5 }} />);

      expect(scrolled).toHaveLength(0);
    });
  });
});
