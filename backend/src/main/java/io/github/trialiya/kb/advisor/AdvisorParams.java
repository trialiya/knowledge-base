package io.github.trialiya.kb.advisor;

/**
 * Ключи, которыми прогон едет через advisor-параметры запроса ({@code
 * ChatClientRequestSpec#advisors}). Ставит их владелец прогона ({@code ChatRunService}), читают
 * advisor-ы: без этого ключа advisor не знает, к какому прогону относится поток, который он видит.
 */
public final class AdvisorParams {

    /** runId прогона. */
    public static final String RUN_ID_PARAM = "RUN_ID";

    private AdvisorParams() {}
}
