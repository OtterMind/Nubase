package ai.nubase.cron.service;

import ai.nubase.cron.CronProperties;
import ai.nubase.cron.target.ScheduledJobTarget;
import ai.nubase.cron.target.ScheduledJobTarget.RunOutcome;
import ai.nubase.metadata.cron.entity.ScheduledJob;
import ai.nubase.metadata.cron.entity.ScheduledJobRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledJobRunnerTest {

    @Mock
    private ScheduledJobStore store;
    @Mock
    private CronTenantContext tenantContext;
    @Mock
    private ScheduledJobTarget target;

    private ScheduledJobRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(target.type()).thenReturn(ScheduledJob.TARGET_DB_FUNCTION);
        runner = new ScheduledJobRunner(store, new CronProperties(), tenantContext, Runnable::run, List.of(target));
        lenient().when(tenantContext.runAs(anyString(), any())).thenAnswer(inv -> {
            Callable<?> action = inv.getArgument(1);
            return action.call();
        });
        lenient().when(store.complete(any(), any(), any(), any())).thenReturn(true);
        lenient().when(store.isLockHeld(any(), any())).thenReturn(true);
    }

    @Test
    void queuedClaimWhoseLockExpiredIsSkippedWithoutExecuting() throws Exception {
        ScheduledJob job = dueJob();
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(true);
        // Another instance re-claimed while this claim sat in the executor queue.
        when(store.isLockHeld(eq(job.getId()), any())).thenReturn(false);

        runner.tick();

        verify(target, never()).execute(any());
        ArgumentCaptor<ScheduledJobRun> runCaptor = ArgumentCaptor.forClass(ScheduledJobRun.class);
        verify(store).recordRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(ScheduledJobRun.STATUS_SKIPPED);
        // The lock belongs to the newer claim — this run must not complete/release it.
        verify(store, never()).complete(any(), any(), any(), any());
        verify(store, never()).releaseLock(any(), any(), any());
    }

    @Test
    void rejectedExecutionReleasesClaimEvenWhenHistoryWriteFails() throws Exception {
        ScheduledJob job = dueJob();
        ScheduledJobRunner rejectingRunner = new ScheduledJobRunner(
                store, new CronProperties(), tenantContext,
                command -> { throw new java.util.concurrent.RejectedExecutionException("queue full"); },
                List.of(target));
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("metadata pool exhausted"))
                .when(store).recordRun(any());

        rejectingRunner.tick();

        // History write failed, but the claim must still be completed/released —
        // otherwise the job stays locked for the full lock duration with nothing running.
        verify(store).complete(eq(job.getId()), any(), eq(ScheduledJobRun.STATUS_FAILED), any());
    }

    @Test
    void claimedDueJobIsExecutedAndRecorded() throws Exception {
        ScheduledJob job = dueJob();
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));
        when(store.claim(eq(job.getId()), eq(job.getNextRunAt()), any(), any(), any())).thenReturn(true);
        when(target.execute(job)).thenReturn(RunOutcome.success("1 row"));

        runner.tick();

        ArgumentCaptor<ScheduledJobRun> runCaptor = ArgumentCaptor.forClass(ScheduledJobRun.class);
        verify(store).recordRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(ScheduledJobRun.STATUS_SUCCESS);
        assertThat(runCaptor.getValue().getResult()).isEqualTo("1 row");
        // complete()'s guard must compare against the value the claim WROTE (the
        // advanced next_run_at), not the pre-claim entity snapshot — using the
        // snapshot made the guard always fail and left every job locked until expiry.
        ArgumentCaptor<Instant> claimedNext = ArgumentCaptor.forClass(Instant.class);
        verify(store).claim(eq(job.getId()), eq(job.getNextRunAt()), claimedNext.capture(), any(), any());
        verify(store).complete(eq(job.getId()), eq(claimedNext.getValue()), eq(ScheduledJobRun.STATUS_SUCCESS), any(Instant.class));
    }

    @Test
    void scheduleChangedMidRunReleasesLockWithoutTouchingNextRun() throws Exception {
        ScheduledJob job = dueJob();
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(true);
        when(target.execute(job)).thenReturn(RunOutcome.success("ok"));
        // Guard mismatch: an admin re-anchored next_run_at while the job ran.
        when(store.complete(any(), any(), any(), any())).thenReturn(false);

        runner.tick();

        ArgumentCaptor<Instant> lockToken = ArgumentCaptor.forClass(Instant.class);
        verify(store).claim(any(), any(), any(), lockToken.capture(), any());
        verify(store).releaseLock(eq(job.getId()), eq(lockToken.getValue()), eq(ScheduledJobRun.STATUS_SUCCESS));
    }

    @Test
    void unclaimedJobIsNotExecuted() throws Exception {
        ScheduledJob job = dueJob();
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(false);

        runner.tick();

        verify(target, never()).execute(any());
        verify(store, never()).recordRun(any());
    }

    @Test
    void targetFailureIsRecordedAsFailedRun() throws Exception {
        ScheduledJob job = dueJob();
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(true);
        when(target.execute(job)).thenThrow(new RuntimeException("boom"));

        runner.tick();

        ArgumentCaptor<ScheduledJobRun> runCaptor = ArgumentCaptor.forClass(ScheduledJobRun.class);
        verify(store).recordRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(ScheduledJobRun.STATUS_FAILED);
        assertThat(runCaptor.getValue().getErrorMessage()).contains("boom");
        ArgumentCaptor<Instant> claimedNext = ArgumentCaptor.forClass(Instant.class);
        verify(store).claim(eq(job.getId()), any(), claimedNext.capture(), any(), any());
        verify(store).complete(eq(job.getId()), eq(claimedNext.getValue()), eq(ScheduledJobRun.STATUS_FAILED), any(Instant.class));
    }

    @Test
    void completeFailureDoesNotPreventNextClaimedJob() throws Exception {
        ScheduledJob first = dueJob();
        ScheduledJob second = dueJob();
        second.setName("second");
        when(store.findDue(any(), anyInt())).thenReturn(List.of(first, second));
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(true);
        when(target.execute(any())).thenReturn(RunOutcome.success("ok"));
        when(store.complete(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(true);

        runner.tick();

        verify(target).execute(first);
        verify(target).execute(second);
    }

    @Test
    void unknownTargetTypeFailsWithoutExecuting() {
        ScheduledJob job = dueJob();
        job.setTargetType("something_else");
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(true);

        runner.tick();

        ArgumentCaptor<ScheduledJobRun> runCaptor = ArgumentCaptor.forClass(ScheduledJobRun.class);
        verify(store).recordRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getErrorMessage()).contains("UNKNOWN_TARGET_TYPE");
    }

    @Test
    void invalidCronExpressionSkipsClaiming() {
        ScheduledJob job = dueJob();
        job.setCronExpression("bogus");
        when(store.findDue(any(), anyInt())).thenReturn(List.of(job));

        runner.tick();

        verify(store, never()).claim(any(), any(), any(), any(), any());
    }

    private ScheduledJob dueJob() {
        return ScheduledJob.builder()
                .id(UUID.randomUUID())
                .projectRef("app1")
                .name("nightly")
                .cronExpression("*/5 * * * *")
                .targetType(ScheduledJob.TARGET_DB_FUNCTION)
                .dbFunctionName("do_cleanup")
                .enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60))
                .build();
    }
}
