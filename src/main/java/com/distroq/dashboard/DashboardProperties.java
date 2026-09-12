package com.distroq.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The v1.1 read-only operations dashboard.
 *
 * <p>Nothing here changes queue, worker, retry or reconciliation behaviour. The values are the
 * dashboard's own budgets: how large a page it will serve, how often it will let a browser ask,
 * and how long it may reuse an answer that is expensive to compute.
 *
 * <p>{@code reconciliationCacheMs} and {@code analyticsCacheMs} exist because two of the panels
 * are backed by work that is not free. A reconciliation preview is a batch of indexed scans and a
 * PostgreSQL advisory lock; an analytics page is a directory of files written by a Spark run. A
 * browser polling every five seconds must not turn either into a per-poll cost, and neither answer
 * changes fast enough for that to lose anything.
 *
 * <p>{@code staticPath} is a directory holding a built frontend bundle, not a document root for
 * arbitrary files: it is served read-only, under {@code /dashboard/} only, and only when it
 * actually contains an {@code index.html}. It is deliberately not a secret and must never be
 * pointed at anything that is.
 */
@ConfigurationProperties(prefix = "distroq.dashboard")
public record DashboardProperties(
        @DefaultValue("true") boolean enabled,
        /** Advertised to the browser. The frontend polls at this interval unless the user changes it. */
        @DefaultValue("5000") long refreshIntervalMs,
        @DefaultValue("50") int defaultPageSize,
        /** A hard ceiling, applied after the requested size, so no caller can ask for the table. */
        @DefaultValue("200") int maxPageSize,
        @DefaultValue("100") int maxActivityEvents,
        /** How far back the "recent" throughput and activity windows look. */
        @DefaultValue("86400000") long recentWindowMs,
        @DefaultValue("30000") long reconciliationCacheMs,
        @DefaultValue("60000") long analyticsCacheMs,
        /** Where v0.9 wrote its report directories. Read-only; never written to by this application. */
        @DefaultValue("analytics/output") String analyticsDirectory,
        /** A built frontend bundle to serve at {@code /dashboard/}, or blank to serve none. */
        @DefaultValue("dashboard/dist") String staticPath,
        /** Warn in the UI once a lease has less than this long to run. */
        @DefaultValue("5000") long leaseWarningMs) {

    /** The requested page size, defaulted when absent and clamped to {@link #maxPageSize()}. */
    public int pageSize(Integer requested) {
        int ceiling = Math.max(1, maxPageSize);
        if (requested == null) {
            return Math.clamp(defaultPageSize, 1, ceiling);
        }
        return Math.clamp(requested, 1, ceiling);
    }

    public int pageNumber(Integer requested) {
        return requested == null ? 0 : Math.max(0, requested);
    }
}
