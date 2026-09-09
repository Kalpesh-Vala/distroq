package com.distroq.reliability;

/**
 * One inconsistency, named and located.
 *
 * <p>{@code targetId} is a string rather than a UUID because effect keys are not UUIDs, and
 * {@code detail} is a short structural sentence rather than an error body — nothing here should
 * ever need redacting before an operator reads it.
 */
public record ReliabilityFinding(FindingType type, String targetType, String targetId,
                                 String detail, Resolution resolution) {

    public static ReliabilityFinding reported(FindingType type, String targetType, String targetId,
                                              String detail) {
        return new ReliabilityFinding(type, targetType, targetId, detail, Resolution.REPORTED);
    }

    public ReliabilityFinding resolvedAs(Resolution resolution) {
        return new ReliabilityFinding(type, targetType, targetId, detail, resolution);
    }

    public ReliabilityFinding resolvedAs(Resolution resolution, String detail) {
        return new ReliabilityFinding(type, targetType, targetId, detail, resolution);
    }

    public FindingCategory category() {
        return type.category();
    }
}
