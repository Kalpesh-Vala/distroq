package com.distroq.worker;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.queue.ScheduledJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The poller does one thing, and the tests are mostly about the things it must <em>not</em> do:
 * execute a job, write to PostgreSQL, or let an exception escape and cancel its own schedule.
 */
class ScheduledJobPromoterTest {

    private ScheduledJobQueue scheduledJobQueue;
    private ScheduledJobPromoter promoter;
    private com.distroq.lifecycle.ShutdownState shutdownState;

    @BeforeEach
    void setUp() {
        scheduledJobQueue = mock(ScheduledJobQueue.class);
        when(scheduledJobQueue.key()).thenReturn("distroq:jobs:scheduled");
        shutdownState = new com.distroq.lifecycle.ShutdownState();
        promoter = new ScheduledJobPromoter(scheduledJobQueue, shutdownState,
                new com.distroq.health.SubsystemHealth(), TestProperties.defaults());
    }

    @Test
    void eachTickPromotesDueJobsUpToTheConfiguredBatchSize() {
        promoter.sweep();

        verify(scheduledJobQueue).promoteDueJobs(any(Instant.class), eq(100));
    }

    @Test
    void theBatchSizeComesFromConfigurationRatherThanBeingHardcoded() {
        ScheduledJobPromoter tuned = new ScheduledJobPromoter(scheduledJobQueue, shutdownState,
                new com.distroq.health.SubsystemHealth(),
                TestProperties.of(new DistroqProperties.Scheduling(250L, 7)));

        tuned.sweep();

        verify(scheduledJobQueue).promoteDueJobs(any(Instant.class), eq(7));
    }

    @Test
    void theCutoffIsTheCurrentInstant() {
        Instant before = Instant.now();

        promoter.sweep();

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(scheduledJobQueue).promoteDueJobs(now.capture(), anyInt());
        assertThat(now.getValue()).isBetween(before, Instant.now());
    }

    @Test
    void anEmptySweepIsNotAnError() {
        when(scheduledJobQueue.promoteDueJobs(any(), anyInt())).thenReturn(0);

        assertThatCode(promoter::sweep).doesNotThrowAnyException();
    }

    @Test
    void aFailedSweepDoesNotPropagateBecauseAnEscapingExceptionCancelsTheSchedule() {
        // Spring cancels all future executions of a @Scheduled method whose invocation throws.
        // That would strand every scheduled job until the next restart, so the sweep swallows
        // and logs instead
        when(scheduledJobQueue.promoteDueJobs(any(), anyInt()))
                .thenThrow(new IllegalStateException("redis is down"));

        assertThatCode(promoter::sweep).doesNotThrowAnyException();

        promoter.sweep();
        verify(scheduledJobQueue, times(2)).promoteDueJobs(any(), anyInt());
    }

    @Test
    void theSweepStopsTouchingRedisOnceShutdownHasBegun() {
        // matches Worker and RetryScheduler: the connection is closed during bean destruction,
        // and a tick that lands after that would log a stack trace on a clean shutdown
        shutdownState.onContextClosed();

        promoter.sweep();

        verify(scheduledJobQueue, never()).promoteDueJobs(any(), anyInt());
    }

    @Test
    void thePromoterNeverDoesAnythingToAJobBeyondMovingIt() {
        // no execution, no attempt rows, no status writes - all of that belongs to the worker
        // that receives the entry, and splitting it across two threads is how a job gets run twice
        when(scheduledJobQueue.promoteDueJobs(any(), anyInt())).thenReturn(3);

        promoter.sweep();

        verify(scheduledJobQueue).promoteDueJobs(any(), anyInt());
        verify(scheduledJobQueue).key();
        verifyNoMoreInteractions(scheduledJobQueue);
    }
}
