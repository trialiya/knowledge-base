import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileView from './FileView';
import gitApi from '@/api/gitApi';
import { navigateToFile } from '@/navigation/fileNavigationBus';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'en' } }),
}));
// Только blame — мок: адрес картинки (`rawUrl`) строится настоящим клиентом.
vi.mock('@/api/gitApi', async (importOriginal) => {
  const actual = await importOriginal();
  return { default: { ...actual.default, getBlame: vi.fn() } };
});
vi.mock('@/navigation/fileNavigationBus', () => ({ navigateToFile: vi.fn() }));

afterEach(() => vi.resetAllMocks());

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

    await user.click(screen.getByTitle('file.togglePicture'));
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByText(/<svg/)).toBeInTheDocument();

    await user.click(screen.getByTitle('file.togglePicture'));
    expect(screen.getByRole('img')).toBeInTheDocument();
  });

  // Выбор вида сделан для одного файла: у следующего он может быть и невозможен
  // (у растра исходника нет), поэтому переживать открытие другого он не должен.
  test('выбор вида не переезжает на другой файл', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<FileView file={svg} path="icon.svg" />);

    await user.click(screen.getByTitle('file.togglePicture'));
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
      await user.click(screen.getByTitle('file.toggleMarkdown'));

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

    // Ячейка blame ведёт сюда с `?lines=`: строки ханка в снимке коммита.
    test('выделенные адресом строки подсвечены, и к ним едет один раз', () => {
      const { rerender } = render(<FileView file={md} path="guide.md" lines="3-4" />);

      const marked = [...document.querySelectorAll('.file-code__row--marked')];
      expect(marked.map((tr) => tr.dataset.line)).toEqual(['3', '4']);
      expect(scrolled).toHaveLength(1);
      expect(scrolled[0].dataset.line).toBe('3');

      // Перечитанный файл (обновление репозитория) уже показанное место не повторяет.
      rerender(<FileView file={{ ...md }} path="guide.md" lines="3-4" />);
      expect(scrolled).toHaveLength(1);
    });

    // Ссылка → другой файл → «Назад» на ссылку: выделение показано заново.
    test('возврат к тем же строкам после файла без выделения едет к ним снова', () => {
      const { rerender } = render(<FileView file={md} path="guide.md" lines="3-4" />);
      rerender(<FileView file={md} path="other.md" />);
      rerender(<FileView file={md} path="guide.md" lines="3-4" />);

      expect(scrolled.map((el) => el.dataset.line)).toEqual(['3', '3']);
    });

    test('мусор в адресе ничего не выделяет', () => {
      render(<FileView file={md} path="guide.md" lines="9-3" />);

      expect(document.querySelectorAll('.file-code__row--marked')).toHaveLength(0);
      expect(scrolled).toHaveLength(0);
    });
  });

  describe('колонка blame', () => {
    const code = { content: 'one\ntwo\nthree', binary: false, lineCount: 3, sizeBytes: 13, tracked: true };
    const hash = 'a'.repeat(40);
    const hunks = [
      {
        fromLine: 1,
        lineCount: 2,
        hash,
        author: 'Alice',
        date: '2024-01-02T03:04:05Z',
        summary: 'first',
        // Файл в том коммите лежал под старым именем: ссылка ведёт по нему.
        path: 'old.js',
        // И строки там стояли ниже: ссылка выделяет их, а не нынешние номера.
        sourceLine: 7,
      },
      { fromLine: 3, lineCount: 1, hash: null },
    ];

    test('без тумблера колонки нет и её не спрашивают', () => {
      render(<FileView file={code} path="a.js" blame />);

      expect(screen.queryByText('file.showBlame')).not.toBeInTheDocument();
      expect(gitApi.getBlame).not.toHaveBeenCalled();
    });

    test('тумблер включает колонку через обработчик, а не сам', async () => {
      const user = userEvent.setup();
      const onToggleBlame = vi.fn();
      render(<FileView file={code} path="a.js" onToggleBlame={onToggleBlame} />);

      await user.click(screen.getByText('file.showBlame'));

      expect(onToggleBlame).toHaveBeenCalledWith(true);
      expect(gitApi.getBlame).not.toHaveBeenCalled();
    });

    test('включённая колонка подписывает первую строку ханка и ведёт к его строкам в снимке коммита', async () => {
      gitApi.getBlame.mockResolvedValue({ path: 'a.js', hunks });
      const user = userEvent.setup();
      render(<FileView file={code} path="a.js" project="kb" blame onToggleBlame={() => {}} />);

      expect(screen.getByText('file.showBlame')).toHaveAttribute('aria-pressed', 'true');
      await waitFor(() => expect(screen.getByText('first')).toBeInTheDocument());
      expect(gitApi.getBlame).toHaveBeenCalledWith('a.js', expect.objectContaining({ project: 'kb' }));
      // Одна ячейка на две строки ханка; незакоммиченная строка подписана словами.
      const link = screen.getByRole('link', { name: /first/ });
      expect(link.closest('td')).toHaveAttribute('rowspan', '2');
      expect(screen.getByText('file.blameUncommitted')).toBeInTheDocument();
      // В подписи — дата и описание; автор и хеш — только в подсказке.
      expect(link).not.toHaveTextContent('Alice');
      expect(link).not.toHaveTextContent('aaaaaaa');
      expect(link.title).toMatch(/^first\nAlice · aaaaaaa · /);
      expect(link).toHaveAttribute('href', `/files/old.js?project=kb&rev=${hash}&lines=7-8&right=commit`);

      await user.click(link);

      // backLines — строки ханка в ЭТОМ файле: к ним вернёт «Назад»; в адрес ссылки они не идут.
      expect(navigateToFile).toHaveBeenCalledWith('old.js', 'kb', {
        rev: hash,
        lines: '7-8',
        backLines: '1-2',
        right: 'commit',
      });
    });

    // Номера строк усечённого файла не настоящие, у неотслеживаемого истории нет,
    // а в diff'е строки — не строки файла.
    test('у усечённого, неотслеживаемого и показанного diff-ом файла тумблера нет', () => {
      const { rerender } = render(
        <FileView file={{ ...code, truncated: true, fromLine: null }} path="a.js" onToggleBlame={() => {}} />,
      );
      expect(screen.queryByText('file.showBlame')).not.toBeInTheDocument();

      rerender(<FileView file={{ ...code, tracked: false }} path="a.js" onToggleBlame={() => {}} />);
      expect(screen.queryByText('file.showBlame')).not.toBeInTheDocument();

      rerender(
        <FileView file={code} path="a.js" diff={{ entry: null, loading: false }} showDiff onToggleBlame={() => {}} />,
      );
      expect(screen.queryByText('file.showBlame')).not.toBeInTheDocument();
    });

    test('отказ сервера показан бейджем, колонка остаётся пустой', async () => {
      gitApi.getBlame.mockRejectedValue(new Error('503'));
      render(<FileView file={code} path="a.js" blame onToggleBlame={() => {}} />);

      await waitFor(() => expect(screen.getByText('file.blameError')).toBeInTheDocument());
      expect(document.querySelectorAll('.file-code__blame')).toHaveLength(3);
    });
  });
});
