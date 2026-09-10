package com.distroq.lifecycle;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import com.distroq.worker.WorkerMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The shutdown sequence, asserted where it can be asserted without a container: the ordering of
 * the phases, the flag that stops new work, and the refusal to invent an outcome for work that
 * outran its budget.
 */
class ShutdownSequenceTest {

    @Test
    void readinessGoesDownBeforeAnyLifecycleBeanStops() {
        // Spring publishes ContextClosedEvent at the top of doClose(), before
        // lifecycleProcessor.onClose() stops a single Lifecycle bean. A SmartLifecycle at
        // Integer.MAX_VALUE would still run after Boot had begun draining HTTP, which is why the
        // coordinator listens for the event instead.
        ShutdownState state = new ShutdownState();
        ApplicationContext context = mock(ApplicationContext.class);

        new ShutdownCoordinator(context, state).onContextClosed();

        assertThat(state.isRunning()).isFalse();

        ArgumentCaptor<ApplicationEvent> published =
                ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(context).publishEvent(published.capture());
        assertThat(((AvailabilityChangeEvent<?>) published.getValue()).getState())
                .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
    }

    @Test
    void workIsDrainedAfterHttpHasStoppedButBeforeTheConnectionsClose() {
        WorkDrainLifecycle drain = new WorkDrainLifecycle(
                new WorkerMetrics(TestProperties.defaults()), TestProperties.defaults());

        // later than Boot's graceful web shutdown (MAX - 1024) and the connector stop (MAX - 2048),
        // so no HTTP request is in flight by the time the drain begins
        assertThat(drain.getPhase()).isLessThan(Integer.MAX_VALUE - 2048);
    }

    @Test
    void theShutdownIsAnnouncedOnceEvenWhenTheEventArrivesTwice() {
        ShutdownState state = new ShutdownState();
        ApplicationContext context = mock(ApplicationContext.class);
        ShutdownCoordinator coordinator = new ShutdownCoordinator(context, state);

        coordinator.onContextClosed();
        coordinator.onContextClosed();

        verify(context, times(1)).publishEvent(any(ApplicationEvent.class));
    }

    @Test
    void theFallbackListenerStopsTheLoopsWithoutClaimingTheAnnouncement() {
        // a context assembled without the coordinator must still stop the sweeps, but it must not
        // consume begin() - otherwise the coordinator would be silent in a real context
        ShutdownState state = new ShutdownState();

        state.onContextClosed();

        assertThat(state.isRunning()).isFalse();
        assertThat(state.isShuttingDown()).isTrue();
    }

    @Test
    void theDrainReturnsImmediatelyWhenNothingIsRunning() {
        WorkerMetrics metrics = new WorkerMetrics(TestProperties.defaults());
        WorkDrainLifecycle drain = new WorkDrainLifecycle(metrics, TestProperties.defaults());
        drain.start();

        long startedAt = System.currentTimeMillis();
        drain.stop();

        assertThat(System.currentTimeMillis() - startedAt).isLessThan(1000L);
        assertThat(drain.isRunning()).isFalse();
    }

    @Test
    void workThatOutrunsTheBudgetIsAbandonedRatherThanForced() {
        WorkerMetrics metrics = new WorkerMetrics(TestProperties.defaults());
        metrics.workerStarted();
        DistroqProperties impatient = TestProperties.builder()
                .shutdown(new DistroqProperties.Shutdown(150L, 10_000L, 10_000L))
                .build();

        WorkDrainLifecycle drain = new WorkDrainLifecycle(metrics, impatient);
        drain.start();
        drain.stop();

        // the point of the assertion: the drain gave up and changed nothing about the job. No
        // outcome was recorded, so the lease expires and another consumer reclaims the entry.
        assertThat(metrics.activeWorkers()).isEqualTo(1);
    }
}
