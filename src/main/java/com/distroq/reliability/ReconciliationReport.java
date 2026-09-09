package com.distroq.reliability;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of one reconciliation run.
 *
 * <p>The four totals are deliberately separate. "Inspected" is how much of the database was
 * looked at, and is bounded by {@code batch-size}, so a report showing findings equal to the
 * batch size means there is probably more. "Repaired", "skipped" and "failed" then partition the
 * findings, which is what makes it possible to tell "reconciliation is working and there was
 * nothing to do" from "reconciliation ran and refused to touch any of it".
 */
public record ReconciliationReport(Instant startedAt,
                                   Instant finishedAt,
                                   boolean autoRepairRequested,
                                   boolean autoRepairAllowedByConfiguration,
                                   boolean autoRepairApplied,
                                   boolean skippedBecauseAnotherRunHoldsTheLock,
                                   int inspected,
                                   int batchSize,
                                   List<ReliabilityFinding> findings) {

    public int findingCount() {
        return findings.size();
    }

    public long repaired() {
        return countBy(Resolution.REPAIRED);
    }

    public long skipped() {
        return countBy(Resolution.SKIPPED);
    }

    public long failed() {
        return countBy(Resolution.FAILED);
    }

    public long unresolved() {
        return countBy(Resolution.REPORTED) + countBy(Resolution.SKIPPED) + countBy(Resolution.FAILED);
    }

    private long countBy(Resolution resolution) {
        return findings.stream().filter(finding -> finding.resolution() == resolution).count();
    }

    /** Every category appears, including empty ones, so the response shape does not move. */
    public Map<FindingCategory, List<ReliabilityFinding>> byCategory() {
        Map<FindingCategory, List<ReliabilityFinding>> grouped = new EnumMap<>(FindingCategory.class);
        for (FindingCategory category : FindingCategory.values()) {
            grouped.put(category, List.of());
        }
        for (ReliabilityFinding finding : findings) {
            grouped.merge(finding.category(), List.of(finding),
                    (existing, added) -> {
                        List<ReliabilityFinding> merged =
                                new java.util.ArrayList<>(existing.size() + 1);
                        merged.addAll(existing);
                        merged.addAll(added);
                        return List.copyOf(merged);
                    });
        }
        return grouped;
    }

    public Map<String, Long> countsByType() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReliabilityFinding finding : findings) {
            counts.merge(finding.type().name(), 1L, Long::sum);
        }
        return counts;
    }
}
