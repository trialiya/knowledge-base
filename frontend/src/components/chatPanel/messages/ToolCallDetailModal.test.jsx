import { render, screen, waitFor } from '@testing-library/react';
import ToolCallDetailModal from './ToolCallDetailModal';
import chatApi from '@/api/chatApi';

// Проверяется дотягивание результата в уже открытую модалку: она открывается на ещё
// работающем вызове, и полагаться на одну перезагрузку по смене статуса плашки нельзя —
// событие ответа публикуется до коммита транзакции, а на остановке и ошибке прогона
// статус плашки не меняется вовсе.

// Частичный мок: `@/i18n/index` (его тянет formatFieldValue) поднимает настоящий i18next и
// требует `initReactI18next` — подменяем только чтение переводов.
vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

vi.mock('@/api/chatApi', () => ({
  default: { getToolCallDetails: vi.fn() },
}));

const tc = { name: 'searchCodebase', status: 'STARTED', callId: 'call_1' };

const detail = (status, resultText) => ({
  name: 'searchCodebase',
  argumentsRaw: '{"q":"кэш"}',
  status,
  error: null,
  resultText,
  resultMeta: null,
  createdAt: '2026-08-23T10:00:00',
});

const open = () => render(<ToolCallDetailModal conversationId="c1" callId="call_1" tc={tc} onClose={() => {}} />);

describe('ToolCallDetailModal', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    chatApi.getToolCallDetails.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('на работающем вызове показывает аргументы и дотягивает результат опросом', async () => {
    chatApi.getToolCallDetails
      .mockResolvedValueOnce(detail('STARTED', null))
      .mockResolvedValueOnce(detail('OK', '"нашлось 3 файла"'));

    open();

    // Аргументы видны сразу, вместо результата — пометка о том, что инструмент работает.
    expect(await screen.findByText('кэш')).toBeInTheDocument();
    expect(screen.getByText('toolCall.detail.running')).toBeInTheDocument();

    // Статус плашки не менялся — результат приносит именно опрос.
    await vi.advanceTimersByTimeAsync(1000);
    expect(await screen.findByText(/нашлось 3 файла/)).toBeInTheDocument();
    expect(screen.queryByText('toolCall.detail.running')).not.toBeInTheDocument();

    // Ответ пришёл — опрос прекращается.
    const calls = chatApi.getToolCallDetails.mock.calls.length;
    await vi.advanceTimersByTimeAsync(60000);
    expect(chatApi.getToolCallDetails).toHaveBeenCalledTimes(calls);
  });

  it('сорвавшийся перезапрос не стирает уже показанные аргументы', async () => {
    chatApi.getToolCallDetails
      .mockResolvedValueOnce(detail('STARTED', null))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(detail('OK', '"готово"'));

    open();
    expect(await screen.findByText('кэш')).toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(1000);
    await waitFor(() => expect(chatApi.getToolCallDetails).toHaveBeenCalledTimes(2));
    expect(screen.getByText('кэш')).toBeInTheDocument();
    expect(screen.queryByText('toolCall.detail.loadError')).not.toBeInTheDocument();

    // Повтор после ошибки приносит результат.
    await vi.advanceTimersByTimeAsync(2000);
    expect(await screen.findByText(/готово/)).toBeInTheDocument();
  });
});
