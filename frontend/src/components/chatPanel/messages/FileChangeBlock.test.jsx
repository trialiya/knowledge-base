import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileChangeBlock from './FileChangeBlock';
import chatApi from '@/api/chatApi';

// Кнопка отката у каждой строки: она есть только у последнего ответа свободного чата, откатывает
// один файл после подтверждения (правки исчезают, созданный файл удаляется), у уже откаченного
// файла её нет, а отказ сервера показывается словами — «файл изменился после ответа» это и есть
// ответ пользователю.

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

vi.mock('@/api/chatApi', () => ({
  default: { revertFiles: vi.fn() },
}));

const toolCalls = [
  {
    name: 'editFile',
    status: 'OK',
    resultMeta: { path: 'src/App.java', operation: 'edit', additions: 1, deletions: 1, diff: '@@ -1 +1 @@' },
  },
  {
    name: 'createFile',
    status: 'OK',
    resultMeta: { path: 'src/New.java', operation: 'create', additions: 3, deletions: 0, diff: '@@ -0,0 +1,3 @@' },
  },
];

const block = (props) => render(<FileChangeBlock toolCalls={toolCalls} project="kb" conversationId="c1" {...props} />);

const expand = async (user) => user.click(screen.getByRole('button', { name: /fileChange.summary/ }));

describe('FileChangeBlock', () => {
  beforeEach(() => {
    chatApi.revertFiles.mockReset();
  });

  it('не показывает откат у ответа, который не последний', async () => {
    const user = userEvent.setup();
    block({ canRevert: false });

    await expand(user);

    expect(screen.queryByRole('button', { name: 'fileChange.revertFile' })).toBeNull();
  });

  it('у каждого файла своя кнопка, и откатывает она только этот файл после подтверждения', async () => {
    const user = userEvent.setup();
    chatApi.revertFiles.mockResolvedValue({ id: 7, event: { project: 'kb', paths: ['src/New.java'] } });
    block({ canRevert: true });

    await expand(user);
    const buttons = screen.getAllByRole('button', { name: 'fileChange.revertFile' });
    expect(buttons).toHaveLength(2);

    await user.click(buttons[1]);
    expect(chatApi.revertFiles).not.toHaveBeenCalled();
    // Созданный файл предупреждает об удалении, а не о «возврате к прежнему состоянию».
    expect(screen.getByText('fileChange.revertMessageCreated')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'fileChange.revert' }));

    await waitFor(() => expect(chatApi.revertFiles).toHaveBeenCalledWith('c1', ['src/New.java']));
  });

  it('у ответа, правившего файлы скриптом, кнопок нет вовсе', async () => {
    const user = userEvent.setup();
    const script = {
      name: 'runScript',
      status: 'OK',
      resultMeta: { edits: [{ path: 'src/Gen.java', operation: 'edit', additions: 2, deletions: 0 }] },
    };
    render(<FileChangeBlock toolCalls={[...toolCalls, script]} project="kb" conversationId="c1" canRevert />);

    await expand(user);

    expect(screen.queryByRole('button', { name: 'fileChange.revertFile' })).toBeNull();
  });

  it('у уже откаченного файла кнопки нет, у соседнего — есть', async () => {
    const user = userEvent.setup();
    block({ canRevert: true, revertedPaths: new Set(['src/App.java']) });

    await expand(user);

    expect(screen.getAllByRole('button', { name: 'fileChange.revertFile' })).toHaveLength(1);
    expect(screen.getByTitle('fileChange.revertedFile')).toBeInTheDocument();
  });

  it('показывает причину отказа словами сервера рядом с файлом', async () => {
    const user = userEvent.setup();
    const refusal = new Error('nope');
    refusal.reason = 'oldString not found in src/App.java';
    chatApi.revertFiles.mockRejectedValue(refusal);
    block({ canRevert: true });

    await expand(user);
    await user.click(screen.getAllByRole('button', { name: 'fileChange.revertFile' })[0]);
    await user.click(screen.getByRole('button', { name: 'fileChange.revert' }));

    expect(await screen.findByText(/oldString not found in src\/App.java/)).toBeInTheDocument();
  });
});
