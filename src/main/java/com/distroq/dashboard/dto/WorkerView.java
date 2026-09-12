package com.distroq.dashboard.dto;

import java.time.Instant;

/**
 * Who is working, and on what authority.
 *
 * <p>The distinction this page exists to make: a Redis consumer holding a delivery is not the same
 * as a worker owning an execution. Redis decides who was handed an entry; PostgreSQL decides who
 * may run the job, through the execution lease. The two normally agree, and when they do not it is
 * the lease that is right — so consumers and leases arrive as two independent sections, not as one
 * merged list. That also means a Redis outage costs the consumer list and leaves the lease list
 * standing, which is the half an operator needs during exactly that outage.
 *
 * <p>No payload field appears anywhere below, and there is no route to one: a lease row carries
 * identifiers, a job type and timestamps.
 */
public final class WorkerView {

    private WorkerView() {
    }

    /**
     * @param instanceLocalCounters true, always. {@code configuredConcurrency},
     *                              {@code activeWorkers} and {@code reclaimedEntries} come from
     *                              this process's own counters and describe the instance serving
     *                              the request; {@code activeLeases} and
     *                              {@code abandonedAttempts} are database counts and are
     *                              cluster-wide. The flag exists so the UI can say so rather than
     *                              show six numbers of two different kinds side by side.
     */
    public record Totals(int configuredConcurrency,
                         int activeWorkers,
                         int idleWorkers,
                         long activeLeases,
                         long reclaimedEntries,
                         long abandonedAttempts,
                         boolean instanceLocalCounters) {
    }

    /**
     * @param workerId       the same value as {@code consumerName}: a worker claims a job under
     *                       the consumer name it was delivered to, so the database's
     *                       {@code execution_owner} is the Redis consumer name. Both are present
     *                       because they answer different questions and may diverge in a future
     *                       version.
     * @param heartbeatStale the lease is live but Redis has not heard from this consumer within a
     *                       whole lease period, so the two views of the same worker disagree. Null
     *                       when Redis could not be reached, which is not the same as false.
     */
    public record LeaseRow(String jobId,
                           String attemptId,
                           String workerId,
                           String consumerName,
                           String priority,
                           String jobType,
                           String status,
                           Instant startedAt,
                           Instant leaseUntil,
                           long remainingLeaseMs,
                           boolean expiringSoon,
                           boolean expired,
                           Boolean heartbeatStale,
                           int attemptCount,
                           int maxAttempts) {
    }

    /** One unacknowledged delivery. The entry ID is stream metadata, never a payload field. */
    public record PendingEntryRow(String entryId,
                                  String consumerName,
                                  String priority,
                                  long idleMs,
                                  long deliveryCount,
                                  Long ageMs,
                                  boolean redelivered) {
    }
}
