import { act, render, screen, waitFor } from '@testing-library/react';
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

const open = (call = tc) =>
  render(<ToolCallDetailModal conversationId="c1" callId="call_1" tc={call} onClose={() => {}} />);

// Тик опроса: таймер будит запрос, и его ответ приходит уже вне рендера — без act(...)
// React ругается на состояние, обновлённое мимо него.
const tick = (ms) => act(() => vi.advanceTimersByTimeAsync(ms));

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
    await tick(1000);
    expect(await screen.findByText(/нашлось 3 файла/)).toBeInTheDocument();
    expect(screen.queryByText('toolCall.detail.running')).not.toBeInTheDocument();

    // Ответ пришёл — опрос прекращается.
    const calls = chatApi.getToolCallDetails.mock.calls.length;
    await tick(60000);
    expect(chatApi.getToolCallDetails).toHaveBeenCalledTimes(calls);
  });

  it('переспрашивает и по несохранённому исходу: его допишет конец прогона', async () => {
    // Ответ инструмента уже записан, меты прогона ещё нет — бэкенд отвечает UNKNOWN. Статус
    // плашки при этом свой (её красит живое событие), и переспросить, кроме опроса, некому.
    chatApi.getToolCallDetails
      .mockResolvedValueOnce(detail('UNKNOWN', '"нашлось 3 файла"'))
      .mockResolvedValueOnce(detail('ERROR', '"нашлось 3 файла"'));

    open({ ...tc, status: 'OK' });

    expect(await screen.findByText('toolCall.statusValue.UNKNOWN')).toBeInTheDocument();

    await tick(1000);
    expect(await screen.findByText('toolCall.statusValue.ERROR')).toBeInTheDocument();

    // Исход известен — опрос прекращается.
    const calls = chatApi.getToolCallDetails.mock.calls.length;
    await tick(60000);
    expect(chatApi.getToolCallDetails).toHaveBeenCalledTimes(calls);
  });

  it('сорвавшийся перезапрос не стирает уже показанные аргументы', async () => {
    chatApi.getToolCallDetails
      .mockResolvedValueOnce(detail('STARTED', null))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(detail('OK', '"готово"'));

    open();
    expect(await screen.findByText('кэш')).toBeInTheDocument();

    await tick(1000);
    await waitFor(() => expect(chatApi.getToolCallDetails).toHaveBeenCalledTimes(2));
    expect(screen.getByText('кэш')).toBeInTheDocument();
    expect(screen.queryByText('toolCall.detail.loadError')).not.toBeInTheDocument();

    // Повтор после ошибки приносит результат.
    await tick(2000);
    expect(await screen.findByText(/готово/)).toBeInTheDocument();
  });

  // Цена вызова приезжает в resultMeta плашки, а не в результате: модели эти числа намеренно
  // не показывают (SearchAgentResult помечает их @JsonIgnore), а человеку они нужны здесь.
  it('показывает модель инструмента и его total input, когда он их сообщил', async () => {
    chatApi.getToolCallDetails.mockResolvedValue(detail('OK', '"нашлось"'));
    const withCost = {
      ...tc,
      status: 'OK',
      resultMeta: {
        model: 'gpt-5-mini',
        usage: { contextTokens: 18400, outputTokens: 870, promptTokens: 41260, modelCalls: 4 },
      },
    };

    open(withCost);

    await waitFor(() => expect(screen.getByText(/costModel/)).toBeInTheDocument());
    expect(screen.getByText(/costTokens/)).toBeInTheDocument();
  });

  it('у инструмента без собственной модели строки цены нет', async () => {
    chatApi.getToolCallDetails.mockResolvedValue(detail('OK', '"нашлось"'));

    open({ ...tc, status: 'OK', resultMeta: { project: 'kb' } });

    await waitFor(() => expect(screen.getByText('toolCall.detail.result')).toBeInTheDocument());
    expect(screen.queryByText(/costModel/)).not.toBeInTheDocument();
  });
});
