import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileChangeBlock from './FileChangeBlock';
import chatApi from '@/api/chatApi';

// Кнопка отката: она есть только у последнего ответа свободного чата, спрашивает подтверждение
// (правки исчезают, созданные файлы удаляются) и показывает отказ сервера словами — «файл
// изменился после ответа» это и есть ответ пользователю.

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

    expect(screen.queryByRole('button', { name: 'fileChange.revert' })).toBeNull();
  });

  it('откатывает только после подтверждения', async () => {
    const user = userEvent.setup();
    chatApi.revertFiles.mockResolvedValue({ id: 7, event: { project: 'kb', paths: ['src/App.java'] } });
    block({ canRevert: true });

    await expand(user);
    await user.click(screen.getByRole('button', { name: 'fileChange.revert' }));
    expect(chatApi.revertFiles).not.toHaveBeenCalled();

    // Подтверждение — вторая кнопка с тем же текстом (в модалке), поэтому берём последнюю.
    const confirm = screen.getAllByRole('button', { name: 'fileChange.revert' }).at(-1);
    await user.click(confirm);

    await waitFor(() => expect(chatApi.revertFiles).toHaveBeenCalledWith('c1'));
  });

  it('показывает причину отказа словами сервера', async () => {
    const user = userEvent.setup();
    const refusal = new Error('nope');
    refusal.reason = 'oldString not found in src/App.java';
    chatApi.revertFiles.mockRejectedValue(refusal);
    block({ canRevert: true });

    await expand(user);
    await user.click(screen.getByRole('button', { name: 'fileChange.revert' }));
    await user.click(screen.getAllByRole('button', { name: 'fileChange.revert' }).at(-1));

    expect(await screen.findByText('oldString not found in src/App.java')).toBeInTheDocument();
  });
});
