import { useRef } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileContent from './FileContent';
import ModalShell from '@/components/common/modal/ModalShell';
import useDismissable from '@/components/common/layout/useDismissable';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key }),
}));

const pressCtrlF = () => fireEvent.keyDown(window, { key: 'f', code: 'KeyF', ctrlKey: true });
const pressEscape = () => fireEvent.keyDown(document, { key: 'Escape' });

const bar = () => document.querySelector('.find-bar');
const counter = () => document.querySelector('.find-bar__count')?.textContent;

const FILE = {
  content: 'const needle = 1;\nconst other = 2;\n// needle again\n',
  language: 'js',
  lineCount: 3,
  sizeBytes: 52,
};

function renderFile(props = {}) {
  const onFindChange = vi.fn();
  const view = render(
    <FileContent
      content={{ type: 'file', path: 'a.js', file: props.file ?? FILE }}
      path="a.js"
      loading={false}
      find=""
      findRegex={false}
      onFindChange={onFindChange}
      {...props}
    />,
  );
  return { ...view, onFindChange };
}

/**
 * Совпадения подсвечиваются через CSS Custom Highlight API, которого в jsdom
 * нет: проверяем то, что видно и без него, — счётчик и то, что бар вообще
 * открылся. Это ровно тот фолбэк, который остаётся в браузерах без поддержки.
 */
