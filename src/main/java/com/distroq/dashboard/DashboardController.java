package com.distroq.dashboard;

import com.distroq.api.error.ApiException;
import com.distroq.api.error.ErrorCode;
import com.distroq.dashboard.dto.DashboardResponse;
import com.distroq.model.JobStatus;
import com.distroq.model.OutboxStatus;
import com.distroq.model.Priority;
import com.distroq.outbox.OutboxEventType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The read-only operations dashboard API.
 *
 * <p>Eleven endpoints, every one of them a {@code @GetMapping}. There is no {@code @PostMapping},
 * {@code @PutMapping}, {@code @PatchMapping} or {@code @DeleteMapping} in this file and there is
 * not meant to be one: retry, replay, repair and cleanup already exist on
 * {@link com.distroq.api.AdminController}, where the {@code X-Admin-Reason} header and the
 * {@code reliability_actions} audit row are, and moving any of them here would move an audited
 * mutation onto a surface a browser polls every five seconds.
 *
 * <p>{@link DashboardReadOnlyFilter} enforces that from outside, so the guarantee does not depend
 * on nobody adding an annotation later. {@link com.distroq.api.AdminAuthenticationFilter}
 * authenticates every path below with the same bearer token the administrative API uses.
 *
 * <p>Bad enum and timestamp values are converted by Spring and rejected by
 * {@link com.distroq.api.error.GlobalExceptionHandler} as {@code INVALID_PARAMETER}, so this
 * controller does no validation of its own beyond bounding pages, which
 * {@link DashboardProperties} does.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final DashboardProperties properties;

    public DashboardController(DashboardService dashboard, DashboardProperties properties) {
        this.dashboard = dashboard;
        this.properties = properties;
    }

    @GetMapping("/overview")
    public DashboardResponse.Overview overview() {
        return dashboard.overview();
    }

    @GetMapping("/queues")
    public DashboardResponse.Queues queues() {
        return dashboard.queues();
    }

    @GetMapping("/workers")
    public DashboardResponse.Workers workers() {
        return dashboard.workers();
    }

    @GetMapping("/outbox")
    public DashboardResponse.Outbox outbox(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(required = false) OutboxEventType eventType,
            @RequestParam(required = false) UUID aggregateId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return dashboard.outbox(status, eventType, aggregateId, page, size, sort, direction);
    }

    @GetMapping("/reconciliation")
    public DashboardResponse.Reconciliation reconciliation() {
        return dashboard.reconciliation();
    }

    @GetMapping("/jobs")
    @SuppressWarnings("java:S107") // one parameter per filter the jobs table offers
    public DashboardResponse.Jobs jobs(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) Instant createdAfter,
            @RequestParam(required = false) Instant createdBefore,
            @RequestParam(required = false) Boolean scheduled,
            @RequestParam(required = false) Boolean hasAttempts,
            @RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return dashboard.jobs(status, priority, jobType, createdAfter, createdBefore, scheduled,
                hasAttempts, jobId, page, size, sort, direction);
    }

    @GetMapping("/jobs/{jobId}")
    public DashboardResponse.JobDetail job(@PathVariable UUID jobId) {
        return dashboard.job(jobId);
    }

    @GetMapping("/dlq")
    public DashboardResponse.Dlq dlq(
            @RequestParam(required = false) Boolean replayed,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return dashboard.dlq(replayed, page, size);
    }

    /**
     * @param run a window identifier from {@code availableRuns}, or absent for the newest. It is
     *            matched against the discovered list rather than resolved as a path.
     */
    @GetMapping("/analytics")
    public DashboardResponse.Analytics analytics(
            @RequestParam(required = false) String run,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String jobType) {
        return dashboard.analytics(run, startDate, endDate, priority, jobType);
    }

    @GetMapping("/system")
    public DashboardResponse.System system() {
        return dashboard.system();
    }

    @GetMapping("/activity")
    public DashboardResponse.Activity activity(@RequestParam(required = false) Integer limit) {
        if (limit != null && limit > properties.maxActivityEvents()) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "'limit' must not exceed " + properties.maxActivityEvents());
        }
        return dashboard.activity(limit);
    }
}
