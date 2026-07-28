package io.github.trialiya.kb.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;

@Configuration
@EnableJdbcAuditing
public class JdbcConfig {

    /**
     * The clock behind every timestamp that is compared against an audit stamp.
     *
     * <p>{@code chat_topic.updated_at} is written from two places — Spring Data auditing when the
     * row is saved, and the "touch" query when a chat is opened ({@code
     * ChatTopicRepository#updateUpdatedAt}) — and the list of chats is sorted by it. Whenever those
     * two disagree, a freshly opened chat drops down the list; that already happened once, when the
     * touch read {@code clock_timestamp()} (the database server's clock) instead of the
     * application's. Both now go through this bean, so "the same clock" is a wiring fact rather
     * than a coincidence of two {@code LocalDateTime.now()} calls.
     *
     * <p>Entities that stamp their own {@code updated_at} without being compared to an audit stamp
     * (documents, chat messages) still call {@code LocalDateTime.now()} directly — nothing breaks
     * if they drift, so they are deliberately not routed through here.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public DateTimeProvider dateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
