package io.github.trialiya.kb.model.chat.dto;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Тело команды {@code /script}: что запустить в этом чате и с чем.
 *
 * <p>Разбор хвоста команды делает фронт, а не сервер: `key=value` — это про то, как человек
 * набирает команду в поле, и правило это живёт там же, где поле (см. {@code
 * composer/scriptCommand.js}). Сюда приезжает уже разобранное, и тем же телом пользуется любой
 * другой клиент, который команду не набирает вовсе.
 *
 * @param name имя из манифеста проекта или {@code attachment:<id>}
 * @param args аргументы по объявленным именам; проверяются до запуска ({@code ScriptArgs})
 * @param timeoutSeconds бюджет прогона; {@code null} — бюджет самого скрипта, затем {@code
 *     kb.script.timeout}
 */
public record ScriptRunRequest(
        @Nullable String name,
        @Nullable Map<String, Object> args,
        @Nullable Integer timeoutSeconds) {}
