import { render, screen, waitFor } from '@testing-library/react';
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
function renderModal(phraseText) {
  const onSubmit = vi.fn();
  render(<PhraseFillModal phraseText={phraseText} onSubmit={onSubmit} onCancel={vi.fn()} />);
  return onSubmit;
}

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

  it('inserts a file chip token for a picked file, not the typed text', async () => {
    gitApi.searchFiles.mockResolvedValue([{ path: 'src/App.jsx', name: 'App.jsx' }]);
    const onSubmit = renderModal('Посмотри {{Файл:file}}');

    await userEvent.type(screen.getByLabelText(/Файл/), 'App');
    await userEvent.click(await screen.findByText('src/App.jsx'));
    await submit();

    expect(onSubmit).toHaveBeenCalledWith('Посмотри ⟦file:src/App.jsx⟧');
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
