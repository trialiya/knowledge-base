# TOOL_PREPARING — ранний сигнал о вызове инструмента

## Что это

Событие SSE-потока, которое бэкенд должен отправлять **до** `TOOL_CALL`, чтобы фронтенд мог
показать индикатор «готовлю данные…» во время тихой паузы между последним текстовым токеном и
стартом инструмента.

## Текущее состояние

**Отключено.** Событие приходит вплотную к `TOOL_CALL` — зазора нет, индикатор никогда не успевает
появиться. Обработчик в `chatEventReducer.js` закомментирован.

## Почему не работает

### Архитектура стрима в Spring AI 2.0 + openai-java 4.x

`OpenAiChatModel.internalStream` получает от `OpenAIClientAsync` сырой
`AsyncStreamResponse<ChatCompletionChunk>` — поток дельт, где каждая дельта содержит либо кусок
текста, либо фрагмент аргументов tool call. Внутри `internalStream` стоит буфер:

```java
AtomicBoolean isInsideTool = new AtomicBoolean(false);
chunks
    .doOnNext(chunk -> { if (ChunkMerger.hasToolCall(chunk)) isInsideTool.set(true); })
    .bufferUntil(chunk -> {
        if (isInsideTool.get() && ChunkMerger.toolCallsDone(chunk)) {
            isInsideTool.set(false);
            return true;        // буфер сбрасывается одним элементом
        }
        return !isInsideTool.get();
    })
    .map(ChunkMerger::mergeChunks)
    .map(ChunkMerger::chunkToChatCompletion);
```

Как только приходит первый tool-дельта, `bufferUntil` начинает копить все последующие чанки и не
выдаёт ничего наружу, пока не придёт `toolCallsDone`. После этого `ChunkMerger` склеивает их в
**один** `ChatCompletion` с полными аргументами.

Всё, что находится выше (advisor-chain, `ChatRunService`, observation) видит только этот
агрегированный чанк. К моменту его появления `ToolCallingAdvisor` немедленно запускает
инструмент — `TOOL_PREPARING` и `TOOL_CALL` уходят в SSE практически одновременно.

### Проверенные точки расширения

| Точка | Проблема |
|---|---|
| `StreamAdvisor` (`ToolPreparingAdvisor`, `LOWEST_PRECEDENCE`) | Видит чанки уже после `bufferUntil` — первый чанк с `hasToolCalls()=true` уже содержит полные аргументы |
| `ChatModelObservationConvention` / `ObservationHandler` | Spring AI вызывает `setResponse` один раз по завершении, а не на каждом чанке; `requestTools` содержит **все** зарегистрированные инструменты, а не выбранный |
| `OpenAIClientAsync.withOptions(...)` | Фасад клиентских сервисов, не поток — подписаться не на что |

### Единственный реальный хук

`AsyncStreamResponse<ChatCompletionChunk>` из `openai-java`:

```java
// com.openai.core.http.AsyncStreamResponse
interface Handler<T> {
    void onNext(T chunk);
    default void onComplete(Optional<Throwable> error) {}
}
```

Первый tool-дельта (`choice.delta().toolCalls()` непуст, `function.name()` уже есть) приходит
сюда **до** `bufferUntil`. `OpenAiChatModel.Builder.openAiClientAsync(...)` позволяет передать
собственный декоратор клиента, который оборачивает `AsyncStreamResponse`.

**Почему не реализовано:** корреляция перехваченного события с `conversationId`/`runId` сложна
(callback-поток SDK не несёт Reactor-контекст), единственный чистый путь — прокидывать `runId`
через поле `user` или `metadata` в `ChatCompletionCreateParams`, но это утечка внутреннего id в
OpenAI и дополнительная проводка в `ChatConfig`. Стоимость нетривиальна ради косметического
индикатора.

## Варианты реализации

### A. Детекция тишины на фронте (рекомендуется)

Клиент сам отслеживает паузу между событиями SSE: таймер запускается после каждого `STREAM`,
сбрасывается при следующем событии. Если тишина длится ≥ 600–800 мс — показываем индикатор.

- Плюсы: ловит **реальную** паузу, не зависит от бэкенда, минимум кода
- Минусы: порог подбирается эмпирически; не знает имени инструмента

Вся обвязка (`preparing`-флаг, `ToolPreparingIndicator`, `clearPreparing`) уже есть — меняется
только триггер: вместо бэкенд-события запускается клиентский таймер тишины.

### B. Декоратор `OpenAIClientAsync`

Оборачиваем реальный клиент, перехватываем первый tool-дельта в `Handler.onNext`, публикуем
`TOOL_PREPARING` с именем инструмента. Декоратор передаётся через
`OpenAiChatModel.Builder.openAiClientAsync(...)`.

- Плюсы: реальный ранний сигнал + имя инструмента до начала аргументов
- Минусы: OpenAI-специфично; корреляция с `runId` требует хака (поле `user`/`metadata`);
  декоратор покрывает большой интерфейс

### C. Детекция первого дельта через `internalStream` (не рекомендуется)

Патч или переопределение `OpenAiChatModel`, чтобы добавить `doOnNext` до `bufferUntil`.
Теряется вся framework-обвязка при обновлении Spring AI.

## Связанные файлы

- `backend/src/main/java/io/github/trialiya/kb/advisor/ToolPreparingAdvisor.java` — существующий advisor, публикует событие когда видит `hasToolCalls()`; остаётся рабочим, просто сигнал поздний
- `backend/src/main/java/io/github/trialiya/kb/config/ChatConfig.java` — регистрирует `ToolPreparingAdvisor` как самый внутренний advisor (LOWEST_PRECEDENCE)
- `frontend/src/components/chatPanel/chatEventReducer.js` — обработчик `TOOL_PREPARING` отключён (no-op)
- `frontend/src/components/chatPanel/Message.jsx` — `ToolPreparingIndicator` + `showPreparing`-логика готовы к использованию
- `docs/проект/диагностика-tool-preparing-стриминг.md` — история диагностики (Spring AI 1.1.x)
