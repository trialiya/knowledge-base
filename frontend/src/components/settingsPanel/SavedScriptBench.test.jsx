import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SavedScriptBench from './SavedScriptBench';
import settingsApi from '@/api/settingsApi';

// Стенд запускает чужой код по имени, поэтому проверяется ровно то, что уезжает
// на сервер: имя выбранного скрипта и аргументы — без пустых полей, потому что
// пустая строка и «не передавали» для объявленного аргумента разные вещи (на
// втором подставится default манифеста, на первом — нет).

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

vi.mock('@/api/settingsApi', () => ({
  default: { listSavedScripts: vi.fn(), runSavedScript: vi.fn() },
}));

const CATALOG = {
  project: 'kb',
  label: 'Knowledge Base',
  scripts: [
    {
      name: 'locale-diff',
      desc: 'Ключи en против ru',
      file: 'frontend/scripts/locale-diff.js',
      write: false,
      timeoutSeconds: null,
      params: [
        { name: 'area', desc: 'Поддерево', type: 'string', required: false, defaultValue: null },
        { name: 'limit', desc: 'Строк', type: 'number', required: false, defaultValue: 50 },
      ],
    },
    { name: 'bump', desc: 'Правит файлы', file: 'scripts/bump.js', write: true, timeoutSeconds: null, params: [] },
    {
      name: 'paths',
      desc: 'Берёт список путей',
      file: 'scripts/paths.js',
      write: false,
      timeoutSeconds: null,
      params: [{ name: 'files', desc: 'Пути', type: 'array', required: false, defaultValue: null }],
    },
  ],
};

const EMPTY_RESULT = {
  stats: { filesRead: 0, bytesRead: 0, calls: 0, elapsedMs: 1 },
  error: null,
  log: [],
  value: 7,
  filesRead: [],
};

beforeEach(() => {
  vi.clearAllMocks();
  settingsApi.listSavedScripts.mockResolvedValue(CATALOG);
  settingsApi.runSavedScript.mockResolvedValue(EMPTY_RESULT);
});

const pick = async (user, name) => {
  await waitFor(() => screen.getByRole('combobox'));
  await user.selectOptions(screen.getByRole('combobox'), name);
};

describe('SavedScriptBench', () => {
  it('отправляет только заполненные аргументы', async () => {
    const user = userEvent.setup();
    render(<SavedScriptBench enabled />);

    await pick(user, 'locale-diff');
    await user.type(screen.getAllByRole('textbox')[0], 'components');
    await user.click(screen.getByRole('button', { name: 'scripts.bench.run' }));

    await waitFor(() => expect(settingsApi.runSavedScript).toHaveBeenCalled());
    // limit остался пустым — его в запросе нет вовсе, и на сервере сработает default манифеста.
    expect(settingsApi.runSavedScript).toHaveBeenCalledWith('locale-diff', { area: 'components' });
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  // Массив и объект бэкенд строкой не принимает, поэтому такое поле — это JSON,
  // и разобрать его обязана форма: иначе аргумент нельзя заполнить ничем.
  it('аргумент-массив уезжает разобранным, а не строкой', async () => {
    const user = userEvent.setup();
    render(<SavedScriptBench enabled />);

    await pick(user, 'paths');
    // fireEvent, а не user.type: в user-event квадратная скобка — начало описателя клавиши.
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '["a.js", "b.js"]' } });
    await user.click(screen.getByRole('button', { name: 'scripts.bench.run' }));

    await waitFor(() => expect(settingsApi.runSavedScript).toHaveBeenCalled());
    expect(settingsApi.runSavedScript).toHaveBeenCalledWith('paths', { files: ['a.js', 'b.js'] });
  });

  it('у скрипта, объявленного пишущим, кнопка заблокирована: стенд не пишет никогда', async () => {
    const user = userEvent.setup();
    render(<SavedScriptBench enabled />);

    await pick(user, 'bump');

    expect(screen.getByRole('button', { name: 'scripts.bench.run' })).toBeDisabled();
    expect(screen.getByText('scripts.saved.writeNote')).toBeInTheDocument();
  });

  it('репозиторий без манифеста — это строка объяснения, а не пустой список', async () => {
    settingsApi.listSavedScripts.mockResolvedValue({ project: 'kb', label: 'KB', scripts: [] });
    render(<SavedScriptBench enabled />);

    await waitFor(() => expect(screen.getByText('scripts.saved.emptyNote')).toBeInTheDocument());
    expect(screen.queryByRole('combobox')).toBeNull();
  });
});
