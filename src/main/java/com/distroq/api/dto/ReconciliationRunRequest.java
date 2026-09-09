package com.distroq.api.dto;

/**
 * Body of {@code POST /api/admin/reconciliation/run}.
 *
 * <p>{@code autoRepair} is a request, not a grant. Configuration is the ceiling, so this can only
 * ask for less than {@code distroq.reconciliation.auto-repair} already allows.
 */
public record ReconciliationRunRequest(Boolean autoRepair, String reason) {

    public boolean autoRepairRequested() {
        return Boolean.TRUE.equals(autoRepair);
    }
}