describe('поиск в открытом файле', () => {
  test('переход из поиска открывает бар с подставленным запросом', () => {
    renderFile({ find: 'needle' });

    expect(bar()).not.toBeNull();
    expect(screen.getByPlaceholderText('find.placeholder')).toHaveValue('needle');
    expect(counter()).toBe('1/2');
  });

  test('без запроса в адресе бара нет — файл открыли не из поиска', () => {
    renderFile();

    expect(bar()).toBeNull();
  });

  test('Ctrl+F открывает бар и в файле, гася браузерный поиск по странице', () => {
    renderFile();

    const notPrevented = pressCtrlF();

    expect(bar()).not.toBeNull();
    expect(notPrevented).toBe(false);
  });

  test('стрелки ходят по совпадениям по кругу', async () => {
    renderFile({ find: 'needle' });

    await userEvent.click(screen.getByTitle('find.next'));
    expect(counter()).toBe('2/2');

    await userEvent.click(screen.getByTitle('find.next'));
    expect(counter()).toBe('1/2');

    await userEvent.click(screen.getByTitle('find.prev'));
    expect(counter()).toBe('2/2');
  });

  /** Регулярку из поиска обязан подхватить и файл — иначе `log\.(a|b)` не найдётся. */
  test('запрос из адреса читается как выражение, когда так помечен', () => {
    renderFile({ find: 'needle|other', findRegex: true });

    expect(counter()).toBe('1/3');
  });

  /**
   * Набранное уходит в адрес по Enter и уходу фокуса, а не на каждую букву:
   * history.replaceState на каждый символ браузеры считают злоупотреблением.
   */
  test('набранный запрос ищется сразу, а в адрес уходит по Enter', async () => {
    const { onFindChange } = renderFile();
    pressCtrlF();

    await userEvent.type(screen.getByPlaceholderText('find.placeholder'), 'other');
    expect(counter()).toBe('1/1');
    expect(onFindChange).not.toHaveBeenCalled();

    await userEvent.keyboard('{Enter}');
    expect(onFindChange).toHaveBeenCalledWith('other', false);
  });

  test('Escape закрывает бар — как и в модалке', () => {
    const { onFindChange } = renderFile({ find: 'needle' });

    pressEscape();

    expect(bar()).toBeNull();
    expect(onFindChange).toHaveBeenCalledWith('', false);
  });

  test('закрытие бара стирает запрос из адреса', async () => {
    const { onFindChange } = renderFile({ find: 'needle' });

    await userEvent.click(screen.getByTitle('find.close'));

    expect(bar()).toBeNull();
    expect(onFindChange).toHaveBeenCalledWith('', false);
  });

  /**
   * Под открытым диалогом шорткат его: там ищут по тому, что на экране, а экран
   * сейчас — модалка. Иначе одно нажатие открывало бы два бара разом.
   */
  test('Ctrl+F под открытой модалкой файлу не достаётся', () => {
    render(
      <>
        <FileContent
          content={{ type: 'file', path: 'a.js', file: FILE }}
          path="a.js"
          loading={false}
          find=""
          findRegex={false}
          onFindChange={vi.fn()}
        />
        <ModalShell onClose={vi.fn()}>
          <p>needle в диалоге</p>
        </ModalShell>
      </>,
    );

    pressCtrlF();

    // Бар ровно один, и он внутри диалога.
    expect(document.querySelectorAll('.find-bar')).toHaveLength(1);
    expect(document.querySelector('[aria-modal="true"] .find-bar')).not.toBeNull();
  });

  /** Симметрично: Escape под диалогом закрывает диалог, а не бар под ним. */
  test('Escape под открытой модалкой бар файла не трогает', () => {
    const onFindChange = vi.fn();
    render(
      <>
        <FileContent
          content={{ type: 'file', path: 'a.js', file: FILE }}
          path="a.js"
          loading={false}
          find="needle"
          findRegex={false}
          onFindChange={onFindChange}
        />
        <ModalShell onClose={vi.fn()}>
          <p>диалог поверх</p>
        </ModalShell>
      </>,
    );

    pressEscape();

    expect(document.querySelector('.find-bar')).not.toBeNull();
    expect(onFindChange).not.toHaveBeenCalled();
  });

  /**
   * Escape закрывает открытый поповер (меню git над деревом) — и только его.
   * Слушатели меню висят на всплытии, наш — на перехвате, иначе к нашей очереди
   * меню уже закрылось бы и бар уехал бы вместе с ним.
   */
  test('Escape при открытом поповере закрывает поповер, а не бар', () => {
    const onFindChange = vi.fn();
    const onClosePopover = vi.fn();
    const Popover = () => {
      const ref = useRef(null);
      useDismissable(true, ref, onClosePopover);
      return <div ref={ref}>меню</div>;
    };
    render(
      <>
        <FileContent
          content={{ type: 'file', path: 'a.js', file: FILE }}
          path="a.js"
          loading={false}
          find="needle"
          findRegex={false}
          onFindChange={onFindChange}
        />
        <Popover />
      </>,
    );

    pressEscape();

    expect(onClosePopover).toHaveBeenCalled();
    expect(bar()).not.toBeNull();
    expect(onFindChange).not.toHaveBeenCalled();
  });

  /**
   * А Ctrl+F поповеру не уступает: искать он не умеет, и нажатие ушло бы в
   * браузерный поиск по всей странице. Уступаем только диалогу — у того бар свой.
   */
  test('Ctrl+F при открытом поповере всё равно достаётся файлу', () => {
    const Popover = () => {
      const ref = useRef(null);
      useDismissable(true, ref, vi.fn());
      return <div ref={ref}>меню</div>;
    };
    render(
      <>
        <FileContent
          content={{ type: 'file', path: 'a.js', file: FILE }}
          path="a.js"
          loading={false}
          find=""
          findRegex={false}
          onFindChange={vi.fn()}
        />
        <Popover />
      </>,
    );

    const notPrevented = pressCtrlF();

    expect(bar()).not.toBeNull();
    expect(notPrevented).toBe(false);
  });

  /** У обрезанного файла часть совпадений просто не загружена — счётчик про них не знает. */
  test('обрезанный файл оговаривает, что счётчик считает показанное', () => {
    renderFile({ find: 'needle', file: { ...FILE, truncated: true, fromLine: 1 } });

    expect(screen.getByText('find.truncated')).toBeInTheDocument();
  });
});
