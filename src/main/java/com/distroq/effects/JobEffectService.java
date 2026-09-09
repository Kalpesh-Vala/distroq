package com.distroq.effects;

import com.distroq.config.DistroqProperties;
import com.distroq.model.EffectStatus;
import com.distroq.model.JobEffect;
import com.distroq.repository.EffectCounterRepository;
import com.distroq.repository.JobEffectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The effect protocol: claim a key, do the work, record the result — all in one transaction.
 *
 * <p>This is what v0.8 does and does not promise. It makes a <em>cooperating</em> effect happen
 * at most once per logical key, because the claim and the effect commit together, so there is no
 * instant at which the effect has happened and the ledger does not know. It says nothing about an
 * arbitrary external call, which commits somewhere DistroQ has no transaction over. An HTTP POST
 * to a payment provider cannot join this transaction, and no amount of bookkeeping here changes
 * that. See NOTES.md.
 */
@Service
public class JobEffectService {

    public static final String COUNTER_EFFECT_TYPE = "counter";

    private static final Logger log = LoggerFactory.getLogger(JobEffectService.class);

    private final JobEffectRepository effects;
    private final EffectCounterRepository counters;
    private final EffectMetrics metrics;
    private final DistroqProperties.Effects properties;

    public JobEffectService(JobEffectRepository effects, EffectCounterRepository counters,
                            EffectMetrics metrics, DistroqProperties properties) {
        this.effects = effects;
        this.counters = counters;
        this.metrics = metrics;
        this.properties = properties.effects();
    }

    /**
     * Increment a durable counter exactly once for {@code <job-id>:counter:<normalized-payload>}.
     *
     * <p>The counter row and the ledger row move in the same transaction, so the pair can never
     * disagree: there is no ordering of a crash that leaves the counter incremented and the effect
     * un-recorded, or the reverse.
     */
    @Transactional
    public EffectOutcome applyCounter(UUID jobId, int attemptNumber, String payload) {
        if (!properties.enabled()) {
            throw new IllegalStateException(
                    "distroq.effects.enabled is false; the idempotent_counter job type is disabled");
        }
        String counterName = EffectKeys.normalize(payload);
        String effectKey = EffectKeys.counter(jobId, counterName);
        Claim claim = claim(effectKey, jobId, attemptNumber, COUNTER_EFFECT_TYPE);

        if (claim.kind() == Claim.Kind.ALREADY_COMPLETED) {
            metrics.deduplicationHit();
            log.info("Effect {} was already COMPLETED; the counter is not incremented again",
                    effectKey);
            return new EffectOutcome(effectKey, COUNTER_EFFECT_TYPE, EffectStatus.COMPLETED, false,
                    claim.responseHash(), counters.currentValue(counterName));
        }
        if (claim.kind() == Claim.Kind.IN_PROGRESS) {
            throw new EffectInProgressException("Effect " + effectKey + " has been STARTED since "
                    + claim.createdAt() + " and is not resolved; refusing to apply it a second "
                    + "time because a STARTED effect may or may not already have happened");
        }

        Instant now = Instant.now();
        counters.increment(counterName, now);
        long value = counters.currentValue(counterName);
        // the hash covers the identity and the observed result, never the payload
        String responseHash = ResponseHash.of(effectKey + ":" + value);

        JobEffect effect = effects.lockByKey(effectKey).orElseThrow(() ->
                new IllegalStateException("Effect " + effectKey + " vanished mid-transaction"));
        effect.complete(responseHash, now);
        metrics.applied();
        log.info("Effect {} applied; counter {} is now {}", effectKey, counterName, value);
        return new EffectOutcome(effectKey, COUNTER_EFFECT_TYPE, EffectStatus.COMPLETED, true,
                responseHash, value);
    }

    /**
     * Take ownership of an effect key, or find out who already has it.
     *
     * <p>The claim is an {@code INSERT ... ON CONFLICT DO NOTHING}, so two transactions racing the
     * same key are separated by the primary key rather than by timing. The loser blocks on the
     * uncommitted insert, and once the winner commits it reads COMPLETED — which is why concurrent
     * claims produce one application rather than one application and one error.
     */
    @Transactional
    public Claim claim(String effectKey, UUID jobId, int attemptNumber, String effectType) {
        Instant now = Instant.now();
        if (effects.claim(effectKey, jobId, attemptNumber, effectType, now) == 1) {
            return new Claim(Claim.Kind.CLAIMED, null, now);
        }

        JobEffect existing = effects.lockByKey(effectKey).orElseThrow(() ->
                new IllegalStateException("Effect " + effectKey + " could not be claimed and does "
                        + "not exist"));
        return switch (existing.getStatus()) {
            case COMPLETED -> new Claim(Claim.Kind.ALREADY_COMPLETED, existing.getResponseHash(),
                    existing.getCreatedAt());
            // FAILED means a worker observed the effect not happening, so the key is free again.
            // The row is reused rather than replaced, so the retry history stays on one row.
            case FAILED -> {
                existing.reclaim(attemptNumber, now);
                yield new Claim(Claim.Kind.CLAIMED, null, now);
            }
            case STARTED -> new Claim(Claim.Kind.IN_PROGRESS, null, existing.getCreatedAt());
        };
    }

    @Transactional
    public void fail(String effectKey, String error) {
        effects.lockByKey(effectKey)
                .ifPresent(effect -> effect.fail(truncate(error), Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<JobEffect> forJob(UUID jobId) {
        return effects.findByJobIdOrderByCreatedAtAsc(jobId);
    }

    public Instant staleBefore(Instant now) {
        return now.minusMillis(properties.staleStartedAfterMs());
    }

    private static String truncate(String error) {
        if (error == null) {
            return "unknown";
        }
        return error.length() <= 4000 ? error : error.substring(0, 4000);
    }

    public record Claim(Kind kind, String responseHash, Instant createdAt) {
        public enum Kind { CLAIMED, ALREADY_COMPLETED, IN_PROGRESS }
    }
}
