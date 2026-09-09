package com.distroq.effects;

import com.distroq.model.EffectStatus;
import com.distroq.model.Job;
import com.distroq.model.JobEffect;
import com.distroq.repository.EffectCounterRepository;
import com.distroq.repository.JobEffectRepository;
import com.distroq.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrent claim, against a real PostgreSQL.
 *
 * <p>This is the one part of the effect protocol that cannot be shown with a mock. The guarantee
 * comes from two connections colliding on a primary key — the loser blocks on the winner's
 * uncommitted insert and then reads a committed COMPLETED row — and a stubbed repository decides
 * that outcome by itself rather than demonstrating it.
 *
 * <p>Off by default because it needs {@code docker compose up -d}. Run it with:
 *
 * <pre>{@code .\mvnw.cmd test -Dtest=JobEffectConcurrencyLiveTest -Ddistroq.live=true}</pre>
 *
 * <p>The queue keys are redirected so that this context's worker polls its own empty streams
 * rather than competing with a running instance for real work.
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
class JobEffectConcurrencyLiveTest {

    @Autowired
    private JobEffectService effects;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JobEffectRepository ledger;

    @Autowired
    private EffectCounterRepository counters;

    @Test
    void twoThreadsRacingOneEffectKeyApplyItExactlyOnce() throws Exception {
        String counterName = "concurrent-" + UUID.randomUUID();
        Job job = jobs.save(Job.create("idempotent_counter", counterName, 3));
        String effectKey = EffectKeys.counter(job.getId(), EffectKeys.normalize(counterName));

        List<Outcome> outcomes = race(2, () -> {
            try {
                return new Outcome(effects.applyCounter(job.getId(), 1, counterName), null);
            } catch (RuntimeException e) {
                return new Outcome(null, e);
            }
        });

        long applied = outcomes.stream()
                .filter(outcome -> outcome.outcome() != null && outcome.outcome().newlyApplied())
                .count();
        long observedExisting = outcomes.stream()
                .filter(outcome -> outcome.outcome() != null && !outcome.outcome().newlyApplied())
                .count();
        long sawInProgress = outcomes.stream()
                .filter(outcome -> outcome.failure() instanceof EffectInProgressException)
                .count();

        assertThat(applied).as("exactly one worker applies the effect").isEqualTo(1);
        assertThat(observedExisting + sawInProgress)
                .as("the other worker observes an existing or in-progress effect").isEqualTo(1);
        assertThat(counters.currentValue(EffectKeys.normalize(counterName))).isEqualTo(1L);

        JobEffect stored = ledger.findById(effectKey).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(EffectStatus.COMPLETED);
        assertThat(ledger.findByJobIdOrderByCreatedAtAsc(job.getId())).hasSize(1);
    }

    private static <T> List<T> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startLine = new CyclicBarrier(threads);
        try {
            List<Future<T>> futures = new ArrayList<>(threads);
            for (int thread = 0; thread < threads; thread++) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>(threads);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Outcome(EffectOutcome outcome, RuntimeException failure) {
    }
}
