package com.distroq.queue;

/**
 * What a delivery is given to. One implementation, {@code Worker} — the interface exists so that
 * {@link PendingEntryRecovery} cannot grow an execution path of its own, and so that the queue
 * package does not have to depend on the worker package to reach one.
 */
public interface DeliveryHandler {

    void handle(StreamDelivery delivery);

    /** True while this process is executing the entry, and so should not reclaim it from itself. */
    boolean isInFlight(String streamKey, String entryId);
}
