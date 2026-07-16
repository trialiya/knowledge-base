package io.github.trialiya.kb.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.model.embedding.EmbeddingEntityType;
import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import io.github.trialiya.kb.model.embedding.EmbeddingTaskStatus;
import io.github.trialiya.kb.repository.EmbeddingTaskRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Юнит-тесты диспетчеризации и обработки ошибок в {@link EmbeddingTaskScheduler}. */
class EmbeddingTaskSchedulerTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final int BACKOFF_SECONDS = 30;

    private EmbeddingTaskRepository taskRepo;
    private EmbeddingExecutor executor;
    private SemanticSearchService searchService;
    private EmbeddingTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskRepo = mock(EmbeddingTaskRepository.class);
        executor = mock(EmbeddingExecutor.class);
        searchService = mock(SemanticSearchService.class);
        scheduler =
                new EmbeddingTaskScheduler(
                        taskRepo,
                        executor,
                        searchService,
                        new EmbeddingConfiguration(
                                "test-model",
                                50,
                                4,
                                20,
                                MAX_ATTEMPTS,
                                BACKOFF_SECONDS,
                                10,
                                7,
                                null,
                                null));
    }

    private static EmbeddingTaskEntity task(
            EmbeddingEntityType entityType, long entityId, int attempts) {
        EmbeddingTaskEntity t = new EmbeddingTaskEntity();
        t.setId(entityId * 10);
        t.setEntityType(entityType);
        t.setEntityId(entityId);
        t.setStatus(EmbeddingTaskStatus.STARTING);
        t.setAttempts(attempts);
        t.setClaimToken(UUID.randomUUID());
        return t;
    }

    /** Настраивает executor исполнять задачи синхронно — так тест видит работу воркера. */
    private void runTasksInline() {
        when(executor.submit(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.<Runnable>getArgument(0).run();
                            return true;
                        });
    }

    // ── poll(): адаптивный размер батча ──────────────────────────────────────

    @Test
    void pollSkipsClaimWhenExecutorSaturated() {
        when(executor.availablePermits()).thenReturn(0);

        scheduler.poll();

        verify(taskRepo, never()).claimPending(anyInt(), anyInt());
    }

    @Test
    void pollClaimsNoMoreThanFreePermits() {
        when(executor.availablePermits()).thenReturn(2);
        when(taskRepo.claimPending(2, BACKOFF_SECONDS)).thenReturn(List.of());

        scheduler.poll();

        verify(taskRepo).claimPending(2, BACKOFF_SECONDS);
    }

    @Test
    void pollReleasesClaimWhenExecutorRejects() {
        EmbeddingTaskEntity t = task(EmbeddingEntityType.DOCUMENT, 1L, 1);
        when(executor.availablePermits()).thenReturn(4);
        when(taskRepo.claimPending(4, BACKOFF_SECONDS)).thenReturn(List.of(t));
        when(executor.submit(any())).thenReturn(false);
        when(taskRepo.isMyClaimValid(t.getId(), t.getClaimToken())).thenReturn(true);

        scheduler.poll();

        verify(taskRepo).releaseClaim(t.getId(), t.getClaimToken());
        verify(taskRepo, never()).markDone(any(), any());
    }

    // ── Обработка задачи ─────────────────────────────────────────────────────

    @Test
    void successfulTaskIsMarkedDone() {
        EmbeddingTaskEntity t = task(EmbeddingEntityType.DOCUMENT, 2L, 1);
        when(executor.availablePermits()).thenReturn(4);
        when(taskRepo.claimPending(4, BACKOFF_SECONDS)).thenReturn(List.of(t));
        when(taskRepo.isMyClaimValid(t.getId(), t.getClaimToken())).thenReturn(true);
        runTasksInline();

        scheduler.poll();

        verify(searchService).indexDocumentById(2L);
        verify(taskRepo).markDone(t.getId(), t.getClaimToken());
    }

    @Test
    void attachmentTaskRoutesToAttachmentIndexing() {
        EmbeddingTaskEntity t = task(EmbeddingEntityType.ATTACHMENT, 3L, 1);
        when(executor.availablePermits()).thenReturn(4);
        when(taskRepo.claimPending(4, BACKOFF_SECONDS)).thenReturn(List.of(t));
        when(taskRepo.isMyClaimValid(t.getId(), t.getClaimToken())).thenReturn(true);
        runTasksInline();

        scheduler.poll();

        verify(searchService).indexAttachmentById(3L);
        verify(taskRepo).markDone(t.getId(), t.getClaimToken());
    }

    @Test
    void reclaimedTaskIsSkippedWithoutAiCall() {
        EmbeddingTaskEntity t = task(EmbeddingEntityType.DOCUMENT, 4L, 1);
        when(executor.availablePermits()).thenReturn(4);
        when(taskRepo.claimPending(4, BACKOFF_SECONDS)).thenReturn(List.of(t));
        when(taskRepo.isMyClaimValid(t.getId(), t.getClaimToken())).thenReturn(false);
        runTasksInline();

        scheduler.poll();

        verify(searchService, never()).indexDocumentById(any());
        verify(taskRepo, never()).markDone(any(), any());
    }

    @Test
    void failureBelowMaxAttemptsResetsToPending() {
        EmbeddingTaskEntity t = task(EmbeddingEntityType.DOCUMENT, 5L, 1);
        when(executor.availablePermits()).thenReturn(4);
        when(taskRepo.claimPending(4, BACKOFF_SECONDS)).thenReturn(List.of(t));
        when(taskRepo.isMyClaimValid(t.getId(), t.getClaimToken())).thenReturn(true);
        doThrow(new RuntimeException("AI down")).when(searchService).indexDocumentById(5L);
        runTasksInline();

        scheduler.poll();

        verify(taskRepo).resetToPending(t.getId(), t.getClaimToken());
        verify(taskRepo, never()).markFailed(any(), any());
    }

    @Test
    void failureAtMaxAttemptsMarksFailed() {
        EmbeddingTaskEntity t = task(EmbeddingEntityType.DOCUMENT, 6L, MAX_ATTEMPTS);
        when(executor.availablePermits()).thenReturn(4);
        when(taskRepo.claimPending(4, BACKOFF_SECONDS)).thenReturn(List.of(t));
        when(taskRepo.isMyClaimValid(t.getId(), t.getClaimToken())).thenReturn(true);
        doThrow(new RuntimeException("AI down")).when(searchService).indexDocumentById(6L);
        runTasksInline();

        scheduler.poll();

        verify(taskRepo).markFailed(t.getId(), t.getClaimToken());
        verify(taskRepo, never()).resetToPending(any(), any());
    }
}
