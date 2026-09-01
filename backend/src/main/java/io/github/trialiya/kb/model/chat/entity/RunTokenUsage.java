package io.github.trialiya.kb.model.chat.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Итог прогона в терминах, в которых о токенах думает пользователь. Собирается из замеров отдельных
 * обращений к модели ({@code TokenUsage}) в области прогона ({@code RunScope}), по ходу прогона
 * уезжает во вкладки событием {@code RUN_USAGE}, а по его завершении оседает в мете последнего
 * ответа ({@link ChatMessageMeta#usage}) — оттуда его и берут плашка под ответом и счётчик
 * контекста в шапке чата после перезагрузки страницы.
 *
 * <p>Главное здесь — что заголовочная цифра НЕ сумма всех обращений. Ответ с инструментами это
 * несколько обращений подряд, и каждое несёт всю историю диалога заново, поэтому сумма prompt'ов
 * растёт квадратично от числа вызовов инструментов и быстро выходит на числа, по которым ничего не
 * понять. Считать её всё равно надо — это правда про счёт от провайдера, — но в вопросе «а сколько
 * места занял разговор» она бесполезна, а повторная часть вдобавок оплачивается по ставке кэша, то
 * есть заметно дешевле. Поэтому сумма ушла в расширенную статистику ({@link #promptTokens} рядом с
 * {@link #cacheReadTokens}), а наверх вынесены три числа, каждое из которых считает своё и ничего
 * не пересчитывает дважды.
 *
 * @param contextTokens сколько занято контекста после ответа: prompt последнего обращения плюс его
 *     же выход. Именно последнего: prompt каждого следующего обращения включает предыдущее целиком,
 *     так что последний и есть весь разговор, посчитанный один раз
 * @param basePromptTokens prompt ПЕРВОГО обращения — с чего прогон начал. У первого прогона чата
 *     это и есть системная часть контекста: системный промпт со схемами инструментов плюс сам
 *     вопрос, то есть всё, что занято до разговора. Отдельным числом, а не разностью: из остальных
 *     полей его не достать — выход последнего обращения в них не отделён от суммы по прогону
 * @param toolTokens насколько прогон нарастил контекст: prompt последнего обращения минус prompt
 *     первого. Разность съедает общую часть, оставляя ровно то, что дописали в диалог вызовы
 *     инструментов и ответы на них. Ноль у прогона без инструментов — там дописывать было нечего
 * @param outputTokens сколько модель сгенерировала за прогон — сумма по обращениям. Здесь именно
 *     сумма и никак иначе: выход каждого обращения свой и в чужой prompt входит только один раз
 * @param promptTokens сумма prompt'ов всех обращений — total input. База для расширенной статистики
 *     и знаменатель доли кэша
 * @param cacheReadTokens прочитано из кэша промпта; часть {@link #promptTokens}, а не добавка —
 *     доля кэша это их отношение
 * @param cacheWriteTokens записано в кэш промпта
 * @param totalTokens итог по счёту провайдера — сумма его же {@code total} по обращениям. Обычно
 *     равен {@link #promptTokens} плюс {@link #outputTokens}, но у модели с reasoning-токенами
 *     больше: провайдер считает их отдельно от видимого выхода, а платит за них клиент. Отсюда и
 *     отдельное поле: без него итог по чату занижал бы счёт ровно на невидимую часть
 * @param modelCalls обращений к модели за прогон: единица у обычного ответа, больше — у ответа с
 *     инструментами. Без него расширенную статистику не прочитать — непонятно, откуда разрыв между
 *     {@link #contextTokens} и {@link #promptTokens}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunTokenUsage(
        long contextTokens,
        long basePromptTokens,
        long toolTokens,
        long outputTokens,
        long promptTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long totalTokens,
        int modelCalls) {

    public static final RunTokenUsage EMPTY = new RunTokenUsage(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Не измерено ничего. Проверяем замеры, а не {@link #modelCalls}: обращение к эндпоинту без
     * поддержки usage в стриме состоялось, но не измерено, и показать по нему уверенный ноль было
     * бы хуже, чем не показать ничего.
     *
     * <p>Это условие «есть что записать в лог», а не «есть что показать»: плашку возглавляет {@link
     * #contextTokens}, и решение публиковать событие принимается по нему одному — см. {@code
     * TokenUsageAdvisor}.
     *
     * <p>{@code @JsonIgnore} — запись ездит и в SSE-событии, и в колонке {@code chat_message.meta};
     * без него Jackson выписал бы вычислимое поле {@code empty}, и чтение записанного ряда падало
     * бы на нём как на незнакомом.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return contextTokens == 0 && outputTokens == 0 && promptTokens == 0;
    }

    /**
     * Деньги нескольких раундов, у которых своего ряда в истории не осталось, одним числом (см.
     * {@code CompactMeta#carried}). Складываются только те поля, которые у раунда свои: выход,
     * total input, кэш, итог провайдера и число обращений — они и есть счёт от провайдера.
     *
     * <p>Контекстные числа при этом остаются нулями, а не суммой: контекст у раундов общий и
     * растёт, а не набирается (см. {@link #contextTokens}), так что сумма по ним была бы числом
     * ниоткуда. Отсюда и правило чтения на фронте — такой замер идёт только в итог по чату, и
     * никогда в «сколько занято сейчас».
     */
    public static RunTokenUsage spentTogether(Iterable<RunTokenUsage> rounds) {
        long output = 0;
        long prompt = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        long total = 0;
        int calls = 0;
        for (RunTokenUsage round : rounds) {
            output += round.outputTokens();
            prompt += round.promptTokens();
            cacheRead += round.cacheReadTokens();
            cacheWrite += round.cacheWriteTokens();
            total += round.billedTotal();
            calls += round.modelCalls();
        }
        return new RunTokenUsage(0, 0, 0, output, prompt, cacheRead, cacheWrite, total, calls);
    }

    /**
     * Итог по счёту провайдера, не меньше суммы частей. Складывать в сумме по раундам надо именно
     * его: у прогонов, записанных до появления {@link #totalTokens}, поле нулевое, и сырая сумма
     * оказалась бы меньше суммы входов с выходами. Тогда потребителю остаётся лишь взять большее из
     * двух — и один такой раунд стёр бы reasoning-токены всех остальных.
     */
    public long billedTotal() {
        return Math.max(totalTokens, promptTokens + outputTokens);
    }

    /**
     * Накопитель прогона: из него получается {@link RunTokenUsage}. Держит первое и последнее
     * обращение отдельно от суммы, потому что три числа выше считаются по-разному и из одной лишь
     * суммы два из них уже не достать.
     *
     * <p>Общий на оба места, где обращения к модели идут пачкой: tool-цикл чата ({@code RunScope})
     * и цикл поискового суб-агента ({@code SearchAgentService}). Правило у них обязано быть одно —
     * иначе одна и та же работа в двух местах интерфейса называлась бы разными числами.
     *
     * @param first первое измеренное обращение — вычитаемое в {@link #toolTokens}
     * @param last последнее измеренное обращение — из него весь контекст разговора
     * @param sum сумма по обращениям — из неё output и total input
     * @param calls сколько обращений учтено
     */
    public record Tally(TokenUsage first, TokenUsage last, TokenUsage sum, int calls) {

        public static final Tally EMPTY =
                new Tally(TokenUsage.EMPTY, TokenUsage.EMPTY, TokenUsage.EMPTY, 0);

        /**
         * Учитывает замер обращения.
         *
         * <p>На роль первого и последнего годится только замер с prompt'ом. Провайдер вправе
         * прислать по ходу обращения чанк с одним лишь выходом, и такой замер, назначенный
         * последним, обрушил бы {@link #contextTokens} до размера ответа, а назначенный первым —
         * раздул бы {@link #toolTokens} до всего контекста. В сумму он при этом входит: выход в нём
         * настоящий.
         */
        public Tally with(TokenUsage call) {
            if (call.isEmpty()) {
                return this;
            }
            final boolean measuresPrompt = call.promptTokens() > 0;
            return new Tally(
                    measuresPrompt && first.promptTokens() == 0 ? call : first,
                    measuresPrompt ? call : last,
                    sum.plus(call),
                    calls + 1);
        }

        /** Итог по накопленному. */
        public RunTokenUsage view() {
            return new RunTokenUsage(
                    last.promptTokens() + last.completionTokens(),
                    first.promptTokens(),
                    // Отрицательной разность быть не может — prompt следующего обращения включает
                    // предыдущее целиком, — но провайдеру, который посчитал иначе, верить незачем.
                    Math.max(0, last.promptTokens() - first.promptTokens()),
                    sum.completionTokens(),
                    sum.promptTokens(),
                    sum.cacheReadTokens(),
                    sum.cacheWriteTokens(),
                    sum.totalTokens(),
                    calls);
        }
    }
}
