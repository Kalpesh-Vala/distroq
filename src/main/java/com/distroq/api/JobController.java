package com.distroq.api;

import com.distroq.api.dto.JobDetailResponse;
import com.distroq.api.dto.IdempotencyResponse;
import com.distroq.api.dto.JobResponse;
import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.api.error.ApiException;
import com.distroq.api.error.ErrorCode;
import com.distroq.config.DistroqProperties;
import com.distroq.effects.JobEffectService;
import com.distroq.model.AttemptOutcome;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import com.distroq.queue.JobQueue;
import com.distroq.queue.ScheduledJobQueue;
import com.distroq.reliability.ReliabilityMetrics;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.IdempotencyKeyRepository;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobRepository;
import com.distroq.worker.WorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final JobSubmissionService submissionService;
    private final JobEffectService effectService;
    private final ReliabilityMetrics reliabilityMetrics;
    private final WorkerMetrics workerMetrics;
    private final JobQueue jobQueue;
    private final ScheduledJobQueue scheduledJobQueue;

    public JobController(JobRepository jobRepository,
                         JobAttemptRepository jobAttemptRepository,
                         DeadLetterRepository deadLetterRepository,
                         IdempotencyKeyRepository idempotencyKeyRepository,
                         JobSubmissionService submissionService,
                         JobEffectService effectService,
                         ReliabilityMetrics reliabilityMetrics,
                         WorkerMetrics workerMetrics,
                         JobQueue jobQueue,
                         ScheduledJobQueue scheduledJobQueue) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.submissionService = submissionService;
        this.effectService = effectService;
        this.reliabilityMetrics = reliabilityMetrics;
        this.workerMetrics = workerMetrics;
        this.jobQueue = jobQueue;
        this.scheduledJobQueue = scheduledJobQueue;
    }

    /**
     * Two outcomes, decided entirely by {@code scheduledAt}.
     *
     * <p>Every validation runs before the first write, so a rejected request leaves nothing behind
     * in either system. A future time saves the job SCHEDULED and puts it on the scheduled sorted
     * set; anything else saves it QUEUED and puts it straight on its tier's stream.
     */
    @PostMapping("/jobs")
    public ResponseEntity<JobResponse> submit(
            @RequestBody SubmitJobRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        JobSubmissionService.SubmissionResult result =
                submissionService.submit(request, idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted();
        if (result.replayed()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(JobResponse.from(result.job()));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobDetailResponse> get(@PathVariable UUID id) {
        return jobRepository.findById(id)
                .map(job -> JobDetailResponse.from(
                        job, jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId()),
                        effectService.forJob(job.getId())))
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND,
                        "No job with id " + id));
    }

    @GetMapping("/jobs")
    public List<JobResponse> list(@RequestParam(required = false) JobStatus status,
                                  @RequestParam(required = false) Priority priority) {
        // every combination is a distinct query rather than a filtered-in-memory page: trimming a
        // 50-row page after the fact would silently return fewer than the 50 matches that exist
        List<Job> jobs;
        if (status == null && priority == null) {
            jobs = jobRepository.findTop50ByOrderByCreatedAtDesc();
        } else if (priority == null) {
            jobs = jobRepository.findTop50ByStatusOrderByCreatedAtDesc(status);
        } else if (status == null) {
            jobs = jobRepository.findTop50ByPriorityOrderByCreatedAtDesc(priority);
        } else {
            jobs = jobRepository.findTop50ByStatusAndPriorityOrderByCreatedAtDesc(status, priority);
        }
        return jobs.stream().map(JobResponse::from).toList();
    }

    /**
     * Replay a dead-lettered job. History is continued rather than reset: {@code attemptCount}
     * carries over and the budget is extended, so the job detail still reads as one story.
     */
    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<JobResponse> replay(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(JobResponse.from(submissionService.replay(id)));
        }

        @GetMapping("/idempotency/{key}")
        public ResponseEntity<IdempotencyResponse> idempotency(@PathVariable String key) {
        return idempotencyKeyRepository.findById(key.trim())
            .flatMap(record -> jobRepository.findById(record.getJobId())
                .map(job -> IdempotencyResponse.from(record, job)))
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ApiException(ErrorCode.IDEMPOTENCY_KEY_NOT_FOUND,
                "No job was submitted with that idempotency key"));
    }

    /**
     * Three different counts of "entries in a stream", because they answer three different
     * questions and conflating them is the easiest mistake to make with Streams:
     *
     * <ul>
     *   <li>{@code queueDepth} / {@code queueDepthByPriority} — waiting to be delivered. The
     *       successor to v0.4's list depth, read from the consumer group's {@code lag}. Exact
     *       while nothing is trimmed or deleted, and nothing in v0.5 trims or deletes.</li>
     *   <li>{@code streamDepthByPriority} — {@code XLEN}. Everything the stream has ever held,
     *       acknowledged or not. It only ever grows. It is not a backlog.</li>
     *   <li>{@code pendingEntriesByPriority} — delivered and not yet acknowledged. In-flight work
     *       plus anything abandoned and not yet reclaimed. Exact.</li>
     * </ul>
     *
     * <p>{@code activeConsumers} counts consumers <em>holding</em> pending entries, not consumers
     * registered in the group: an idle worker holds nothing and does not appear here.
     *
     * <p>v0.6 adds a fourth kind of waiting, kept out of all three of the above.
     * {@code scheduledDepth} counts jobs waiting on a time the submitter asked for. They are not
     * backlog — no worker could run them yet even if every worker were idle — and folding them
     * into {@code queueDepth} would make an autoscaler start workers for work that is not due, or
     * into {@code delayedDepth} would make a retry-rate alarm fire because someone scheduled a
     * report for midnight.
     * <p>v0.8 adds a reliability block from {@link ReliabilityMetrics}. Those values are live
     * database counts rather than remembered numbers, so they survive a restart and read the same
     * on every instance; the two exceptions are named in that class.
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        // LinkedHashMap rather than Map.of: the key order is stable in the response, and there are
        // now well over Map.of's ten-pair overload set
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("queueDepth", jobQueue.readyDepth());
        metrics.put("queueDepthByPriority", jobQueue.readyDepthByPriority());
        metrics.put("delayedDepth", jobQueue.delayedDepth());
        metrics.put("scheduledDepth", scheduledJobQueue.scheduledDepth());
        metrics.put("scheduledDepthByPriority", scheduledJobQueue.scheduledDepthByPriority());
        metrics.put("totalJobs", jobRepository.count());
        metrics.put("deadLetterCount", deadLetterRepository.countByReplayed(false));
        metrics.put("replayedCount", deadLetterRepository.countByReplayed(true));
        metrics.put("streamDepthByPriority", jobQueue.streamDepthByPriority());
        metrics.put("pendingEntriesByPriority", jobQueue.pendingEntriesByPriority());
        metrics.put("activeConsumers", jobQueue.consumersHoldingEntries().size());
        Instant now = Instant.now();
        metrics.putAll(reliabilityMetrics.snapshot(now));
        metrics.put("workerConcurrency", workerMetrics.concurrency());
        metrics.put("activeWorkers", workerMetrics.activeWorkers());
        metrics.put("activeLeases", jobRepository.countActiveLeases(now));
        metrics.put("reclaimedEntries", workerMetrics.reclaimedEntries());
        metrics.put("abandonedAttempts", jobAttemptRepository.countByOutcome(AttemptOutcome.ABANDONED));
        return metrics;
    }
}
