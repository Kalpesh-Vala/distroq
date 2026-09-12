package com.distroq.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What reconciliation found, and nothing about fixing it.
 *
 * <p>{@code autoRepairEnabled} and {@code operatorActionRequired} are here because the most
 * dangerous way to read this page is to assume something is being done. The dashboard runs a
 * preview: every finding it shows is reported, none is repaired, and a deployment with
 * {@code auto-repair} off is one where nothing else is repairing them either.
 *
 * <p>{@code repairable} is copied from {@code FindingType#autoRepairable()} and means "the
 * database state proves a repair would be correct", not "this will be repaired".
 */
public record ReconciliationView(Summary summary,
                                 Configuration configuration,
                                 Map<String, Long> countsByType,
                                 Map<String, Long> countsByCategory,
                                 Map<String, Long> countsBySeverity,
                                 List<FindingRow> findings,
                                 Instant generatedAt,
                                 boolean previewOnly) {

    public record Summary(Instant startedAt,
                          Instant finishedAt,
                          long durationMs,
                          int inspected,
                          int batchSize,
                          boolean batchFull,
                          int findings,
                          long repaired,
                          long skipped,
                          long failed,
                          long unresolved,
                          boolean skippedBecauseAnotherRunHoldsTheLock) {
    }

    public record Configuration(boolean enabled,
                                boolean autoRepairAllowed,
                                int batchSize,
                                long pollIntervalMs,
                                long staleScheduledAfterMs,
                                long staleOutboxAfterMs,
                                long staleLeaseAfterMs,
                                boolean requeueFailedOutbox) {
    }

    public record FindingRow(String findingType,
                             String category,
                             String severity,
                             String targetType,
                             String targetId,
                             String description,
                             boolean repairable,
                             String resolution) {
    }
}
