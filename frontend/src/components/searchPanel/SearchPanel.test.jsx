import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SearchPanel from './SearchPanel';

vi.mock('@/components/common/config/useProjectConfig', () => ({
  default: () => ({
    projectOptions: [
      { id: 'kb', label: 'kb' },
      { id: 'other', label: 'other' },
    ],
    defaultProjectId: 'kb',
    ready: true,
  }),
}));

// Панель здесь проверяется как набор фильтров: что ищется по ним — дело
// useSearchResults, и его собственных тестов. Сам ответ хука задаёт тест —
// панели он нужен ради счётчиков и признака «ещё идёт».
const idle = { entry: null, loading: false };
let results = { files: idle, docs: idle, chats: idle };

vi.mock('./useSearchResults', () => ({ default: () => results }));

beforeEach(() => {
  results = { files: idle, docs: idle, chats: idle };
});

const renderPanel = (filters, onRefine) =>
  render(
    <SearchPanel
      query="needle"
      scope="files"
      mode="hybrid"
      filters={{ path: '', project: '', rev: '', regex: false, untracked: false, ...filters }}
      onRefine={onRefine}
      onOpenFile={vi.fn()}
      onOpenDoc={vi.fn()}
      onOpenChat={vi.fn()}
      panels={{}}
    />,
  );

/** Выбрать репозиторий в фильтрах — через тот же список, что видит человек. */
const pickProject = async (label) => {
  await userEvent.click(screen.getByRole('button', { name: 'filters.project' }));
  await userEvent.click(screen.getByRole('option', { name: label }));
};

it('пока категория ищет, вместо счётчика крутится волчок', () => {
  // Смена репозитория оставляет прежнюю выдачу до ответа: число рядом с только
  // что выбранным репозиторием читалось бы как его результат.
  results = {
    files: {
      entry: {
        data: {
          total: 3,
          files: [
            { path: 'a.java', lines: [] },
            { path: 'b.java', lines: [] },
          ],
        },
        error: null,
      },
      loading: true,
    },
    docs: {
      entry: { data: { total: 1, documents: [{ id: 1, title: 'Док', parentList: [], fragments: [] }] }, error: null },
      loading: false,
    },
    chats: idle,
  };
  renderPanel({});

  const [files, docs] = screen.getAllByRole('option');
  expect(files.querySelector('.search-spinner')).toBeInTheDocument();
  expect(files).not.toHaveTextContent('2');
  expect(docs).toHaveTextContent('1');
});

it('смена репозитория снимает ревизию и маску пути прежнего', async () => {
  // Ветки с тем же именем в новом репозитории может не быть вовсе — это 400
  // вместо выдачи, а каталога из маски — молчаливый ноль найденного.
  const onRefine = vi.fn();
  renderPanel({ project: '', rev: 'release-1', path: 'backend/**' }, onRefine);

  await pickProject('other');

  expect(onRefine).toHaveBeenCalledWith({ searchProject: 'other', searchRev: '', searchPath: '' });
});

it.each([
  ['пустым значением', ''],
  ['явно названным', 'kb'],
])('выбор того же репозитория (%s в адресе) ничего не сбрасывает', async (_name, project) => {
  // Дефолтный проект в адрес не пишут, но по ссылке он мог прийти и записанным:
  // выбор той же строки списка — не смена репозитория ни в том, ни в другом виде.
  const onRefine = vi.fn();
  renderPanel({ project, rev: 'release-1', path: 'backend/**' }, onRefine);

  await pickProject('kb');

  expect(onRefine).toHaveBeenCalledWith({ searchProject: '' });
});
