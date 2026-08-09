import { render, screen, waitFor } from '@testing-library/react';
import AttachmentPanel from './AttachmentPanel';
import attachmentApi from '../../api/attachmentApi';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

vi.mock('../../api/attachmentApi', () => ({
  default: { list: vi.fn(), upload: vi.fn(), delete: vi.fn(), summarize: vi.fn() },
}));

const file = (id, name) => ({ id, fileName: name, fileSize: 10, summary: null });

describe('AttachmentPanel', () => {
  // Регрессия: спиннер выведен из «ключ запроса ≠ ключ ответа», а запрос по
  // каждому ключу уходит ровно один раз. Ответ, обогнавший более свежий, отбрасывал
  // бы ключ назад — и спиннер зависал бы навсегда, потому что перезапросить уже
  // нечем.
  it('ответ, пришедший не по порядку, не оставляет список в загрузке', async () => {
    let resolveFirst;
    let resolveSecond;
    attachmentApi.list
      .mockImplementationOnce(() => new Promise((r) => (resolveFirst = r)))
      .mockImplementationOnce(() => new Promise((r) => (resolveSecond = r)));

    const { rerender } = render(<AttachmentPanel ownerType="chat" ownerId="c1" refreshSignal={0} />);
    await waitFor(() => expect(attachmentApi.list).toHaveBeenCalledTimes(1));

    // Второе чтение (файл приложили мимо панели) стартует до ответа на первое.
    rerender(<AttachmentPanel ownerType="chat" ownerId="c1" refreshSignal={1} />);
    await waitFor(() => expect(attachmentApi.list).toHaveBeenCalledTimes(2));

    // Свежий ответ приходит первым, устаревший — следом.
    resolveSecond([file(2, 'новый.txt')]);
    await screen.findByText('новый.txt');

    resolveFirst([file(1, 'старый.txt')]);
    await waitFor(() => expect(screen.queryByText('старый.txt')).not.toBeInTheDocument());

    expect(screen.queryByText('attachments.loadingList')).not.toBeInTheDocument();
    expect(screen.getByText('новый.txt')).toBeInTheDocument();
  });
});
