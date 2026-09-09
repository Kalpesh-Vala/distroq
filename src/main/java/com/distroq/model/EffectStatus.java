package com.distroq.model;

/** Lifecycle of one protected side effect. */
public enum EffectStatus {

    /** Claimed by a worker. Ambiguous after a crash: the effect may or may not have happened. */
    STARTED,

    /** The effect happened and its result is recorded. A second claim of this key is a no-op. */
    COMPLETED,

    /** The effect did not happen, as far as the worker could tell. The key may be claimed again. */
    FAILED
}
