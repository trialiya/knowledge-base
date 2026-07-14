package io.github.trialiya.kb.config.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timeouts around the chat stream.
 *
 * <p>The OpenAI client call timeout (openai-java SDK, shared by every spring-ai-openai model) is
 * NOT here — it has no kb-specific equivalent, it's the standard {@code spring.ai.openai.timeout}
 * property. That one property drives both the SDK's own request deadline (the thing that actually
 * cancels a call — {@code ClientOptions.timeout}) and the underlying OkHttp client's timeouts, so
 * it must be set there rather than via a {@code OpenAiHttpClientBuilderCustomizer} bean, which only
 * reaches the OkHttp layer and leaves the SDK-level deadline at its 60s default.
 *
 * <pre>
 * kb:
 *   chat:
 *     timeout:
 *       sse: 30m     # SseEmitter timeout for a subscribed browser tab (replay/reconnect window).
 * </pre>
 */
@ConfigurationProperties(prefix = "kb.chat.timeout")
public record ChatTimeoutProperties(Duration sse) {}
