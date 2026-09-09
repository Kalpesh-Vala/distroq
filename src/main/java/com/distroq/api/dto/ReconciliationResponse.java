package com.distroq.api.dto;

import com.distroq.reliability.FindingCategory;
import com.distroq.reliability.ReconciliationReport;
import com.distroq.reliability.ReliabilityFinding;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reconciliation report as an operator reads it.
 *
 * <p>The four outcome counts are kept apart on purpose. "Inspected" is bounded by
 * {@code batch-size}, so {@code inspected == batchSize} means there is probably more to see;
 * "repaired", "skipped" and "failed" then partition the findings, which is what distinguishes
 * "nothing was wrong" from "everything was wrong and I refused to touch any of it".
 */
public record ReconciliationResponse(Instant startedAt,
                                     Instant finishedAt,
                                     long durationMs,
                                     boolean autoRepairRequested,
                                     boolean autoRepairAllowedByConfiguration,
                                     boolean autoRepairApplied,
                                     boolean skippedBecauseAnotherRunHoldsTheLock,
                                     int inspected,
                                     int batchSize,
                                     boolean batchFull,
                                     int findings,
                                     long repaired,
                                     long skipped,
                                     long failed,
                                     long unresolved,
                                     Map<String, Long> countsByType,
                                     Map<String, List<Finding>> byCategory) {

    public record Finding(String type, String targetType, String targetId, String detail,
                          String resolution) {

        static Finding from(ReliabilityFinding finding) {
            return new Finding(finding.type().name(), finding.targetType(), finding.targetId(),
                    finding.detail(), finding.resolution().name());
        }
    }

    public static ReconciliationResponse from(ReconciliationReport report) {
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        Map<FindingCategory, List<ReliabilityFinding>> source = report.byCategory();
        for (FindingCategory category : FindingCategory.values()) {
            grouped.put(category.name(),
                    source.get(category).stream().map(Finding::from).toList());
        }
        return new ReconciliationResponse(
                report.startedAt(),
                report.finishedAt(),
                java.time.Duration.between(report.startedAt(), report.finishedAt()).toMillis(),
                report.autoRepairRequested(),
                report.autoRepairAllowedByConfiguration(),
                report.autoRepairApplied(),
                report.skippedBecauseAnotherRunHoldsTheLock(),
                report.inspected(),
                report.batchSize(),
                report.inspected() >= report.batchSize(),
                report.findingCount(),
                report.repaired(),
                report.skipped(),
                report.failed(),
                report.unresolved(),
                report.countsByType(),
                grouped);
    }
}
