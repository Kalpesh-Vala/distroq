package com.distroq.reliability;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-instance reconciliation, against a real PostgreSQL.
 *
 * <p>The unit test can only prove that a denied lock produces a skipped report, because it decides
 * the denial itself. What it cannot show is that the lock is actually contended — that two runs
 * asking for the same advisory key really do exclude each other, and that the scheduled sweep and
 * an operator-initiated run make opposite choices about waiting for it. Both need two connections.
 *
 * <p>Off by default because it needs {@code docker compose up -d}. Run it with:
 *
 * <pre>{@code .\mvnw.cmd test -Dtest=ReconciliationLockLiveTest -Ddistroq.live=true}</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "distroq.live", matches = "true")
@TestPropertySource(properties = {
        "distroq.stream-key=distroq:livetest:stream",
        "distroq.queue-key=distroq:livetest:pending",
        "distroq.delayed-key=distroq:livetest:delayed",
        "distroq.scheduled-key=distroq:livetest:scheduled",
        "distroq.outbox.relay-enabled=false",
        "distroq.reconciliation.enabled=false"
})
class ReconciliationLockLiveTest {

    @Autowired
    private ReconciliationService service;

    @Autowired
    private LockHolder lockHolder;

    /** The scheduled sweep has nothing useful to add to a run already in progress, so it skips. */
    @Test
    void aScheduledSweepSkipsItsTickWhileAnotherRunHoldsTheLock() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> lockHolder.holdUntil(acquired, release));
            assertThat(acquired.await(30, TimeUnit.SECONDS)).isTrue();

            ReconciliationReport report =
                    service.run(false, "concurrent tick", "reconciliation", false);

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(report.skippedBecauseAnotherRunHoldsTheLock()).isTrue();
            assertThat(report.findingCount()).isZero();
            assertThat(report.inspected()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** An operator asked a question and should get an answer, so this one queues instead. */
    @Test
    void anOperatorRunWaitsForTheLockRatherThanSkipping() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> lockHolder.holdUntil(acquired, release));
            assertThat(acquired.await(30, TimeUnit.SECONDS)).isTrue();

            Future<ReconciliationReport> waiting = pool.submit(() ->
                    service.run(false, "operator run", "operator", true));
            assertThat(waiting.isDone()).as("blocked on the advisory lock").isFalse();
            Thread.sleep(500);
            assertThat(waiting.isDone()).as("still blocked while the lock is held").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            ReconciliationReport report = waiting.get(30, TimeUnit.SECONDS);
            assertThat(report.skippedBecauseAnotherRunHoldsTheLock()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @TestConfiguration
    static class LockHolderConfiguration {

        @Bean
        LockHolder lockHolder(EntityManager entityManager) {
            return new LockHolder(entityManager);
        }
    }

    /** Takes the same advisory key reconciliation uses, and sits on it until told to let go. */
    static class LockHolder {

        private final EntityManager entityManager;

        LockHolder(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        @Transactional
        public void holdUntil(CountDownLatch acquired, CountDownLatch release) {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                    .setParameter(1, ReconciliationService.ADVISORY_LOCK_KEY)
                    .getSingleResult();
            acquired.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
