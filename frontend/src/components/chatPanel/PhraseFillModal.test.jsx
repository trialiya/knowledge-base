import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PhraseFillModal from './PhraseFillModal';
import gitApi from '../../api/gitApi';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

vi.mock('../../i18n', () => ({ default: { t: (key) => key } }));

vi.mock('../../api/gitApi', () => ({
  default: { searchFiles: vi.fn(), searchCommits: vi.fn(), getFileContent: vi.fn() },
}));

vi.mock('../../api/documentsApi', () => ({
  default: { searchByName: vi.fn(), fetchById: vi.fn() },
}));

/** Достаёт текст, с которым диалог позвал onSubmit. */
function renderModal(phraseText, phraseLabel) {
  const onSubmit = vi.fn();
  render(<PhraseFillModal phraseText={phraseText} phraseLabel={phraseLabel} onSubmit={onSubmit} onCancel={vi.fn()} />);
  return onSubmit;
}

const previewText = () => document.querySelector('.phrase-fill__preview-text').textContent;

const submit = () => userEvent.click(screen.getByRole('button', { name: 'phraseFill.submit' }));

describe('PhraseFillModal', () => {
  it('substitutes text and number fields into the phrase', async () => {
    const onSubmit = renderModal('Проверь {{Файл}} за {{Дней:number}} дней');

    await userEvent.type(screen.getByLabelText(/Файл/), 'README.md');
    await userEvent.type(screen.getByLabelText(/Дней/), '7');
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('Проверь README.md за 7 дней');
  });

  it('fills every occurrence of the same placeholder from one field', async () => {
    const onSubmit = renderModal('{{Тема}} — про {{Тема}}');

    await userEvent.type(screen.getByLabelText(/Тема/), 'кэш');
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('кэш — про кэш');
  });

  // Поведение до появления диалога: незаполненный плейсхолдер уезжает в поле
  // ввода литералом, и пользователь правит его там.
  it('leaves an untouched field as its literal', async () => {
    const onSubmit = renderModal('{{A}} и {{B}}');

    await userEvent.type(screen.getByLabelText(/A/), 'значение');
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('значение и {{B}}');
  });

  it('turns a boolean field into a yes/no word', async () => {
    const onSubmit = renderModal('Тесты: {{Учитывать:boolean}}');

    await userEvent.click(screen.getByRole('checkbox'));
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('Тесты: chat:phraseFill.booleanYes');
  });

  it('defaults an untouched boolean to «no» instead of leaving the literal', async () => {
    const onSubmit = renderModal('Тесты: {{Учитывать:boolean}}');

    await submit();

    expect(onSubmit).toHaveBeenCalledWith('Тесты: chat:phraseFill.booleanNo');
  });

  it('inserts a file ref token for a picked file, not the typed text or its content', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    const onSubmit = renderModal('Посмотри {{Файл:file}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'App');
    await userEvent.click(await screen.findByText('src/App.jsx'));
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('Посмотри ⟦ref:src/App.jsx⟧');
  });

  it('inserts a commit chip token carrying the short hash and subject', async () => {
    gitApi.searchCommits.mockResolvedValue([
      { hash: 'a1b2c3d4e5', shortHash: 'a1b2c3d', author: 'Тест', message: 'почини кэш' },
    ]);
    const onSubmit = renderModal('Разбери {{Коммит:commit}}');

    await userEvent.type(screen.getByLabelText(/Коммит/), 'кэш');
    // Кликаем по подзаголовку: в заголовке совпадение подсвечено, и он разбит на узлы.
    await userEvent.click(await screen.findByText('a1b2c3d · Тест'));
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('Разбери ⟦commit:a1b2c3d:почини кэш⟧');
  });

  // Регрессия: .modal-shell и колонка полей обрезают по overflow, поэтому
  // вложенный в форму список был бы не виден вообще.
  it('renders the results list outside the clipping modal box', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'App');
    await screen.findByText('src/App.jsx');

    expect(document.querySelector('.phrase-fill__results').closest('.modal-shell')).toBeNull();
  });

  // Регрессия: useSearchDropdown гасит Enter только когда результаты уже пришли,
  // так что Enter в дебаунсе долетал до <form> и отправлял весь диалог.
  it('does not submit the dialog on Enter while the search is still pending', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    const onSubmit = renderModal('Посмотри {{Файл:file}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'App{Enter}');

    expect(onSubmit).not.toHaveBeenCalled();
  });

  // Регрессия: выбор закрывает список, и без повторного открытия правка уже
  // выбранного значения искала бы в невидимый список.
  it('reopens the list when an already-picked value is edited', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}}');

    const input = screen.getByLabelText(/Файл/);
    await userEvent.type(input, 'App');
    await userEvent.click(await screen.findByText('src/App.jsx'));
    expect(document.querySelector('.phrase-fill__results')).toBeNull();

    await userEvent.type(input, 'x');
    expect(await screen.findByText('src/App.jsx')).toBeInTheDocument();
  });

  // Регрессия: ModalShell гасит mousedown внутри диалога (stopPropagation
  // достаёт и до нативного события), поэтому bubble-слушатель на document до
  // клика по соседнему полю не доживал и список висел поверх формы.
  it('closes the results list when another field of the dialog is clicked', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}} по теме {{Тема}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'App');
    await screen.findByText('src/App.jsx');

    await userEvent.click(screen.getByLabelText(/Тема/));

    expect(document.querySelector('.phrase-fill__results')).toBeNull();
  });

  // Регрессия: закрытие списка сбрасывает запрос хука, а поле показывало именно
  // его — прокрутка колонки полей стирала набранное.
  it('keeps the typed text when scrolling the field column shuts the list', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}}');

    const input = screen.getByLabelText(/Файл/);
    await userEvent.type(input, 'App');
    await screen.findByText('src/App.jsx');

    fireEvent.scroll(document.querySelector('.phrase-fill__fields'));

    expect(document.querySelector('.phrase-fill__results')).toBeNull();
    expect(input).toHaveValue('App');

    // Закрытие фокус из поля не уводит, поэтому возврат к поиску — по клику.
    await userEvent.click(input);
    expect(await screen.findByText('src/App.jsx')).toBeInTheDocument();
  });

  it('keeps the typed text when Escape closes the list', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}}');

    const input = screen.getByLabelText(/Файл/);
    await userEvent.type(input, 'App');
    await screen.findByText('src/App.jsx');

    await userEvent.keyboard('{Escape}');

    expect(document.querySelector('.phrase-fill__results')).toBeNull();
    expect(input).toHaveValue('App');
  });

  // Регрессия: Enter гасился на любом открытом списке, в том числе на пустой
  // выдаче, и диалог нельзя было отправить с клавиатуры.
  it('submits the dialog on Enter once the search came back empty', async () => {
    gitApi.searchFiles.mockResolvedValue([]);
    const onSubmit = renderModal('Посмотри {{Файл:file}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'нет-такого');
    await screen.findByText('phraseFill.nothingFound');
    await userEvent.keyboard('{Enter}');

    expect(onSubmit).toHaveBeenCalled();
  });

  // Набранное без выбора подстановка выбрасывает — жёлтая рамка говорит об этом
  // до нажатия «Вставить», а не после.
  it('warns on a search field whose text matched nothing', async () => {
    gitApi.searchFiles.mockResolvedValue([]);
    renderModal('Посмотри {{Файл:file}}');

    const input = screen.getByLabelText(/Файл/);
    await userEvent.type(input, 'нет-такого');
    await screen.findByText('phraseFill.nothingFound');

    expect(input).toHaveClass('phrase-fill__input--warn');
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });

  it('warns when the list was closed and nothing was picked from it', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}}');

    const input = screen.getByLabelText(/Файл/);
    await userEvent.type(input, 'App');
    await screen.findByText('src/App.jsx');
    await userEvent.keyboard('{Escape}');

    expect(input).toHaveClass('phrase-fill__input--warn');
  });

  // Регрессия: снаружи список гасит только mousedown, поэтому уход по Tab
  // оставлял брошенное поле неподсвеченным, а выдачу — висеть поверх формы.
  it('warns and shuts the list when the field is left by Tab with nothing picked', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}} по теме {{Тема}}');

    const input = screen.getByLabelText(/Файл/);
    await userEvent.type(input, 'App');
    await screen.findByText('src/App.jsx');

    await userEvent.tab();

    expect(input).toHaveClass('phrase-fill__input--warn');
    expect(document.querySelector('.phrase-fill__results')).toBeNull();
    expect(input).toHaveValue('App');
  });

  // Пока выдача открыта, выбор ещё впереди: жёлтый на каждой букве был бы шумом.
  it('leaves a search field unmarked while the list still has something to pick', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}}');

    const input = screen.getByLabelText(/Файл/);
    await userEvent.type(input, 'App');
    await screen.findByText('src/App.jsx');
    expect(input).not.toHaveClass('phrase-fill__input--warn');

    await userEvent.click(screen.getByText('src/App.jsx'));
    expect(input).not.toHaveClass('phrase-fill__input--warn');
  });

  it('titles the dialog with the phrase name, falling back to the generic title', () => {
    const { unmount } = render(
      <PhraseFillModal phraseText="{{A}}" phraseLabel="История коммитов" onSubmit={vi.fn()} onCancel={vi.fn()} />,
    );
    expect(screen.getByRole('heading')).toHaveTextContent('История коммитов');
    unmount();

    renderModal('{{A}}');
    expect(screen.getByRole('heading')).toHaveTextContent('phraseFill.title');
  });

  // ModalShell фокус не переносит, а первым полем бывает не только текстовое.
  it('focuses the first field whatever its type', () => {
    const props = { onSubmit: vi.fn(), onCancel: vi.fn() };
    const { unmount } = render(<PhraseFillModal phraseText="Разбери {{Коммит:commit}} по теме {{Тема}}" {...props} />);
    expect(screen.getByLabelText(/Коммит/)).toHaveFocus();
    unmount();

    render(<PhraseFillModal phraseText="Тесты: {{Учитывать:boolean}}, тема {{Тема}}" {...props} />);
    expect(screen.getByRole('checkbox')).toHaveFocus();
  });

  it('previews the phrase with field labels standing in for empty placeholders', () => {
    renderModal('Проверь {{Файл}} за {{Дней:number}} дней');

    expect(previewText()).toBe('Проверь Файл за Дней дней');
  });

  it('replaces a label in the preview as soon as its field is filled', async () => {
    renderModal('Проверь {{Файл}} за {{Дней:number}} дней');

    await userEvent.type(screen.getByLabelText(/Дней/), '7');

    expect(previewText()).toBe('Проверь Файл за 7 дней');
  });

  // В превью читается название файла, а не чип-токен, который уедет в текст.
  it('previews a picked file by its name, not by the chip token', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    renderModal('Посмотри {{Файл:file}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'App');
    await userEvent.click(await screen.findByText('src/App.jsx'));

    expect(previewText()).toBe('Посмотри App.jsx');
  });

  // Свободный текст указателем не считается: путь, которого нет в репозитории,
  // не должен уехать в сообщение как выбранный файл.
  it('ignores typed text in a search field when nothing was picked', async () => {
    gitApi.searchFiles.mockResolvedValue([]);
    const onSubmit = renderModal('Посмотри {{Файл:file}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'нет-такого');
    await waitFor(() => expect(screen.getByText('phraseFill.nothingFound')).toBeInTheDocument());
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('Посмотри {{Файл:file}}');
  });
});
