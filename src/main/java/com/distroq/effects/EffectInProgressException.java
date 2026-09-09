package com.distroq.effects;

/**
 * A STARTED effect was found by a second claimer.
 *
 * <p>Deliberately not a success and deliberately not a silent skip. STARTED is the one ledger
 * state that carries no information: the holder may be mid-flight, or may have died a
 * millisecond after the external system accepted the call. Failing the job hands the decision to
 * the retry machinery, and — if the row stays STARTED past the stale threshold — to
 * reconciliation and then to a human. See NOTES.md.
 */
public class EffectInProgressException extends RuntimeException {

    public EffectInProgressException(String message) {
        super(message);
    }
}
