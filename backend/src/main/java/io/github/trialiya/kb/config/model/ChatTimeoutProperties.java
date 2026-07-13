package io.github.trialiya.kb.config.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timeouts around the chat stream.
 *
 * <pre>
 * kb:
 *   chat:
 *     timeout:
 *       stream: 5m   # OpenAI HTTP client call timeout (openai-java SDK, applies to all
 *                    # spring-ai-openai models); too short aborts slow/long generations,
 *                    # too long delays detecting a genuinely dead upstream.
 *       sse: 30m     # SseEmitter timeout for a subscribed browser tab (replay/reconnect window).
 * </pre>
 */
@ConfigurationProperties(prefix = "kb.chat.timeout")
public record ChatTimeoutProperties(Duration stream, Duration sse) {}
